package config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Settings — persistent application configuration.
 *
 * Stored inside the distribution at portable-data/profile/settings.properties.
 * Loaded once at startup and saved whenever a value changes. Everything has a
 * sensible default so a missing or partial file still yields a working
 * configuration.
 *
 * This is intentionally dependency-free (java.util.Properties) so it adds no
 * new build requirements.
 */
public class Settings {

    public static final Path DIR  = distRoot().resolve("portable-data").resolve("profile");
    public static final Path FILE = DIR.resolve("settings.properties");

    // ── Keys ─────────────────────────────────────────────────────────────────
    private static final String K_HOMEPAGE      = "homepage";
    private static final String K_SIDEBAR       = "layout.sidebar";
    private static final String K_SIDEBAR_WIDTH = "layout.sidebar.percent";
    private static final String K_THEME         = "theme";            // theme name, e.g. "Atelier", "Nebula", "Forest" …
    private static final String K_SEARCH        = "search.template";  // %s = query
    private static final String K_DOWNLOAD_DIR  = "download.dir";
    private static final String K_ASK_DOWNLOAD  = "download.ask";     // ask for path each time
    private static final String K_BLOCKLIST_ON  = "blocklist.enabled";
    private static final String K_UPSTREAM_TYPE = "proxy.type";
    private static final String K_UPSTREAM_HOST = "proxy.host";
    private static final String K_UPSTREAM_PORT = "proxy.port";
    private static final String K_ZOOM          = "zoom";
    private static final String K_HISTORY_ON    = "history.enabled";
    private static final String K_NATIVE_CLIP   = "clipboard.native.enabled";
    private static final String K_CONTROLLER_ON = "controller.bridge.enabled";
    private static final String K_CONTROLLER_FACE_LAYOUT = "controller.face.layout";
    private static final String K_HOME_SHORTCUTS_ON = "home.shortcuts.enabled";
    private static final String K_HOME_SHORTCUTS = "home.shortcuts";
    private static final String K_RESTORE_TABS  = "session.restore.enabled";
    private static final String K_SESSION_URLS  = "session.urls";
    private static final String K_SESSION_INDEX = "session.active";
    private static final String K_GLASS_CHROME  = "appearance.glass.chrome";
    private static final String K_AV1_NORMALIZE_ALL = "media.av1.normalize.all-sites";
    private static final String K_WEB_INPUT_SITE_MODES = "input.web.site.modes";

    private final Properties props = new Properties();
    private int batchDepth;
    private boolean savePending;

    private static final String DEFAULT_HOME_SHORTCUTS = String.join("\n",
            "YouTube | https://www.youtube.com | YT",
            "TikTok | https://www.tiktok.com | TT",
            "ChatGPT | https://chatgpt.com | AI",
            "GitHub | https://github.com | GH");

    public Settings() {
        load();
    }

    private static Path distRoot() {
        try {
            URI location = Settings.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Paths.get(location).toAbsolutePath().normalize();
            if (Files.isRegularFile(path)) {
                Path parent = path.getParent();
                if (parent != null && parent.getFileName() != null
                        && "target".equals(parent.getFileName().toString())
                        && parent.getParent() != null) {
                    return parent.getParent();
                }
                return parent == null ? Paths.get(System.getProperty("user.dir")).toAbsolutePath() : parent;
            }
            if (path.getFileName() != null && "classes".equals(path.getFileName().toString())
                    && path.getParent() != null) {
                return path.getParent();
            }
            return path;
        } catch (Exception ignored) {
            return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        }
    }

    // ── Load / save ──────────────────────────────────────────────────────────

    private void load() {
        if (Files.exists(FILE)) {
            try (InputStream in = Files.newInputStream(FILE)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("[Rigel] Could not read settings: " + e.getMessage());
            }
        }
    }

    public synchronized void save() {
        if (batchDepth > 0) {
            savePending = true;
            return;
        }
        saveNow();
    }

    public synchronized void runBatch(Runnable update) {
        if (update == null) return;
        batchDepth++;
        try {
            update.run();
        } finally {
            batchDepth--;
            if (batchDepth == 0 && savePending) {
                savePending = false;
                saveNow();
            }
        }
    }

    private void saveNow() {
        try {
            AtomicFiles.write(FILE, temporary -> {
                try (OutputStream out = Files.newOutputStream(temporary)) {
                    props.store(out, "Rigel browser settings");
                }
            });
        } catch (IOException e) {
            System.err.println("[Rigel] Could not save settings: " + e.getMessage());
        }
    }

    // ── Typed accessors ──────────────────────────────────────────────────────

    public String getHomepage() {
        return props.getProperty(K_HOMEPAGE, "rigel://home");
    }
    public void setHomepage(String v) { props.setProperty(K_HOMEPAGE, v); save(); }

