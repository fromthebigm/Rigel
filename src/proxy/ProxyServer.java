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
        /** HTTP CONNECT over TLS: the hop to the upstream is encrypted, so the
         *  local network only sees a TLS connection to the upstream host. */
        HTTPS,
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

    public record Upstream(UpstreamType type, String host, int port,
                           String username, String password) {
        /** Compact constructor: credentials normalize to "" so records with
         *  and without auth compare unequal only when credentials differ. */
        public Upstream {
            username = username == null ? "" : username;
            password = password == null ? "" : password;
        }
        public Upstream(UpstreamType type, String host, int port) {
            this(type, host, port, "", "");
        }
        public boolean hasCredentials() { return !username.isEmpty(); }
    }

    private volatile HttpProxyServer server;
    private volatile int localPort = DEFAULT_LOCAL_PORT;
    private volatile Upstream upstream;
    private final Blocklist blocklist;
    private final int preferredLocalPort;
    private volatile LoopbackSocks5Server browserProxy;

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
            // SOCKS ingress is a convenience; losing it must not kill the HTTP
            // proxy or the whole app. Log loudly and keep serving on localPort.
            browserProxy.close();
            System.err.println("[Rigel] Browser SOCKS5 proxy failed to start (HTTP proxy still up on "
                    + localPort + "): " + failure);
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
                    .withServerResolver(SecureDns.asHostResolver())
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
                                        boolean connect = HttpMethod.CONNECT.equals(req.method());
                                        boolean blocked = blocklist.isBlocked(host);
                                        if (!blocked && connect) {
                                            blocked = blocklist.isBlocked(req.uri());
                                        }
                                        if (blocked) {
                                            HttpResponseStatus status = connect
                                                    ? HttpResponseStatus.FORBIDDEN
                                                    : HttpResponseStatus.NO_CONTENT;
                                            DefaultFullHttpResponse response =
                                                    new DefaultFullHttpResponse(
                                                            HttpVersion.HTTP_1_1, status);
                                            response.headers().set(
                                                    HttpHeaderNames.CONTENT_LENGTH, 0);
                                            response.headers().set(HttpHeaderNames.CONNECTION,
                                                    HttpHeaderValues.CLOSE);
                                            return response;
                                        }
                                        // ── Domain fronting ─────────────────────
                                        // For CONNECT tunnels to fronted domains,
                                        // rewrite the tunnel target to the front
                                        // host. The inner TLS session still targets
                                        // the real host (SNI = real host from the
                                        // browser), but LittleProxy opens the TCP
                                        // connection to the FRONT host's IP — the
                                        // router's IP block only sees the front
                                        // domain's IP. The real Host header rides
                                        // inside the tunnel.
                                        if (connect) {
                                            String tunnelHost = tunnelHost(req.uri());
                                            if (DomainFronting.isFronted(tunnelHost)) {
                                                String front = DomainFronting.frontHostFor(tunnelHost);
                                                if (front != null) {
                                                    int port = tunnelPort(req.uri());
                                                    req.setUri(front + ":" + port);
                                                    // Stash the real host so
                                                    // proxyToServerRequest can
                                                    // restore it in the Host header
                                                    // after the tunnel is established.
                                                    originalRequest.headers().set(
                                                            "X-Rigel-Real-Host", tunnelHost);
                                                    System.out.println("[Rigel fronting] "
                                                            + tunnelHost + " → " + front);
                                                }
                                            }
                                        } else if (host != null
                                                && DomainFronting.isFronted(host)) {
                                            // Plain HTTP: rewrite the Host header path
                                            // through the front domain.
                                            String front = DomainFronting.frontHostFor(host);
                                            if (front != null) {
                                                req.headers().set(
                                                        "X-Rigel-Real-Host", host);
                                                req.headers().set("Host", front);
                                                System.out.println("[Rigel fronting http] "
                                                        + host + " → " + front);
                                            }
                                        }
                                    }
                                    return null; // null = allow request to proceed
                                }
                            };
                        }
                    })
                    .start();
    }

    /** Extract the host from a CONNECT "host:port" URI. */
    private static String tunnelHost(String uri) {
        if (uri == null) return null;
        int colon = uri.lastIndexOf(':');
        return colon > 0 ? uri.substring(0, colon) : uri;
    }

    /** Extract the port from a CONNECT "host:port" URI (default 443). */
    private static int tunnelPort(String uri) {
        if (uri == null) return 443;
        try {
            int colon = uri.lastIndexOf(':');
            return colon > 0 ? Integer.parseInt(uri.substring(colon + 1)) : 443;
        } catch (NumberFormatException e) {
            return 443;
        }
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

    public void setUpstream(String host, int port) {
        setUpstream(UpstreamType.HTTP, host, port);
    }

    public void setUpstream(UpstreamType type, String host, int port) {
        setUpstream(type, host, port, "", "");
    }

    public void setUpstream(UpstreamType type, String host, int port,
                            String username, String password) {
        String cleanHost = host == null ? "" : host.trim();
        String cleanUser = username == null ? "" : username;
        String cleanPass = password == null ? "" : password;
        if (type == null || cleanHost.isEmpty() || port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid upstream proxy address");
        // A password without a username has no defined wire behaviour on any
        // of the four upstream types; reject rather than silently drop it.
        if (cleanUser.isEmpty() && !cleanPass.isEmpty())
            throw new IllegalArgumentException(
                    "Upstream proxy password requires a username");
        // SOCKS4's only identity field (USERID) maps to the username; a
        // password would never leave this machine, so fail loudly.
        if (type == UpstreamType.SOCKS4 && !cleanPass.isEmpty())
            throw new IllegalArgumentException(
                    "SOCKS4 upstreams do not support a password");
        // Validation and the loopback check run OUTSIDE the monitor: a slow
        // DoH exchange inside pointsAtLocalListener must never stall
        // stop()/start()/setUpstream on other threads for seconds.
        if (pointsAtLocalListener(cleanHost, port))
            throw new IllegalArgumentException(
                    "Upstream proxy cannot point to Rigel's own local listener");
        applyUpstream(type, cleanHost, port, cleanUser, cleanPass);
    }

    private synchronized void applyUpstream(UpstreamType type, String cleanHost, int port,
                                            String cleanUser, String cleanPass) {
        Upstream next = new Upstream(type, cleanHost, port, cleanUser, cleanPass);
        if (next.equals(upstream)) return;
        upstream = next;
        resetBrowserSessions();
        System.out.println("[Rigel] Upstream set -> " + type + " " + cleanHost + ":" + port
                + (next.hasCredentials() ? " (authenticated)" : " (open)"));
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
            // Note: called from synchronized setUpstream(); a DoH exchange can
            // block for seconds, so only pay it when the port could possibly
            // be one of ours.
            if (candidate.indexOf(':') >= 0 || !candidate.matches("[0-9.]+")) {
                InetSocketAddress resolved = SecureDns.resolve(candidate, 0);
                InetAddress address = resolved.getAddress();
                if (address != null
                        && (address.isLoopbackAddress() || address.isAnyLocalAddress())) {
                    return true;
                }
            } else {
                InetAddress address = InetAddress.getByName(candidate);
                if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
                    return true;
                }
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

    /** Reachability probe for a saved upstream. A stale upstream (e.g. a
     *  localhost proxy that is no longer running) causes every browser
     *  connection to be reset; detect it at startup and fall back to DIRECT. */
    public boolean probeUpstream(int timeoutMs) {
        Upstream value = upstream;
        if (value == null) return true;
        try (java.net.Socket probe = new java.net.Socket()) {
            probe.connect(new java.net.InetSocketAddress(value.host(), value.port()), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

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
