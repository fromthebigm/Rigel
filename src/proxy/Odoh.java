package proxy;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.XECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.time.Duration;
import java.util.Arrays;

/**
 * Oblivious DNS-over-HTTPS (RFC 9230) client for Rigel's resolver stack.
 *
 * <p>Plain DoH hides query contents from the local network but still hands
 * the client's IP address to the resolver. ODoH splits that knowledge across
 * two non-colluding parties: queries are HPKE-sealed (RFC 9180,
 * DHKEM(X25519)/HKDF-SHA256/AES-128-GCM — the mandatory RFC 9230 suite) to
 * the target's public key and then POSTed through a public relay. The relay
 * sees the client IP but only ciphertext; the target decrypts and resolves
 * but only ever sees the relay's IP. Neither party learns both.</p>
 *
 * <p>Everything is implemented on stock JDK crypto (JCA XDH, HKDF via
 * HMAC-SHA256, AES-GCM) — no new dependencies. The HPKE core is validated
 * against the RFC 9180 A.1.1 test vectors by {@link proxy.SecureDnsCoreTest},
 * and the wire format was verified live against Cloudflare's ODoH target
 * through the public edgecompute relay (the same target/relay pair
 * dnscrypt-proxy's public resolver list ships).</p>
 *
 * <p>Bootstrap note: fetching the target's rotating public-key config from
 * {@code /.well-known/odohconfigs} and reaching the relay each require the
 * system resolver to look up those two <b>fixed</b> hostnames once per
 * session — a constant, query-independent disclosure that the router already
 * observes as TLS connections anyway. The queries themselves never touch the
 * plaintext resolver.</p>
 */
final class Odoh {

    private Odoh() {}

    // ── endpoints (public, no account, from the DNSCrypt resolver list) ──

    /** Cloudflare's ODoH target: decrypts, resolves, re-encrypts. */
    private static final String TARGET_HOST = "odoh.cloudflare-dns.com";
    private static final String TARGET_PATH = "/dns-query";
    private static final String CONFIG_URL =
            "https://" + TARGET_HOST + "/.well-known/odohconfigs";
    /**
     * Public relay (crypto.sx / Fastly Compute). Sees the client IP, never
     * the query. RFC 9230 §4.1 URI Template, Level 3: targetpath is
     * percent-encoded per dnscrypt-proxy's production client.
     */
    private static final String RELAY_URL = "https://odoh-relay.edgecompute.app/"
            + "?targethost=" + TARGET_HOST + "&targetpath=%2Fdns-query";

    /** Config rotation guidance is daily; refresh on this cadence. */
    private static final long CONFIG_TTL_NANOS = Duration.ofHours(24).toNanos();
    /** Failed config fetches / decrypts retry at most this often. */
    private static final long CONFIG_RETRY_NANOS = Duration.ofSeconds(60).toNanos();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    // ── crypto plumbing ──────────────────────────────────────────────────

    private static final Hexer HEX = new Hexer();

    /** RFC 9180 §4: suite ids for the labeled KDF. */
    private static final byte[] KEM_SUITE =
            {0x4B, 0x45, 0x4D, 0x00, 0x20};                       // "KEM" + 0x0020
    private static final byte[] HPKE_SUITE =
            {0x48, 0x50, 0x4B, 0x45, 0x00, 0x20, 0x00, 0x01, 0x00, 0x01};
    private static final byte[] HPKE_V1 = "HPKE-v1".getBytes();

    static final class ProtocolException extends Exception {
        private static final long serialVersionUID = 1L;
        ProtocolException(String message) { super(message); }
    }

    // ── HKDF-SHA256 (RFC 5869) ───────────────────────────────────────────

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** KDF-Extract: empty salt becomes HashLen zero bytes (RFC 5869 §2.2). */
    static byte[] extract(byte[] salt, byte[] ikm) {
        return hmacSha256(salt.length == 0 ? new byte[32] : salt, ikm);
    }