    public boolean isSidebar() {
        return Boolean.parseBoolean(props.getProperty(K_SIDEBAR, "false"));
    }
    public void setSidebar(boolean v) { props.setProperty(K_SIDEBAR, Boolean.toString(v)); save(); }

    public int getSidebarPercent() {
        try {
            return Math.max(12, Math.min(40,
                    Integer.parseInt(props.getProperty(K_SIDEBAR_WIDTH, "20"))));
        } catch (NumberFormatException e) {
            return 20;
        }
    }
    public void setSidebarPercent(int v) {
        props.setProperty(K_SIDEBAR_WIDTH,
                Integer.toString(Math.max(12, Math.min(40, v))));
        save();
    }

    public String getTheme() {
        return props.getProperty(K_THEME, "Atelier");
    }
    public void setTheme(String v) { props.setProperty(K_THEME, v); save(); }

    public String getSearchTemplate() {
        return props.getProperty(K_SEARCH, "https://www.google.com/search?q=%s");
    }
    public void setSearchTemplate(String v) { props.setProperty(K_SEARCH, v); save(); }

    public String getDownloadDir() {
        return props.getProperty(K_DOWNLOAD_DIR,
                Paths.get(System.getProperty("user.home"), "Downloads").toString());
    }
    public void setDownloadDir(String v) { props.setProperty(K_DOWNLOAD_DIR, v); save(); }

    public boolean isAskBeforeDownload() {
        return Boolean.parseBoolean(props.getProperty(K_ASK_DOWNLOAD, "true"));
    }
    public void setAskBeforeDownload(boolean v) { props.setProperty(K_ASK_DOWNLOAD, Boolean.toString(v)); save(); }

    public boolean isBlocklistEnabled() {
        return Boolean.parseBoolean(props.getProperty(K_BLOCKLIST_ON, "false"));
    }
    public void setBlocklistEnabled(boolean v) { props.setProperty(K_BLOCKLIST_ON, Boolean.toString(v)); save(); }

    public String getUpstreamHost() { return props.getProperty(K_UPSTREAM_HOST, ""); }
    public String getUpstreamType() { return props.getProperty(K_UPSTREAM_TYPE, "http"); }
    public int    getUpstreamPort() {
        try { return Integer.parseInt(props.getProperty(K_UPSTREAM_PORT, "-1")); }
        catch (NumberFormatException e) { return -1; }
    }
    public void setUpstream(String host, int port) {
        setUpstream("http", host, port);
    }
    public void setUpstream(String type, String host, int port) {
        props.setProperty(K_UPSTREAM_TYPE,
                type == null || type.isBlank() ? "http" : type.trim().toLowerCase());
        props.setProperty(K_UPSTREAM_HOST, host == null ? "" : host);
        props.setProperty(K_UPSTREAM_PORT, Integer.toString(port));
        save();
    }
    public void clearUpstream() {
        props.remove(K_UPSTREAM_TYPE);
        props.remove(K_UPSTREAM_HOST);
        props.remove(K_UPSTREAM_PORT);
        save();
    }

    public int getZoomPercent() {
        try { return Integer.parseInt(props.getProperty(K_ZOOM, "100")); }
        catch (NumberFormatException e) { return 100; }
    }
    public void setZoomPercent(int v) { props.setProperty(K_ZOOM, Integer.toString(v)); save(); }

    public boolean isHistoryEnabled() {
        return Boolean.parseBoolean(props.getProperty(K_HISTORY_ON, "true"));
    }
    public void setHistoryEnabled(boolean v) { props.setProperty(K_HISTORY_ON, Boolean.toString(v)); save(); }

    public boolean isNativeClipboardEnabled() {
        return Boolean.parseBoolean(props.getProperty(K_NATIVE_CLIP, "true"));
    }
    public void setNativeClipboardEnabled(boolean v) { props.setProperty(K_NATIVE_CLIP, Boolean.toString(v)); save(); }

    public boolean isControllerBridgeEnabled() {
        return Boolean.parseBoolean(props.getProperty(K_CONTROLLER_ON, "true"));
    }
    public void setControllerBridgeEnabled(boolean v) { props.setProperty(K_CONTROLLER_ON, Boolean.toString(v)); save(); }

    public String getControllerFaceLayout() {
        return props.getProperty(K_CONTROLLER_FACE_LAYOUT, "standard");
    }
    public void setControllerFaceLayout(String v) {
        String clean = (v == null || v.isBlank()) ? "standard" : v.trim().toLowerCase();
        if (!clean.equals("nintendo")) clean = "standard";
        props.setProperty(K_CONTROLLER_FACE_LAYOUT, clean);
        save();
    }

