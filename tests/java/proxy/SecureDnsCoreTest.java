package proxy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Offline, deterministic checks for SecureDns's fan-out safety: the
 * single-flight lookup, the TTL cache, address rotation, and NXDOMAIN
 * negative caching. The network backend is stubbed, so no DoH server is
 * contacted and the suite behaves identically on any network.
 */
public final class SecureDnsCoreTest {

    private static final InetAddress EDGE_A = address("203.0.113.10");
    private static final InetAddress EDGE_B = address("203.0.113.11");
    private static final int TTL_SECONDS = 300;

    public static void main(String[] args) throws Exception {
        try {
            runTests();
        } finally {
            SecureDns.BACKEND_OVERRIDE = null;
            SecureDns.setMode(SecureDns.Mode.SYSTEM);
        }
        System.out.println("SecureDns core checks passed: single-flight fan-out, "
                + "TTL cache, rotation, NXDOMAIN negative cache, transport fallback cleanup");
    }

    private static void runTests() throws Exception {
        SecureDns.setMode(SecureDns.Mode.CLOUDFLARE);

        testSingleFlightUnderParallelConnectFanOut();
        testCacheSuppliesSubsequentLookupsAndRotatesAddresses();
        testDualStackAnswersPreferReachableFamily();
        testNxdomainIsAuthoritativeAndNegativeCached();
        testTransportFailureLeavesNoCachedOrInFlightState();
    }

    /**
     * Dual-stack answers (A + AAAA) must never round-robin onto the v6
     * address: on an IPv4-only or half-broken-v6 network that hard-fails
     * every other CONNECT (real report: tailscale.com unreachable). Any v6
     * pick here means the family preference regressed.
     */
    private static void testDualStackAnswersPreferReachableFamily() throws Exception {
        SecureDns.setMode(SecureDns.Mode.CLOUDFLARE);
        SecureDns.BACKEND_OVERRIDE = type -> {
            if (type == 1) {
                return new SecureDns.Response(List.of(EDGE_A, EDGE_B), 300, false, false);
            }
            return new SecureDns.Response(
                    List.of(InetAddress.getByName("2001:db8::1")), 300, false, false);
        };
        try {
            for (int i = 0; i < 8; i++) {
                InetSocketAddress resolved = SecureDns.resolve("dual.example", 443);
                if (!(resolved.getAddress() instanceof java.net.Inet4Address)) {
                    throw new AssertionError(
                            "dual-stack rotation handed out IPv6 on a mixed answer: "
                                    + resolved.getAddress());
                }
            }
        } finally {
            SecureDns.BACKEND_OVERRIDE = null;
            SecureDns.setMode(SecureDns.Mode.CLOUDFLARE);
        }
    }

