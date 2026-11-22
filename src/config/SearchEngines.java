package config;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Search engines for the address bar (a.k.a. omnibox). Google is the
 * default; Bing, DuckDuckGo and Brave are supported; "custom" takes
 * whatever name/base URL/query parameter the user typed, persisted by
 * Settings under search.engine(.custom.*).
 *
 * Each {@link Engine} carries the four persisted fields — name, template
 * (baseUrl plus an optional {query}/{q} placeholder or trailing {@code ?}),
 * queryParameter and homeShortcut — plus derived accessors baseUrl() and
 * queryParameter().
 *
 * URL building ({@link #replaceQuery}): the first {@code %s}/{@code {q}}
 * occurrence is substituted with the encoded query, or the query is
 * appended as {@code ?q=...} when the template ends with {@code ?} or has
 * no query string yet.
 */
public final class SearchEngines {
    private SearchEngines() {}

    public static final String GOOGLE = "google";
    public static final String BING = "bing";
    public static final String DUCK_DUCK_GO = "ddg";
    public static final String BRAVE = "brave";
    public static final String CUSTOM = "custom";
    public static final String ID_DDG = DUCK_DUCK_GO;

    /** One search engine — the persisted model. */
    public static final class Engine {
        public final String id;
        public final String name;
        public final String template;       // base URL + query part, e.g. https://duckduckgo.com/?q=%s
        public final String queryParam;
        public final boolean editable;
        public final boolean custom;
        private final String homeShortcut;

        public Engine(String id, String name, String template, String queryParam,
                      boolean editable, boolean custom, String homeShortcut) {
            this.id = id;
            this.name = name;
            this.template = template;
            this.queryParam = queryParam == null || queryParam.isBlank() ? "q" : queryParam;
            this.editable = editable;
            this.custom = custom;
            this.homeShortcut = homeShortcut;
        }

        public boolean isCustom() { return custom; }

        /** The engine's base URL (template without its query part). */
        public String baseUrl() { return stripQuery(template); }

        /** Effective query parameter name ("q" by default). */
        public String queryParameter() { return queryParam; }

        /** Home-page shortcut label for the new-tab page ("" = none). */
        public String homeShortcut() { return homeShortcut == null ? "" : homeShortcut; }

        /** Search URL for {@code query}: substitutes {query}/{q}/{?} or appends. */
        public String searchUrl(String rawQuery) {
            return replaceQuery(template, encode(rawQuery));
        }

        @Override public String toString() { return name + " (" + id + ")"; }
    }

    private static final Engine GOOGLE_ENGINE = new Engine(GOOGLE, "Google",
            "https://www.google.com/search?q=%s", "q", false, false, "GG");
    private static final Engine BING_ENGINE = new Engine(BING, "Bing",
            "https://www.bing.com/search?q=%s", "q", false, false, "BNG");
    private static final Engine DDG_ENGINE = new Engine(DUCK_DUCK_GO, "DuckDuckGo",
            "https://duckduckgo.com/?q=%s", "q", false, false, "DDG");
    private static final Engine BRAVE_ENGINE = new Engine(BRAVE, "Brave",
            "https://search.brave.com/search?q=%s", "q", false, false, "BRV");
    private static final Engine CUSTOM_ENGINE = new Engine(CUSTOM, "Custom URL",
            "https://example.com/search", "q", true, true, "");

    private static final List<Engine> ENGINES = List.of(
            GOOGLE_ENGINE, BING_ENGINE, DDG_ENGINE, BRAVE_ENGINE, CUSTOM_ENGINE);

    /** Built-in engines plus the "Custom URL" row: Google, Bing,
     *  DuckDuckGo, Brave, Custom URL. */
    public static List<Engine> options() { return ENGINES; }

    /** Named (non-custom) engines in display order. */
    public static List<Engine> namedEngines() { return ENGINES.subList(0, 4); }

    /** The "Custom URL" option shown in Settings and used when custom is selected. */
    public static Engine custom() { return CUSTOM_ENGINE; }

    /** True for built-in engine ids (google, bing, ddg, brave). */
    public static boolean isNamed(String id) {
        return namedEngines().stream().anyMatch(e -> e.id.equals(id));
    }

    /** Engine for a stored id; unknown or missing ids return Google. */
    public static Engine find(String id) {
        for (Engine e : ENGINES) if (e.id.equals(id)) return e;
        return GOOGLE_ENGINE;
    }

    /** The configured engine for Settings — uses {@link Settings#getSearchEngineId()}. */
    public static Engine currentEngine(Settings settings) {
        return find(settings == null ? null : settings.getSearchEngineId());
    }

    /** The configured engine, or Google as the final default. */
    public static Engine currentEngineOrGoogle(Settings settings) {
        return currentEngine(settings);
    }

    /** Static search URL for {@code engine} and {@code query}. */
    public static String searchUrl(Engine engine, String query) {
        if (engine == null) return null;
        return engine.searchUrl(query);
    }

    /** {@code %s}-style template equivalent of {@link Engine#searchUrl}:
     *  the first {query}/{q} placeholder or a {@code ?} becomes {@code %s};
     *  appends {@code ?q=%s} when the base has no query part. */
    public static String urlTemplate(Engine engine) {
        if (engine == null || engine.template == null) return "";
        String t = engine.template.trim();
        if (t.isEmpty()) return "";
        int p = t.indexOf("%s");
        if (p >= 0) return t.substring(0, p) + "%s" + t.substring(p + 2);
        p = t.indexOf("{q}");
        if (p >= 0) return t.substring(0, p) + "%s" + t.substring(p + 3);
        if (t.endsWith("?")) return t + "%s";
        if (t.indexOf('?') < 0) return t + "?q=%s";
        return replaceQuery(t, "%s");
    }

    /** Replaces the first {@code %s} or {@code {q}} occurrence, else appends
     *  as {@code ?q=<encoded>} when there is no query string yet. */
    public static String replaceQuery(String template, String encodedQuery) {
        if (template == null || template.isBlank() || encodedQuery == null) return "";
        String t = template.trim();
        int at = t.indexOf("%s");
        if (at >= 0) return t.substring(0, at) + encodedQuery + t.substring(at + 2);
        at = t.indexOf("{q}");
        if (at >= 0) return t.substring(0, at) + encodedQuery + t.substring(at + 3);
        if (t.endsWith("?")) return t + encodedQuery;
        if (t.indexOf('?') < 0) return t + "?q=" + encodedQuery;
        return t;
    }

    /** Strips the query part from a URL template — used when showing the
     *  engine's search URL as a suggestion in the address bar. */
    public static String stripQuery(String template) {
        String t = (template == null ? "" : template).trim();
        int q = t.indexOf("?");
        return q < 0 ? t : t.substring(0, q);
    }

    /** Validates a custom URL: absolute http(s) prefix, no spaces, host present. */
    public static boolean isCustomUrlValid(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if (!t.startsWith("http://") && !t.startsWith("https://")) return false;
        String cleaned = t.replace("{query}", "x").replace("{q}", "x");
        try {
            URI uri = URI.create(cleaned.replace("x", "rigel"));
            return uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** Validates a legacy search template: contains {@code %s} and a
     *  absolute http(s) host. */
    public static boolean isValidSearchTemplate(String template) {
        if (template == null || !template.contains("%s")) return false;
        return isCustomUrlValid(template.replace("%s", "rigel"));
    }

    private static String encode(String query) {
        return URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8);
    }

    /** True for a "looks like it carries a query" custom URL when the user
     *  typed one ending in a trailing space? — placeholder for future
     *  empty-input completion behavior. Not used today. */
    @SuppressWarnings("unused")
    private static final Pattern USER_TYPED_TAIL = Pattern.compile("\\s+$");
}
