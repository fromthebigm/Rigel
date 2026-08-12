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
        proxyServer = new ProxyServer(blocklist);
        proxyServer.start();
        if (!settings.getUpstreamHost().isEmpty() && settings.getUpstreamPort() > 0) {
            proxyServer.setUpstream(
                    ProxyServer.UpstreamType.fromSetting(settings.getUpstreamType()),
                    settings.getUpstreamHost(), settings.getUpstreamPort());
        }
        configureWebRouting();

        try {
            openWindow(true);
            while (!display.isDisposed() && !windows.isEmpty()) {
                if (!display.readAndDispatch()) display.sleep();
            }
        } finally {
            if (proxyServer != null) proxyServer.stop();
        }
    }

    private void configureWebRouting() {
        // WebKit talks straight to LittleProxy using HTTP CONNECT. LittleProxy
        // then either connects directly (Chrome-style DIRECT behavior) or uses
        // the configured upstream. This preserves one local proxy hop for all
        // sites without the former SOCKS-to-HTTP loopback adapter.
        RigelBrowser.configureProxy(proxyServer.getLocalPort(), RigelBrowser.PROXY_HTTP);
    }

    private void openWindow(boolean restoreSession) {
        if (quitting || display.isDisposed()) return;
        BrowserUI window = new BrowserUI();
        windows.add(window);
        try {
            window.open(display, settings, bookmarks, history, proxyServer,
                    () -> display.asyncExec(() -> openWindow(false)),
                    this::quit,
                    () -> windows.remove(window),
                    this::broadcastSharedSettings,
                    restoreSession);
        } catch (Throwable failure) {
            windows.remove(window);
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
