package proxy;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loopback-only SOCKS5 ingress used by WKWebView.
 *
 * <p>TCP CONNECT requests are converted into HTTP CONNECT requests against
 * Rigel's LittleProxy instance. That keeps ordinary HTTP/HTTPS traffic on the
 * existing blocklist and direct/upstream routing path. UDP ASSOCIATE requests
 * are relayed directly in Direct mode or through a configured SOCKS5 upstream.
 * HTTP and SOCKS4 upstreams reject UDP rather than leaking it outside the
 * selected route.</p>
 */
public final class LoopbackSocks5Server implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int HEADER_LIMIT = 64 * 1024;
    private static final int UDP_BUFFER_SIZE = 65_535;
    private static final int UDP_POLL_TIMEOUT_MS = 1_000;
    private static final int MAX_DIRECT_UDP_DESTINATIONS = 512;
    private static final long DIRECT_UDP_DESTINATION_TTL_NANOS =
            TimeUnit.MINUTES.toNanos(5);
    private static final int SOCKS_VERSION = 0x05;
    private static final int COMMAND_CONNECT = 0x01;
    private static final int COMMAND_UDP_ASSOCIATE = 0x03;
    private static final int STATUS_SUCCEEDED = 0x00;
    private static final int STATUS_GENERAL_FAILURE = 0x01;
    private static final int STATUS_COMMAND_NOT_SUPPORTED = 0x07;
    private static final int STATUS_ADDRESS_NOT_SUPPORTED = 0x08;
    private static final InetAddress LOOPBACK_V4 = loopbackV4();

    private final ProxyServer routes;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final Set<DatagramSocket> udpRelays = ConcurrentHashMap.newKeySet();
    private final AtomicLong connectRequests = new AtomicLong();
    private final AtomicLong udpAssociationRequests = new AtomicLong();
    private volatile ServerSocket server;
    private volatile Thread listener;
    private volatile int littleProxyPort;

    LoopbackSocks5Server(ProxyServer routes) {
        this.routes = Objects.requireNonNull(routes, "routes");
    }

    public synchronized void start(int targetLittleProxyPort) {
        if (server != null) return;
        if (targetLittleProxyPort < 1 || targetLittleProxyPort > 65_535) {
            throw new IllegalArgumentException("Invalid LittleProxy port");
        }

        try {
            ServerSocket socket = new ServerSocket();
            try {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(LOOPBACK_V4, 0), 128);
            } catch (IOException bindFailure) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                throw bindFailure;
            }
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

    long getConnectRequestCount() {
        return connectRequests.get();
    }

    long getUdpAssociationRequestCount() {
        return udpAssociationRequests.get();
    }

    int getActiveClientCount() {
        return clients.size();
    }

    private void acceptLoop() {
        int consecutiveFailures = 0;
        while (server != null) {
            try {
                ServerSocket listener = server;
                if (listener == null) break;
                Socket client = listener.accept();
                consecutiveFailures = 0;
                clients.add(client);
                try {
                    Thread.ofVirtual()
                            .name("rigel-socks5-client")
                            .start(() -> handle(client));
                } catch (Throwable startFailure) {
                    // Handler never ran: without this the socket stays in
                    // `clients` forever, a permanent fd leak.
                    clients.remove(client);
                    try { client.close(); } catch (IOException ignored) {}
                }
            } catch (IOException error) {
                if (server != null) {
                    consecutiveFailures++;
                    // Back off on persistent accept() failures (e.g. FD
                    // exhaustion at macOS's soft limit): instant retries would
                    // spin the thread at 100% CPU and flood stderr.
                    if (consecutiveFailures <= 5) {
                        System.err.println("[Rigel] SOCKS5 ingress accept failed: "
                                + error.getMessage());
                    }
                    try {
                        Thread.sleep(Math.min(2_000, 100L << Math.min(consecutiveFailures, 4)));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private void handle(Socket client) {
        try (client) {
            client.setKeepAlive(true);
            client.setTcpNoDelay(true);
            client.setSoTimeout(CONNECT_TIMEOUT_MS);

            InputStream input = client.getInputStream();
            OutputStream output = client.getOutputStream();
            negotiateNoAuthentication(input, output);
            Request request;
            try {
                request = readRequest(input);
            } catch (AddressTypeException unsupported) {
                sendReply(output, STATUS_ADDRESS_NOT_SUPPORTED, null, 0);
                return;
            }
            if (diagnosticsEnabled()) {
                System.err.println("[Rigel proxy diagnostics] SOCKS5 command="
                        + request.command() + " destination="
                        + request.destination().authority());
            }
            if (request.command() == COMMAND_CONNECT) {
                connectRequests.incrementAndGet();
                handleConnect(client, input, output, request.destination());
            } else if (request.command() == COMMAND_UDP_ASSOCIATE) {
                udpAssociationRequests.incrementAndGet();
                handleUdpAssociate(client, input, output);
            } else {
                sendReply(output, STATUS_COMMAND_NOT_SUPPORTED, null, 0);
            }
        } catch (EOFException ignored) {
        } catch (SocketException error) {
            if (server != null) {
                System.err.println("[Rigel] SOCKS5 client socket failed: " + error.getMessage());
            }
        } catch (Exception error) {
            System.err.println("[Rigel] SOCKS5 ingress connection failed: " + error.getMessage());
        } finally {
            clients.remove(client);
        }
    }

    private void handleConnect(Socket client, InputStream clientInput,
                               OutputStream clientOutput, Destination destination) {
        boolean connected = false;
        // Socket is closed explicitly further down (the bounded join path);
        // keep one reference so both the try-with-targets and the timeout
        // path release it the same way.
        Socket littleProxy = new Socket();
        try {
            if (destination.port() < 1) {
                sendReply(clientOutput, STATUS_GENERAL_FAILURE, null, 0);
                return;
            }
            littleProxy.connect(new InetSocketAddress(LOOPBACK_V4, littleProxyPort),
                    CONNECT_TIMEOUT_MS);
            littleProxy.setKeepAlive(true);
            littleProxy.setTcpNoDelay(true);
            littleProxy.setSoTimeout(CONNECT_TIMEOUT_MS);

            String authority = destination.authority();
            String connect = "CONNECT " + authority + " HTTP/1.1\r\n"
                    + "Host: " + authority + "\r\n"
                    + "Proxy-Connection: keep-alive\r\n\r\n";
            littleProxy.getOutputStream().write(
                    connect.getBytes(StandardCharsets.ISO_8859_1));
            littleProxy.getOutputStream().flush();

            String response = readHeaders(littleProxy.getInputStream());
            String statusLine = response.lines().findFirst().orElse("");
            if (!statusLine.matches("HTTP/\\d(?:\\.\\d)? 2\\d\\d(?: .*)?")) {
                sendReply(clientOutput, STATUS_GENERAL_FAILURE, null, 0);
                return;
            }

            sendReply(clientOutput, STATUS_SUCCEEDED,
                    littleProxy.getLocalAddress(), littleProxy.getLocalPort());
            if (diagnosticsEnabled()) {
                System.err.println("[Rigel proxy diagnostics] SOCKS5 CONNECT established "
                        + destination.authority());
            }
            connected = true;
            client.setSoTimeout(0);
            littleProxy.setSoTimeout(0);

            Thread back = Thread.ofVirtual()
                    .name("rigel-socks5-connect-back")
                    .start(() -> copyAndShutdownOutput(littleProxy, client));
            copyAndShutdownOutput(clientInput, littleProxy);
            try {
                // Bounded join: a remote that stays silently open after the
                // client half-closes would otherwise pin both sockets and two
                // virtual threads indefinitely (OS keepalive takes ~2h).
                // Closing the littleProxy side forces the back-copy to error
                // out and exit; the client socket unwinds with it.
                back.join(30_000);
                if (back.isAlive()) {
                    try { littleProxy.close(); } catch (IOException ignored) {}
                    back.join(5_000);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        } catch (Exception error) {
            if (!connected) {
                try {
                    sendReply(clientOutput, STATUS_GENERAL_FAILURE, null, 0);
                } catch (IOException ignored) {
                }
            }
            if (!(error instanceof EOFException) && server != null) {
                System.err.println("[Rigel] SOCKS5 CONNECT failed for "
                        + destination.authority() + ": " + error.getMessage());
            }
        } finally {
            try { littleProxy.close(); } catch (IOException ignored) {}
        }
    }

    private void handleUdpAssociate(Socket client, InputStream clientInput,
                                    OutputStream clientOutput) throws IOException {
        ProxyServer.Upstream upstream = routes.getUpstream();
        if (upstream != null && upstream.type() != ProxyServer.UpstreamType.SOCKS5) {
            // HTTP CONNECT and SOCKS4 cannot represent UDP. Failing the
            // association allows WebKit to fall back to its TCP web path and
            // avoids silently bypassing the upstream selected by the user.
            sendReply(clientOutput, STATUS_COMMAND_NOT_SUPPORTED, null, 0);
            return;
        }

        UpstreamUdpAssociation upstreamAssociation = null;
        DatagramSocket clientRelay = new DatagramSocket(null);
        DatagramSocket networkRelay;
        try {
            networkRelay = new DatagramSocket(null);
        } catch (IOException firstLeak) {
            // The first socket of the pair must not leak when the second
            // constructor fails under fd exhaustion.
            clientRelay.close();
            throw firstLeak;
        }
        AtomicBoolean active = new AtomicBoolean(true);
        boolean replied = false;
        try {
            clientRelay.setReuseAddress(true);
            clientRelay.bind(new InetSocketAddress(LOOPBACK_V4, 0));
            clientRelay.setSoTimeout(UDP_POLL_TIMEOUT_MS);
            networkRelay.bind(new InetSocketAddress(0));
            networkRelay.setSoTimeout(UDP_POLL_TIMEOUT_MS);
            udpRelays.add(clientRelay);
            udpRelays.add(networkRelay);
            if (upstream != null) {
                upstreamAssociation = UpstreamUdpAssociation.open(upstream);
            }

            sendReply(clientOutput, STATUS_SUCCEEDED,
                    LOOPBACK_V4, clientRelay.getLocalPort());
            replied = true;
            if (diagnosticsEnabled()) {
                System.err.println("[Rigel proxy diagnostics] SOCKS5 UDP relay="
                        + LOOPBACK_V4.getHostAddress() + ":" + clientRelay.getLocalPort()
                        + (upstream == null ? " route=direct" : " route=socks5-upstream"));
            }
            // Bounded control read: a client that vanished without a FIN
            // (power cut, interface teardown) would otherwise hold 3 sockets
            // and 4 virtual threads until OS keepalive reaps it (~2h).
            // Relay traffic keeps the association alive; five minutes of
            // total silence means the peer is gone, and a SOCKS client that
            // revives simply re-associates.
            client.setSoTimeout(1_000);
            java.util.concurrent.atomic.AtomicLong lastRelayAt =
                    new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
            UpstreamUdpAssociation selectedUpstream = upstreamAssociation;
            AtomicReference<InetSocketAddress> clientEndpoint = new AtomicReference<>();
            ConcurrentHashMap<InetSocketAddress, AtomicLong> directDestinations =
                    new ConcurrentHashMap<>();
            if (selectedUpstream != null) {
                // RFC 1928 ties the UDP association to the *upstream* control
                // connection too; without this watcher a dead upstream would
                // blackhole UDP silently.
                Thread.ofVirtual()
                        .name("rigel-socks5-udp-upstream-watch")
                        .start(() -> {
                            try {
                                byte[] drain = new byte[256];
                                while (selectedUpstream.control.getInputStream().read(drain) >= 0) { }
                            } catch (Exception ignored) {
                            }
                            active.set(false);
                            clientRelay.close();
                            networkRelay.close();
                        });
            }
            Thread outbound = Thread.ofVirtual()
                    .name("rigel-socks5-udp-out")
                    .start(() -> relayUdpFromClient(clientRelay, networkRelay,
                            client.getInetAddress(), clientEndpoint,
                            directDestinations, selectedUpstream, active, lastRelayAt));
            Thread inbound = Thread.ofVirtual()
                    .name("rigel-socks5-udp-in")
                    .start(() -> relayUdpToClient(clientRelay, networkRelay,
                            clientEndpoint, directDestinations,
                            selectedUpstream, active, lastRelayAt));

            try {
                // The control read now times out once per second; each tick
                // re-checks the relay clock, so the association cannot
                // outlive a vanished peer by more than the idle window.
                outer:
                while (true) {
                    try {
                        if (clientInput.read() < 0) break;
                    } catch (SocketTimeoutException idleTick) {
                        if (System.currentTimeMillis() - lastRelayAt.get() > 300_000L) {
                            if (diagnosticsEnabled()) {
                                System.err.println("[Rigel proxy diagnostics] SOCKS5 "
                                        + "UDP association idle-expired");
                            }
                            break outer;
                        }
                    }
                }
            } finally {
                active.set(false);
                clientRelay.close();
                networkRelay.close();
                try {
                    outbound.join(2_000);
                    inbound.join(2_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (Exception error) {
            if (!replied) {
                try {
                    sendReply(clientOutput, STATUS_GENERAL_FAILURE, null, 0);
                } catch (IOException ignored) {
                }
            }
            if (server != null && !client.isClosed()
                    && !clientRelay.isClosed() && !networkRelay.isClosed()) {
                System.err.println("[Rigel] SOCKS5 UDP association failed: "
                        + error.getMessage());
            }
        } finally {
            active.set(false);
            udpRelays.remove(clientRelay);
            udpRelays.remove(networkRelay);
            clientRelay.close();
            networkRelay.close();
            if (upstreamAssociation != null) upstreamAssociation.close();
        }
    }

    private void relayUdpFromClient(DatagramSocket clientRelay,
                                    DatagramSocket networkRelay,
                                    InetAddress expectedClientAddress,
                                    AtomicReference<InetSocketAddress> clientEndpoint,
                                    ConcurrentHashMap<InetSocketAddress, AtomicLong>
                                            directDestinations,
                                    UpstreamUdpAssociation upstream,
                                    AtomicBoolean active,
                                    java.util.concurrent.atomic.AtomicLong lastRelayAt) {
        byte[] receiveBuffer = new byte[UDP_BUFFER_SIZE];
        DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
        while (active.get() && !clientRelay.isClosed()) {
            try {
                packet.setLength(receiveBuffer.length);
                clientRelay.receive(packet);
                lastRelayAt.set(System.currentTimeMillis());
                InetSocketAddress source = new InetSocketAddress(
                        packet.getAddress(), packet.getPort());
                InetSocketAddress knownClient = clientEndpoint.get();
                if (knownClient == null) {
                    if (!packet.getAddress().equals(expectedClientAddress)
                            || !packet.getAddress().isLoopbackAddress()) continue;
                    clientEndpoint.compareAndSet(null, source);
                    knownClient = clientEndpoint.get();
                }
                if (!knownClient.equals(source)) continue;

                byte[] datagram = Arrays.copyOfRange(packet.getData(),
                        packet.getOffset(), packet.getOffset() + packet.getLength());
                UdpFrame frame = UdpFrame.parse(datagram);
                if (frame == null || routes.getBlocklist().isBlocked(
                        frame.destination().host())) continue;
                if (upstream != null) {
                    sendDatagram(networkRelay, datagram, upstream.relayEndpoint());
                } else {
                    InetSocketAddress destination = frame.destination().resolve();
                    rememberDirectDestination(directDestinations, destination);
                    sendDatagram(networkRelay, frame.payload(), destination);
                }
            } catch (SocketTimeoutException ignored) {
            } catch (SocketException error) {
                logUdpSocketFailure(clientRelay, active, error);
                return;
            } catch (Exception error) {
                logUdpPacketFailure(active, error);
            }
        }
    }

    private void relayUdpToClient(DatagramSocket clientRelay,
                                  DatagramSocket networkRelay,
                                  AtomicReference<InetSocketAddress> clientEndpoint,
                                  ConcurrentHashMap<InetSocketAddress, AtomicLong>
                                          directDestinations,
                                  UpstreamUdpAssociation upstream,
                                  AtomicBoolean active,
                                  java.util.concurrent.atomic.AtomicLong lastRelayAt) {
        byte[] receiveBuffer = new byte[UDP_BUFFER_SIZE];
        DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
        while (active.get() && !networkRelay.isClosed()) {
            try {
                packet.setLength(receiveBuffer.length);
                networkRelay.receive(packet);
                lastRelayAt.set(System.currentTimeMillis());
                InetSocketAddress source = new InetSocketAddress(
                        packet.getAddress(), packet.getPort());
                InetSocketAddress client = clientEndpoint.get();
                if (client == null) continue;
                byte[] datagram = Arrays.copyOfRange(packet.getData(),
                        packet.getOffset(), packet.getOffset() + packet.getLength());
                if (upstream != null) {
                    if (!upstream.relayEndpoint().equals(source)
                            || UdpFrame.parse(datagram) == null) continue;
                    sendDatagram(clientRelay, datagram, client);
                } else if (isRecentDirectDestination(directDestinations, source)) {
                    byte[] response = UdpFrame.wrap(source, datagram);
                    sendDatagram(clientRelay, response, client);
                }
            } catch (SocketTimeoutException ignored) {
            } catch (SocketException error) {
                logUdpSocketFailure(networkRelay, active, error);
                return;
            } catch (Exception error) {
                logUdpPacketFailure(active, error);
            }
        }
    }

    private static void rememberDirectDestination(
            ConcurrentHashMap<InetSocketAddress, AtomicLong> destinations,
            InetSocketAddress destination) {
        long now = System.nanoTime();
        AtomicLong lastSent = destinations.get(destination);
        if (lastSent == null) {
            AtomicLong candidate = new AtomicLong(now);
            lastSent = destinations.putIfAbsent(destination, candidate);
            if (lastSent == null) lastSent = candidate;
        }
        lastSent.set(now);
        if (destinations.size() <= MAX_DIRECT_UDP_DESTINATIONS) return;

        for (var entry : destinations.entrySet()) {
            if (now - entry.getValue().get() > DIRECT_UDP_DESTINATION_TTL_NANOS) {
                destinations.remove(entry.getKey(), entry.getValue());
            }
        }
        while (destinations.size() > MAX_DIRECT_UDP_DESTINATIONS) {
            InetSocketAddress oldestDestination = null;
            AtomicLong oldestTimestamp = null;
            long oldestAge = Long.MIN_VALUE;
            for (var entry : destinations.entrySet()) {
                long age = now - entry.getValue().get();
                if (oldestDestination == null || age > oldestAge) {
                    oldestDestination = entry.getKey();
                    oldestTimestamp = entry.getValue();
                    oldestAge = age;
                }
            }
            if (oldestDestination == null) return;
            destinations.remove(oldestDestination, oldestTimestamp);
        }
    }

    private static boolean isRecentDirectDestination(
            ConcurrentHashMap<InetSocketAddress, AtomicLong> destinations,
            InetSocketAddress source) {
        AtomicLong lastSent = destinations.get(source);
        if (lastSent == null) return false;
        if (System.nanoTime() - lastSent.get()
                <= DIRECT_UDP_DESTINATION_TTL_NANOS) return true;
        destinations.remove(source, lastSent);
        return false;
    }

    private static void logUdpSocketFailure(DatagramSocket socket,
                                            AtomicBoolean active,
                                            SocketException error) {
        if (active.get() && !socket.isClosed()) {
            System.err.println("[Rigel] SOCKS5 UDP socket failed: " + error.getMessage());
        }
    }

    private static void logUdpPacketFailure(AtomicBoolean active, Exception error) {
        if (active.get()) {
            System.err.println("[Rigel] SOCKS5 UDP packet dropped: " + error.getMessage());
        }
    }

    private static void sendDatagram(DatagramSocket socket, byte[] bytes,
                                     InetSocketAddress destination) throws IOException {
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length,
                destination.getAddress(), destination.getPort());
        socket.send(packet);
    }

    private static void negotiateNoAuthentication(InputStream input, OutputStream output)
            throws IOException {
        int version = readByte(input);
        int methodCount = readByte(input);
        if (version != SOCKS_VERSION || methodCount < 1) {
            throw new IOException("Invalid SOCKS5 greeting");
        }

        boolean noAuthentication = false;
        for (int index = 0; index < methodCount; index++) {
            if (readByte(input) == 0x00) noAuthentication = true;
        }
        output.write(new byte[] {
                SOCKS_VERSION, (byte) (noAuthentication ? 0x00 : 0xFF)
        });
        output.flush();
        if (!noAuthentication) throw new IOException("SOCKS5 client requires authentication");
    }

    private static Request readRequest(InputStream input) throws IOException {
        int version = readByte(input);
        int command = readByte(input);
        int reserved = readByte(input);
        int addressType = readByte(input);
        if (version != SOCKS_VERSION || reserved != 0x00) {
            throw new IOException("Invalid SOCKS5 request");
        }
        Destination destination = Destination.read(input, addressType, true);
        return new Request(command, destination);
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

    private static void sendReply(OutputStream output, int status,
                                  InetAddress address, int port) throws IOException {
        InetAddress replyAddress = address == null ? LOOPBACK_V4 : address;
        byte[] rawAddress = replyAddress.getAddress();
        int addressType = rawAddress.length == 4 ? 0x01 : 0x04;
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        reply.write(SOCKS_VERSION);
        reply.write(status);
        reply.write(0x00);
        reply.write(addressType);
        reply.write(rawAddress);
        reply.write((port >>> 8) & 0xFF);
        reply.write(port & 0xFF);
        output.write(reply.toByteArray());
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

    private static void copyAndShutdownOutput(Socket source, Socket destination) {
        try {
            copyAndShutdownOutput(source.getInputStream(), destination);
        } catch (IOException ignored) {
        }
    }

    private static void copyAndShutdownOutput(InputStream input, Socket destination) {
        try {
            copy(input, destination.getOutputStream());
        } catch (IOException ignored) {
        } finally {
            try {
                destination.shutdownOutput();
            } catch (IOException ignored) {
            }
        }
    }

    private static void copy(InputStream input, OutputStream output) {
        try {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) >= 0; ) {
                output.write(buffer, 0, count);
                output.flush();
            }
        } catch (IOException ignored) {
        }
    }

    void resetActiveSessions() {
        for (DatagramSocket relay : udpRelays.toArray(new DatagramSocket[0])) {
            relay.close();
        }
        for (Socket client : clients.toArray(new Socket[0])) {
            try {
                client.close();
            } catch (IOException ignored) {
            }
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
        for (DatagramSocket relay : udpRelays) relay.close();
        udpRelays.clear();
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

    private static InetAddress loopbackV4() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (IOException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private static final boolean DIAGNOSTICS =
            System.getenv("RIGEL_PROXY_DIAGNOSTICS") != null;

    private static boolean diagnosticsEnabled() {
        return DIAGNOSTICS;
    }

    private record Request(int command, Destination destination) {}

    private record Destination(int addressType, String host, byte[] rawAddress, int port) {
        static Destination read(InputStream input, int addressType, boolean allowZeroPort)
                throws IOException {
            String host;
            byte[] rawAddress = null;
            if (addressType == 0x01) {
                rawAddress = readExact(input, 4);
                host = InetAddress.getByAddress(rawAddress).getHostAddress();
            } else if (addressType == 0x03) {
                int length = readByte(input);
                if (length < 1) throw new IOException("Empty SOCKS5 domain");
                String candidate = new String(readExact(input, length), StandardCharsets.ISO_8859_1);
                // The domain is interpolated into the CONNECT request line
                // sent to LittleProxy; anything outside RFC 3986 reg-name
                // (CR/LF especially) would smuggle extra headers.
                if (!candidate.matches("[A-Za-z0-9._\\-]+")) {
                    throw new IOException("Invalid SOCKS5 domain");
                }
                host = candidate;
            } else if (addressType == 0x04) {
                rawAddress = readExact(input, 16);
                host = InetAddress.getByAddress(rawAddress).getHostAddress();
            } else {
                throw new AddressTypeException();
            }

            int port = (readByte(input) << 8) | readByte(input);
            if (!allowZeroPort && port < 1) throw new IOException("Invalid SOCKS5 port");
            return new Destination(addressType, host, rawAddress, port);
        }

        InetSocketAddress resolve() throws IOException {
            if (rawAddress != null) {
                return new InetSocketAddress(InetAddress.getByAddress(rawAddress), port);
            }
            return SecureDns.resolve(host, port);
        }

        String authority() {
            return addressType == 0x04 ? "[" + host + "]:" + port : host + ":" + port;
        }

        void writeTo(OutputStream output) throws IOException {
            output.write(addressType);
            if (addressType == 0x03) {
                byte[] domain = host.getBytes(StandardCharsets.ISO_8859_1);
                output.write(domain.length);
                output.write(domain);
            } else {
                output.write(rawAddress);
            }
            output.write((port >>> 8) & 0xFF);
            output.write(port & 0xFF);
        }
    }

    private record UdpFrame(Destination destination, byte[] payload) {
        static UdpFrame parse(byte[] datagram) {
            try {
                if (datagram.length < 7 || datagram[0] != 0 || datagram[1] != 0
                        || datagram[2] != 0) return null;
                InputStream input = new java.io.ByteArrayInputStream(datagram, 3,
                        datagram.length - 3);
                int addressType = readByte(input);
                Destination destination = Destination.read(input, addressType, false);
                return new UdpFrame(destination, input.readAllBytes());
            } catch (Exception ignored) {
                return null;
            }
        }

        static byte[] wrap(InetSocketAddress source, byte[] payload) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write(new byte[] {0x00, 0x00, 0x00});
            byte[] address = source.getAddress().getAddress();
            Destination destination = new Destination(
                    source.getAddress() instanceof Inet4Address ? 0x01 : 0x04,
                    source.getAddress().getHostAddress(), address, source.getPort());
            destination.writeTo(output);
            output.write(payload);
            return output.toByteArray();
        }
    }

    private static final class UpstreamUdpAssociation implements AutoCloseable {
        private final Socket control;
        private final InetSocketAddress relayEndpoint;

        private UpstreamUdpAssociation(Socket control, InetSocketAddress relayEndpoint) {
            this.control = control;
            this.relayEndpoint = relayEndpoint;
        }

        static UpstreamUdpAssociation open(ProxyServer.Upstream upstream) throws IOException {
            Socket control = new Socket();
            try {
                // Resolve through SecureDns so the upstream hostname is not
                // leaked to the local network resolver when DoH is active.
                control.connect(SecureDns.resolve(upstream.host(), upstream.port()),
                        CONNECT_TIMEOUT_MS);
                control.setKeepAlive(true);
                control.setTcpNoDelay(true);
                control.setSoTimeout(CONNECT_TIMEOUT_MS);
                InputStream input = control.getInputStream();
                OutputStream output = control.getOutputStream();
                if (upstream.hasCredentials()) {
                    // RFC 1929: offer NO AUTH and USERNAME/PASSWORD and let the
                    // upstream pick; an open UDP gate despite configured
                    // credentials is accepted (the relay still works), an
                    // explicit "no acceptable methods" is not.
                    byte[] user = upstream.username().getBytes(StandardCharsets.UTF_8);
                    byte[] pass = upstream.password().getBytes(StandardCharsets.UTF_8);
                    if (user.length < 1 || user.length > 255 || pass.length > 255) {
                        throw new IOException("SOCKS5 upstream credentials exceed RFC 1929 limits");
                    }
                    output.write(new byte[] {SOCKS_VERSION, 0x02, 0x00, 0x02});
                    output.flush();
                    if (readByte(input) != SOCKS_VERSION) {
                        throw new IOException("Invalid SOCKS5 upstream auth reply");
                    }
                    int method = readByte(input);
                    if (method == 0xFF) {
                        throw new IOException("SOCKS5 upstream accepted no authentication method");
                    }
                    if (method == 0x02) {
                        ByteArrayOutputStream auth = new ByteArrayOutputStream();
                        auth.write(0x01);
                        auth.write(user.length);
                        auth.write(user);
                        auth.write(pass.length);
                        auth.write(pass);
                        output.write(auth.toByteArray());
                        output.flush();
                        if (readByte(input) != 0x01 || readByte(input) != 0x00) {
                            throw new IOException("SOCKS5 upstream rejected the credentials");
                        }
                    }
                } else {
                    output.write(new byte[] {SOCKS_VERSION, 0x01, 0x00});
                    output.flush();
                    if (readByte(input) != SOCKS_VERSION || readByte(input) != 0x00) {
                        throw new IOException("SOCKS5 upstream requires unsupported authentication");
                    }
                }

                output.write(new byte[] {
                        SOCKS_VERSION, COMMAND_UDP_ASSOCIATE, 0x00, 0x01,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                });
                output.flush();
                if (readByte(input) != SOCKS_VERSION) {
                    throw new IOException("Invalid SOCKS5 upstream UDP reply");
                }
                int status = readByte(input);
                readByte(input); // reserved
                int addressType = readByte(input);
                Destination relay = Destination.read(input, addressType, false);
                if (status != STATUS_SUCCEEDED) {
                    throw new IOException("SOCKS5 upstream rejected UDP with status " + status);
                }
                InetSocketAddress endpoint = relay.resolve();
                if (endpoint.getAddress().isAnyLocalAddress()) {
                    endpoint = new InetSocketAddress(
                            control.getInetAddress(), endpoint.getPort());
                }
                control.setSoTimeout(0);
                return new UpstreamUdpAssociation(control, endpoint);
            } catch (IOException error) {
                try {
                    control.close();
                } catch (IOException ignored) {
                }
                throw error;
            }
        }

        InetSocketAddress relayEndpoint() {
            return relayEndpoint;
        }

        @Override
        public void close() {
            try {
                control.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class AddressTypeException extends IOException {
        private static final long serialVersionUID = 1L;

        AddressTypeException() {
            super("Unsupported SOCKS5 address type");
        }
    }
}
