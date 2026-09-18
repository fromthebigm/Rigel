package ui;

import config.Bookmarks;
import config.History;
import config.Settings;
import org.eclipse.swt.widgets.Display;
import proxy.Blocklist;
import proxy.ProxyServer;

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
    private boolean quitting;

    public BrowserApplication(Display display) {
        this.display = display;
    }

    public void run() {
        RigelBrowser.configureAv1NormalizationAllSites(
                settings.isAv1NormalizationAllSitesEnabled());
        RigelBrowser.configureContentBlocking(settings.isBlocklistEnabled());
        Blocklist blocklist = new Blocklist(settings.isBlocklistEnabled());
        proxy.SecureDns.setMode(proxy.SecureDns.Mode.fromSetting(settings.getDnsMode()));
        proxyServer = new ProxyServer(blocklist);
        try {
            proxyServer.start();
            restorePersistedUpstream();
            configureWebRouting();
            openWindow(true);
            while (!display.isDisposed() && !windows.isEmpty()) {
                if (!display.readAndDispatch()) display.sleep();
            }
        } finally {
            if (proxyServer != null) proxyServer.stop();
        }
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
                    ProxyServer.UpstreamType.fromSetting(settings.getUpstreamType()), host, port);
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
