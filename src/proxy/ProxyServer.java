package proxy;

import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * LittleProxy wrapper running on 127.0.0.1:8888.
 *
 *   - getUpstreamHost() / getUpstreamPort() let BrowserUI pre-fill the proxy
 *     dialog when an upstream is already configured.
 *   - A toggleable host Blocklist short-circuits ordinary ad/tracker requests
 *     with a 204 and rejects blocked CONNECT tunnels. Disabled by default;
 *     when off the filter is a pass-through.
 */
public class ProxyServer {

    public static final int DEFAULT_LOCAL_PORT = 8888;

    public enum UpstreamType {
        HTTP,
        SOCKS4,
        SOCKS5;

        public static UpstreamType fromSetting(String value) {
            if (value == null || value.isBlank()) return HTTP;
            try {
                return valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                return HTTP;
            }
        }

        public String settingValue() { return name().toLowerCase(); }
    }

    public record Upstream(UpstreamType type, String host, int port) {}

    private volatile HttpProxyServer server;
    private volatile int localPort = DEFAULT_LOCAL_PORT;
    private volatile Upstream upstream;
    private final Blocklist blocklist;
    private final int preferredLocalPort;
    private LoopbackSocks5Server browserProxy;

    public ProxyServer() {
        this(new Blocklist(false), DEFAULT_LOCAL_PORT);
    }

    public ProxyServer(Blocklist blocklist) {
        this(blocklist, DEFAULT_LOCAL_PORT);
    }

    /** Allows deterministic tests to use an isolated preferred HTTP port. */
    public ProxyServer(Blocklist blocklist, int preferredLocalPort) {
        if (preferredLocalPort < 1 || preferredLocalPort > 65535)
            throw new IllegalArgumentException("Invalid preferred local proxy port");
        this.blocklist = blocklist;
        this.preferredLocalPort = preferredLocalPort;
    }

    public Blocklist getBlocklist() { return blocklist; }

    // Lifecycle

    public synchronized void start() {
        if (server != null) return;
        if (browserProxy == null) browserProxy = new LoopbackSocks5Server(this);
        Throwable defaultPortFailure = null;
        try {
            server = startOnPort(preferredLocalPort);
        } catch (Throwable failure) {
            defaultPortFailure = failure;
            try {
                server = startOnPort(0);
            } catch (Throwable fallbackFailure) {
                fallbackFailure.addSuppressed(defaultPortFailure);
                throw new IllegalStateException("Could not start the Rigel local proxy", fallbackFailure);
            }
        }
        localPort = server.getListenAddress().getPort();
        if (localPort != preferredLocalPort) {
            System.err.println("[Rigel] Port " + preferredLocalPort
                    + " was unavailable; using 127.0.0.1:" + localPort);
        }
        System.out.println("[Rigel] Proxy running on 127.0.0.1:" + localPort);
        try {
            browserProxy.start(localPort);
            System.out.println("[Rigel] Browser SOCKS5 proxy running on 127.0.0.1:"
                    + browserProxy.getLocalPort());
        } catch (Throwable failure) {
            browserProxy.close();
            HttpProxyServer running = server;
            server = null;
            if (running != null) running.stop();
            throw new IllegalStateException("Could not start the Rigel browser proxy", failure);
        }
    }

    private HttpProxyServer startOnPort(int port) {
        return DefaultHttpProxyServer.bootstrap()
                    .withName("Rigel Local Media Proxy")
                    .withAddress(new InetSocketAddress("127.0.0.1", port))
                    .withAllowLocalOnly(true)
                    // Keep long-lived media tunnels alive and reduce callback
                    // overhead for unencrypted HTTP streams. HTTPS/AV1 remains
                    // a raw CONNECT tunnel and is never decoded or transcoded.
                    .withIdleConnectionTimeout(java.time.Duration.ofMinutes(5))
                    .withConnectTimeout(15_000)
                    .withMaxChunkSize(64 * 1024)
                    .withChainProxyManager(new SwitchableProxy(this))
                    .withFiltersSource(new HttpFiltersSourceAdapter() {
                        @Override
                        public HttpFilters filterRequest(HttpRequest originalRequest) {
                            return new HttpFiltersAdapter(originalRequest) {
                                @Override
                                public HttpResponse clientToProxyRequest(HttpObject httpObject) {
                                    if (httpObject instanceof HttpRequest) {
                                        HttpRequest req = (HttpRequest) httpObject;
                                        String host = req.headers().get("Host");
                                        // For HTTPS the first request is a CONNECT whose
                                        // URI is the "host:port" authority; the Host header
                                        // may be absent. Check both so HTTPS ad/tracker
                                        // hosts are caught at tunnel-open, not just HTTP.
                                        boolean connect = HttpMethod.CONNECT.equals(req.method());
                                        boolean blocked = blocklist.isBlocked(host);
                                        if (!blocked && connect) {
                                            blocked = blocklist.isBlocked(req.uri());
                                        }
                                        if (blocked) {
                                            // A CONNECT needs a non-2xx response or SOCKS clients
                                            // will treat the blocked tunnel as established.
                                            HttpResponseStatus status = connect
                                                    ? HttpResponseStatus.FORBIDDEN
                                                    : HttpResponseStatus.NO_CONTENT;
                                            DefaultFullHttpResponse response =
                                                    new DefaultFullHttpResponse(
                                                            HttpVersion.HTTP_1_1, status);
                                            if (connect) {
                                                response.headers().set(
                                                        HttpHeaderNames.CONTENT_LENGTH, 0);
                                                response.headers().set(HttpHeaderNames.CONNECTION,
                                                        HttpHeaderValues.CLOSE);
                                            }
                                            return response;
                                        }
                                    }
                                    return null; // null = allow request to proceed
                                }
                            };
                        }
                    })
                    .start();
    }