    /** KDF-Expand (RFC 5869 §2.3). */
    static byte[] expand(byte[] prk, byte[] info, int length) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(length + 32);
            byte[] t = new byte[0];
            int counter = 1;
            while (out.size() < length) {
                ByteArrayOutputStream block = new ByteArrayOutputStream(t.length + info.length + 1);
                block.write(t);
                block.write(info);
                block.write(counter++);
                t = hmacSha256(prk, block.toByteArray());
                out.write(t);
            }
            return Arrays.copyOf(out.toByteArray(), length);
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** RFC 9180 §4 LabeledExtract. */
    private static byte[] labeledExtract(byte[] salt, String label, byte[] ikm, byte[] suiteId) {
        return extract(salt, concat(HPKE_V1, suiteId, label.getBytes(), ikm));
    }

    /** RFC 9180 §4 LabeledExpand. */
    private static byte[] labeledExpand(byte[] prk, String label, byte[] info,
                                        int length, byte[] suiteId) {
        return expand(prk, concat(u16(length), HPKE_V1, suiteId, label.getBytes(), info), length);
    }

    // ── X25519 (RFC 7748 wire form is little-endian) ─────────────────────

    private static byte[] reverse(byte[] b) {
        byte[] out = new byte[b.length];
        for (int i = 0; i < b.length; i++) out[b.length - 1 - i] = b[i];
        return out;
    }

    private static BigInteger leToBig(byte[] le) { return new BigInteger(1, reverse(le)); }

    private static byte[] bigToLe(BigInteger u) {
        byte[] be = u.toByteArray();
        if (be.length > 32) be = Arrays.copyOfRange(be, be.length - 32, be.length);
        byte[] padded = new byte[32];
        System.arraycopy(be, 0, padded, 32 - be.length, be.length);
        return reverse(padded);
    }

    /**
     * DH(sk, pkRaw) in RFC 7748 wire bytes. The JDK's XDH KeyAgreement
     * already returns little-endian wire form on the bundled Azul JDK 25 —
     * pinned by the RFC 9180 A.1.1 vector check, which fails loudly if a
     * future JDK changes that convention.
     */
    private static byte[] x25519(Object sk, byte[] pkRaw) throws Exception {
        KeyAgreement ka = KeyAgreement.getInstance("X25519");
        ka.init((java.security.PrivateKey) sk);
        KeyFactory kf = KeyFactory.getInstance("X25519");
        ka.doPhase(kf.generatePublic(new XECPublicKeySpec(
                NamedParameterSpec.X25519, leToBig(pkRaw))), true);
        return ka.generateSecret();
    }

    // ── HPKE base mode (RFC 9180 §5.1.1) ────────────────────────────────

    /** Sender context: exactly what ODoH needs — one Seal, then Export. */
    private static final class SenderContext {
        byte[] key, baseNonce, exporterSecret;

