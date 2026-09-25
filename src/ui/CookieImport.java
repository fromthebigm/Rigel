package ui;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

/** Sync-in of sign-ins that live OUTSIDE the portable folder.
 *
 * Previous Rigel generations (and any launch that bypasses run.sh) kept the
 * WebKit cookie jar in the real home under whichever storage identity the JDK
 * carried (com.rigel.weather-host, com.azul.zulu.java, ...), and WebKit never
 * re-flushes those jars on this host — those files are the only place older
 * logins still exist. On launch run.sh exports RIGEL_REAL_HOME; for every
 * known identity jar there whose content changed since the last import, we
 * decode the binarycookies file and write the still-valid cookies as an XML
 * plist in exactly the shape rigel_wk_cookies_save produces, so the normal
 * native restore path can replay them with no bridge changes.
 *
 * Safety rules: the real-home files are only read, never written. Every jar
 * is fingerprinted (profile/cookie-jar-imports.properties) and re-imported
 * only when its content changed, so cookies the user deleted in the portable
 * browser are not resurrected. The portable snapshot is restored AFTER the
 * import, so portable state always wins any overlap.
 */
final class CookieImport {
    private static final String[] IDENTITIES = {
        "com.azul.zulu.java", "net.java.openjdk.java", "com.oracle.java.jdk",
        "com.rigel.weather-host"
    };
    /** Seconds between the Cocoa reference date (2001-01-01) and the unix epoch. */
    private static final long REF_DATE_EPOCH_DELTA = 978307200L;
    /** written beside cookies.store; consumed once per changed jar */
    private static final String IMPORT_FILE = "cookie-import.store";
    private static final String STAMPS_FILE = "cookie-jar-imports.properties";

    private CookieImport() {
    }

    /** Decode every real-home jar with unseen content and return the XML plist
     * to replay BEFORE the portable snapshot, or null when nothing changed.
     * Never throws: any failure just skips the import for that source. */
    static Path syncIn(Path profileDir) {
        String realHome = System.getenv("RIGEL_REAL_HOME");
        if (realHome == null || realHome.isEmpty()) return null;
        try {
            return syncIn(profileDir, Path.of(realHome));
        } catch (Throwable failure) {
            System.err.println("[Rigel] Cookie jar sync-in failed: " + failure.getMessage());
            return null;
        }
    }

    private static Path syncIn(Path profileDir, Path realHome) throws IOException {
        Path stampFile = profileDir.resolve(STAMPS_FILE);
        Properties stamps = new Properties();
        if (Files.isRegularFile(stampFile)) {
            try (var in = Files.newInputStream(stampFile)) { stamps.load(in); }
        }
        Path importFile = profileDir.resolve(IMPORT_FILE);
        // First jar wins on key collisions across identities; the portable
        // snapshot restore afterwards wins regardless.
        Map<String, Entry> entries = new LinkedHashMap<>();
        List<String> sources = new ArrayList<>();
        boolean stampsChanged = false;
        for (String identity : IDENTITIES) {
            Path jar = realHome.resolve("Library/HTTPStorages/" + identity + ".binarycookies");
            if (!Files.isRegularFile(jar)) continue;
            byte[] bytes = Files.readAllBytes(jar);
            String fingerprint = sha256(bytes);
            if (fingerprint.equals(stamps.getProperty(identity))) continue;
            List<Entry> decoded;
            try {
                decoded = decode(bytes);
            } catch (Throwable failure) {
                // Not stamped: a corrupt/unknown jar is retried next launch.
                System.err.println("[Rigel] Could not decode real-home jar " + identity
                        + ": " + failure.getMessage());
                continue;
            }
            int added = 0;
            for (Entry entry : decoded) {
                if (entries.putIfAbsent(entry.key(), entry) == null) added++;
            }
            stamps.setProperty(identity, fingerprint);
            stampsChanged = true;
            if (added > 0) sources.add(identity + " (" + added + ")");
        }
        if (entries.isEmpty()) {
            Files.deleteIfExists(importFile);  // nothing new: never replay a stale import
            if (stampsChanged) saveStamps(stampFile, stamps);
            return null;
        }
        writePlist(importFile, new ArrayList<>(entries.values()));
        saveStamps(stampFile, stamps);  // only after the plist landed safely
        for (String source : sources) {
            System.err.println("[Rigel] Synced sign-in cookies in from real-home jar " + source);
        }
        return importFile;
    }

    // binarycookies: file header is big-endian ("cook", page count, page
    // sizes); page tables and cookie records are little-endian on current
    // macOS. Verified against jars written by macOS 15/26.
    private static List<Entry> decode(byte[] bytes) {
        if (bytes.length < 12 || bytes[0] != 'c' || bytes[1] != 'o'
                || bytes[2] != 'o' || bytes[3] != 'k') {
            throw new IllegalArgumentException("not a binarycookies file");
        }
        ByteBuffer head = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int pages = head.getInt(4);
        if (pages < 0 || pages > 4096 || 8 + 4L * pages > bytes.length) {
            throw new IllegalArgumentException("implausible page table");
        }
        long nowMac = (System.currentTimeMillis() / 1000L) - REF_DATE_EPOCH_DELTA;
        List<Entry> out = new ArrayList<>();
        int offset = 8 + 4 * pages;
        for (int i = 0; i < pages; i++) {
            int size = head.getInt(8 + 4 * i);
            if (size <= 0 || offset + size > bytes.length) break;  // trailing checksum area
            decodePage(bytes, offset, size, nowMac, out);
            offset += size;
        }
        return out;
    }

