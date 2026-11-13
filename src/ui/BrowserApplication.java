package ui;

import config.Bookmarks;
import config.History;
import config.Settings;
import org.eclipse.swt.widgets.Display;
import proxy.Blocklist;
import proxy.ProxyServer;
import proxy.LocalOriginServer;

import java.util.ArrayList;
import java.util.List;

/** Owns the one SWT event loop and the resources shared by all browser windows. */
public final class BrowserApplication {
    private final Display display;
    private final Settings settings = new Settings();
    private final Bookmarks bookmarks = new Bookmarks();
    private final History history = new History();
    private final List<BrowserUI> windows = new ArrayList<>();
    private ProxyServer proxyServer;
    private LocalOriginServer localOriginServer;
    private boolean quitting;
    /** Last time a cookie snapshot was successfully written (main-thread
     *  autosave or clean quit). The TERM-path save must not run when one of
     *  these is fresh — see saveCookiesAtShutdown(). */
    private volatile long lastCookieSnapshotOkAt;
    private static final int COOKIE_AUTOSAVE_MS = 45_000;
    private static final long COOKIE_AUTOSAVE_NANOS = COOKIE_AUTOSAVE_MS * 1_000_000L; // 45s

    public BrowserApplication(Display display) {
        this.display = display;
    }

    public void run() {
        run(null);
    }

    /** @param startUrl deep link from the launcher (e.g. a website card); when
     *  present the first window opens this page instead of homepage/session. */
    public void run(String startUrl) {
        installCookieShutdownHook();
        RigelBrowser.configureAv1NormalizationAllSites(
                settings.isAv1NormalizationAllSitesEnabled());
        RigelBrowser.configureContentBlocking(settings.isBlocklistEnabled());
        Blocklist blocklist = new Blocklist(settings.isBlocklistEnabled());
        proxy.SecureDns.setMode(proxy.SecureDns.Mode.fromSetting(settings.getDnsMode()));
        // Domain fronting must be live before any CONNECT is dialed: the
        // list persists in settings but was previously only loaded when the
        // Network settings page was opened — a restart silently ran with
        // fronting off. Load the saved map at startup, before the proxy
        // accepts its first tunnel.
        proxy.DomainFronting.loadFrom(settings.getDomainFronting());
        localOriginServer = new LocalOriginServer();
        localOriginServer.start();
        MacWKWebView.setOriginPort(localOriginServer.getPort());
        proxyServer = new ProxyServer(blocklist);
        try {
            proxyServer.start();
            restorePersistedUpstream();
            configureWebRouting();
            // WebKit never gets a clean network-process exit on this host (the
            // JVM leaves the moment the last window closes), so its
            // HTTPStorages jar is never flushed and on-disk cookies freeze.
            // Replay the snapshot of the last session before the first page
            // can navigate; saved again below once all windows are gone.
            restoreCookies();
            if (System.getenv("RIGEL_COOKIE_DEBUG") != null) {
                // Diagnostic: where do replayed cookies stand over time?
                // debugNN = snapshot NN seconds after startup.
                MacWKWebView.saveCookies(Settings.DIR.resolve("cookies.debug00.store"));
                for (int mark : new int[] {4, 10, 14, 20, 28, 40, 60, 75}) {
                    display.timerExec(mark * 1000, () ->
                            MacWKWebView.saveCookies(Settings.DIR.resolve(
                                    "cookies.debug" + mark + ".store")));
                }
            }
            if (startUrl != null) {
                openWindow(true, startUrl, -1, -1);
            } else {
                openWindow(true);
            }
            scheduleCookieAutosave();
            while (!display.isDisposed() && !windows.isEmpty()) {
                if (!display.readAndDispatch()) display.sleep();
            }
        } finally {
            saveCookies();
            if (localOriginServer != null) localOriginServer.stop();
            if (proxyServer != null) proxyServer.stop();
        }
    }