    public synchronized void stop() {
        if (browserProxy != null) browserProxy.close();
        HttpProxyServer running = server;
        server = null;
        if (running != null) {
            running.stop();
            System.out.println("[Rigel] Proxy stopped");
        }
    }

    public int getLocalPort() { return localPort; }
    public int getBrowserProxyPort() {
        LoopbackSocks5Server running = browserProxy;
        return running == null ? -1 : running.getLocalPort();
    }
    public long getBrowserConnectRequestCount() {
        LoopbackSocks5Server running = browserProxy;
        return running == null ? 0 : running.getConnectRequestCount();
    }
    public long getBrowserUdpAssociationRequestCount() {
        LoopbackSocks5Server running = browserProxy;
        return running == null ? 0 : running.getUdpAssociationRequestCount();
    }
    public int getActiveBrowserConnectionCount() {
        LoopbackSocks5Server running = browserProxy;
        return running == null ? 0 : running.getActiveClientCount();
    }

    // Upstream control

    public synchronized void setUpstream(String host, int port) {
        setUpstream(UpstreamType.HTTP, host, port);
    }

    public synchronized void setUpstream(UpstreamType type, String host, int port) {
        String cleanHost = host == null ? "" : host.trim();
        if (type == null || cleanHost.isEmpty() || port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid upstream proxy address");
        if (pointsAtLocalListener(cleanHost, port))
            throw new IllegalArgumentException(
                    "Upstream proxy cannot point to Rigel's own local listener");
        Upstream next = new Upstream(type, cleanHost, port);
        if (next.equals(upstream)) return;
        upstream = next;
        resetBrowserSessions();
        System.out.println("[Rigel] Upstream set -> " + type + " " + cleanHost + ":" + port);
    }

    public synchronized void clearUpstream() {
        if (upstream == null) return;
        upstream = null;
        resetBrowserSessions();
        System.out.println("[Rigel] Upstream cleared -> DIRECT");
    }

    private boolean pointsAtLocalListener(String host, int port) {
        HttpProxyServer runningHttp = server;
        int httpPort = runningHttp == null ? -1 : localPort;
        LoopbackSocks5Server runningBrowser = browserProxy;
        int browserPort = runningBrowser == null ? -1 : runningBrowser.getLocalPort();
        if (port != httpPort && port != browserPort) return false;

        String candidate = host;
        if (candidate.length() > 1 && candidate.charAt(0) == '['
                && candidate.charAt(candidate.length() - 1) == ']') {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(candidate)) {
                if (address.isLoopbackAddress() || address.isAnyLocalAddress()) return true;
            }
        } catch (Exception ignored) {
            // An unresolved upstream will fail normally when a request uses it.
        }
        return false;
    }

    private void resetBrowserSessions() {
        LoopbackSocks5Server running = browserProxy;
        if (running != null) running.resetActiveSessions();
    }

    public boolean isUsingUpstream() { return upstream != null; }
    public Upstream getUpstream() { return upstream; }

    public String getUpstreamHost() {
        Upstream value = upstream;
        return value == null ? null : value.host();
    }
    public int getUpstreamPort() {
        Upstream value = upstream;
        return value == null ? -1 : value.port();
    }
    public UpstreamType getUpstreamType() {
        Upstream value = upstream;
        return value == null ? null : value.type();
    }
}
