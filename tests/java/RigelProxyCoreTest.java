import com.sun.net.httpserver.HttpServer;
import proxy.Blocklist;
import proxy.ProxyServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Deterministic smoke checks for Rigel's one-process local proxy route. */
public final class RigelProxyCoreTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    public static void main(String[] args) throws Exception {
        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", "9");
        System.setProperty("https.proxyHost", "127.0.0.1");
        System.setProperty("https.proxyPort", "9");

        HttpServer destination = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        destination.createContext("/direct", exchange -> {
            byte[] body = "DIRECT_OK".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        destination.start();

        ProxyServer proxy = new ProxyServer(new Blocklist(false));
        try {
            proxy.start();
            require(proxy.getLocalPort() == ProxyServer.DEFAULT_LOCAL_PORT,
                    "default local port was not selected");
            HttpClient client = clientFor(proxy.getLocalPort());
            URI direct = URI.create("http://127.0.0.1:"
                    + destination.getAddress().getPort() + "/direct");
            require("DIRECT_OK".equals(get(client, direct)),
                    "direct route inherited a system proxy or failed");

            try (MockHttpProxy upstream = new MockHttpProxy()) {
                proxy.setUpstream(ProxyServer.UpstreamType.HTTP,
                        "127.0.0.1", upstream.port());
                URI throughUpstream = URI.create("http://rigel-upstream.invalid/probe");
                require("UPSTREAM_OK".equals(get(client, throughUpstream)),
                        "HTTP upstream route failed");
                String request = upstream.request().get(5, TimeUnit.SECONDS);
                require(request.startsWith("GET http://rigel-upstream.invalid/probe "),
                        "upstream did not receive the original absolute URI");

                require("DIRECT_OK".equals(get(client, direct)),
                        "localhost was incorrectly routed through the upstream");
                proxy.clearUpstream();
                require("DIRECT_OK".equals(get(client, direct)),
                        "direct route did not recover after clearing upstream");
            }
        } finally {
            proxy.stop();
            destination.stop(0);
        }
        assertPortReleased(ProxyServer.DEFAULT_LOCAL_PORT);

        try (ServerSocket occupied = new ServerSocket(
                ProxyServer.DEFAULT_LOCAL_PORT, 1, LOOPBACK)) {
            require(occupied.isBound(), "could not occupy the default proxy port");
            ProxyServer fallback = new ProxyServer(new Blocklist(false));
            int fallbackPort;
            try {
                fallback.start();
                fallbackPort = fallback.getLocalPort();
                require(fallbackPort != ProxyServer.DEFAULT_LOCAL_PORT,
                        "busy default port did not select a loopback fallback");
            } finally {
                fallbackPort = fallback.getLocalPort();
                fallback.stop();
            }
            assertPortReleased(fallbackPort);
        }
        assertPortReleased(ProxyServer.DEFAULT_LOCAL_PORT);
        System.out.println("Rigel proxy core checks passed.");
    }

    private static HttpClient clientFor(int port) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", port)))
                .build();
    }

    private static String get(HttpClient client, URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static void assertPortReleased(int port) throws Exception {
        if (port < 1) return;
        try (ServerSocket check = new ServerSocket(port, 1, LOOPBACK)) {
            require(check.isBound(), "proxy listener remained after shutdown");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class MockHttpProxy implements AutoCloseable {
        private final ServerSocket server;
        private final CompletableFuture<String> request = new CompletableFuture<>();
        private final Thread worker;

        MockHttpProxy() throws Exception {
            server = new ServerSocket(0, 1, LOOPBACK);
            worker = new Thread(this::serve, "rigel-test-upstream");
            worker.setDaemon(true);
            worker.start();
        }

        int port() { return server.getLocalPort(); }
        CompletableFuture<String> request() { return request; }

        private void serve() {
            try (Socket socket = server.accept()) {
                String headers = readHeaders(socket.getInputStream());
                request.complete(headers);
                byte[] body = "UPSTREAM_OK".getBytes(StandardCharsets.UTF_8);
                String response = "HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                        + "\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(response.getBytes(StandardCharsets.ISO_8859_1));
                socket.getOutputStream().write(body);
                socket.getOutputStream().flush();
            } catch (Exception error) {
                request.completeExceptionally(error);
            }
        }

        private static String readHeaders(InputStream input) throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int matched = 0;
            byte[] ending = {'\r', '\n', '\r', '\n'};
            while (matched < ending.length) {
                int value = input.read();
                if (value < 0) throw new IllegalStateException("upstream request ended early");
                bytes.write(value);
                matched = value == ending[matched] ? matched + 1
                        : (value == '\r' ? 1 : 0);
                if (bytes.size() > 64 * 1024) throw new IllegalStateException("headers too large");
            }
            return bytes.toString(StandardCharsets.ISO_8859_1);
        }

        @Override public void close() {
            try {
                server.close();
            } catch (Exception ignored) {
            }
            try {
                worker.join(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
