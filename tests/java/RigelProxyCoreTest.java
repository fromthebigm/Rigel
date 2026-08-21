import com.sun.net.httpserver.HttpServer;
import proxy.Blocklist;
import proxy.ProxyServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Deterministic smoke checks for Rigel's HTTP core and browser SOCKS5 route. */
public final class RigelProxyCoreTest {
    private static final InetAddress LOOPBACK = loopback();
    private static final byte[] DIRECT_UDP_REQUEST =
            "DIRECT_UDP_REQUEST".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DIRECT_UDP_RESPONSE =
            "DIRECT_UDP_RESPONSE".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UPSTREAM_UDP_REQUEST =
            "UPSTREAM_UDP_REQUEST".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UPSTREAM_UDP_RESPONSE =
            "UPSTREAM_UDP_RESPONSE".getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", "9");
        System.setProperty("https.proxyHost", "127.0.0.1");
        System.setProperty("https.proxyPort", "9");

        int preferredPort = availableTcpPort();
        HttpServer destination = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        destination.createContext("/direct", exchange -> {
            byte[] body = "DIRECT_OK".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        destination.start();

        int browserPort = -1;
        ProxyServer proxy = new ProxyServer(new Blocklist(false), preferredPort);
        try {
            proxy.start();
            require(proxy.getLocalPort() == preferredPort,
                    "preferred local HTTP port was not selected");
            browserPort = proxy.getBrowserProxyPort();
            int currentBrowserPort = browserPort;
            require(browserPort > 0 && browserPort != proxy.getLocalPort(),
                    "browser SOCKS5 ingress did not start on an isolated port");

            HttpClient client = clientFor(proxy.getLocalPort());
            URI direct = URI.create("http://127.0.0.1:"
                    + destination.getAddress().getPort() + "/direct");
            require("DIRECT_OK".equals(get(client, direct)),
                    "direct HTTP route inherited a system proxy or failed");
            require("DIRECT_OK".equals(getThroughSocks(
                            browserPort, destination.getAddress().getPort())),
                    "browser SOCKS5 CONNECT did not traverse LittleProxy");

            proxy.getBlocklist().setEnabled(true);
            HttpResponse<String> blockedHttp = client.send(
                    HttpRequest.newBuilder(URI.create("http://doubleclick.net/probe"))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            require(blockedHttp.statusCode() == 204,
                    "ordinary blocklisted HTTP request did not return 204");
            require(socksConnectStatus(browserPort, "doubleclick.net", 443) != 0,
                    "blocklisted CONNECT was reported as a successful SOCKS5 tunnel");
            proxy.getBlocklist().setEnabled(false);

            expectIllegalArgument(() -> proxy.setUpstream(ProxyServer.UpstreamType.HTTP,
                            "localhost", proxy.getLocalPort()),
                    "HTTP listener was accepted as its own upstream");
            expectIllegalArgument(() -> proxy.setUpstream(ProxyServer.UpstreamType.SOCKS5,
                            "127.0.0.1", currentBrowserPort),
                    "browser SOCKS5 listener was accepted as its own upstream");
            require(proxy.getUpstream() == null,
                    "rejected self-referential upstream changed the active route");

            try (ControllableTcpPeer peer = new ControllableTcpPeer();
                 Socket tunnel = openSocksTunnel(browserPort, peer.port())) {
                peer.awaitConnected();
                peer.closeConnection();
                require(tunnel.getInputStream().read() < 0,
                        "destination EOF was not propagated through the SOCKS5 tunnel");
            }
            for (int cycle = 0; cycle < 64; cycle++) {
                try (ControllableTcpPeer peer = new ControllableTcpPeer();
                     Socket tunnel = openSocksTunnel(browserPort, peer.port())) {
                    peer.awaitConnected();
                    peer.closeConnection();
                    require(tunnel.getInputStream().read() < 0,
                            "destination EOF was lost during tunnel retirement cycle " + cycle);
                }
            }
            long retirementDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (proxy.getActiveBrowserConnectionCount() != 0
                    && System.nanoTime() < retirementDeadline) Thread.sleep(10);
            require(proxy.getActiveBrowserConnectionCount() == 0,
                    "retired SOCKS5 tunnels left active client handlers");

            verifyRouteChangeResetsBrowserSessions(proxy, browserPort);

            int directUdpRelayPort;
            try (UdpEcho echo = new UdpEcho();
                 SocksUdpClient udp = SocksUdpClient.open(browserPort)) {
                directUdpRelayPort = udp.relayPort();
                UdpMessage response = udp.exchange(
                        "127.0.0.1", echo.port(), DIRECT_UDP_REQUEST);
                require(Arrays.equals(DIRECT_UDP_RESPONSE, response.payload()),
                        "direct SOCKS5 UDP response payload was corrupted");
                require(response.port() == echo.port(),
                        "direct SOCKS5 UDP response reported the wrong source port");
            }
            assertUdpPortReleased(directUdpRelayPort);

            InetAddress networkAddress = nonLoopbackIpv4();
            if (networkAddress != null) {
                try (UdpEcho echo = new UdpEcho(networkAddress);
                     SocksUdpClient udp = SocksUdpClient.open(browserPort)) {
                    UdpMessage response = udp.exchange(
                            networkAddress.getHostAddress(), echo.port(), DIRECT_UDP_REQUEST);
                    require(Arrays.equals(DIRECT_UDP_RESPONSE, response.payload()),
                            "SOCKS5 UDP relay could not reach a non-loopback interface");
                    require(networkAddress.getHostAddress().equals(response.host()),
                            "non-loopback SOCKS5 UDP response reported the wrong source");
                }
            }

            try (MockHttpProxy upstream = new MockHttpProxy()) {
                proxy.setUpstream(ProxyServer.UpstreamType.HTTP,
                        "127.0.0.1", upstream.port());
                URI throughUpstream = URI.create("http://rigel-upstream.invalid/probe");
                require("UPSTREAM_OK".equals(get(client, throughUpstream)),
                        "HTTP upstream route failed");
                String request = upstream.request().get(5, TimeUnit.SECONDS);
                require(request.startsWith("GET http://rigel-upstream.invalid/probe "),
                        "upstream did not receive the original absolute URI");
                require(udpAssociationStatus(browserPort) == 0x07,
                        "UDP was not rejected for a TCP-only HTTP upstream");

                require("DIRECT_OK".equals(get(client, direct)),
                        "localhost was incorrectly routed through the upstream");
                proxy.clearUpstream();
                require("DIRECT_OK".equals(get(client, direct)),
                        "direct route did not recover after clearing upstream");
            }

            try (MockSocks5UdpProxy upstream = new MockSocks5UdpProxy()) {
                proxy.setUpstream(ProxyServer.UpstreamType.SOCKS5,
                        "127.0.0.1", upstream.port());
                try (SocksUdpClient udp = SocksUdpClient.open(browserPort)) {
                    UdpMessage response = udp.exchange(
                            "udp.example.test", 443, UPSTREAM_UDP_REQUEST);
                    require(Arrays.equals(UPSTREAM_UDP_RESPONSE, response.payload()),
                            "SOCKS5 upstream UDP response payload was corrupted");
                }
                upstream.verify();
                proxy.clearUpstream();
            }
        } finally {
            proxy.stop();
            destination.stop(0);
        }
        assertTcpPortReleased(preferredPort);
        assertTcpPortReleased(browserPort);

        try (ServerSocket occupied = new ServerSocket(preferredPort, 1, LOOPBACK)) {
            require(occupied.isBound(), "could not occupy the preferred proxy port");
            ProxyServer fallback = new ProxyServer(new Blocklist(false), preferredPort);
            int fallbackPort;
            int fallbackBrowserPort;
            try {
                fallback.start();
                fallbackPort = fallback.getLocalPort();
                fallbackBrowserPort = fallback.getBrowserProxyPort();
                require(fallbackPort != preferredPort,
                        "busy preferred port did not select a loopback fallback");
                require(fallbackBrowserPort > 0,
                        "browser SOCKS5 ingress failed with an HTTP fallback port");
            } finally {
                fallbackPort = fallback.getLocalPort();
                fallbackBrowserPort = fallback.getBrowserProxyPort();
                fallback.stop();
            }
            assertTcpPortReleased(fallbackPort);
            assertTcpPortReleased(fallbackBrowserPort);
        }
        assertTcpPortReleased(preferredPort);
        System.out.println("Rigel proxy core checks passed: HTTP, blocked CONNECT,"
                + " self-loop rejection, route resets, 65 destination-first tunnel"
                + " retirements, direct UDP, SOCKS5 upstream UDP, TCP-only rejection,"
                + " and fallback.");
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

    private static void verifyRouteChangeResetsBrowserSessions(
            ProxyServer proxy, int browserPort) throws Exception {
        int upstreamPort = availableTcpPort();
        proxy.setUpstream(ProxyServer.UpstreamType.HTTP, "127.0.0.1", upstreamPort);
        try {
            try (ControllableTcpPeer peer = new ControllableTcpPeer();
                 Socket tunnel = openSocksTunnel(browserPort, peer.port())) {
                peer.awaitConnected();
                awaitActiveBrowserConnections(proxy, 1);
                proxy.setUpstream(ProxyServer.UpstreamType.HTTP,
                        "127.0.0.1", upstreamPort);
                requireSocketOpen(tunnel,
                        "unchanged upstream reset an existing SOCKS5 TCP tunnel");
                proxy.clearUpstream();
                requireSocketClosed(tunnel,
                        "changed upstream did not reset an existing SOCKS5 TCP tunnel");
                awaitActiveBrowserConnections(proxy, 0);
            }

            int relayPort;
            try (SocksUdpClient udp = SocksUdpClient.open(browserPort)) {
                relayPort = udp.relayPort();
                awaitActiveBrowserConnections(proxy, 1);
                proxy.clearUpstream();
                udp.requireControlOpen(
                        "unchanged direct route reset an existing SOCKS5 UDP association");
                proxy.setUpstream(ProxyServer.UpstreamType.HTTP,
                        "127.0.0.1", upstreamPort);
                udp.requireControlClosed(
                        "changed upstream did not reset an existing SOCKS5 UDP association");
                awaitActiveBrowserConnections(proxy, 0);
            }
            assertUdpPortReleased(relayPort);
        } finally {
            proxy.clearUpstream();
        }
    }

    private static int socksConnectStatus(int socksPort, String host, int port)
            throws Exception {
        try (Socket socket = new Socket(LOOPBACK, socksPort)) {
            socket.setSoTimeout(5_000);
            negotiateSocks(socket);
            byte[] domain = host.getBytes(StandardCharsets.ISO_8859_1);
            require(domain.length > 0 && domain.length <= 255,
                    "invalid test SOCKS5 domain");
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            request.write(new byte[] {0x05, 0x01, 0x00, 0x03, (byte) domain.length});
            request.write(domain);
            writePort(request, port);
            socket.getOutputStream().write(request.toByteArray());
            socket.getOutputStream().flush();
            return readSocksReply(socket.getInputStream()).status();
        }
    }

    private static void awaitActiveBrowserConnections(ProxyServer proxy, int expected)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (proxy.getActiveBrowserConnectionCount() != expected
                && System.nanoTime() < deadline) Thread.sleep(10);
        require(proxy.getActiveBrowserConnectionCount() == expected,
                "expected " + expected + " active browser proxy connections, found "
                        + proxy.getActiveBrowserConnectionCount());
    }

    private static void requireSocketOpen(Socket socket, String message) throws Exception {
        int previousTimeout = socket.getSoTimeout();
        socket.setSoTimeout(200);
        try {
            int value = socket.getInputStream().read();
            throw new AssertionError(message + " (read returned " + value + ")");
        } catch (SocketTimeoutException expected) {
            // No data and no EOF means the tunnel is still active.
        } finally {
            socket.setSoTimeout(previousTimeout);
        }
    }

    private static void requireSocketClosed(Socket socket, String message) throws Exception {
        socket.setSoTimeout(5_000);
        try {
            require(socket.getInputStream().read() < 0, message);
        } catch (SocketException closed) {
            // A reset is also a valid closed-session result.
        }
    }

    private static void expectIllegalArgument(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static String getThroughSocks(int socksPort, int destinationPort) throws Exception {
        try (Socket socket = openSocksTunnel(socksPort, destinationPort)) {
            String http = "GET /direct HTTP/1.1\r\nHost: 127.0.0.1:"
                    + destinationPort + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(http.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            String headers = readHeaders(socket.getInputStream());
            int contentLength = Arrays.stream(headers.split("\r\n"))
                    .filter(line -> line.toLowerCase().startsWith("content-length:"))
                    .map(line -> line.substring(line.indexOf(':') + 1).trim())
                    .mapToInt(Integer::parseInt)
                    .findFirst().orElseThrow();
            return new String(readExact(socket.getInputStream(), contentLength),
                    StandardCharsets.UTF_8);
        }
    }

    private static Socket openSocksTunnel(int socksPort, int destinationPort) throws Exception {
        Socket socket = new Socket(LOOPBACK, socksPort);
        try {
            socket.setSoTimeout(5_000);
            negotiateSocks(socket);
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            request.write(new byte[] {0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1});
            writePort(request, destinationPort);
            socket.getOutputStream().write(request.toByteArray());
            socket.getOutputStream().flush();
            require(readSocksReply(socket.getInputStream()).status() == 0,
                    "SOCKS5 CONNECT was rejected");
            return socket;
        } catch (Exception error) {
            socket.close();
            throw error;
        }
    }

    private static int udpAssociationStatus(int socksPort) throws Exception {
        try (Socket control = new Socket(LOOPBACK, socksPort)) {
            control.setSoTimeout(5_000);
            negotiateSocks(control);
            control.getOutputStream().write(new byte[] {
                    0x05, 0x03, 0x00, 0x01,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00
            });
            control.getOutputStream().flush();
            return readSocksReply(control.getInputStream()).status();
        }
    }

    private static void negotiateSocks(Socket socket) throws Exception {
        socket.getOutputStream().write(new byte[] {0x05, 0x01, 0x00});
        socket.getOutputStream().flush();
        require(readByte(socket.getInputStream()) == 0x05
                        && readByte(socket.getInputStream()) == 0x00,
                "SOCKS5 no-auth negotiation failed");
    }

    private static SocksReply readSocksReply(InputStream input) throws Exception {
        require(readByte(input) == 0x05, "invalid SOCKS5 reply version");
        int status = readByte(input);
        readByte(input); // reserved
        int addressType = readByte(input);
        InetAddress address;
        if (addressType == 0x01) {
            address = InetAddress.getByAddress(readExact(input, 4));
        } else if (addressType == 0x04) {
            address = InetAddress.getByAddress(readExact(input, 16));
        } else if (addressType == 0x03) {
            address = InetAddress.getByName(new String(
                    readExact(input, readByte(input)), StandardCharsets.ISO_8859_1));
        } else {
            throw new AssertionError("invalid SOCKS5 reply address type");
        }
        int port = (readByte(input) << 8) | readByte(input);
        return new SocksReply(status, address, port);
    }

    private static byte[] udpFrame(String host, int port, byte[] payload) throws Exception {
        byte[] domain = host.getBytes(StandardCharsets.ISO_8859_1);
        require(domain.length > 0 && domain.length <= 255, "invalid test UDP domain");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[] {0x00, 0x00, 0x00, 0x03, (byte) domain.length});
        output.write(domain);
        writePort(output, port);
        output.write(payload);
        return output.toByteArray();
    }

    private static UdpMessage parseUdpFrame(byte[] bytes) throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        require(readByte(input) == 0 && readByte(input) == 0 && readByte(input) == 0,
                "invalid SOCKS5 UDP header");
        int addressType = readByte(input);
        String host;
        if (addressType == 0x01) {
            host = InetAddress.getByAddress(readExact(input, 4)).getHostAddress();
        } else if (addressType == 0x04) {
            host = InetAddress.getByAddress(readExact(input, 16)).getHostAddress();
        } else if (addressType == 0x03) {
            host = new String(readExact(input, readByte(input)), StandardCharsets.ISO_8859_1);
        } else {
            throw new AssertionError("invalid SOCKS5 UDP address type");
        }
        int port = (readByte(input) << 8) | readByte(input);
        return new UdpMessage(host, port, input.readAllBytes());
    }

    private static void writePort(ByteArrayOutputStream output, int port) {
        output.write((port >>> 8) & 0xFF);
        output.write(port & 0xFF);
    }

    private static int readByte(InputStream input) throws Exception {
        int value = input.read();
        if (value < 0) throw new EOFException();
        return value;
    }

    private static byte[] readExact(InputStream input, int length) throws Exception {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException();
        return bytes;
    }

    private static String readHeaders(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        byte[] ending = {'\r', '\n', '\r', '\n'};
        while (matched < ending.length) {
            int value = readByte(input);
            bytes.write(value);
            matched = value == ending[matched] ? matched + 1
                    : (value == '\r' ? 1 : 0);
            if (bytes.size() > 64 * 1024) throw new IllegalStateException("headers too large");
        }
        return bytes.toString(StandardCharsets.ISO_8859_1);
    }

    private static int availableTcpPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, LOOPBACK)) {
            return socket.getLocalPort();
        }
    }

    private static void assertTcpPortReleased(int port) throws Exception {
        if (port < 1) return;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (true) {
            try (ServerSocket check = new ServerSocket(port, 1, LOOPBACK)) {
                require(check.isBound(), "TCP listener remained after shutdown");
                return;
            } catch (Exception busy) {
                if (System.nanoTime() >= deadline) throw busy;
                Thread.sleep(25);
            }
        }
    }

    private static void assertUdpPortReleased(int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (true) {
            try (DatagramSocket check = new DatagramSocket(null)) {
                check.bind(new InetSocketAddress(LOOPBACK, port));
                require(check.isBound(), "UDP relay remained after association close");
                return;
            } catch (Exception busy) {
                if (System.nanoTime() >= deadline) throw busy;
                Thread.sleep(25);
            }
        }
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (Exception impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private static InetAddress nonLoopbackIpv4() throws Exception {
        var interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback()) continue;
            var addresses = network.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet4Address && !address.isLinkLocalAddress()) {
                    return address;
                }
            }
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record SocksReply(int status, InetAddress address, int port) {}
    private record UdpMessage(String host, int port, byte[] payload) {}

    private static final class SocksUdpClient implements AutoCloseable {
        private final Socket control;
        private final DatagramSocket udp;
        private final InetSocketAddress relay;

        private SocksUdpClient(Socket control, DatagramSocket udp,
                               InetSocketAddress relay) {
            this.control = control;
            this.udp = udp;
            this.relay = relay;
        }

        static SocksUdpClient open(int socksPort) throws Exception {
            Socket control = new Socket(LOOPBACK, socksPort);
            try {
                control.setSoTimeout(5_000);
                negotiateSocks(control);
                control.getOutputStream().write(new byte[] {
                        0x05, 0x03, 0x00, 0x01,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                });
                control.getOutputStream().flush();
                SocksReply reply = readSocksReply(control.getInputStream());
                require(reply.status() == 0, "SOCKS5 UDP ASSOCIATE was rejected");
                InetAddress address = reply.address().isAnyLocalAddress()
                        ? control.getInetAddress() : reply.address();
                DatagramSocket udp = new DatagramSocket(
                        new InetSocketAddress(LOOPBACK, 0));
                udp.setSoTimeout(5_000);
                return new SocksUdpClient(control, udp,
                        new InetSocketAddress(address, reply.port()));
            } catch (Exception error) {
                control.close();
                throw error;
            }
        }

        int relayPort() {
            return relay.getPort();
        }

        UdpMessage exchange(String host, int port, byte[] payload) throws Exception {
            byte[] request = udpFrame(host, port, payload);
            udp.send(new DatagramPacket(request, request.length,
                    relay.getAddress(), relay.getPort()));
            DatagramPacket response = new DatagramPacket(new byte[65_535], 65_535);
            udp.receive(response);
            require(response.getAddress().equals(relay.getAddress())
                            && response.getPort() == relay.getPort(),
                    "SOCKS5 UDP response came from an unexpected relay");
            return parseUdpFrame(Arrays.copyOfRange(response.getData(),
                    response.getOffset(), response.getOffset() + response.getLength()));
        }

        void requireControlOpen(String message) throws Exception {
            int previousTimeout = control.getSoTimeout();
            control.setSoTimeout(200);
            try {
                int value = control.getInputStream().read();
                throw new AssertionError(message + " (read returned " + value + ")");
            } catch (SocketTimeoutException expected) {
                // The association remains open and intentionally sends no control data.
            } finally {
                control.setSoTimeout(previousTimeout);
            }
        }

        void requireControlClosed(String message) throws Exception {
            control.setSoTimeout(5_000);
            try {
                require(control.getInputStream().read() < 0, message);
            } catch (SocketException closed) {
                // A reset is also a valid closed-session result.
            }
        }

        @Override public void close() {
            udp.close();
            try {
                control.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static final class UdpEcho implements AutoCloseable {
        private final DatagramSocket socket;
        private final Thread worker;

        UdpEcho() throws Exception {
            this(LOOPBACK);
        }

        UdpEcho(InetAddress address) throws Exception {
            socket = new DatagramSocket(new InetSocketAddress(address, 0));
            socket.setSoTimeout(5_000);
            worker = Thread.ofPlatform().daemon(true).name("udp-echo-test").start(() -> {
                try {
                    DatagramPacket request = new DatagramPacket(new byte[1024], 1024);
                    socket.receive(request);
                    require(Arrays.equals(DIRECT_UDP_REQUEST, Arrays.copyOfRange(
                                    request.getData(), request.getOffset(),
                                    request.getOffset() + request.getLength())),
                            "direct UDP request payload was corrupted");
                    socket.send(new DatagramPacket(DIRECT_UDP_RESPONSE,
                            DIRECT_UDP_RESPONSE.length,
                            request.getAddress(), request.getPort()));
                } catch (Exception error) {
                    if (!socket.isClosed()) throw new RuntimeException(error);
                }
            });
        }

        int port() { return socket.getLocalPort(); }

        @Override public void close() {
            socket.close();
            try {
                worker.join(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class ControllableTcpPeer implements AutoCloseable {
        private final ServerSocket server;
        private final CompletableFuture<Socket> accepted = new CompletableFuture<>();
        private final Thread worker;

        ControllableTcpPeer() throws Exception {
            server = new ServerSocket(0, 1, LOOPBACK);
            worker = Thread.ofPlatform().daemon(true).name("controllable-tcp-peer")
                    .start(() -> {
                        try {
                            accepted.complete(server.accept());
                        } catch (Exception error) {
                            accepted.completeExceptionally(error);
                        }
                    });
        }

        int port() { return server.getLocalPort(); }

        void awaitConnected() throws Exception {
            accepted.get(5, TimeUnit.SECONDS);
        }

        void closeConnection() throws Exception {
            accepted.get(5, TimeUnit.SECONDS).close();
        }

        @Override public void close() {
            try {
                Socket socket = accepted.getNow(null);
                if (socket != null) socket.close();
            } catch (Exception ignored) {
            }
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

    private static final class MockHttpProxy implements AutoCloseable {
        private final ServerSocket server;
        private final CompletableFuture<String> request = new CompletableFuture<>();
        private final Thread worker;

        MockHttpProxy() throws Exception {
            server = new ServerSocket(0, 1, LOOPBACK);
            worker = Thread.ofPlatform().daemon(true).name("mock-http-upstream")
                    .start(this::serve);
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
                socket.getOutputStream().write(
                        response.getBytes(StandardCharsets.ISO_8859_1));
                socket.getOutputStream().write(body);
                socket.getOutputStream().flush();
            } catch (Exception error) {
                request.completeExceptionally(error);
            }
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

    private static final class MockSocks5UdpProxy implements AutoCloseable {
        private final ServerSocket server;
        private final DatagramSocket relay;
        private final Thread worker;
        private final CountDownLatch packetSeen = new CountDownLatch(1);
        private volatile Throwable failure;

        MockSocks5UdpProxy() throws Exception {
            server = new ServerSocket(0, 1, LOOPBACK);
            relay = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0));
            relay.setSoTimeout(5_000);
            worker = Thread.ofPlatform().daemon(true).name("mock-socks5-udp-upstream")
                    .start(this::serve);
        }

        int port() { return server.getLocalPort(); }

        private void serve() {
            try (Socket control = server.accept()) {
                control.setSoTimeout(5_000);
                InputStream input = control.getInputStream();
                require(readByte(input) == 0x05, "invalid upstream SOCKS5 greeting");
                int methods = readByte(input);
                readExact(input, methods);
                control.getOutputStream().write(new byte[] {0x05, 0x00});
                control.getOutputStream().flush();

                require(readByte(input) == 0x05 && readByte(input) == 0x03,
                        "upstream did not request UDP ASSOCIATE");
                readByte(input); // reserved
                int addressType = readByte(input);
                if (addressType == 0x01) readExact(input, 4);
                else if (addressType == 0x04) readExact(input, 16);
                else if (addressType == 0x03) readExact(input, readByte(input));
                else throw new AssertionError("invalid upstream UDP request address");
                readExact(input, 2);

                ByteArrayOutputStream reply = new ByteArrayOutputStream();
                reply.write(new byte[] {0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1});
                writePort(reply, relay.getLocalPort());
                control.getOutputStream().write(reply.toByteArray());
                control.getOutputStream().flush();

                DatagramPacket packet = new DatagramPacket(new byte[65_535], 65_535);
                relay.receive(packet);
                byte[] request = Arrays.copyOfRange(packet.getData(), packet.getOffset(),
                        packet.getOffset() + packet.getLength());
                UdpMessage message = parseUdpFrame(request);
                require("udp.example.test".equals(message.host()) && message.port() == 443,
                        "SOCKS5 upstream lost the UDP destination");
                require(Arrays.equals(UPSTREAM_UDP_REQUEST, message.payload()),
                        "SOCKS5 upstream UDP request payload was corrupted");

                byte[] response = udpFrame("203.0.113.44", 443, UPSTREAM_UDP_RESPONSE);
                relay.send(new DatagramPacket(response, response.length,
                        packet.getAddress(), packet.getPort()));
                packetSeen.countDown();
                while (input.read() >= 0) { }
            } catch (Throwable error) {
                failure = error;
                packetSeen.countDown();
            }
        }

        void verify() throws Exception {
            require(packetSeen.await(5, TimeUnit.SECONDS),
                    "SOCKS5 upstream did not receive UDP");
            if (failure != null) throw new AssertionError("SOCKS5 UDP upstream failed", failure);
        }

        @Override public void close() {
            try {
                server.close();
            } catch (Exception ignored) {
            }
            relay.close();
            try {
                worker.join(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
