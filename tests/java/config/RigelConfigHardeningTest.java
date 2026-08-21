package config;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/** Regression coverage for bounded/corrupt portable-profile handling. */
public final class RigelConfigHardeningTest {
    private RigelConfigHardeningTest() {}

    public static void main(String[] args) throws Exception {
        Path expectedRoot = Path.of(System.getProperty("rigel.test.root", ""))
                .toRealPath();
        Path actualRoot = Settings.DIR.getParent().getParent().toRealPath();
        require(actualRoot.equals(expectedRoot),
                "test profile escaped its temporary root: " + Settings.DIR);

        resetProfile();
        testMalformedPropertiesFallBackToDefaults();
        testProxyPortValidation();
        testSessionSanitizingAndActiveIndex();
        testShortcutLimit();
        testBoundedBookmarks();
        testBoundedHistory();
        resetProfile();
        System.out.println("Rigel config hardening tests passed");
    }

    private static void testMalformedPropertiesFallBackToDefaults() throws Exception {
        Files.createDirectories(Settings.DIR);
        String malformed = "homepage=https://partially-loaded.invalid\nmalformed="
                + '\\' + "u12GZ\n";
        Files.writeString(Settings.FILE, malformed);
        Settings settings = new Settings();
        require("rigel://home".equals(settings.getHomepage()),
                "malformed properties left a partially loaded profile active");
    }

    private static void testProxyPortValidation() throws Exception {
        Properties invalid = new Properties();
        invalid.setProperty("proxy.host", "proxy.test");
        invalid.setProperty("proxy.port", "70000");
        writeSettings(invalid);
        Settings settings = new Settings();
        require(settings.getUpstreamPort() == -1,
                "out-of-range persisted proxy port was accepted");

        boolean rejected = false;
        try {
            settings.setUpstream("http", "proxy.test", 70000);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "out-of-range proxy setter input was accepted");
        settings.setUpstream("socks5", "proxy.test", 65535);
        require(settings.getUpstreamPort() == 65535,
                "valid maximum proxy port was rejected");
    }

    private static void testSessionSanitizingAndActiveIndex() throws Exception {
        resetProfile();
        Settings settings = new Settings();
        settings.setSession(List.of(
                "https://one.test/path",
                "javascript:alert(1)",
                "https://one.test/path",
                "file:///tmp/rigel-test.html",
                "rigel://home",
                "about:blank",
                "http://bad-port.test:70000/",
                "not a url"), 2);
        require(settings.getSessionUrls().equals(List.of(
                        "https://one.test/path",
                        "file:///tmp/rigel-test.html",
                        "rigel://home",
                        "about:blank")),
                "session URLs were not validated and deduplicated");
        require(settings.getSessionActiveIndex() == 0,
                "active duplicate did not map to its restored tab");

        List<String> many = new ArrayList<>();
        for (int i = 0; i < 70; i++) many.add("https://tab" + i + ".test/");
        settings.setSession(many, 69);
        require(settings.getSessionUrls().size() == 50,
                "session URL cap was not enforced");
        require(settings.getSessionActiveIndex() == 49,
                "active session index was not clamped to the restored set");

        Properties corruptIndex = new Properties();
        corruptIndex.setProperty("session.urls",
                "https://a.test/\nhttps://b.test/\nhttps://a.test/");
        corruptIndex.setProperty("session.active", "2");
        writeSettings(corruptIndex);
        settings = new Settings();
        require(settings.getSessionActiveIndex() == 0,
                "persisted duplicate active tab did not map to the deduplicated set");

        corruptIndex.setProperty("session.urls", "https://a.test/\nhttps://b.test/");
        corruptIndex.setProperty("session.active", "999999");
        writeSettings(corruptIndex);
        settings = new Settings();
        require(settings.getSessionActiveIndex() == 1,
                "persisted active session index was not clamped to the nearest restored tab");
    }

    private static void testShortcutLimit() throws Exception {
        Properties properties = new Properties();
        StringBuilder shortcuts = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            if (i > 0) shortcuts.append('\n');
            shortcuts.append("Shortcut ").append(i)
                    .append(" | https://shortcut").append(i).append(".test/ | S");
        }
        properties.setProperty("home.shortcuts", shortcuts.toString());
        writeSettings(properties);
        Settings settings = new Settings();
        require(settings.getHomeShortcuts().size() == 64,
                "shortcut entry cap was not enforced");
        require(settings.getHomeShortcutsText().lines().count() == 64,
                "settings UI shortcut text was not bounded");
    }

    private static void testBoundedBookmarks() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 2505; i++) {
            lines.add("Bookmark " + i + "\thttps://bookmark" + i + ".test/");
        }
        Files.createDirectories(Settings.DIR);
        Path file = Settings.DIR.resolve("bookmarks.tsv");
        Files.write(file, lines);
        Bookmarks bookmarks = new Bookmarks();
        require(bookmarks.all().size() == 2000, "bookmark load was not bounded");
        require("https://bookmark505.test/".equals(bookmarks.all().get(0).url),
                "bookmark loader did not retain the most recent bounded set");
        require(Files.readAllLines(file).size() == 2000,
                "oversized bookmark file was not compacted");
        bookmarks.add("Newest", "https://newest-bookmark.test/");
        require(bookmarks.all().size() == 2000,
                "adding a bookmark exceeded the in-memory cap");
    }

    private static void testBoundedHistory() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 2505; i++) {
            lines.add(i + "\tHistory " + i + "\thttps://history" + i + ".test/");
        }
        Files.createDirectories(Settings.DIR);
        Path file = Settings.DIR.resolve("history.tsv");
        Files.write(file, lines);
        History history = new History();
        List<History.Entry> recent = history.recent();
        require(recent.size() == 2000, "history load was not bounded");
        require("https://history2504.test/".equals(recent.get(0).url),
                "history loader lost the newest entry");
        require("https://history505.test/".equals(recent.get(recent.size() - 1).url),
                "history loader retained entries outside the bounded window");
        require(Files.readAllLines(file).size() == 2000,
                "oversized history file was not compacted");
    }

    private static void writeSettings(Properties properties) throws Exception {
        Files.createDirectories(Settings.DIR);
        try (OutputStream out = Files.newOutputStream(Settings.FILE)) {
            properties.store(out, "Rigel config hardening test");
        }
    }

    private static void resetProfile() throws Exception {
        if (!Files.exists(Settings.DIR)) return;
        try (var paths = Files.walk(Settings.DIR)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