    /**
     * The regression that stuttered video sites: a page opens many CONNECT
     * tunnels to the same CDN host at once, and every tunnel used to pay its
     * own DoH round-trip. All callers must share ONE exchange per family.
     */
    private static void testSingleFlightUnderParallelConnectFanOut() throws Exception {
        SecureDns.setMode(SecureDns.Mode.CLOUDFLARE);
        CountDownLatch holdFirstLookup = new CountDownLatch(1);
        AtomicInteger queries = new AtomicInteger();

        // Hold the first A query so the fan-out genuinely overlaps one live
        // exchange; every family is counted.
        SecureDns.BACKEND_OVERRIDE = type -> {
            queries.incrementAndGet();
            if (type == 1) holdFirstLookup.await(5, TimeUnit.SECONDS);
            return respond(type);
        };

        int callers = 12;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        ConcurrentHashMap<Integer, String> results = new ConcurrentHashMap<>();
        for (int i = 0; i < callers; i++) {
            int id = i;
            Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    InetSocketAddress resolved = SecureDns.resolve("fan-out.cdn.example", 443);
                    results.put(id, resolved.getAddress().getHostAddress());
                } catch (Exception e) {
                    results.put(id, "error: " + e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        // Let the owner thread reach its (held) exchange before releasing.
        Thread.sleep(150);
        holdFirstLookup.countDown();
        if (!done.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("fan-out callers did not finish");
        }

        for (String result : results.values()) {
            if (!"203.0.113.10".equals(result) && !"203.0.113.11".equals(result)) {
                throw new AssertionError("unexpected resolve result: " + result);
            }
        }
        if (queries.get() != 2) {
            throw new AssertionError(
                    "single-flight violated: " + callers + " parallel callers produced "
                            + queries.get() + " backend queries (expected 2: A + AAAA)");
        }
        if (!SecureDns.IN_FLIGHT.isEmpty()) {
            throw new AssertionError("in-flight table leaked an entry");
        }
    }

    private static void testCacheSuppliesSubsequentLookupsAndRotatesAddresses() throws Exception {
        SecureDns.setMode(SecureDns.Mode.CLOUDFLARE);
        SecureDns.BACKEND_OVERRIDE = new CountingBackend(TTL_SECONDS);

        SecureDns.resolve("rotate.cdn.example", 443);
        int queriesAfterFirst = ((CountingBackend) SecureDns.BACKEND_OVERRIDE).count();

        Set<String> picked = java.util.concurrent.ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 6; i++) {
            InetSocketAddress resolved = SecureDns.resolve("rotate.cdn.example", 443);
            picked.add(resolved.getAddress().getHostAddress());
        }
        if (((CountingBackend) SecureDns.BACKEND_OVERRIDE).count() != queriesAfterFirst) {
            throw new AssertionError("cached hostname re-queried the backend");
        }
        if (!picked.contains("203.0.113.10") || !picked.contains("203.0.113.11")) {
            throw new AssertionError(
                    "rotation pinned a single address: " + picked + " — a congested "
                            + "CDN edge would carry every connection for a whole TTL");
        }
    }

    private static void testNxdomainIsAuthoritativeAndNegativeCached() throws Exception {
        SecureDns.setMode(SecureDns.Mode.CLOUDFLARE);
        AtomicInteger queries = new AtomicInteger();
        SecureDns.BACKEND_OVERRIDE = type -> {
            queries.incrementAndGet();
            return new SecureDns.Response(List.of(), 0, type == 1, false);
        };

        try {
            SecureDns.resolve("missing.cdn.example", 443);
            throw new AssertionError("NXDOMAIN must not resolve");
        } catch (UnknownHostException expected) { }
        int afterFirst = queries.get();

        try {
            SecureDns.resolve("missing.cdn.example", 443);
            throw new AssertionError("negative entry must not resolve");
        } catch (UnknownHostException expected) { }
        if (queries.get() != afterFirst) {
            throw new AssertionError("NXDOMAIN was not negative-cached");
        }
    }

    private static void testTransportFailureLeavesNoCachedOrInFlightState() throws Exception {
        SecureDns.setMode(SecureDns.Mode.CLOUDFLARE);
        AtomicInteger queries = new AtomicInteger();
        SecureDns.BACKEND_OVERRIDE = type -> {
            queries.incrementAndGet();
            throw new java.io.IOException("simulated DoH outage");
        };

        // Transport failure falls back to the system resolver; ".example" is
        // IANA-reserved and cannot resolve there, so this ends as UHE.
        try {
            SecureDns.resolve("outage.cdn.example", 443);
            throw new AssertionError("reserved name must not resolve");
        } catch (UnknownHostException expected) { }
        if (!SecureDns.CACHE.isEmpty()) {
            throw new AssertionError("transport failure polluted the cache");
        }
        if (!SecureDns.IN_FLIGHT.isEmpty()) {
            throw new AssertionError("transport failure leaked an in-flight entry");
        }
        if (queries.get() != 2) {
            throw new AssertionError("unexpected query count after outage: " + queries.get());
        }
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private static SecureDns.Response respond(int type) {
        if (type == 1) {
            return new SecureDns.Response(List.of(EDGE_A, EDGE_B), TTL_SECONDS, false, false);
        }
        // IPv6-less environment: NODATA for AAAA is normal and must not fail
        // the lookup while A answered.
        return new SecureDns.Response(List.of(), 0, false, false);
    }

    private static final class CountingBackend implements SecureDns.FamilyBackend {
        private final AtomicInteger queries = new AtomicInteger();
        private final int ttl;

        CountingBackend(int ttl) { this.ttl = ttl; }

        int count() { return queries.get(); }

        @Override public SecureDns.Response query(int type) {
            queries.incrementAndGet();
            if (type == 1) {
                return new SecureDns.Response(List.of(EDGE_A, EDGE_B), ttl, false, false);
            }
            return new SecureDns.Response(List.of(), 0, false, false);
        }
    }

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
