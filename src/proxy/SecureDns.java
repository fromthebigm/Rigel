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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p>Throughput notes (the regression that shipped between 2026.09.17 and
 * 2026.09.20 taught these the hard way): media-heavy pages open dozens of
 * CONNECT tunnels to the same CDN host at the same moment, so this class
 * <b>must</b> be safe under that fan-out —
 * one shared in-flight lookup per hostname (single-flight), A/AAAA queried
 * in parallel instead of serially, cached answers rotated across addresses
 * so a single congested CDN edge cannot carry every connection for a whole
 * TTL window, and cache eviction that sheds expired slices instead of
 * flushing every warm entry at once.</p>
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
    /** Waiter ceiling for a shared in-flight lookup: generously above the
     *  owner's worst-case (parallel families, both endpoints tried). */
    private static final long SHARED_LOOKUP_TIMEOUT_MS =
            REQUEST_TIMEOUT.toMillis() * 2 + 2_000;

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
    // Package-private (not private) so the offline core test can assert
    // cache and in-flight state directly.
    static final ConcurrentHashMap<String, CacheEntry> CACHE =
            new ConcurrentHashMap<>();
    /** One in-flight lookup per hostname: N simultaneous CONNECTs to the same
     *  host share a single DoH exchange instead of firing N of them. */
    static final ConcurrentHashMap<String, CompletableFuture<CacheEntry>> IN_FLIGHT =
            new ConcurrentHashMap<>();    /** Round-robin cursor over cached addresses. */
    private static final AtomicInteger ROTATION = new AtomicInteger();
    /**
     * Offline test seam: when non-null, replaces the real DoH exchange so the
     * core test can drive fan-out, caching, and rotation deterministically.
     * Production never sets it. Package-private on purpose.
     */
    static volatile FamilyBackend BACKEND_OVERRIDE;
    /** DoH exchanges run here so A and AAAA go out in parallel. Daemon
     *  threads: the JVM must never be held open by a lingering lookup. */
    private static final ExecutorService LOOKUP_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "rigel-doh-lookup");
        t.setDaemon(true);
        return t;
    });
    private static volatile boolean fallbackLogged = false;

    private record CacheEntry(List<InetAddress> addresses, long expiresAtNanos) {}

    /**
     * Network backend seam. Package-private so the offline core test can
     * exercise single-flight, rotation, and caching deterministically with a
     * counting stub; production wires the real DoH exchange.
     */
    interface FamilyBackend {
        Response query(int type) throws Exception;
    }

    /** Decoded answer of one family's exchange. */
    record Response(List<InetAddress> addresses, long minTtlSeconds,
                    boolean nxdomain, boolean refused) {}

    private SecureDns() {}

    public static Mode getMode() { return mode; }

    public static void setMode(Mode next) {
        mode = Objects.requireNonNull(next, "mode");
        CACHE.clear();
        IN_FLIGHT.clear();
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

        String key = lowered;
        long now = System.nanoTime();
        CacheEntry cached = CACHE.get(key);
        if (cached != null) {
            if (now < cached.expiresAtNanos()) {
                return addressFrom(cached, port, host);
            }
            CACHE.remove(key, cached);
        }

        try {
            return addressFrom(sharedLookup(active, host, key), port, host);
        } catch (UnknownHostException notFound) {
            // A definitive NXDOMAIN/NODATA over DoH is authoritative; do not
            // second-guess it with a plaintext system lookup.
            throw notFound;
        } catch (Exception transportFailure) {
            logFallbackOnce(active, transportFailure);
            return systemResolve(host, port);
        }
    }

    /**
     * Single-flight lookup: the first caller for a hostname owns the network
     * exchange; concurrent callers for the same name await (and share) that
     * one result. A cache-priming burst of parallel CONNECTs therefore costs
     * one DoH round-trip, not one per tunnel.
     */
    private static CacheEntry sharedLookup(Mode active, String host, String key)
            throws Exception {
        CompletableFuture<CacheEntry> mine = new CompletableFuture<>();
        CompletableFuture<CacheEntry> existing = IN_FLIGHT.putIfAbsent(key, mine);
        if (existing != null) {
            return awaitLookup(existing);
        }
        try {
            FamilyBackend backend = BACKEND_OVERRIDE != null
                    ? BACKEND_OVERRIDE
                    : type -> exchange(active, host, type);
            CacheEntry entry = lookupViaBackend(host, backend);
            putCache(key, entry);
            mine.complete(entry);
            return entry;
        } catch (Throwable failure) {
            // Transport failures are not negative-cached; the next caller
            // retries immediately (or falls back via resolve()).
            mine.completeExceptionally(failure);
            throw rethrow(failure);
        } finally {
            IN_FLIGHT.remove(key, mine);
        }
    }

    /** Joins a lookup owned by another thread, preserving exception types. */
    private static CacheEntry awaitLookup(CompletableFuture<CacheEntry> future)
            throws Exception {
        try {
            return future.get(SHARED_LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException wrapped) {
            throw rethrow(wrapped.getCause());
        } catch (java.util.concurrent.TimeoutException timedOut) {
            throw new IOException("Shared DoH lookup timed out", timedOut);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while awaiting DoH lookup", interrupted);
        }
    }

    private static Exception rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) throw exception;
        throw new IOException(failure);
    }

    /**
     * Core lookup over a (possibly stubbed) backend. A and AAAA are queried
     * in parallel; one family failing to transport while the other answers
     * keeps the answers (availability), while both failing falls through to
     * the system-resolver path in {@link #resolve}.
     */
    static CacheEntry lookupViaBackend(String host, FamilyBackend backend) throws Exception {
        CompletableFuture<Response> a = supplyFamily(backend, TYPE_A);
        CompletableFuture<Response> aaaa = supplyFamily(backend, TYPE_AAAA);
        Exception transportFailure = null;
        List<Response> answered = new ArrayList<>(2);
        for (CompletableFuture<Response> family : List.of(a, aaaa)) {
            try {
                answered.add(family.get(
                        REQUEST_TIMEOUT.toMillis() + 1_000, TimeUnit.MILLISECONDS));
            } catch (ExecutionException wrapped) {
                transportFailure = wrapped.getCause() instanceof Exception cause
                        ? cause : new IOException(wrapped.getCause());
            }
        }
        if (answered.isEmpty()) {
            throw transportFailure != null
                    ? transportFailure : new IOException("No DoH endpoints configured");
        }
        for (Response response : answered) {
            if (response.refused) throw new IOException("DoH server refused the query");
        }
        long now = System.nanoTime();
        for (Response response : answered) {
            if (response.nxdomain) {
                // One family reporting NXDOMAIN is definitive for the name.
                return new CacheEntry(List.of(),
                        now + NEGATIVE_TTL_SECONDS * 1_000_000_000L);
            }
        }
        List<InetAddress> addresses = new ArrayList<>(4);
        long minTtl = MAX_TTL_SECONDS;
        for (Response response : answered) {
            addresses.addAll(response.addresses());
            minTtl = Math.min(minTtl, response.minTtlSeconds);
        }
        if (addresses.isEmpty()) {
            if (transportFailure != null) throw transportFailure;
            return new CacheEntry(List.of(),
                    now + NEGATIVE_TTL_SECONDS * 1_000_000_000L);
        }
        long ttlSeconds = Math.max(MIN_TTL_SECONDS, Math.min(minTtl, MAX_TTL_SECONDS));
        return new CacheEntry(List.copyOf(addresses),
                now + ttlSeconds * 1_000_000_000L);
    }

    private static CompletableFuture<Response> supplyFamily(FamilyBackend backend, int type) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return backend.query(type);
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        }, LOOKUP_POOL);
    }

    /** Picks the next cached address round-robin; negative entries throw. */
    private static InetSocketAddress addressFrom(CacheEntry entry, int port, String host)
            throws UnknownHostException {
        List<InetAddress> addresses = entry.addresses();
        if (addresses.isEmpty()) throw new UnknownHostException(host);
        InetAddress pick = addresses.get(
                Math.floorMod(ROTATION.getAndIncrement(), addresses.size()));
        return new InetSocketAddress(pick, port);
    }

    /** System resolver path shared by SYSTEM mode and the DoH fallback. */
    private static InetSocketAddress systemResolve(String host, int port)
            throws UnknownHostException {
        return new InetSocketAddress(InetAddress.getByName(host), port);
    }

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

    private static void putCache(String key, CacheEntry entry) {
        if (CACHE.size() >= MAX_CACHE_ENTRIES) {
            // Shed expired entries first; only if that is not enough, drop a
            // slice. Never flush the whole cache: a flush turns every warm
            // hostname into a simultaneous re-resolve storm (the exact fan-out
            // this class exists to survive).
            long now = System.nanoTime();
            for (var it = CACHE.entrySet().iterator(); it.hasNext(); ) {
                if (it.next().getValue().expiresAtNanos() <= now) it.remove();
            }
            if (CACHE.size() >= MAX_CACHE_ENTRIES) {
                int toShed = Math.max(1, MAX_CACHE_ENTRIES / 8);
                var it = CACHE.keySet().iterator();
                while (it.hasNext() && toShed-- > 0) {
                    it.next();
                    it.remove();
                }
            }
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