        byte[] seal(byte[] aad, byte[] pt) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, baseNonce)); // sequence number 0
            cipher.updateAAD(aad);
            return cipher.doFinal(pt);
        }

        /** RFC 9180 §5.3 Export — domain-separated via "sec". */
        byte[] export(String context, int length) {
            return labeledExpand(exporterSecret, "sec",
                    context.getBytes(), length, HPKE_SUITE);
        }
    }

    /** DHKEM(X25519, HKDF-SHA256) Encap + KeySchedule, base mode.
     *  Returns {enc, ctx, sharedSecret}. */
    private static Object[] setupBaseS(byte[] pkR, byte[] info) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("X25519");
        KeyPair pair = gen.generateKeyPair();
        return setupBaseS(pkR, info, pair.getPrivate(),
                bigToLe(((XECPublicKey) pair.getPublic()).getU()));
    }

    /** Fixed-ephemeral variant used by the RFC 9180 A.1.1 vector check. */
    private static Object[] setupBaseS(byte[] pkR, byte[] info,
                                       java.security.PrivateKey skE, byte[] enc)
            throws Exception {
        byte[] dh = x25519(skE, pkR);

        byte[] eaePrk = labeledExtract(new byte[0], "eae_prk", dh, KEM_SUITE);
        byte[] shared = labeledExpand(eaePrk, "shared_secret",
                concat(enc, pkR), 32, KEM_SUITE);

        byte[] pskIdHash = labeledExtract(new byte[0], "psk_id_hash",
                new byte[0], HPKE_SUITE);
        byte[] infoHash = labeledExtract(new byte[0], "info_hash", info, HPKE_SUITE);
        byte[] scheduleContext = concat(new byte[]{0x00}, pskIdHash, infoHash);
        byte[] secret = labeledExtract(shared, "secret", new byte[0], HPKE_SUITE);

        SenderContext ctx = new SenderContext();
        ctx.key = labeledExpand(secret, "key", scheduleContext, 16, HPKE_SUITE);
        ctx.baseNonce = labeledExpand(secret, "base_nonce", scheduleContext, 12, HPKE_SUITE);
        ctx.exporterSecret = labeledExpand(secret, "exp", scheduleContext, 32, HPKE_SUITE);
        return new Object[]{enc, ctx, shared};
    }

    // ── ODoH protocol (RFC 9230 §5–§7) ─────────────────────────────────

    private static final class TargetConfig {
        byte[] contents;  // serialized ObliviousDoHConfigContents
        byte[] publicKey; // raw X25519 (little-endian wire form)
        byte[] keyId;    // key_id WITH its u16 length prefix (wire form)
    }

    /** RFC 9230 §6: key_id = Expand(Extract("", config), "odoh key id", Nh). */
    private static byte[] keyId(byte[] contents) {
        byte[] id = expand(extract(new byte[0], contents),
                "odoh key id".getBytes(), 32);
        return concat(u16(id.length), id);
    }

    /** Parses an ObliviousDoHConfigs blob (§5), X25519/AES-128 suite only. */
    private static TargetConfig parseConfigs(byte[] blob) throws ProtocolException {
        try {
            if (blob.length < 10) throw new ProtocolException("config blob too small");
            int total = readU16(blob, 0);
            if (blob.length != total + 2)
                throw new ProtocolException("config blob length mismatch");
            int off = 2;
            while (off + 4 <= blob.length) {
                int version = readU16(blob, off);
                int length = readU16(blob, off + 2);
                if (off + 4 + length > blob.length)
                    throw new ProtocolException("truncated config");
                if (version != 0x0001) { off += 4 + length; continue; }
                byte[] contents = Arrays.copyOfRange(blob, off + 4, off + 4 + length);
                int kem = readU16(contents, 0);
                int kdf = readU16(contents, 2);
                int aead = readU16(contents, 4);
                int pkLen = readU16(contents, 6);
                if (kem != 0x0020 || kdf != 0x0001 || aead != 0x0001 || pkLen != 32
                        || contents.length != 8 + pkLen)
                    throw new ProtocolException("unsupported ODoH suite");
                TargetConfig cfg = new TargetConfig();
                cfg.contents = contents;
                cfg.publicKey = Arrays.copyOfRange(contents, 8, 8 + pkLen);
                cfg.keyId = keyId(contents);
                return cfg;
            }
            throw new ProtocolException("no supported ODoH config in blob");
        } catch (ProtocolException e) {
            throw e;
        } catch (Exception e) {
            throw new ProtocolException("bad config blob: " + e);
        }
    }

    private static final class SealedQuery {
        byte[] plaintext;  // Q_plain, needed for the response salt
        byte[] message;    // wire ObliviousDoHMessage (query)
        SenderContext ctx;
    }

    private static SealedQuery encryptQuery(TargetConfig cfg, byte[] dnsQuery)
            throws Exception {
        // Q_plain = u16(dns) || dns || u16(0)  [RFC 9230 §6.1, zero padding]
        byte[] qPlain = concat(u16(dnsQuery.length), dnsQuery, u16(0));
        Object[] setup = setupBaseS(cfg.publicKey, "odoh query".getBytes());
        byte[] enc = (byte[]) setup[0];
        SenderContext ctx = (SenderContext) setup[1];
        byte[] aad = concat(new byte[]{0x01}, cfg.keyId);
        byte[] ct = ctx.seal(aad, qPlain);
        byte[] encrypted = concat(enc, ct);
        SealedQuery q = new SealedQuery();
        q.plaintext = qPlain;
        q.ctx = ctx;
        // wire: 0x01 || u16 key_id || key_id || u16 enc || enc
        q.message = concat(new byte[]{0x01}, cfg.keyId,
                u16(encrypted.length), encrypted);
        return q;
    }

    private static byte[] decryptResponse(SealedQuery q, byte[] response)
            throws Exception {
        if (response.length < 5 || response[0] != 0x02)
            throw new ProtocolException("malformed ODoH response");
        int nonceLen = readU16(response, 1);
        if (response.length < 5 + nonceLen)
            throw new ProtocolException("short ODoH response");
        byte[] nonceEnc = Arrays.copyOfRange(response, 1, 3 + nonceLen);
        int ctLen = readU16(response, 3 + nonceLen);
        if (ctLen < 16 || 5 + nonceLen + ctLen != response.length)
            throw new ProtocolException("ODoH response ct length mismatch");
        byte[] ct = Arrays.copyOfRange(response, 5 + nonceLen, response.length);

        // RFC 9230 §6.2 response key schedule.
        byte[] secret = q.ctx.export("odoh response", 16);
        byte[] salt = concat(q.plaintext, nonceEnc);
        byte[] prk = extract(salt, secret);
        byte[] key = expand(prk, "odoh key".getBytes(), 16);
        byte[] nonce = expand(prk, "odoh nonce".getBytes(), 12);
        byte[] aad = Arrays.copyOfRange(response, 0, 3 + nonceLen);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        byte[] plain = cipher.doFinal(ct);

        int dnsLen = readU16(plain, 0);
        int padOff = 2 + dnsLen;
        if (padOff + 2 > plain.length)
            throw new ProtocolException("ODoH response plaintext truncated");
        int padLen = readU16(plain, padOff);
        if (padOff + 2 + padLen != plain.length)
            throw new ProtocolException("ODoH response padding mismatch");
        for (int i = padOff + 2; i < plain.length; i++)
            if (plain[i] != 0) throw new ProtocolException("non-zero ODoH padding");
        return Arrays.copyOfRange(plain, 2, 2 + dnsLen);
    }

    // ── config cache + the public entry point ────────────────────────────

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static volatile TargetConfig cachedConfig;
    private static volatile long configFetchedAt;       // nanos
    private static volatile long lastConfigFailureAt;   // nanos

    private static TargetConfig currentConfig() throws Exception {
        TargetConfig cfg = cachedConfig;
        long now = System.nanoTime();
        if (cfg != null && now - configFetchedAt < CONFIG_TTL_NANOS) return cfg;
        if (now - lastConfigFailureAt < CONFIG_RETRY_NANOS) {
            if (cfg != null) return cfg;
            throw new ProtocolException("ODoH config fetch in retry cooldown");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(CONFIG_URL))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/binary")
                    .GET().build();
            HttpResponse<byte[]> reply =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (reply.statusCode() != 200)
                throw new ProtocolException("config HTTP " + reply.statusCode());
            TargetConfig fresh = parseConfigs(reply.body());
            cachedConfig = fresh;
            configFetchedAt = now;
            return fresh;
        } catch (Exception failure) {
            lastConfigFailureAt = now;
            if (cfg != null) return cfg;
            throw failure instanceof ProtocolException pe ? pe
                    : new ProtocolException("config fetch failed: " + failure);
        }
    }

    /**
     * One full oblivious resolution round-trip: seal the DNS wire query to
     * the target's current public key, relay it, and open the sealed reply.
     * Any failure propagates so the caller can fall back to plain DoH.
     */
    static byte[] query(byte[] dnsQuery) throws Exception {
        SealedQuery sealed = encryptQuery(currentConfig(), dnsQuery);
        HttpRequest request = HttpRequest.newBuilder(URI.create(RELAY_URL))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/oblivious-dns-message")
                .header("Accept", "application/oblivious-dns-message")
                .POST(HttpRequest.BodyPublishers.ofByteArray(sealed.message))
                .build();
        HttpResponse<byte[]> reply =
                CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (reply.statusCode() == 401 || reply.statusCode() == 400) {
            // 401 = target didn't recognize key_id (config rotated under us);
            // 400 = the target rejected our message. Either way: drop the
            // cached config so the next attempt re-fetches, and let the
            // caller retry via plain DoH for this query.
            cachedConfig = null;
            lastConfigFailureAt = System.nanoTime();
            throw new ProtocolException("ODoH target rejected query: HTTP "
                    + reply.statusCode());
        }
        if (reply.statusCode() != 200)
            throw new ProtocolException("ODoH relay HTTP " + reply.statusCode());
        return decryptResponse(sealed, reply.body());
    }

    /** Test hook: wipes the cached target config. */
    static void resetForTest() {
        cachedConfig = null;
        configFetchedAt = 0;
        lastConfigFailureAt = 0;
    }

    // ── RFC 9180 A.1.1 vector self-check (offline, deterministic) ──────

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    private static void expect(String label, byte[] got, String want) throws Exception {
        if (!Arrays.equals(got, hex(want)))
            throw new Exception("RFC 9180 A.1.1 vector mismatch: " + label
                    + " got " + HEX.hex(got));
    }

    /**
     * Runs the RFC 9180 A.1.1 base-suite test vectors end-to-end through the
     * exact KEM/KDF/schedule/Seal/Export code the live protocol uses. Also
     * pins two JDK XDH conventions this implementation depends on: the
     * little-endian wire form of generated secrets and the scalar byte order
     * accepted by XECPrivateKeySpec. Offline; called from
     * {@link proxy.SecureDnsCoreTest} on every proxy-core test run.
     */
    static void vectorSelfCheck() throws Exception {
        byte[] skEm = hex("52c4a758a802cd8b936eceea314432798d5baf2d7e9235dc084ab1b9cfa2f736");
        byte[] pkRm  = hex("3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d");
        byte[] info  = hex("4f6465206f6e2061204772656369616e2055726e");

        KeyFactory kf = KeyFactory.getInstance("X25519");
        java.security.PrivateKey skE = kf.generatePrivate(
                new XECPrivateKeySpec(NamedParameterSpec.X25519, skEm));

        // pkE = X25519(skE, base point 9) — validates the scalar byte order.
        byte[] basePoint = new byte[32];
        basePoint[0] = 9;
        byte[] enc = x25519(skE, basePoint);
        expect("enc (pkEm)", enc,
                "37fda3567bdbd628e88668c3c8d7e97d1d1253b6d4ea6d44c150f741f1bf4431");

        Object[] setup = setupBaseS(pkRm, info, skE, enc);
        SenderContext ctx = (SenderContext) setup[1];
        expect("shared_secret", (byte[]) setup[2],
                "fe0e18c9f024ce43799ae393c7e8fe8fce9d218875e8227b0187c04e7d2ea1fc");
        expect("key", ctx.key, "4531685d41d65f03dc48f6b8302c05b0");
        expect("base_nonce", ctx.baseNonce, "56d890e5accaaf011cff4b7d");
        expect("exporter_secret", ctx.exporterSecret,
                "45ff1c2e220db587171952c0592d5f5ebe103f1561a2614e38f2ffd47e99e3f8");

        byte[] ct = ctx.seal(hex("436f756e742d30"),
                hex("4265617574792069732074727574682c20747275746820626561757479"));
        expect("seal ct", ct,
                "f938558b5d72f1a23810b4be2ab4f84331acc02fc97babc53a52ae8218a355a9"
                        + "6d8770ac83d07bea87e13c512a");

        expect("export", ctx.export("", 32),
                "3853fe2b4035195a573ffc53856e77058e15d9ea064de3e59f4961d0095250ee");
    }

    // ── byte helpers ──────────────────────────────────────────────────────

    static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, pos, p.length); pos += p.length; }
        return out;
    }

    static byte[] u16(int v) { return new byte[]{(byte) (v >>> 8), (byte) v}; }

    static int readU16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    /** Minimal hex printer for diagnostics. */
    static final class Hexer {
        String hex(byte[] b) {
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        }
    }
}
