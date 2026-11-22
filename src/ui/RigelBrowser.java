package ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.LocationAdapter;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Menu;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Backend-neutral browser control used by Rigel.
 *
 * On macOS the default backend is Apple's modern WKWebView. The original SWT
 * Browser is available only when explicitly requested with
 * -Drigel.browser.backend=swt; Rigel never silently downgrades renderers.
 */
public final class RigelBrowser extends Composite {

    static final int PROXY_DIRECT = 0;
    static final int PROXY_HTTP = 1;
    static final int PROXY_SOCKS5 = 2;

    private static final java.util.concurrent.atomic.AtomicBoolean BACKEND_REPORTED =
            new java.util.concurrent.atomic.AtomicBoolean();

    public enum EditAction {
        // Indices match FFMWKWebView.editAction selector table:
        // 0 undo:, 1 redo:, 2 cut:, 3 copy:, 4 paste:, 5 selectAll:, 6 delete:
        UNDO(0, "undo"),
        REDO(1, "redo"),
        CUT(2, "cut"),
        COPY(3, "copy"),
        PASTE(4, "paste"),
        SELECT_ALL(5, "selectAll"),
        DELETE(6, "delete");

        final int nativeCode;
        final String command;

        EditAction(int nativeCode, String command) {
            this.nativeCode = nativeCode;
            this.command = command;
        }
    }

    public interface LocationListener {
        default boolean changing(String location) { return true; }
        default void changed(String location) {}
    }

    public interface ProgressListener {
        default void changed(int current, int total) {}
        default void completed() {}
    }

    @FunctionalInterface public interface TitleListener { void changed(String title); }
    @FunctionalInterface public interface ScriptFunction { Object call(Object[] arguments); }
    @FunctionalInterface public interface OpenHandler { RigelBrowser open(String initialUrl); }
    @FunctionalInterface public interface CloseHandler { void close(); }

    private enum Backend { WKWEBVIEW, SWT }

    private final List<LocationListener> locationListeners = new ArrayList<>();
    private final List<ProgressListener> progressListeners = new ArrayList<>();
    private final List<TitleListener> titleListeners = new ArrayList<>();
    private final Map<String, ScriptFunction> scriptFunctions = new ConcurrentHashMap<>();

    private Backend backend;
    private MacWKWebView wkWebView;
    private Browser swtBrowser;
    private OpenHandler openHandler;
    private CloseHandler closeHandler;
    private final List<Runnable> nativeClosedListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private String currentUrl = "about:blank";
    private String currentTitle = "";
    private boolean contentProcessReloadPending;
    public RigelBrowser(Composite parent, int style) {
        super(parent, style);
        setLayout(new FillLayout());

        String requested = System.getProperty("rigel.browser.backend", "wk").trim().toLowerCase();
        if (requested.equals("swt")) {
            createSwtBackend();
            return;
        }

        if (SWT.getPlatform().equals("cocoa")) {
            try {
                wkWebView = new MacWKWebView(this, SWT.NONE, new MacWKWebView.Delegate() {
                    @Override public void onNativeEvent(int type, String first, String second) {
                        handleNativeEvent(type, first, second);
                    }
                    @Override public void onScriptMessage(String name, String payload) {
                        handleScriptMessage(name, payload);
                    }
                    @Override public boolean shouldAllowNavigation(String url) {
                        return shouldAllowNavigationTo(url);
                    }
                });
                backend = Backend.WKWEBVIEW;
                reportBackend("WKWebView");
                return;
            } catch (Throwable failure) {
                throw new IllegalStateException(
                        "WKWebView could not start. Rigel did not fall back to the legacy renderer: "
                                + safeMessage(failure), failure);
            }
        }

        createSwtBackend();
    }

    private void createSwtBackend() {
        swtBrowser = new Browser(this, SWT.NONE);
        backend = Backend.SWT;
        reportBackend("SWT legacy WebView (explicit)");

        swtBrowser.addLocationListener(new LocationAdapter() {
            @Override public void changing(LocationEvent event) {
                for (LocationListener listener : List.copyOf(locationListeners)) {
                    if (!listener.changing(event.location)) {
                        event.doit = false;
                        return;
                    }
                }
            }
            @Override public void changed(LocationEvent event) {
                currentUrl = safe(event.location);
                fireLocationChanged(currentUrl);
            }
        });
        swtBrowser.addTitleListener(event -> {
            currentTitle = safe(event.title);
            fireTitleChanged(currentTitle);
        });
        swtBrowser.addProgressListener(new ProgressAdapter() {
            @Override public void changed(ProgressEvent event) {
                for (ProgressListener listener : List.copyOf(progressListeners))
                    listener.changed(event.current, event.total);
            }
            @Override public void completed(ProgressEvent event) {
                for (ProgressListener listener : List.copyOf(progressListeners)) listener.completed();
            }
        });
        swtBrowser.addOpenWindowListener(event -> {
            if (openHandler == null) return;
            RigelBrowser popup = openHandler.open("about:blank");
            if (popup != null && popup.swtBrowser != null) {
                event.required = true;
                event.browser = popup.swtBrowser;
            }
        });
        swtBrowser.addCloseWindowListener(event -> {
            if (closeHandler != null) closeHandler.close();
        });
    }