    private static void decodePage(byte[] bytes, int base, int size, long nowMac, List<Entry> out) {
        if (size < 8 || bytes[base] != 0 || bytes[base + 1] != 0
                || bytes[base + 2] != 1 || bytes[base + 3] != 0) return;
        // One LE view over the WHOLE file: absolute get(int) indexes the
        // backing array from zero, so every read below carries base along.
        ByteBuffer le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int count = le.getInt(base + 4);
        if (count <= 0 || 8 + 4L * count > size) return;
        for (int i = 0; i < count; i++) {
            int at = le.getInt(base + 8 + 4 * i);
            if (at < 0 || at + 56 > size) continue;
            Entry entry = decodeRecord(le, base + at, base + size, nowMac);
            if (entry != null) out.add(entry);
        }
    }

    private static Entry decodeRecord(ByteBuffer le, int at, int pageEnd, long nowMac) {
        long size = le.getInt(at) & 0xffffffffL;
        if (size < 56 || at + size > pageEnd) return null;
        int flags = le.getInt(at + 8);
        int domainOff = le.getInt(at + 16);
        int nameOff = le.getInt(at + 20);
        int pathOff = le.getInt(at + 24);
        int valueOff = le.getInt(at + 28);
        double expires = le.getDouble(at + 40);
        double created = le.getDouble(at + 48);
        if (expires <= 0 || expires > 1e11 || expires < nowMac) return null;  // corrupt/expired
        if (domainOff <= 0 || nameOff <= 0 || pathOff <= 0
                || domainOff >= size || nameOff >= size || pathOff >= size) return null;
        String domain = cString(le, at + domainOff, at + (int) size);
        String name = cString(le, at + nameOff, at + (int) size);
        String path = cString(le, at + pathOff, at + (int) size);
        String value = valueOff > 0 && valueOff < size ? cString(le, at + valueOff, at + (int) size) : "";
        if (domain == null || domain.isEmpty() || name == null || name.isEmpty()) return null;
        Entry entry = new Entry();
        entry.domain = domain;
        entry.name = name;
        entry.path = path == null || path.isEmpty() ? "/" : path;
        entry.value = value == null ? "" : value;
        entry.secure = (flags & 1) != 0;
        entry.httpOnly = (flags & 4) != 0;
        entry.expires = expires;
        entry.created = created > 0 && created < 1e11 ? created : nowMac;  // cosmetic only; normalise junk
        return entry;
    }

    private static String cString(ByteBuffer le, int start, int limit) {
        byte[] raw = le.array();
        int end = start;
        while (end < limit && raw[end] != 0) end++;
        return new String(raw, start, end - start, StandardCharsets.UTF_8);
    }

    /** Same XML shape rigel_wk_cookies_save writes: an NSArray of
     * NSHTTPCookie property dictionaries (keys sorted, TRUE-string flags,
     * Created as CFAbsoluteTime real, Expires as a plist date). */
    private static void writePlist(Path file, List<Entry> entries) throws IOException {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        StringBuilder out = new StringBuilder(64 + entries.size() * 400);
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
           .append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" ")
           .append("\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
           .append("<plist version=\"1.0\">\n<array>\n");
        for (Entry entry : entries) {
            Date expiresDate = new Date(REF_DATE_EPOCH_DELTA * 1000L + (long) (entry.expires * 1000.0));
            out.append("\t<dict>\n")
               .append("\t\t<key>Created</key>\n\t\t<real>").append(real(entry.created)).append("</real>\n")
               .append("\t\t<key>Domain</key>\n\t\t<string>").append(xml(entry.domain)).append("</string>\n")
               .append("\t\t<key>Expires</key>\n\t\t<date>").append(iso.format(expiresDate)).append("</date>\n");
            if (entry.httpOnly) out.append("\t\t<key>HttpOnly</key>\n\t\t<string>TRUE</string>\n");
            out.append("\t\t<key>Name</key>\n\t\t<string>").append(xml(entry.name)).append("</string>\n")
               .append("\t\t<key>Path</key>\n\t\t<string>").append(xml(entry.path)).append("</string>\n");
            if (entry.secure) out.append("\t\t<key>Secure</key>\n\t\t<string>TRUE</string>\n");
            out.append("\t\t<key>Value</key>\n\t\t<string>").append(xml(entry.value)).append("</string>\n")
               .append("\t\t<key>Version</key>\n\t\t<string>1</string>\n")
               .append("\t</dict>\n");
        }
        out.append("</array>\n</plist>\n");
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, out.toString(), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void saveStamps(Path stampFile, Properties stamps) throws IOException {
        try (var out = Files.newOutputStream(stampFile)) {
            stamps.store(out, "Rigel real-home jar imports");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(Character.forDigit((b >> 4) & 0xf, 16))
                                     .append(Character.forDigit(b & 0xf, 16));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String real(double value) {
        String plain = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        return plain.indexOf('.') < 0 ? plain + ".0" : plain;
    }

    private static String xml(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> { if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') out.append(c); }
            }
        }
        return out.toString();
    }

    private static final class Entry {
        String domain, name, path, value;
        double expires, created;
        boolean secure, httpOnly;
        String key() { return domain + '\n' + name + '\n' + path; }
    }
}