    /** Snapshot cookies from a JVM shutdown hook (TERM/kill paths) where the
     * caller is NOT the SWT thread — the bridge handles the cross-thread
     * round-trip to the main runloop itself. */
    private void installCookieShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveCookiesAtShutdown, "RigelCookieSave"));
    }

    /** Live refresh on the display timer, so the portable snapshot on disk is
     * never more than 45 s behind the real session — regardless of how the
     * process later dies. */
    private void scheduleCookieAutosave() {
        Runnable tick = new Runnable() {
            public void run() {
                if (display.isDisposed()) return;
                saveCookies();
                display.timerExec(COOKIE_AUTOSAVE_MS, this);
            }
        };
        // First tick soon after launch (the restored state is worth landing on
        // disk immediately), then every 45 s.
        display.timerExec(20_000, tick);
    }

    private void saveCookiesAtShutdown() {
        // The death-time WKHTTPCookieStore fetch races AppKit/WebKit teardown
        // and has been observed silently serving the OLD on-disk view: 161
        // live cookies on the main thread seconds earlier came back from the
        // hook's fetch as the previous snapshot's 4, and the write then
        // clobbered the good file with the stale set. A timer-driven snapshot
        // already on disk beats anything the teardown fetch can see, so when
        // one is fresh, keep it and skip the fetch entirely. Only young
        // sessions (no autosave yet) still take the live fetch — that path is
        // what the r7 harness exercised all along.
        if (System.nanoTime() - lastCookieSnapshotOkAt < COOKIE_AUTOSAVE_NANOS * 3 / 2) {
            return;  // autosave is fresh; the teardown fetch is untrustworthy
        }
        saveCookies();
    }

    private void saveCookies() {
        try {
            if (MacWKWebView.saveCookies(cookieSnapshot())) {
                lastCookieSnapshotOkAt = System.nanoTime();
                mirrorCookieSnapshot();
            } else {
                System.err.println("[Rigel] Cookie snapshot failed");
            }
        } catch (Throwable failure) {
            System.err.println("[Rigel] Cookie snapshot failed: " + failure.getMessage());
        }
    }

    private void restoreCookies() {
        try {
            // First disarm WebKit's ITP storage sweep: it deletes replayed
            // cookies within seconds (no interaction records in a fresh
            // store), which is exactly the "logins don't save" trap.
            MacWKWebView.disableStorageSweeps();
            // Sign-ins that live OUTSIDE the portable folder (older identity
            // jars in the real home) are additive and replay first; the
            // portable snapshot goes last so current state wins any overlap.
            java.nio.file.Path imported = CookieImport.syncIn(Settings.DIR);
            if (imported != null) {
                System.err.println("[Rigel] Replay import -> " + MacWKWebView.restoreCookies(imported));
            }
            // If the primary snapshot is missing or unreadable, sessions fall
            // back to the Support copy — logins keep working exactly as if
            // WebKit's own jar had never broken.
            int restored = MacWKWebView.restoreCookies(cookieSnapshot());
            if (restored == 0 && java.nio.file.Files.isRegularFile(cookieSnapshotFallback())) {
                restored = MacWKWebView.restoreCookies(cookieSnapshotFallback());
                System.err.println("[Rigel] Sign-ins restored from the Support fallback snapshot");
            }
            System.err.println("[Rigel] Replay snapshot -> " + restored);
        } catch (Throwable failure) {
            System.err.println("[Rigel] Cookie restore failed: " + failure.getMessage());
        }
    }

    /** Belt-and-suspenders copy outside portable-data (same philosophy as
     * run.sh's Support/cookie-jar backup): survives a wiped profile, and the
     * restore path above falls back to it transparently. */
    private void mirrorCookieSnapshot() {
        try {
            java.nio.file.Path fallback = cookieSnapshotFallback();
            java.nio.file.Files.createDirectories(fallback.getParent());
            java.nio.file.Files.copy(cookieSnapshot(), fallback,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable failure) {
            System.err.println("[Rigel] Cookie fallback copy failed: " + failure.getMessage());
        }
    }

    private java.nio.file.Path cookieSnapshot() {
        return Settings.DIR.resolve("cookies.store");
    }

    private java.nio.file.Path cookieSnapshotFallback() {
        return Settings.ROOT.resolve("Support").resolve("cookies.store");
    }

    private void restorePersistedUpstream() {
        String host = settings.getUpstreamHost().trim();
        if (host.isEmpty()) {
            if (!settings.getUpstreamHost().isEmpty()) settings.clearUpstream();
            return;
        }
        int port = settings.getUpstreamPort();
        if (port < 1 || port > 65535) {
            System.err.println("[Rigel] Ignoring invalid saved upstream proxy; using DIRECT");
            settings.clearUpstream();
            return;
        }
        try {
            proxyServer.setUpstream(
                    ProxyServer.UpstreamType.fromSetting(settings.getUpstreamType()),
                    host, port,
                    settings.getUpstreamUser(), settings.getUpstreamPass());
        } catch (IllegalArgumentException invalid) {
            System.err.println("[Rigel] Ignoring invalid saved upstream proxy; using DIRECT: "
                    + invalid.getMessage());
            proxyServer.clearUpstream();
            settings.clearUpstream();
        }
    }

    private void configureWebRouting() {
        // WebKit uses Rigel's loopback SOCKS5 ingress. TCP CONNECT requests are
        // forwarded through LittleProxy so the blocklist and upstream routing
        // stay unchanged; UDP ASSOCIATE is handled by the SOCKS5 relay.
        RigelBrowser.configureProxy(
                proxyServer.getBrowserProxyPort(), RigelBrowser.PROXY_SOCKS5);
    }

    private void openWindow(boolean restoreSession) {
        openWindow(restoreSession, null, -1, -1);
    }

    private void openWindow(boolean restoreSession, String initialUrl, int x, int y) {
        if (quitting || display.isDisposed()) return;
        BrowserUI window = new BrowserUI();
        windows.add(window);
        try {
            window.open(display, settings, bookmarks, history, proxyServer,
                    // Deliberately synchronous: a tear-off must add the new
                    // window to this list before the old (possibly now empty)
                    // window finishes closing, or the run loop could see an
                    // empty window list and quit mid-drag.
                    (url, px, py) -> openWindow(false, url, px, py),
                    this::quit,
                    () -> windows.remove(window),
                    this::broadcastSharedSettings,
                    restoreSession,
                    initialUrl, x, y);
        } catch (Throwable failure) {
            windows.remove(window);
            // The shell may already exist even when open() blew up partway
            // (e.g. WKWebView unavailable); always release it.
            org.eclipse.swt.widgets.Shell created = window.getShell();
            if (created != null && !created.isDisposed()) created.dispose();
            throw failure;
        }
    }

    private void broadcastSharedSettings() {
        if (display.isDisposed()) return;
        for (BrowserUI window : List.copyOf(windows)) {
            window.applySharedSettings();
        }
    }

    private void quit() {
        if (quitting) return;
        quitting = true;
        for (BrowserUI window : List.copyOf(windows)) {
            if (window.getShell() != null && !window.getShell().isDisposed()) {
                window.getShell().close();
            }
        }
    }
}
