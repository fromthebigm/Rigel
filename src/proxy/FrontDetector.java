package proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Detects which CDN a hostname sits behind, so domain fronting can
 * auto-fill a working front instead of the user guessing.
 *
 * <p>Fronting only works when the front domain shares the blocked site's
 * CDN — an Akamai site (TikTok) can be reached through any other Akamai
 * edge (bing), but never through a Cloudflare front. The detector reads
 * the CNAME chain and A-record IPs through the active resolver
 * (ODoH included — detection queries reveal the blocked name to the
 * resolver, so they travel the same oblivious path as everything else)
 * and maps the CDN to a <b>live-proven</b> front:</p>
 *
 * <ul>
 *   <li><b>Akamai</b> (edgesuite.net / akamaiedge.net / edgekey.net /
 *       akadns.net) → {@code www.bing.com}. Proven: www.tiktok.com
 *       returns its real homepage through bing's shared edge, valid
 *       TikTok cert (Apple's Akamai edge is dedicated and refuses —
 *       cross-tenant serving depends on shared akamaiedge hosts).</li>
 *   <li><b>Cloudflare</b> (A record inside Cloudflare's published v4
 *       ranges) → {@code chess.com}. Proven: example.com and itch.io
 *       both serve 200 through chess.com's edge. (cdnjs.cloudflare.com,
 *       the old default, hits Cloudflare error 1034 "Edge IP Restricted"
 *       for some zones, so it is no longer suggested.)</li>
 *   <li><b>Fastly</b> (*.fastly.net / fastlylb.net) →
 *       {@code www.python.org}. Proven: python.org and pypi.org serve
 *       each other through each other's edges, valid certs both ways.</li>
 *   <li><b>CloudFront</b> (cloudfront.net) → no suggestion: each
 *       distribution carries its own cert SANs, so cross-site fronting
 *       fails at TLS. The user must find a same-distribution front, if
 *       one even exists.</li>
 * </ul>
 */
public final class FrontDetector {

    private FrontDetector() {}

    /** CDN name + the suggested front (front null = CDN known, no front). */
    public record Result(String cdn, String front, String detail) {}

    public static Result detect(String host) {
        if (host == null || host.isBlank()) return null;
        String clean = host.trim().toLowerCase(Locale.ROOT);
        while (clean.endsWith(".")) clean = clean.substring(0, clean.length() - 1);
        if (clean.isEmpty()) return null;
        Result direct = detectOnce(clean);
        if (direct != null) return direct;
        // Humans type the bare domain; the CDN alias almost always hangs
        // off www (tiktok.com has no CNAME, www.tiktok.com → edgesuite).
        if (!clean.startsWith("www.") && clean.indexOf('.') >= 0) {
            return detectOnce("www." + clean);
        }
        return null;
    }

    private static Result detectOnce(String clean) {
        try {
            // CNAME chain first: most CDN-hosted sites announce the CDN in
            // their aliases (www.tiktok.com → *.edgesuite.net → akamai).
            byte[] cnameReply = SecureDns.rawDnsReply(clean, 5);
            if (cnameReply != null && cnameReply.length >= 12) {
                for (String target : namesInAnswer(cnameReply, 5)) {
                    Result byCname = classifyByCname(target);
                    if (byCname != null) return byCname;
                }
            }
            // Apex hosts (example.com) carry no CNAME; CDN edges are
            // identified by their A records inside published ranges.
            byte[] aReply = SecureDns.rawDnsReply(clean, 1);
            if (aReply != null && aReply.length >= 12) {
                for (int[] ipv4 : ipv4sInAnswer(aReply)) {
                    if (isCloudflareIp(ipv4)) {
                        return new Result("Cloudflare", "chess.com",
                                "A record inside Cloudflare ranges");
                    }
                    if (isFastlyIp(ipv4)) {
                        return new Result("Fastly", "www.python.org",
                                "A record inside Fastly ranges");
                    }
                }
            }
        } catch (Exception detectionFailure) {
            return null;
        }
        return null;
    }

    private static Result classifyByCname(String target) {
        String t = target.toLowerCase(Locale.ROOT);
        if (t.endsWith(".akamaiedge.net") || t.endsWith(".edgesuite.net")
                || t.endsWith(".edgekey.net") || t.endsWith(".akadns.net")
                || t.endsWith(".akamaiedge-staging.net")) {
            return new Result("Akamai", "www.bing.com", "via " + t);
        }
        if (t.endsWith(".fastly.net") || t.endsWith(".fastlylb.net")) {
            return new Result("Fastly", "www.python.org", "via " + t);
        }
        if (t.endsWith(".cloudfront.net")) {
            return new Result("CloudFront", null,
                    "per-distribution certs; no cross-site front");
        }
        if (t.endsWith(".cdn.cloudflare.net") || t.endsWith(".cloudflare.net")) {
            return new Result("Cloudflare", "chess.com", "via " + t);
        }
        return null;
    }

    // ── Cloudflare published IPv4 ranges (cloudflare.com/ips-v4) ─────────

    private static final String[] CLOUDFLARE_V4_CIDRS = {
            "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22",
            "103.31.4.0/22", "141.101.64.0/18", "108.162.192.0/18",
            "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22",
            "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
            "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
    };