    public String getBackendName() {
        return backend == Backend.WKWEBVIEW ? "wkwebview" : "swt-webview";
    }

    public boolean usesNativeDownloads() {
        return backend == Backend.WKWEBVIEW;
    }

    public void configureDownloads(String directory, boolean askBeforeDownload) {
        if (backend == Backend.WKWEBVIEW && wkWebView != null)
            wkWebView.setDownloadOptions(directory, askBeforeDownload);
    }

    public boolean cancelDownload(String identifier) {
        return backend == Backend.WKWEBVIEW && wkWebView != null
                && wkWebView.cancelDownload(identifier);
    }

    private static void reportBackend(String name) {
        if (BACKEND_REPORTED.compareAndSet(false, true))
            System.out.println("[Rigel] Renderer: " + name);
    }

    public static void configureProxyPort(int proxyPort) {
        configureProxy(proxyPort, PROXY_HTTP);
    }

    public static void configureProxy(int proxyPort, int proxyMode) {
        int port = Math.max(0, proxyPort);
        int mode = port == 0 ? PROXY_DIRECT : proxyMode;
        // Diagnostic escape hatch (aitest.sh A/B runs): route WKWebView
        // directly, bypassing Rigel's loopback proxy chain entirely.
        if ("1".equals(System.getenv("RIGEL_PROXY_DIRECT"))) {
            port = 0;
            mode = PROXY_DIRECT;
        }
        System.setProperty("rigel.proxy.port", Integer.toString(port));
        System.setProperty("rigel.proxy.mode", Integer.toString(mode));
        String requested = System.getProperty("rigel.browser.backend", "wk").trim();
        if (SWT.getPlatform().equals("cocoa") && !requested.equalsIgnoreCase("swt")) {
            try {
                MacWKWebView.setProxy(port, mode);
            } catch (Throwable failure) {
                System.err.println("[Rigel] Could not update WK proxy route: "
                        + safeMessage(failure));
            }
        }
    }

    public static void configureAv1NormalizationAllSites(boolean enabled) {
        String requested = System.getProperty("rigel.browser.backend", "wk").trim();
        if (SWT.getPlatform().equals("cocoa") && !requested.equalsIgnoreCase("swt")) {
            try {
                MacWKWebView.setAv1NormalizationAllSites(enabled);
            } catch (Throwable failure) {
                System.err.println("[Rigel] Could not update AV1 compatibility mode: "
                        + safeMessage(failure));
            }
        }
    }

    public static void configureContentBlocking(boolean enabled) {
        String requested = System.getProperty("rigel.browser.backend", "wk").trim();
        if (SWT.getPlatform().equals("cocoa") && !requested.equalsIgnoreCase("swt")) {
            try {
                MacWKWebView.setContentBlocking(enabled);
            } catch (Throwable failure) {
                System.err.println("[Rigel] Could not update WebKit content blocking: "
                        + safeMessage(failure));
            }
        }
    }

    public boolean setUrl(String url) {
        if (url == null || url.isBlank()) return false;
        if (!shouldAllowNavigationTo(url)) return false;
        return backend == Backend.WKWEBVIEW ? wkWebView.loadUrl(url) : swtBrowser.setUrl(url);
    }

    public boolean setText(String html, boolean trusted) {
        return backend == Backend.WKWEBVIEW ? wkWebView.loadHtml(html) : swtBrowser.setText(html, trusted);
    }

    public boolean execute(String javascript) {
        if (javascript == null || isDisposed()) return false;
        return backend == Backend.WKWEBVIEW
                ? wkWebView.execute(javascript)
                : swtBrowser.execute(javascript);
    }

    /** Installs a document-start script in every frame of future WK pages. */
    public boolean addDocumentStartScript(String javascript) {
        if (javascript == null || javascript.isBlank() || isDisposed()) return false;
        // SWT has no equivalent persistent all-frame hook. Its fallback is
        // injected after each completed navigation by TabManager.
        return backend != Backend.WKWEBVIEW || wkWebView.addDocumentStartScript(javascript);
    }

