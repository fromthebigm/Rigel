package proxy;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Domain Fronting: circumvent home IP blocks by connecting to an "allowed"
 * domain on the same CDN, then sending the real domain in the Host header.
 * The router sees "connecting to allowed-cdn.com" (not blocked), but the
 * CDN routes the request to the real site.
 *
 * Per-site list, managed through the UI. Each entry is a pair:
 *   real-host → front-host
 * e.g. "blocked-site.com → chess.com"
 *
 * For HTTPS (CONNECT tunnels), the fronting happens at the TLS SNI level:
 * connect to the front host's IP, but the CONNECT tunnel targets the real
 * host. The router's SNI check sees the front domain.
 */
public final class DomainFronting {

    /** realHost → frontHost */
    private static final ConcurrentHashMap<String, String> FRONTED = new ConcurrentHashMap<>();

    /**
     * Front used when an entry names no explicit front. chess.com is the
     * live-proven Cloudflare front (cdnjs.cloudflare.com hits Cloudflare
     * error 1034 "Edge IP Restricted" for some zones, so it was retired as
     * the default); Auto-detect fills an explicit same-CDN front anyway.
     */
    private static final String DEFAULT_FRONT = "chess.com";

    private DomainFronting() {}

    /** Enable fronting for a real host, using the default front domain. */
    public static void enable(String realHost) {
        enable(realHost, null);
    }

    /** Enable fronting for a real host with a specific front domain. */
    public static void enable(String realHost, String frontHost) {
        if (realHost == null || realHost.isBlank()) return;
        String real = normalize(realHost);
        String front = (frontHost == null || frontHost.isBlank())
                ? DEFAULT_FRONT : normalize(frontHost);
        FRONTED.put(real, front);
    }

    /** Disable fronting for a real host. */
    public static void disable(String realHost) {
        if (realHost == null) return;
        FRONTED.remove(normalize(realHost));
    }

    /** Clear all fronting rules. */
    public static void clear() {
        FRONTED.clear();
    }

    /** True if this real host has fronting enabled. */
    public static boolean isFronted(String host) {
        return frontHostFor(host) != null;
    }

    /**
     * Get the front domain for a real host. Null if not fronted.
     * Subdomains inherit: adding {@code tiktok.com} also fronts
     * {@code www.tiktok.com} — the browser's CONNECT almost always uses
     * the www form while humans type the bare domain, and a miss there
     * meant the block still won. Exact entry wins over inherited.
     */
    public static String frontHostFor(String realHost) {
        if (realHost == null || realHost.isBlank()) return null;
        String host = normalize(realHost);
        String front = FRONTED.get(host);
        if (front != null) return front;
        // Walk parent domains: www.tiktok.com → tiktok.com
        int dot = host.indexOf('.');
        while (dot > 0 && dot < host.length() - 1) {
            host = host.substring(dot + 1);
            front = FRONTED.get(host);
            if (front != null) return front;
            dot = host.indexOf('.');
        }
        return null;
    }

    /** Get all fronted hosts as a set of "real → front" strings. */
    public static Set<String> entries() {
        Set<String> out = ConcurrentHashMap.newKeySet();
        for (var entry : FRONTED.entrySet()) {
            out.add(entry.getKey() + " → " + entry.getValue());
        }
        return out;
    }

    /** Load from a "real|front" line-delimited string (Settings persistence). */
    public static void loadFrom(String data) {
        clear();
        if (data == null || data.isBlank()) return;
        for (String line : data.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\|", 2);
            if (parts.length == 2) {
                enable(parts[0].trim(), parts[1].trim());
            } else {
                enable(parts[0].trim(), null);
            }
        }
    }

    /** Serialize to a "real|front" line-delimited string. */
    public static String serialize() {
        StringBuilder out = new StringBuilder();
        for (var entry : FRONTED.entrySet()) {
            out.append(entry.getKey()).append('|').append(entry.getValue()).append('\n');
        }
        return out.toString();
    }

    /** Strip port, scheme, and lowercase. */
    private static String normalize(String host) {
        String clean = host.trim().toLowerCase();
        // Remove scheme if present
        if (clean.startsWith("https://")) clean = clean.substring(8);
        else if (clean.startsWith("http://")) clean = clean.substring(7);
        // Remove path
        int slash = clean.indexOf('/');
        if (slash > 0) clean = clean.substring(0, slash);
        // Remove port
        int colon = clean.lastIndexOf(':');
        if (colon > 0 && clean.indexOf(':') == colon) {
            clean = clean.substring(0, colon);
        }
        return clean;
    }

    /** The default front domain, for UI display. */
    public static String defaultFront() {
        return DEFAULT_FRONT;
    }
}
