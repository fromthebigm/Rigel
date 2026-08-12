package proxy;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loopback-only SOCKS5 ingress used by WKWebView.
 *
 * WebKit connects here using its per-data-store SOCKS5 configuration. Each
 * SOCKS CONNECT is converted into an HTTP CONNECT against Rigel's LittleProxy,
 * so both ordinary HTTP and HTTPS still pass through the same blocklist and
 * direct/upstream routing policy.
 */
public final class LoopbackSocks5Server implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int HEADER_LIMIT = 64 * 1024;

    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket server;
    private volatile Thread listener;
    private volatile int littleProxyPort;

    public synchronized void start(int targetLittleProxyPort) {
        if (server != null) return;
        if (targetLittleProxyPort < 1 || targetLittleProxyPort > 65535) {
            throw new IllegalArgumentException("Invalid LittleProxy port");
        }

        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 128);
            littleProxyPort = targetLittleProxyPort;
            server = socket;
            listener = Thread.ofPlatform()
                    .daemon(true)
                    .name("rigel-socks5-ingress")
                    .start(this::acceptLoop);
        } catch (IOException error) {
            throw new IllegalStateException("Could not start Rigel's loopback SOCKS5 ingress", error);
        }
    }

    public int getLocalPort() {
        ServerSocket running = server;
        return running == null ? -1 : running.getLocalPort();
    }

    private void acceptLoop() {
        while (server != null) {
            try {
                Socket client = server.accept();
                clients.add(client);
                Thread.ofVirtual()
                        .name("rigel-socks5-client")
                        .start(() -> handle(client));
            } catch (IOException error) {
                if (server != null) {
                    System.err.println("[Rigel] SOCKS5 ingress accept failed: " + error.getMessage());
                }
            }
        }
    }

    private void handle(Socket client) {
        try (client; Socket littleProxy = new Socket()) {
            client.setKeepAlive(true);
            client.setTcpNoDelay(true);
            client.setSoTimeout(CONNECT_TIMEOUT_MS);

            InputStream clientInput = client.getInputStream();
            OutputStream clientOutput = client.getOutputStream();
            negotiateNoAuthentication(clientInput, clientOutput);
            Destination destination = readConnectRequest(clientInput);

            littleProxy.connect(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), littleProxyPort),
                    CONNECT_TIMEOUT_MS);
            littleProxy.setKeepAlive(true);
            littleProxy.setTcpNoDelay(true);
            littleProxy.setSoTimeout(0);

            String authority = destination.authority();
            String connect = "CONNECT " + authority + " HTTP/1.1\r\n"
                    + "Host: " + authority + "\r\n"
                    + "Proxy-Connection: keep-alive\r\n\r\n";
            littleProxy.getOutputStream().write(connect.getBytes(StandardCharsets.ISO_8859_1));
            littleProxy.getOutputStream().flush();

            String response = readHeaders(littleProxy.getInputStream());
            String statusLine = response.lines().findFirst().orElse("");
            if (!statusLine.matches("HTTP/\\d(?:\\.\\d)? 2\\d\\d(?: .*)?")) {
                sendReply(clientOutput, 0x05);
                return;
            }

            sendReply(clientOutput, 0x00);
            client.setSoTimeout(0);

            Thread back = Thread.ofVirtual()
                    .name("rigel-socks5-back")
                    .start(() -> copy(littleProxy, client));
            copy(client, littleProxy);
            try {
                littleProxy.shutdownOutput();
            } catch (IOException ignored) {
            }
            try {
                back.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        } catch (EOFException ignored) {
        } catch (Exception error) {
            try {
                sendReply(client.getOutputStream(), 0x01);
            } catch (Exception ignored) {
            }
            System.err.println("[Rigel] SOCKS5 ingress connection failed: " + error.getMessage());
        } finally {
            clients.remove(client);
        }
    }

    private static void negotiateNoAuthentication(InputStream input, OutputStream output)
            throws IOException {
        int version = readByte(input);
        int methodCount = readByte(input);
        if (version != 0x05 || methodCount < 1) {
            throw new IOException("Invalid SOCKS5 greeting");
        }

        boolean noAuthentication = false;
        for (int index = 0; index < methodCount; index++) {
            if (readByte(input) == 0x00) noAuthentication = true;
        }
        output.write(new byte[] {0x05, (byte) (noAuthentication ? 0x00 : 0xFF)});
        output.flush();
        if (!noAuthentication) throw new IOException("SOCKS5 client requires authentication");
    }

    private static Destination readConnectRequest(InputStream input) throws IOException {
        int version = readByte(input);
        int command = readByte(input);
        readByte(input); // reserved
        int addressType = readByte(input);
        if (version != 0x05 || command != 0x01) {
            throw new IOException("Only SOCKS5 CONNECT is supported");
        }

        String host;
        if (addressType == 0x01) {
            byte[] address = readExact(input, 4);
            host = InetAddress.getByAddress(address).getHostAddress();
        } else if (addressType == 0x03) {
            int length = readByte(input);
            if (length < 1) throw new IOException("Empty SOCKS5 domain");
            host = new String(readExact(input, length), StandardCharsets.ISO_8859_1);
        } else if (addressType == 0x04) {
            byte[] address = readExact(input, 16);
            host = InetAddress.getByAddress(address).getHostAddress();
        } else {
            throw new IOException("Unsupported SOCKS5 address type");
        }

        int port = (readByte(input) << 8) | readByte(input);
        if (port < 1) throw new IOException("Invalid SOCKS5 destination port");
        return new Destination(host, port);
    }

    private static String readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] ending = {'\r', '\n', '\r', '\n'};
        int matched = 0;
        while (matched < ending.length) {
            int value = input.read();
            if (value < 0) throw new EOFException("LittleProxy closed during CONNECT");
            bytes.write(value);
            matched = value == ending[matched] ? matched + 1 : (value == '\r' ? 1 : 0);
            if (bytes.size() > HEADER_LIMIT) throw new IOException("Oversized proxy response");
        }
        return bytes.toString(StandardCharsets.ISO_8859_1);
    }

    private static void sendReply(OutputStream output, int status) throws IOException {
        output.write(new byte[] {
                0x05, (byte) status, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00
        });
        output.flush();
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException();
        return value;
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(bytes, offset, length - offset);
            if (count < 0) throw new EOFException();
            offset += count;
        }
        return bytes;
    }

    private static void copy(Socket source, Socket destination) {
        try {
            InputStream input = source.getInputStream();
            OutputStream output = destination.getOutputStream();
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) >= 0; ) {
                output.write(buffer, 0, count);
                output.flush();
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public synchronized void close() {
        ServerSocket running = server;
        server = null;
        if (running != null) {
            try {
                running.close();
            } catch (IOException ignored) {
            }
        }
        for (Socket client : clients) {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
        clients.clear();
        Thread accepting = listener;
        listener = null;
        if (accepting != null) {
            try {
                accepting.join(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record Destination(String host, int port) {
        String authority() {
            return host.indexOf(':') >= 0 ? "[" + host + "]:" + port : host + ":" + port;
        }
    }
}