    public boolean performEditAction(EditAction action) {
        if (action == null || isDisposed()) return false;
        if (backend == Backend.WKWEBVIEW) {
            return wkWebView.performEditAction(action.nativeCode);
        }
        return execute("try{document.execCommand('" + action.command + "');}catch(e){}");
    }

    public void evaluateAsync(String javascript, BiConsumer<Object, Throwable> completion) {
        if (backend == Backend.WKWEBVIEW) {
            String wrapped = "(function(){" + javascript + "})()";
            wkWebView.evaluateAsync(wrapped, (value, error) -> {
                completion.accept(value, error == null || error.isBlank()
                        ? null : new IllegalStateException(error));
            });
        } else {
            // SWT's Browser.evaluate has no block scoping: statements that
            // start with top-level `return` (all our callers use this form)
            // would be a SyntaxError without the wrap the WK path applies.
            try { completion.accept(swtBrowser.evaluate("(function(){" + javascript + "})()"), null); }
            catch (Throwable failure) { completion.accept(null, failure); }
        }
    }

    /** Lets the window's vibrancy material show through (rigel://home only). */
    public void setTransparent(boolean transparent) {
        if (backend == Backend.WKWEBVIEW && wkWebView != null && !wkWebView.isDisposed())
            wkWebView.setTransparent(transparent);
    }

    /** WK-only: PNG snapshot (alpha preserved) used by the visual smoke test. */
    public boolean snapshot(String path) {
        return backend == Backend.WKWEBVIEW && wkWebView != null
                && !wkWebView.isDisposed() && wkWebView.snapshot(path);
    }

    /**
     * Applies WebKit-native page zoom when the running WebKit supports it.
     * Returns false when the caller must fall back to CSS zoom.
     */
    public boolean setPageZoom(double zoom) {
        return backend == Backend.WKWEBVIEW && wkWebView != null && !wkWebView.isDisposed()
                && wkWebView.setPageZoom(zoom);
    }

    public void back() { if (backend == Backend.WKWEBVIEW) wkWebView.back(); else swtBrowser.back(); }
    public void forward() { if (backend == Backend.WKWEBVIEW) wkWebView.forward(); else swtBrowser.forward(); }
    public void reload() { if (backend == Backend.WKWEBVIEW) wkWebView.reload(); else swtBrowser.refresh(); }
    public void reloadIgnoringCache() {
        if (backend == Backend.WKWEBVIEW) wkWebView.reloadFromOrigin();
        else swtBrowser.execute("try{window.location.reload(true);}catch(e){window.location.reload();}");
    }
    public void stop() { if (backend == Backend.WKWEBVIEW) wkWebView.stopLoading(); else swtBrowser.stop(); }

    /** Reload a background WKWebView whose content process was reclaimed. */
    void resumeAfterContentProcessTermination() {
        if (backend != Backend.WKWEBVIEW || !contentProcessReloadPending || isDisposed()) return;
        contentProcessReloadPending = false;
        wkWebView.reload();
    }

    boolean simulateContentProcessTerminationForTesting() {
        return backend == Backend.WKWEBVIEW
                && wkWebView.simulateContentProcessTerminationForTesting();
    }

    boolean isContentProcessReloadPendingForTesting() {
        return contentProcessReloadPending;
    }

    public boolean isBackEnabled() {
        return backend == Backend.WKWEBVIEW ? wkWebView.canGoBack() : swtBrowser.isBackEnabled();
    }
    public boolean isForwardEnabled() {
        return backend == Backend.WKWEBVIEW ? wkWebView.canGoForward() : swtBrowser.isForwardEnabled();
    }
    public String getUrl() { return currentUrl; }
    public String getTitle() { return currentTitle; }

    public void addLocationListener(LocationListener listener) { locationListeners.add(listener); }
    public void addProgressListener(ProgressListener listener) { progressListeners.add(listener); }
    public void addTitleListener(TitleListener listener) { titleListeners.add(listener); }
    public void setOpenHandler(OpenHandler handler) { openHandler = handler; }
    public void setCloseHandler(CloseHandler handler) { closeHandler = handler; }

    /** Listeners fired when WebKit reports the view closed (webViewDidClose).
     *  Fired on the SWT main thread right after closeHandler. */
    public void addNativeClosedListener(Runnable listener) { nativeClosedListeners.add(listener); }

    public void addScriptFunction(String name, ScriptFunction function) {
        scriptFunctions.put(name, function);
        if (backend == Backend.SWT) {
            new BrowserFunction(swtBrowser, name) {
                @Override public Object function(Object[] arguments) {
                    return function.call(arguments == null ? new Object[0] : arguments);
                }
            };
        }
    }

    @Override public void setMenu(Menu menu) {
        super.setMenu(menu);
        if (swtBrowser != null && !swtBrowser.isDisposed()) swtBrowser.setMenu(menu);
    }

