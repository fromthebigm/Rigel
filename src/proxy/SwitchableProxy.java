package proxy;

import io.netty.handler.codec.http.HttpRequest;
import org.littleshoot.proxy.ChainedProxy;
import org.littleshoot.proxy.ChainedProxyAdapter;
import org.littleshoot.proxy.ChainedProxyManager;
import org.littleshoot.proxy.ChainedProxyType;
import org.littleshoot.proxy.impl.ClientDetails;

import java.net.InetSocketAddress;
import java.util.Queue;

/**
 * Routes traffic through an upstream HTTP proxy when one is configured,
 * or falls back to a direct connection otherwise.
 */
public class SwitchableProxy implements ChainedProxyManager {

    private final ProxyServer config;

    public SwitchableProxy(ProxyServer config) {
        this.config = config;
    }

    @Override
    public void lookupChainedProxies(HttpRequest httpRequest,
                                     Queue<ChainedProxy> chainedProxies,
                                     ClientDetails clientDetails) {
        // Always send loopback traffic direct, even when an upstream proxy is
        // configured. Chrome bypasses the proxy for localhost automatically;
        // without this, local HTML games and their redirects get routed through
        // the proxy chain and fail to resolve. Normal (non-loopback) routing
        // below is unchanged.
        if (isLoopback(httpRequest.uri())
                || isLoopback(httpRequest.headers().get("Host"))) {
            chainedProxies.add(ChainedProxyAdapter.FALLBACK_TO_DIRECT_CONNECTION);
            return;
        }

        ProxyServer.Upstream upstream = config.getUpstream();
        if (upstream == null) {
            // ChainedProxyAdapter.FALLBACK_TO_DIRECT_CONNECTION tells
            // LittleProxy to connect directly without chaining
            chainedProxies.add(ChainedProxyAdapter.FALLBACK_TO_DIRECT_CONNECTION);
            return;
        }

        chainedProxies.add(new ChainedProxyAdapter() {
            @Override
            public InetSocketAddress getChainedProxyAddress() {
                return new InetSocketAddress(upstream.host(), upstream.port());
            }
            @Override
            public ChainedProxyType getChainedProxyType() {
                return switch (upstream.type()) {
                    case HTTP -> ChainedProxyType.HTTP;
                    case SOCKS4 -> ChainedProxyType.SOCKS4;
                    case SOCKS5 -> ChainedProxyType.SOCKS5;
                };
            }
        });
    }

    /**
     * True for loopback hosts that should never be routed through an upstream
     * proxy. Accepts either a bare host, host:port, or a CONNECT authority
     * (e.g. "localhost:3000").
     */
    private boolean isLoopback(String hostPort) {
        if (hostPort == null || hostPort.isEmpty()) return false;
        String h = hostPort.trim().toLowerCase();
        // Strip scheme if present
        int scheme = h.indexOf("://");
        if (scheme >= 0) h = h.substring(scheme + 3);
        // IPv6 literal: [::1] or [::1]:port
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            if (end > 0) h = h.substring(1, end);
        } else {
            // Strip port and any path
            int colon = h.indexOf(':');
            if (colon >= 0) h = h.substring(0, colon);
            int slash = h.indexOf('/');
            if (slash >= 0) h = h.substring(0, slash);
        }
        return h.equals("localhost")
            || h.equals("127.0.0.1")
            || h.startsWith("127.")
            || h.equals("::1")
            || h.endsWith(".localhost");
    }
}