    public boolean isHomeShortcutsEnabled() {
        return Boolean.parseBoolean(props.getProperty(K_HOME_SHORTCUTS_ON, "true"));
    }
    public void setHomeShortcutsEnabled(boolean v) { props.setProperty(K_HOME_SHORTCUTS_ON, Boolean.toString(v)); save(); }

    public String getHomeShortcutsText() {
        return props.getProperty(K_HOME_SHORTCUTS, DEFAULT_HOME_SHORTCUTS);
    }
    public static String defaultHomeShortcutsText() {
        return DEFAULT_HOME_SHORTCUTS;
    }
    public void setHomeShortcutsText(String raw) {
        props.setProperty(K_HOME_SHORTCUTS, normalizeShortcuts(raw));
        save();
    }

    public List<Shortcut> getHomeShortcuts() {
        List<Shortcut> shortcuts = parseShortcuts(getHomeShortcutsText());
        return shortcuts;
    }

    public static List<Shortcut> defaultHomeShortcuts() {
        return parseShortcuts(DEFAULT_HOME_SHORTCUTS);
    }

    private static String normalizeShortcuts(String raw) {
        if (raw == null) return "";
        List<String> lines = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String clean = line.trim();
            if (clean.isEmpty()) continue;
            String[] parts = clean.split("\\|", 3);
            String title = parts.length > 0 ? parts[0].trim() : "";
            String url = parts.length > 1 ? parts[1].trim() : "";
            String icon = parts.length > 2 ? parts[2].trim() : "";
            if (title.isEmpty() || url.isEmpty()) continue;
            title = limitCodePoints(title, 80);
            url = limitCodePoints(url, 2048);
            icon = limitCodePoints(icon, 8);
            if (icon.isEmpty()) icon = defaultShortcutIcon(title);
            lines.add(title + " | " + url + " | " + icon);
        }
        return String.join("\n", lines);
    }

    private static List<Shortcut> parseShortcuts(String raw) {
        List<Shortcut> shortcuts = new ArrayList<>();
        if (raw == null) return shortcuts;
        for (String line : raw.split("\\R")) {
            String clean = line.trim();
            if (clean.isEmpty()) continue;
            String[] parts = clean.split("\\|", 3);
            String title = parts.length > 0 ? parts[0].trim() : "";
            String url = parts.length > 1 ? parts[1].trim() : "";
            String icon = parts.length > 2 ? parts[2].trim() : "";
            if (title.isEmpty() || url.isEmpty()) continue;
            title = limitCodePoints(title, 80);
            url = limitCodePoints(url, 2048);
            icon = limitCodePoints(icon, 8);
            shortcuts.add(new Shortcut(title, normalizeShortcutUrl(url), icon.isEmpty() ? defaultShortcutIcon(title) : icon));
        }
        return shortcuts;
    }

    private static String normalizeShortcutUrl(String url) {
        String clean = url == null ? "" : url.trim();
        if (clean.isEmpty()) return clean;
        String lower = clean.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("file:") || lower.startsWith("rigel://")) {
            return clean;
        }
        return "https://" + clean;
    }

    private static String defaultShortcutIcon(String title) {
        String clean = title == null ? "" : title.trim();
        if (clean.isEmpty()) return "*";
        StringBuilder icon = new StringBuilder(2);
        for (String part : clean.split("\\s+")) {
            if (!part.isEmpty()) icon.appendCodePoint(Character.toUpperCase(part.codePointAt(0)));
            if (icon.codePointCount(0, icon.length()) == 2) break;
        }
        if (icon.length() == 0) icon.appendCodePoint(Character.toUpperCase(clean.codePointAt(0)));
        return icon.toString();
    }

    private static String limitCodePoints(String value, int maxCodePoints) {
        if (value == null || maxCodePoints < 1) return "";
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) return value;
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    public static class Shortcut {
        public final String title;
        public final String url;
        public final String icon;

        public Shortcut(String title, String url, String icon) {
            this.title = title;
            this.url = url;
            this.icon = icon;
        }
    }


    /**
     * Per-site input compatibility override. "automatic" is intentionally not
     * persisted; removing an override lets Rigel's site heuristics decide.
     */
    public String getWebInputModeForHost(String host) {
        String clean = normalizeHost(host);
        if (clean.isEmpty()) return "automatic";
        java.util.Map<String, String> modes = parseWebInputSiteModes();
        String direct = modes.get(clean);
        if (direct != null) return direct;

        // A setting for anime.nexus also applies to player.anime.nexus.
        int dot = clean.indexOf('.');
        while (dot > 0 && dot + 1 < clean.length()) {
            String parent = clean.substring(dot + 1);
            String inherited = modes.get(parent);
            if (inherited != null) return inherited;
            dot = clean.indexOf('.', dot + 1);
        }
        return "automatic";
    }

    public String getWebInputModeForUrl(String url) {
        if (url == null || url.isBlank()) return "automatic";
        try {
            URI uri = URI.create(url.trim());
            return getWebInputModeForHost(uri.getHost());
        } catch (IllegalArgumentException ignored) {
            return "automatic";
        }
    }

    public void setWebInputModeForHost(String host, String mode) {
        String cleanHost = normalizeHost(host);
        if (cleanHost.isEmpty()) return;
        String cleanMode = normalizeWebInputMode(mode);
        java.util.Map<String, String> modes = parseWebInputSiteModes();
        if ("automatic".equals(cleanMode)) modes.remove(cleanHost);
        else modes.put(cleanHost, cleanMode);

        if (modes.isEmpty()) {
            props.remove(K_WEB_INPUT_SITE_MODES);
        } else {
            java.util.List<String> lines = new java.util.ArrayList<>();
            modes.entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> lines.add(entry.getKey() + " | " + entry.getValue()));
            props.setProperty(K_WEB_INPUT_SITE_MODES, String.join("\n", lines));
        }
        save();
    }

    private java.util.Map<String, String> parseWebInputSiteModes() {
        java.util.Map<String, String> modes = new java.util.LinkedHashMap<>();
        String raw = props.getProperty(K_WEB_INPUT_SITE_MODES, "");
        for (String line : raw.split("\\R")) {
            String clean = line.trim();
            if (clean.isEmpty()) continue;
            String[] parts = clean.split("\\|", 2);
            if (parts.length != 2) continue;
            String host = normalizeHost(parts[0]);
            String mode = normalizeWebInputMode(parts[1]);
            if (!host.isEmpty() && !"automatic".equals(mode)) modes.put(host, mode);
        }
        return modes;
    }

    private static String normalizeWebInputMode(String mode) {
        String clean = mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (clean) {
            case "compatibility", "controls" -> clean;
            default -> "automatic";
        };
    }

    private static String normalizeHost(String host) {
        if (host == null) return "";
        String clean = host.trim().toLowerCase(java.util.Locale.ROOT);
        while (clean.endsWith(".")) clean = clean.substring(0, clean.length() - 1);
        if (clean.startsWith("www.")) clean = clean.substring(4);
        return clean;
    }

    public boolean isRestoreSessionEnabled() {
        return Boolean.parseBoolean(props.getProperty(K_RESTORE_TABS, "false"));
    }
    public void setRestoreSessionEnabled(boolean v) { props.setProperty(K_RESTORE_TABS, Boolean.toString(v)); save(); }

    public boolean isGlassChromeEnabled() {
        return Boolean.parseBoolean(props.getProperty(K_GLASS_CHROME, "true"));
    }
    public void setGlassChromeEnabled(boolean v) { props.setProperty(K_GLASS_CHROME, Boolean.toString(v)); save(); }

    public boolean isAv1NormalizationAllSitesEnabled() {
        return Boolean.parseBoolean(props.getProperty(K_AV1_NORMALIZE_ALL, "false"));
    }
    public void setAv1NormalizationAllSitesEnabled(boolean v) {
        props.setProperty(K_AV1_NORMALIZE_ALL, Boolean.toString(v));
        save();
    }

    public List<String> getSessionUrls() {
        String raw = props.getProperty(K_SESSION_URLS, "");
        List<String> urls = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String url = line.trim();
            if (!url.isEmpty()) urls.add(url);
        }
        return urls;
    }

    public int getSessionActiveIndex() {
        try { return Integer.parseInt(props.getProperty(K_SESSION_INDEX, "0")); }
        catch (NumberFormatException e) { return 0; }
    }

    public void setSession(List<String> urls, int activeIndex) {
        List<String> safe = new ArrayList<>();
        if (urls != null) {
            for (String url : urls) {
                if (url == null) continue;
                String clean = url.replace('\n', ' ').replace('\r', ' ').trim();
                if (!clean.isEmpty()) safe.add(clean);
            }
        }
        if (safe.isEmpty()) {
            props.remove(K_SESSION_URLS);
            props.remove(K_SESSION_INDEX);
        } else {
            props.setProperty(K_SESSION_URLS, String.join("\n", safe));
            int idx = Math.max(0, Math.min(activeIndex, safe.size() - 1));
            props.setProperty(K_SESSION_INDEX, Integer.toString(idx));
        }
        save();
    }

    public void clearSession() {
        props.remove(K_SESSION_URLS);
        props.remove(K_SESSION_INDEX);
        save();
    }
}