    @Override public boolean setFocus() {
        if (backend == Backend.WKWEBVIEW && wkWebView != null) return wkWebView.setFocus();
        return swtBrowser != null && swtBrowser.setFocus();
    }

    private void handleNativeEvent(int type, String first, String second) {
        switch (type) {
            case MacWKWebView.LOAD_STARTED -> {
                contentProcessReloadPending = false;
                updateLocationAndTitle(first, "", false);
                for (ProgressListener listener : List.copyOf(progressListeners)) listener.changed(10, 100);
            }
            case MacWKWebView.LOAD_FINISHED -> {
                updateLocationAndTitle(first, second, true);
                for (ProgressListener listener : List.copyOf(progressListeners)) listener.completed();
            }
            case MacWKWebView.LOAD_FAILED -> {
                updateLocationAndTitle(first, "", false);
                for (ProgressListener listener : List.copyOf(progressListeners)) listener.completed();
            }
            case MacWKWebView.LOCATION -> updateLocationAndTitle(first, second, true);
            case MacWKWebView.CLOSED -> {
                if (closeHandler != null) closeHandler.close();
                for (Runnable listener : List.copyOf(nativeClosedListeners)) listener.run();
            }
            case MacWKWebView.PROCESS_RESTARTED -> {
                contentProcessReloadPending = true;
                System.err.println("[Rigel] WKWebView content process restarted");
            }
            case MacWKWebView.NEW_WINDOW -> {
                if (first.isBlank()) break;
                RigelBrowser tab = null;
                if (openHandler != null) {
                    try { tab = openHandler.open(first); }
                    catch (Throwable ignored) { tab = null; }
                }
                if (tab == null) {
                    // Fallback for hosts that have no tab manager (diagnostic
                    // harnesses, embedded callers): still give the popup a
                    // WKWebView so its navigation reaches the proxy route.
                    try { tab = new RigelBrowser(this, SWT.NONE); }
                    catch (Throwable ignored) { tab = null; }
                }
                if (tab != null) tab.setUrl(first);
            }
            default -> {}
        }
    }

    private void updateLocationAndTitle(String location, String title, boolean titleAuthoritative) {
        if (!location.isBlank() && !location.equals(currentUrl)) {
            currentUrl = location;
            fireLocationChanged(location);
        }
        if ((titleAuthoritative || !title.isBlank()) && !title.equals(currentTitle)) {
            currentTitle = title;
            fireTitleChanged(title);
        }
    }

    private boolean shouldAllowNavigationTo(String url) {
        if (url == null || url.isBlank()) return true;
        for (LocationListener listener : List.copyOf(locationListeners)) {
            if (!listener.changing(url)) return false;
        }
        return true;
    }

    private void handleScriptMessage(String name, String payload) {
        if (name.equals("rigelEvent")) {
            Object[] args = decodeArguments(payload);
            updateLocationAndTitle(args.length > 0 ? args[0].toString() : "",
                    args.length > 1 ? args[1].toString() : "", true);
            return;
        }
        if (name.equals("rigelOpen")) {
            Object[] args = decodeArguments(payload);
            String url = args.length == 0 ? "" : args[0].toString();
            if (openHandler != null && !url.isBlank()) {
                RigelBrowser popup = openHandler.open(url);
                if (popup != null) popup.setUrl(url);
            }
            return;
        }
        if (name.equals("rigelContext")) {
            Object[] args = decodeArguments(payload);
            Menu menu = getMenu();
            if (menu != null && !menu.isDisposed() && args.length >= 2) {
                try {
                    int x = (int) Math.round(Double.parseDouble(args[0].toString()));
                    int y = (int) Math.round(Double.parseDouble(args[1].toString()));
                    Point location = toDisplay(x, y);
                    menu.setLocation(location);
                    menu.setVisible(true);
                } catch (NumberFormatException ignored) {}
            }
            return;
        }
        ScriptFunction function = scriptFunctions.get(name);
        if (function != null) function.call(decodeArguments(payload));
    }

    private Object[] decodeArguments(String payload) {
        if (payload == null || payload.isEmpty()) return new Object[0];
        String[] pieces = payload.split("\\|", -1);
        Object[] values = new Object[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            try { values[i] = URLDecoder.decode(pieces[i], StandardCharsets.UTF_8); }
            catch (Exception ignored) { values[i] = pieces[i]; }
        }
        return values;
    }

    private void fireLocationChanged(String location) {
        for (LocationListener listener : List.copyOf(locationListeners)) listener.changed(location);
    }

    private void fireTitleChanged(String title) {
        for (TitleListener listener : List.copyOf(titleListeners)) listener.changed(title);
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    @Override protected void checkSubclass() {}
}
