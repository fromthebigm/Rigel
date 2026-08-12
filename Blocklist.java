package proxy;

import java.util.Set;

/**
 * Blocklist — lightweight host-based ad/tracker blocking, toggleable.
 *
 * When enabled, requests whose host matches (or is a subdomain of) a blocked
 * domain are short-circuited by the proxy with an empty 204 response. The list
 * is a small built-in set of common ad/tracker domains; it is intentionally
 * conservative so it does not break page functionality. Disabled by default.
 *
 * Matching is suffix-based: a rule "doubleclick.net" blocks "doubleclick.net"
 * and "ad.doubleclick.net" but never "notdoubleclick.net".
 */
public class Blocklist {

    private volatile boolean enabled;
    private final Set<String> domains;

    public Blocklist(boolean enabled) {
        this.enabled = enabled;
        this.domains = seedDefaults();
    }

    private static Set<String> seedDefaults() {
        return Set.of(
            "2mdn.net",
            "adform.net",
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "google-analytics.com",
            "googletagservices.com",
            "adservice.google.com",
            "analytics.google.com",
            "scorecardresearch.com",
            "adnxs.com",
            "ads-twitter.com",
            "adsafeprotected.com",
            "amazon-adsystem.com",
            "taboola.com",
            "outbrain.com",
            "criteo.com",
            "criteo.net",
            "moatads.com",
            "quantserve.com",
            "adsrvr.org",
            "rubiconproject.com",
            "pubmatic.com",
            "openx.net",
            "casalemedia.com",
            "bidswitch.net",
            "zedo.com",
            "advertising.com",
            "serving-sys.com",
            "adzerk.net",
            "app-measurement.com",
            "bluekai.com",
            "chartbeat.com",
            "clarity.ms",
            "demdex.net",
            "everesttech.net",
            "eyeota.net",
            "flashtalking.com",
            "hotjar.com",
            "imrworldwide.com",
            "krxd.net",
            "lijit.com",
            "mathtag.com",
            "media.net",
            "mixpanel.com",
            "newrelic.com",
            "optimizely.com",
            "rlcdn.com",
            "segment.com",
            "segment.io",
            "sharethrough.com",
            "smartadserver.com",
            "spotxchange.com",
            "tapad.com",
            "teads.tv",
            "tradedoubler.com",
            "undertone.com",
            "yieldmanager.com",
            "yieldmo.com"
        );
    }

    public void setEnabled(boolean v) { this.enabled = v; }
    public boolean isEnabled()        { return enabled; }

    /**
     * True if the given host should be blocked. Always false when disabled, so
     * callers can check this unconditionally with no behavior change when off.
     */
    public boolean isBlocked(String host) {
        if (!enabled || host == null || host.isEmpty()) return false;
        String h = normalizeHost(host);
        while (!h.isEmpty()) {
            if (domains.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0) break;
            h = h.substring(dot + 1);
        }
        return false;
    }

    private static String normalizeHost(String value) {
        String host = value.trim().toLowerCase(java.util.Locale.ROOT);
        int scheme = host.indexOf("://");
        if (scheme >= 0) host = host.substring(scheme + 3);
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end > 0 ? host.substring(1, end) : host;
        }
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) host = host.substring(0, colon);
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }
}
