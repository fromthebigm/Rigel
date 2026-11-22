package config;

import java.nio.file.Files;
import java.nio.file.Path;

/** Regression coverage for SearchEngines + Settings search-engine keys. */
public final class RigelSearchEnginesTest {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("rigel.test.root", "")).toRealPath();
        Files.createDirectories(Settings.DIR);
        if (Files.exists(Settings.FILE)) Files.delete(Settings.FILE);
        Settings s = new Settings();

        require("google".equals(s.getSearchEngineId()), "default id is google");
        require("https://www.google.com/search?q=%s".equals(s.getSearchTemplate()), "default template");
        require(!SearchEngines.find("google").isCustom(), "google is named");
        require("https://www.google.com/search".equals(SearchEngines.find("google").baseUrl()), "google base");
        require("GG".equals(SearchEngines.find("google").homeShortcut()), "google shortcut");
        require("https://www.google.com/search?q=a+b".equals(SearchEngines.find("google").searchUrl("a b")), "google encoded");
        require("https://www.bing.com/search?q=cats".equals(SearchEngines.find("bing").searchUrl("cats")), "bing");
        require("https://duckduckgo.com/?q=cats".equals(SearchEngines.find("ddg").searchUrl("cats")), "ddg");
        require("https://search.brave.com/search?q=cats".equals(SearchEngines.find("brave").searchUrl("cats")), "brave");
        require(SearchEngines.options().size() == 5, "5 options");
        require(SearchEngines.isCustomUrlValid("https://x.io/search"), "custom url valid");
        require(!SearchEngines.isCustomUrlValid("nope"), "custom url invalid");
        require(SearchEngines.isValidSearchTemplate("https://example.com/s?q=%s"), "legacy template valid");
        require("https://example.com/search?tab=web&q=cats".equals(SearchEngines.replaceQuery("https://example.com/search?tab=web", "cats")), "append &");
        require("https://example.com/search?q=cats".equals(SearchEngines.replaceQuery("https://example.com/search?", "cats")), "trailing ?");

        s.setSearchEngineId("custom");
        require("custom".equals(s.getSearchEngineId()), "custom id set");
        s.setCustomEngineUrl("https://mysearch.example/search");
        s.setCustomEngineQueryParameter("s");
        s.setCustomEngineName("Mine");
        require("https://mysearch.example/search?s=my+query".equals(SearchEngines.currentEngineOrGoogle(s).searchUrl("my query")), "custom search");
        s.setSearchEngineId("ddg");
        require("ddg".equals(s.getSearchEngineId()), "back to ddg");
        s.setSearchEngineId("duckduckgo");
        require("ddg".equals(s.getSearchEngineId()), "legacy duckduckgo id");

        System.out.println("Rigel search engines tests passed");
    }

    private static void require(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAIL: " + what);
    }
}
