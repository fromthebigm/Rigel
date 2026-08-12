package config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Bookmarks — a flat, persisted list of saved sites.
 *
 * Stored line-delimited at portable-data/profile/bookmarks.tsv as "title\turl".
 * TSV keeps it dependency-free and human-editable. Titles/URLs are sanitised of
 * tabs and newlines on add so the format can't be corrupted.
 */
public class Bookmarks {

    public static class Entry {
        public final String title;
        public final String url;
        public Entry(String title, String url) { this.title = title; this.url = url; }
    }

    private final Path file = Settings.DIR.resolve("bookmarks.tsv");
    private final List<Entry> entries = new ArrayList<>();

    public Bookmarks() { load(); }

    private void load() {
        entries.clear();
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file)) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                entries.add(new Entry(line.substring(0, tab), line.substring(tab + 1)));
            }
        } catch (IOException e) {
            System.err.println("[Rigel] Could not read bookmarks: " + e.getMessage());
        }
    }

    private void save() {
        try {
            List<String> lines = new ArrayList<>(entries.size());
            for (Entry e : entries) lines.add(e.title + "\t" + e.url);
            AtomicFiles.write(file, temporary -> Files.write(temporary, lines));
        } catch (IOException e) {
            System.err.println("[Rigel] Could not save bookmarks: " + e.getMessage());
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    public synchronized void add(String title, String url) {
        String u = clean(url);
        if (u.isEmpty()) return;
        String t = clean(title);
        if (t.isEmpty()) t = u;
        // De-dupe by URL
        for (Entry e : entries) if (e.url.equals(u)) return;
        entries.add(new Entry(t, u));
        save();
    }

    public synchronized void remove(String url) {
        entries.removeIf(e -> e.url.equals(url));
        save();
    }

    public synchronized boolean contains(String url) {
        String u = clean(url);
        for (Entry e : entries) if (e.url.equals(u)) return true;
        return false;
    }

    public synchronized List<Entry> all() { return new ArrayList<>(entries); }
}
