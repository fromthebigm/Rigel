package config;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bookmarks — a flat, persisted list of saved sites.
 *
 * Stored line-delimited at portable-data/profile/bookmarks.tsv as "title\turl".
 * TSV keeps it dependency-free and human-editable. Titles/URLs are sanitised of
 * tabs and newlines on add so the format can't be corrupted.
 */
public class Bookmarks {

    private static final int MAX_ENTRIES = 2000;
    private static final int MAX_TITLE_CODE_POINTS = 512;
    private static final int MAX_URL_CODE_POINTS = 4096;
    private static final int MAX_LINE_CHARS = 16_384;
    private static final long MAX_FILE_BYTES = 16L * 1024L * 1024L;

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
        Deque<Entry> recent = new ArrayDeque<>(MAX_ENTRIES);
        boolean trimmed = false;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                System.err.println("[Rigel] Bookmarks file is too large; ignoring it");
                return;
            }
            try (BufferedReader reader = Files.newBufferedReader(file)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() > MAX_LINE_CHARS) continue;
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    String title = clean(line.substring(0, tab), MAX_TITLE_CODE_POINTS);
                    String url = clean(line.substring(tab + 1), MAX_URL_CODE_POINTS);
                    if (title.isEmpty() || url.isEmpty()) continue;
                    if (recent.size() == MAX_ENTRIES) {
                        recent.removeFirst();
                        trimmed = true;
                    }
                    recent.addLast(new Entry(title, url));
                }
            }
            entries.addAll(recent);
            if (trimmed) save();
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

    private static String clean(String s, int maxCodePoints) {
        String clean = s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        int count = clean.codePointCount(0, clean.length());
        return count <= maxCodePoints
                ? clean : clean.substring(0, clean.offsetByCodePoints(0, maxCodePoints));
    }

    public synchronized void add(String title, String url) {
        String u = clean(url, MAX_URL_CODE_POINTS);
        if (u.isEmpty()) return;
        String t = clean(title, MAX_TITLE_CODE_POINTS);
        if (t.isEmpty()) t = clean(u, MAX_TITLE_CODE_POINTS);
        // De-dupe by URL
        for (Entry e : entries) if (e.url.equals(u)) return;
        while (entries.size() >= MAX_ENTRIES) entries.remove(0);
        entries.add(new Entry(t, u));
        save();
    }

    public synchronized void remove(String url) {
        String u = clean(url, MAX_URL_CODE_POINTS);
        entries.removeIf(e -> e.url.equals(u));
        save();
    }

    public synchronized boolean contains(String url) {
        String u = clean(url, MAX_URL_CODE_POINTS);
        for (Entry e : entries) if (e.url.equals(u)) return true;
        return false;
    }

    public synchronized List<Entry> all() { return new ArrayList<>(entries); }
}