    private static boolean isCloudflareIp(int[] o) {
        long ip = ((long) o[0] << 24) | ((long) o[1] << 16)
                | ((long) o[2] << 8) | o[3];
        for (String cidr : CLOUDFLARE_V4_CIDRS) {
            if (inCidr(ip, cidr)) return true;
        }
        return false;
    }

    /** ip (as produced by isCloudflareIp) inside an a.b.c.d/n range. */
    private static boolean inCidr(long ip, String cidr) {
        int slash = cidr.indexOf('/');
        String[] parts = cidr.substring(0, slash).split("\\.");
        int bits = Integer.parseInt(cidr.substring(slash + 1));
        long low = 0;
        for (String part : parts) low = (low << 8) | Long.parseLong(part);
        long mask = bits == 0 ? 0 : (-1L << (32 - bits)) & 0xFFFFFFFFL;
        return (ip & mask) == (low & mask);
    }

    /** Fastly's published v4 ranges (api.fastly.com/public-ip-list). */
    private static final String[] FASTLY_V4_CIDRS = {
            "23.235.32.0/20", "43.249.72.0/22", "103.244.50.0/24",
            "103.245.222.0/23", "103.245.224.0/24", "146.75.0.0/16",
            "151.101.0.0/16", "167.82.0.0/17", "199.232.0.0/16",
            "216.146.32.0/20", "92.123.128.0/19", "91.216.78.0/24",
    };

    private static boolean isFastlyIp(int[] o) {
        long ip = ((long) o[0] << 24) | ((long) o[1] << 16)
                | ((long) o[2] << 8) | o[3];
        for (String cidr : FASTLY_V4_CIDRS) {
            if (inCidr(ip, cidr)) return true;
        }
        return false;
    }

    // ── minimal DNS answer walker (name decompression included) ────────

    /** All record names of the given type in the answer section, fully
     *  decompressed (CNAME targets, mostly). Best-effort: any parse error
     *  just stops the walk and returns what was collected so far. */
    private static List<String> namesInAnswer(byte[] reply, int wantedType) {
        List<String> out = new ArrayList<>();
        try {
            int answers = ((reply[6] & 0xFF) << 8) | (reply[7] & 0xFF);
            Reader in = new Reader(reply);
            in.skip(12);                        // header
            in.skipName();                      // question name
            in.skip(4);                         // qtype + qclass
            for (int i = 0; i < answers; i++) {
                in.skipName();
                int type = in.readShort();
                in.readShort();                 // class
                in.readUnsignedInt();           // ttl
                int length = in.readShort();
                int rdataStart = in.pos;
                if (type == wantedType && wantedType == 5) {
                    String name = in.readName();
                    if (name != null) out.add(name);
                }
                in.pos = rdataStart + length;
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** All A-record addresses in the answer section, as [a, b, c, d]. */
    private static List<int[]> ipv4sInAnswer(byte[] reply) {
        List<int[]> out = new ArrayList<>();
        try {
            int answers = ((reply[6] & 0xFF) << 8) | (reply[7] & 0xFF);
            Reader in = new Reader(reply);
            in.skip(12);
            in.skipName();
            in.skip(4);
            for (int i = 0; i < answers; i++) {
                in.skipName();
                int type = in.readShort();
                in.readShort();
                in.readUnsignedInt();
                int length = in.readShort();
                byte[] data = in.readBytes(length);
                if (type == 1 && length == 4) {
                    out.add(new int[]{data[0] & 0xFF, data[1] & 0xFF,
                            data[2] & 0xFF, data[3] & 0xFF});
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Bounds-checked cursor over a DNS message with pointer-safe names. */
    private static final class Reader {
        private final byte[] data;
        private int pos;

        Reader(byte[] data) { this.data = data; }

        int readShort() {
            if (pos + 2 > data.length) throw new IndexOutOfBoundsException();
            return ((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF);
        }

        long readUnsignedInt() {
            return ((long) readShort() << 16) | readShort();
        }

        byte[] readBytes(int length) {
            if (length < 0 || pos + length > data.length)
                throw new IndexOutOfBoundsException();
            byte[] out = new byte[length];
            System.arraycopy(data, pos, out, 0, length);
            pos += length;
            return out;
        }

        void skip(int length) {
            if (pos + length > data.length) throw new IndexOutOfBoundsException();
            pos += length;
        }

        void skipName() { readName(); }

        /** Decompressed dotted name, or null on any malformation. */
        String readName() {
            StringBuilder sb = new StringBuilder(64);
            int jumps = 0;
            int saved = -1;
            int p = pos;
            while (true) {
                if (p >= data.length || jumps > 16) return null;
                int length = data[p++] & 0xFF;
                if (length == 0) break;
                if ((length & 0xC0) == 0xC0) {
                    if (p >= data.length) return null;
                    int pointer = ((length & 0x3F) << 8) | (data[p++] & 0xFF);
                    if (saved < 0) saved = p;
                    p = pointer;
                    jumps++;
                    continue;
                }
                if ((length & 0xC0) != 0 || p + length > data.length) return null;
                if (sb.length() > 0) sb.append('.');
                for (int i = 0; i < length; i++) sb.append((char) (data[p++] & 0xFF));
            }
            if (saved > 0) pos = saved;
            else pos = p;
            return sb.toString();
        }
    }
}
