package proxy;

import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.net.InetSocketAddress;

/**
 * LittleProxy wrapper running on 127.0.0.1:8888.
 *
 *   - getUpstreamHost() / getUpstreamPort() let BrowserUI pre-fill the proxy
 *     dialog when an upstream is already configured.
 *   - A toggleable host Blocklist short-circuits ad/tracker hosts with a 204
 *     when enabled. Disabled by default; when off the filter is a pass-through.
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

    public ProxyServer() {
        this(new Blocklist(false));
    }

    public ProxyServer(Blocklist blocklist) {
        this.blocklist = blocklist;
    }

    public Blocklist getBlocklist() { return blocklist; }

    // Lifecycle

    public synchronized void start() {
        if (server != null) return;
        Throwable defaultPortFailure = null;
        try {
            server = startOnPort(DEFAULT_LOCAL_PORT);
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
        if (localPort != DEFAULT_LOCAL_PORT) {
            System.err.println("[Rigel] Port " + DEFAULT_LOCAL_PORT
                    + " was unavailable; using 127.0.0.1:" + localPort);
        }
        System.out.println("[Rigel] Proxy running on 127.0.0.1:" + localPort);
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
                                        boolean blocked = blocklist.isBlocked(host);
                                        if (!blocked && io.netty.handler.codec.http.HttpMethod.CONNECT.equals(req.method())) {
                                            blocked = blocklist.isBlocked(req.uri());
                                        }
                                        if (blocked) {
                                            // Empty 204 - site keeps working, ad call dropped
                                            return new DefaultFullHttpResponse(
                                                HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
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
        HttpProxyServer running = server;
        server = null;
        if (running != null) {
            running.stop();
            System.out.println("[Rigel] Proxy stopped");
        }
    }

    public int getLocalPort() { return localPort; }

    // Upstream control

    public synchronized void setUpstream(String host, int port) {
        setUpstream(UpstreamType.HTTP, host, port);
    }

    public synchronized void setUpstream(UpstreamType type, String host, int port) {
        String cleanHost = host == null ? "" : host.trim();
        if (type == null || cleanHost.isEmpty() || port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid upstream proxy address");
        upstream = new Upstream(type, cleanHost, port);
        System.out.println("[Rigel] Upstream set -> " + type + " " + cleanHost + ":" + port);
    }

    public synchronized void clearUpstream() {
        if (upstream != null) System.out.println("[Rigel] Upstream cleared -> DIRECT");
        upstream = null;
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
