package proxy;

import org.littleshoot.proxy.HostResolver;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DNS resolver for Rigel's proxy stack with optional DNS-over-HTTPS.
 *
 * <p>Without DoH every CONNECT target is resolved through the operating
 * system's plaintext resolver, which reveals every visited hostname to the
 * local network/ISP even though page traffic itself is HTTPS. In
 * {@link Mode#CLOUDFLARE} or {@link Mode#GOOGLE} mode queries are sent as
 * RFC 8484 DNS wire messages inside HTTPS to fixed resolver IP endpoints
 * (no bootstrap DNS lookup is needed, so enabling DoH cannot itself leak).
 *
 * <p>If a DoH exchange fails Rigel falls back to the system resolver rather
 * than breaking the page load; the fallback is logged so the degradation is
 * never silent. Results are cached respecting the record TTL so the hot path
 * adds no network round-trip.</p>
 *
 * <p>LittleProxy consumes this through {@link #asHostResolver()}; the SOCKS5
 * ingress and UDP relay call {@link #resolve(String, int)} directly.</p>
 */
public final class SecureDns {

    public enum Mode {
        /** Plain operating-system resolver (default JVM behaviour). */
        SYSTEM,
        /** Cloudflare DoH at 1.1.1.1 / 1.0.0.1. */
        CLOUDFLARE,
        /** Google DoH at 8.8.8.8 / 8.8.4.4. */
        GOOGLE;

        public static Mode fromSetting(String value) {
            if (value == null || value.isBlank()) return CLOUDFLARE;
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return CLOUDFLARE;
            }
        }

        public String settingValue() { return name().toLowerCase(Locale.ROOT); }
    }

    private static final int TYPE_A = 1;
    private static final int TYPE_AAAA = 28;
    private static final int CLASS_IN = 1;
    private static final int MAX_MESSAGE = 4096;
    private static final int MAX_CACHE_ENTRIES = 2048;
    private static final long MIN_TTL_SECONDS = 30;
    private static final long MAX_TTL_SECONDS = 3600;
    private static final long NEGATIVE_TTL_SECONDS = 60;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);

    /** Resolver IPs only: contacting them needs no preceding DNS lookup. */
    private static final String[] CLOUDFLARE_ENDPOINTS = {
            "https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query"};
    private static final String[] GOOGLE_ENDPOINTS = {
            "https://8.8.8.8/dns-query", "https://8.8.4.4/dns-query"};

    private static volatile Mode mode = Mode.CLOUDFLARE;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final ConcurrentHashMap<String, CacheEntry> CACHE =
            new ConcurrentHashMap<>();
    private static volatile boolean fallbackLogged = false;

    private record CacheEntry(List<InetAddress> addresses, long expiresAtNanos) {}

    private SecureDns() {}

    public static Mode getMode() { return mode; }

    public static void setMode(Mode next) {
        mode = Objects.requireNonNull(next, "mode");
        CACHE.clear();
        fallbackLogged = false;
    }

    /** LittleProxy hook: resolves CONNECT targets and the upstream host. */
    public static HostResolver asHostResolver() {
        return new HostResolver() {
            @Override
            public InetSocketAddress resolve(String host, int port)
                    throws UnknownHostException {
                return SecureDns.resolve(host, port);
            }
        };
    }

    /**
     * Resolves {@code host} to an address according to the configured mode.
     * IP literals never hit the network. DoH failures fall back to the system
     * resolver (logged once per mode change) so connectivity is never broken
     * by a resolver outage.
     */
    public static InetSocketAddress resolve(String host, int port)
            throws UnknownHostException {
        InetAddress literal = tryParseLiteral(host);
        if (literal != null) return new InetSocketAddress(literal, port);
        // Loopback names never leave the machine; resolving them over DoH
        // would needlessly fail on offline networks.
        String lowered = host.toLowerCase(Locale.ROOT);
        if (lowered.equals("localhost") || lowered.endsWith(".localhost")) {
            return systemResolve(host, port);
        }

        Mode active = mode;
        if (active == Mode.SYSTEM) return systemResolve(host, port);

        String key = host.toLowerCase(Locale.ROOT);
        CacheEntry cached = CACHE.get(key);
        long now = System.nanoTime();
        if (cached != null) {
            if (now < cached.expiresAtNanos()) {
                if (cached.addresses().isEmpty()) {
                    throw new UnknownHostException(host);
                }
                return new InetSocketAddress(cached.addresses().get(0), port);
            }
            CACHE.remove(key, cached);
        }

        try {
            return dohResolve(active, host, port, key, now);
        } catch (UnknownHostException notFound) {
            // A definitive NXDOMAIN/NODATA over DoH is authoritative; do not
            // second-guess it with a plaintext system lookup.
            throw notFound;
        } catch (Exception transportFailure) {
            logFallbackOnce(active, transportFailure);
            return systemResolve(host, port);
        }
    }

    /** System resolver path shared by SYSTEM mode and the DoH fallback. */
    private static InetSocketAddress systemResolve(String host, int port)
            throws UnknownHostException {
        return new InetSocketAddress(InetAddress.getByName(host), port);
    }

    private static InetSocketAddress dohResolve(Mode active, String host,
                                                int port, String cacheKey,
                                                long nowNanos) throws Exception {
        // A and AAAA are queried so dual-stack hosts keep working exactly as
        // they would under the system resolver.
        List<InetAddress> addresses = new ArrayList<>(4);
        long minTtl = MAX_TTL_SECONDS;
        boolean anyAnswer = false;
        for (int type : new int[] {TYPE_A, TYPE_AAAA}) {
            Response response = exchange(active, host, type);
            if (response.refused) {
                throw new IOException("DoH server refused the query");
            }
            if (response.nxdomain) {
                // One family reporting NXDOMAIN is definitive for the name.
                cacheNegative(cacheKey, nowNanos);
                throw new UnknownHostException(host);
            }
            if (!response.addresses.isEmpty()) {
                anyAnswer = true;
                addresses.addAll(response.addresses);
                minTtl = Math.min(minTtl, response.minTtlSeconds);
            }
        }
        if (!anyAnswer) {
            cacheNegative(cacheKey, nowNanos);
            throw new UnknownHostException(host);
        }
        long ttlSeconds = Math.max(MIN_TTL_SECONDS, Math.min(minTtl, MAX_TTL_SECONDS));
        putCache(cacheKey, new CacheEntry(List.copyOf(addresses),
                nowNanos + ttlSeconds * 1_000_000_000L));
        return new InetSocketAddress(addresses.get(0), port);
    }

    private record Response(List<InetAddress> addresses, long minTtlSeconds,
                            boolean nxdomain, boolean refused) {}

    /** RFC 8484 GET exchange against each endpoint until one succeeds. */
    private static Response exchange(Mode active, String host, int type)
            throws Exception {
        byte[] query = encodeQuery(host, type);
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(query);
        Exception last = null;
        String[] endpoints = active == Mode.GOOGLE
                ? GOOGLE_ENDPOINTS : CLOUDFLARE_ENDPOINTS;
        for (String endpoint : endpoints) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint + "?dns=" + encoded))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Accept", "application/dns-message")
                        .GET()
                        .build();
                HttpResponse<byte[]> reply = CLIENT.send(request,
                        HttpResponse.BodyHandlers.ofByteArray());
                if (reply.statusCode() != 200) {
                    throw new IOException("DoH status " + reply.statusCode());
                }
                return decodeReply(query, reply.body());
            } catch (Exception error) {
                last = error;
            }
        }
        throw last == null ? new IOException("No DoH endpoints configured") : last;
    }

    // ── DNS wire format (RFC 1035 / RFC 8484) ────────────────────────────

    private static byte[] encodeQuery(String host, int type) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        int id = ThreadLocalRandom.current().nextInt(0x10000);
        writeShort(out, id);            // ID
        writeShort(out, 0x0100);        // RD
        writeShort(out, 1);             // QDCOUNT
        writeShort(out, 0);             // ANCOUNT
        writeShort(out, 0);             // NSCOUNT
        writeShort(out, 0);             // ARCOUNT
        for (String label : host.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            if (bytes.length == 0 || bytes.length > 63) {
                throw new IOException("Invalid hostname label");
            }
            out.write(bytes.length);
            out.write(bytes);
        }
        out.write(0);
        writeShort(out, type);
        writeShort(out, CLASS_IN);
        return out.toByteArray();
    }

    private static Response decodeReply(byte[] query, byte[] reply)
            throws IOException {
        if (reply == null || reply.length > MAX_MESSAGE || reply.length < 12) {
            throw new IOException("Malformed DoH reply");
        }
        Reader in = new Reader(reply);
        int queryId = (query[0] & 0xFF) << 8 | query[1] & 0xFF;
        if (in.readShort() != queryId) throw new IOException("DoH ID mismatch");
        int flags = in.readShort();
        int rcode = flags & 0x0F;
        if (rcode == 2) throw new IOException("DoH SERVFAIL");
        if (rcode == 5) return new Response(List.of(), 0, false, true);
        int questions = in.readShort();
        int answers = in.readShort();
        in.readShort(); // NSCOUNT
        in.readShort(); // ARCOUNT
        for (int i = 0; i < questions; i++) skipQuestion(in);

        List<InetAddress> addresses = new ArrayList<>(4);
        long minTtl = MAX_TTL_SECONDS;
        for (int i = 0; i < answers; i++) {
            skipName(in);
            int type = in.readShort();
            in.readShort(); // class
            long ttl = in.readUnsignedInt();
            int length = in.readShort();
            byte[] data = in.readBytes(length);
            if (type == TYPE_A && length == 4) {
                addresses.add(InetAddress.getByAddress(data));
                minTtl = Math.min(minTtl, ttl);
            } else if (type == TYPE_AAAA && length == 16) {
                addresses.add(InetAddress.getByAddress(data));
                minTtl = Math.min(minTtl, ttl);
            }
            // CNAME and other records are ignored; the resolver already
            // followed them and we keep only the terminal addresses.
        }
        return new Response(addresses, minTtl, rcode == 3, false);
    }

    private static void skipQuestion(Reader in) throws IOException {
        skipName(in);
        in.readShort();
        in.readShort();
    }

    /** Skips a possibly-compressed domain name at the reader position. */
    private static void skipName(Reader in) throws IOException {
        while (true) {
            int length = in.readByte();
            if (length == 0) return;
            if ((length & 0xC0) == 0xC0) {
                in.readByte(); // second pointer octet
                return;
            }
            if ((length & 0xC0) != 0) throw new IOException("Bad DNS label");
            in.skip(length);
        }
    }

    private static void cacheNegative(String key, long nowNanos) {
        putCache(key, new CacheEntry(List.of(),
                nowNanos + NEGATIVE_TTL_SECONDS * 1_000_000_000L));
    }

    private static void putCache(String key, CacheEntry entry) {
        if (CACHE.size() >= MAX_CACHE_ENTRIES) {
            CACHE.clear(); // simplest safe eviction; cache repopulates on demand
        }
        CACHE.put(key, entry);
    }

    private static void logFallbackOnce(Mode active, Exception failure) {
        if (!fallbackLogged) {
            fallbackLogged = true;
            System.err.println("[Rigel] DoH (" + active + ") unreachable ("
                    + failure.getMessage()
                    + "); falling back to system DNS until the setting changes");
        }
    }

    private static InetAddress tryParseLiteral(String host) {
        try {
            if (host == null || host.isEmpty()) return null;
            if (host.indexOf(':') >= 0) {
                // Possible IPv6 literal (with or without brackets).
                String bare = host.startsWith("[") && host.endsWith("]")
                        ? host.substring(1, host.length() - 1) : host;
                if (bare.indexOf(':') < 0) return null;
                InetAddress[] all = InetAddress.getAllByName(bare);
                return all.length > 0 && all[0] instanceof Inet6Address
                        ? all[0] : null;
            }
            boolean numeric = true;
            for (int i = 0; i < host.length(); i++) {
                char c = host.charAt(i);
                if ((c < '0' || c > '9') && c != '.') { numeric = false; break; }
            }
            if (!numeric) return null;
            InetAddress[] all = InetAddress.getAllByName(host);
            return all.length > 0 && all[0] instanceof Inet4Address
                    ? all[0] : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static final class Reader {
        private final byte[] data;
        private int pos;

        Reader(byte[] data) { this.data = data; }

        int readByte() throws IOException {
            if (pos >= data.length) throw new EOFException();
            return data[pos++] & 0xFF;
        }

        int readShort() throws IOException {
            return (readByte() << 8) | readByte();
        }

        long readUnsignedInt() throws IOException {
            return ((long) readShort() << 16) | readShort();
        }

        byte[] readBytes(int length) throws IOException {
            if (length < 0 || pos + length > data.length) throw new EOFException();
            byte[] out = new byte[length];
            System.arraycopy(data, pos, out, 0, length);
            pos += length;
            return out;
        }

        void skip(int length) throws IOException {
            if (length < 0 || pos + length > data.length) throw new EOFException();
            pos += length;
        }
    }
}
