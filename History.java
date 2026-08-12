package config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * History — append-only visited-URL log, persisted across sessions.
 *
 * Stored line-delimited at portable-data/profile/history.tsv as
 * "epochMillis\ttitle\turl". Capped in memory at MAX_ENTRIES (most recent kept)
 * to bound memory and the history panel size; the on-disk file is rewritten
 * from the capped list on each add so it can't grow without bound either.
 */
public class History {

    public static class Entry {
        public final long   time;
        public final String title;
        public final String url;
        public Entry(long time, String title, String url) {
            this.time = time; this.title = title; this.url = url;
        }
    }

    private static final int MAX_ENTRIES = 2000;

    private final Path file = Settings.DIR.resolve("history.tsv");
    private final List<Entry> entries = new ArrayList<>();

    public History() { load(); }

    private void load() {
        entries.clear();
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file)) {
                String[] parts = line.split("\t", 3);
                if (parts.length < 3) continue;
                try {
                    entries.add(new Entry(Long.parseLong(parts[0]), parts[1], parts[2]));
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException e) {
            System.err.println("[Rigel] Could not read history: " + e.getMessage());
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    public synchronized void record(String title, String url) {
        String u = clean(url);
        if (u.isEmpty() || u.equals("rigel://home") || u.equals("about:blank")) return;
        // Collapse consecutive duplicates of the same URL
        if (!entries.isEmpty() && entries.get(entries.size() - 1).url.equals(u)) return;
        Entry entry = new Entry(System.currentTimeMillis(), clean(title), u);
        entries.add(entry);
        boolean trimmed = false;
        if (entries.size() > MAX_ENTRIES) {
            entries.subList(0, entries.size() - MAX_ENTRIES).clear();
            trimmed = true;
        }
        if (trimmed) save();
        else append(entry);
    }

    private void append(Entry entry) {
        try {
            if (!Files.exists(Settings.DIR)) Files.createDirectories(Settings.DIR);
            String line = entry.time + "\t" + entry.title + "\t" + entry.url
                    + System.lineSeparator();
            Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[Rigel] Could not append history: " + e.getMessage());
        }
    }

    private void save() {
        try {
            List<String> lines = new ArrayList<>(entries.size());
            for (Entry e : entries) lines.add(e.time + "\t" + e.title + "\t" + e.url);
            AtomicFiles.write(file, temporary -> Files.write(temporary, lines,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
        } catch (IOException e) {
            System.err.println("[Rigel] Could not save history: " + e.getMessage());
        }
    }

    public synchronized void clear() {
        entries.clear();
        save();
    }

    /** Most-recent-first view of history. */
    public synchronized List<Entry> recent() {
        List<Entry> copy = new ArrayList<>(entries);
        Collections.reverse(copy);
        return copy;
    }
}
