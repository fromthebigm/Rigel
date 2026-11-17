package ui;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Thin SWT host for Apple's modern WKWebView.
 *
 * SWT continues to own the window and layout. A small bundled Objective-C
 * bridge creates WKWebView as a native child of this Composite's NSView and
 * reports navigation/script events back through JNA.
 */
final class MacWKWebView extends Composite {

    interface Delegate {
        void onNativeEvent(int eventType, String value1, String value2);
        void onScriptMessage(String name, String payload);
        boolean shouldAllowNavigation(String url);
    }

    private interface EventCallback extends Callback {
        void invoke(long token, int eventType, String value1, String value2);
    }

    private interface ScriptCallback extends Callback {
        void invoke(long token, String name, String payload);
    }

    private interface EvalCallback extends Callback {
        void invoke(long evalToken, String value, String error);
    }

    private interface PolicyCallback extends Callback {
        int invoke(long token, String url);
    }

    private interface Bridge extends Library {
        int rigel_wk_supported();
        long rigel_webkit_memory_bytes();
        int rigel_wk_cookies_save(String path);
        int rigel_wk_cookies_restore(String path);
        void rigel_wk_disable_resource_load_statistics();
        void rigel_wk_set_proxy_port(int proxyPort);
        void rigel_wk_set_proxy(int proxyPort, int proxyMode);
        void rigel_wk_set_av1_normalization(Pointer handle, int enabled);
        void rigel_wk_set_content_blocking(Pointer handle, int enabled);
        void rigel_wk_set_download_options(Pointer handle, String directory, int askBeforeDownload);
        int rigel_wk_cancel_download(Pointer handle, String identifier);
        Pointer rigel_wk_create(long parentView, long token, int proxyPort, int proxyMode,
                                int unusedMode, long unusedSessionId,
                                EventCallback eventCallback, ScriptCallback scriptCallback,
                                PolicyCallback policyCallback);
        void rigel_wk_destroy(Pointer handle);
        int rigel_wk_test_simulate_content_process_termination(Pointer handle);
        int rigel_wk_load_url(Pointer handle, String url);
        int rigel_wk_load_html(Pointer handle, String html);
        void rigel_wk_back(Pointer handle);
        void rigel_wk_forward(Pointer handle);
        void rigel_wk_reload(Pointer handle);
        void rigel_wk_reload_from_origin(Pointer handle);
        void rigel_wk_stop(Pointer handle);
        int rigel_wk_can_back(Pointer handle);
        int rigel_wk_can_forward(Pointer handle);
        void rigel_wk_set_frame(Pointer handle, double x, double y, double width, double height);
        void rigel_wk_set_transparent(Pointer handle, int transparent);
        int  rigel_wk_snapshot(Pointer handle, String path);
        int  rigel_wk_set_page_zoom(Pointer handle, double zoom);
        void rigel_wk_focus(Pointer handle);
        int rigel_wk_edit_action(Pointer handle, int action);
        void rigel_wk_execute(Pointer handle, String javascript);
        int rigel_wk_add_user_script(Pointer handle, String javascript, int mainFrameOnly);
        void rigel_wk_evaluate(Pointer handle, String javascript, long evalToken,
                               EvalCallback callback);
    }

    static final int LOAD_STARTED = 1;
    static final int LOAD_FINISHED = 2;
    static final int LOAD_FAILED = 3;
    static final int LOCATION = 4;
    static final int CLOSED = 5;
    static final int PROCESS_RESTARTED = 6;
    static final int NEW_WINDOW = 7;

    private static final AtomicLong NEXT_TOKEN = new AtomicLong(1);
    private static final AtomicLong NEXT_EVAL_TOKEN = new AtomicLong(1);
    private static final int EVAL_TIMEOUT_MS = 15_000;
    private static final Map<Long, MacWKWebView> VIEWS = new ConcurrentHashMap<>();
    private static final Map<Long, PendingEval> EVALUATIONS = new ConcurrentHashMap<>();

    private static final EventCallback EVENT_CALLBACK = (token, type, a, b) -> {
        MacWKWebView view = VIEWS.get(token);
        if (view != null) view.dispatch(() -> view.delegate.onNativeEvent(type, safe(a), safe(b)));
    };

    private static final ScriptCallback SCRIPT_CALLBACK = (token, name, payload) -> {
        MacWKWebView view = VIEWS.get(token);
        if (view != null) view.dispatch(() -> view.delegate.onScriptMessage(safe(name), safe(payload)));
    };

    private static final EvalCallback EVAL_CALLBACK = (token, value, error) -> {
        PendingEval pending = EVALUATIONS.remove(token);
        if (pending != null) pending.dispatch(safe(value), safe(error));
    };

    private static final PolicyCallback POLICY_CALLBACK = (token, url) -> {
        MacWKWebView view = VIEWS.get(token);
        return view == null || view.delegate.shouldAllowNavigation(safe(url)) ? 1 : 0;
    };

    private static volatile Bridge bridge;
    private static volatile Throwable loadFailure;
    private static volatile boolean normalizeAv1AllSites;
    private static volatile boolean contentBlockingEnabled;

    private final Delegate delegate;
    private final long token;
    private Pointer handle;

    MacWKWebView(Composite parent, int style, Delegate delegate) {
        super(parent, style | SWT.NO_BACKGROUND);
        this.delegate = delegate;
        this.token = NEXT_TOKEN.getAndIncrement();

        Bridge nativeBridge = bridge();
        if (nativeBridge.rigel_wk_supported() == 0)
            throw new IllegalStateException("WKWebView is unavailable on this macOS version");
        if (view == null || view.id == 0)
            throw new IllegalStateException("SWT did not provide a native NSView host");

        VIEWS.put(token, this);
        try {
            int proxyPort = Integer.getInteger("rigel.proxy.port", 8888);
            int proxyMode = Integer.getInteger("rigel.proxy.mode", RigelBrowser.PROXY_HTTP);
            int mode = (normalizeAv1AllSites ? 1 : 0) | (contentBlockingEnabled ? 2 : 0);
            handle = nativeBridge.rigel_wk_create(view.id, token, proxyPort, proxyMode,
                    mode, 0L,
                    EVENT_CALLBACK, SCRIPT_CALLBACK, POLICY_CALLBACK);
            if (handle == null)
                throw new IllegalStateException("Could not create WKWebView");
        } catch (Throwable failure) {
            VIEWS.remove(token);
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("Could not create WKWebView", failure);
        }

        addControlListener(new ControlAdapter() {
            @Override public void controlResized(ControlEvent e) { updateFrame(); }
        });
        addDisposeListener(e -> destroyNativeView());
        getDisplay().asyncExec(this::updateFrame);
    }

    static boolean isAvailable() {
        try {
            return bridge().rigel_wk_supported() != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void setProxyPort(int proxyPort) {
        setProxy(proxyPort, RigelBrowser.PROXY_HTTP);
    }

    static void setProxy(int proxyPort, int proxyMode) {
        // The first native view applies the stored proxy properties while it
        // builds its WKWebViewConfiguration. Calling into WKWebsiteDataStore
        // before any view exists only configures that same singleton twice and
        // can delay the first window significantly. Existing views still need
        // the live update when Settings changes at runtime.
        if (!VIEWS.isEmpty())
            bridge().rigel_wk_set_proxy(Math.max(0, proxyPort), proxyMode);
    }

    static void setAv1NormalizationAllSites(boolean enabled) {
        normalizeAv1AllSites = enabled;
        for (MacWKWebView view : List.copyOf(VIEWS.values())) {
            view.dispatch(() -> {
                if (view.usable()) {
                    bridge().rigel_wk_set_av1_normalization(view.handle, enabled ? 1 : 0);
                }
            });
        }
    }

    static void setContentBlocking(boolean enabled) {
        if (contentBlockingEnabled == enabled) return;
        contentBlockingEnabled = enabled;
        for (MacWKWebView view : List.copyOf(VIEWS.values())) {
            view.dispatch(() -> {
                if (view.usable()) {
                    bridge().rigel_wk_set_content_blocking(view.handle, enabled ? 1 : 0);
                }
            });
        }
    }

    static String failureReason() {
        Throwable failure = loadFailure;
        return failure == null ? "" : safe(failure.getMessage());
    }

    boolean loadUrl(String url) {
        return usable() && bridge().rigel_wk_load_url(handle, url) != 0;
    }

    boolean loadHtml(String html) {
        return usable() && bridge().rigel_wk_load_html(handle, html) != 0;
    }

    void back() { if (usable()) bridge().rigel_wk_back(handle); }
    void forward() { if (usable()) bridge().rigel_wk_forward(handle); }
    void reload() { if (usable()) bridge().rigel_wk_reload(handle); }
    void reloadFromOrigin() { if (usable()) bridge().rigel_wk_reload_from_origin(handle); }
    void stopLoading() { if (usable()) bridge().rigel_wk_stop(handle); }
    boolean simulateContentProcessTerminationForTesting() {
        if (!Boolean.getBoolean("rigel.tests.allowContentProcessTermination")) return false;
        return usable() && bridge().rigel_wk_test_simulate_content_process_termination(handle) != 0;
    }
    boolean canGoBack() { return usable() && bridge().rigel_wk_can_back(handle) != 0; }
    boolean canGoForward() { return usable() && bridge().rigel_wk_can_forward(handle) != 0; }

    boolean execute(String javascript) {
        if (!usable() || javascript == null) return false;
        bridge().rigel_wk_execute(handle, javascript);
        return true;
    }

    boolean addDocumentStartScript(String javascript) {
        return usable() && javascript != null && !javascript.isBlank()
                && bridge().rigel_wk_add_user_script(handle, javascript, 0) != 0;
    }

    /** See rigel_wk_set_transparent in the native bridge. */
    void setTransparent(boolean transparent) {
        if (usable()) bridge().rigel_wk_set_transparent(handle, transparent ? 1 : 0);
    }

    /** Writes a PNG snapshot (with alpha) of the current page to {@code path}. */
    boolean snapshot(String path) {
        return usable() && bridge().rigel_wk_snapshot(handle, path) != 0;
    }

    /**
     * Applies WebKit-native page zoom when available. Returns false when the
     * running WebKit lacks the API and the caller must fall back to CSS zoom.
     */
    boolean setPageZoom(double zoom) {
        return usable() && zoom >= 0.2 && zoom <= 5.0
                && bridge().rigel_wk_set_page_zoom(handle, zoom) != 0;
    }

    void evaluateAsync(String javascript, BiConsumer<String, String> completion) {
        if (!usable()) {
            completion.accept("", "WKWebView is disposed");
            return;
        }
        long evalToken = NEXT_EVAL_TOKEN.getAndIncrement();
        PendingEval pending = new PendingEval(token, getDisplay(), completion);
        EVALUATIONS.put(evalToken, pending);
        bridge().rigel_wk_evaluate(handle, javascript, evalToken, EVAL_CALLBACK);
        getDisplay().timerExec(EVAL_TIMEOUT_MS, () -> {
            if (EVALUATIONS.remove(evalToken, pending))
                pending.dispatch("", "JavaScript evaluation timed out");
        });
    }

    @Override public boolean setFocus() {
        // SWT must focus its host Composite first. The final first responder
        // then has to be WKWebView; doing these in the opposite order lets
        // SWT immediately steal focus back from webpage inputs and dialogs.
        boolean focused = super.setFocus();
        if (usable()) {
            bridge().rigel_wk_focus(handle);
            return true;
        }
        return focused;
    }

    boolean performEditAction(int action) {
        return usable() && bridge().rigel_wk_edit_action(handle, action) != 0;
    }

    void setDownloadOptions(String directory, boolean askBeforeDownload) {
        if (usable()) bridge().rigel_wk_set_download_options(handle,
                directory == null ? "" : directory, askBeforeDownload ? 1 : 0);
    }

    boolean cancelDownload(String identifier) {
        return usable() && identifier != null && !identifier.isBlank()
                && bridge().rigel_wk_cancel_download(handle, identifier) != 0;
    }

    private void updateFrame() {
        if (!usable()) return;
        Rectangle area = getClientArea();
        bridge().rigel_wk_set_frame(handle, 0, 0,
                Math.max(1, area.width), Math.max(1, area.height));
    }

    private boolean usable() {
        return !isDisposed() && handle != null;
    }

    private void destroyNativeView() {
        Pointer current = handle;
        handle = null;
        VIEWS.remove(token);
        for (Map.Entry<Long, PendingEval> entry : Map.copyOf(EVALUATIONS).entrySet()) {
            PendingEval pending = entry.getValue();
            if (pending.viewToken() == token && EVALUATIONS.remove(entry.getKey(), pending))
                pending.dispatch("", "WKWebView was closed");
        }
        if (current != null) {
            try { bridge().rigel_wk_destroy(current); }
            catch (Throwable ignored) {}
        }
    }

    /**
     * ALWAYS defers to the SWT event loop. These callbacks originate inside
     * WebKit delegate / script-message invocations on the main thread; running
     * Java listener code inline inside those callbacks let tab disposal and
     * other UI mutation happen while the native stack was still using the
     * objects being torn down (use-after-free crashes reported as hs_err logs,
     * especially when opening/closing tabs rapidly). Posting to the event
     * loop keeps every Java reaction off the WebKit call stack.
     */
    private void dispatch(Runnable action) {
        if (isDisposed()) return;
        Display display = getDisplay();
        display.asyncExec(() -> { if (!isDisposed()) action.run(); });
    }

    private static Bridge bridge() {
        Bridge current = bridge;
        if (current != null) return current;
        synchronized (MacWKWebView.class) {
            if (bridge != null) return bridge;
            try {
                // No dylib: all native work is done through FFM in FFMWKWebView.
                bridge = new FfmBridge();
                return bridge;
            } catch (Throwable failure) {
                loadFailure = failure;
                throw new IllegalStateException("Could not initialize the WKWebView bridge", failure);
            }
        }
    }

    /** Adapter from the historic JNA Bridge interface to the FFM host. */
    private static final class FfmBridge implements Bridge {
        private long handle(long parentView, long token, int port, int mode, int flags,
                            EventCallback ec, ScriptCallback sc, PolicyCallback pc) {
            return ui.wk.FFMWKWebView.create(parentView, token, port, mode, flags, 0L,
                    new ui.wk.FFMWKWebView.Events() {
                        @Override public void event(long t, int type, String a, String b) {
                            if (ec != null) ec.invoke(t, type, a, b);
                        }
                        @Override public void script(long t, String name, String payload) {
                            if (sc != null) sc.invoke(t, name, payload);
                        }
                        @Override public boolean policy(long t, String url) {
                            return pc == null || pc.invoke(t, url) != 0;
                        }
                        @Override public void evaluated(long token, String value, String error) {
                            PendingEval pending = EVALUATIONS.remove(token);
                            if (pending != null) pending.dispatch(value, error);
                        }
                    });
        }

        @Override public int rigel_wk_supported() { return ui.wk.FFMWKWebView.supported(); }
        @Override public long rigel_webkit_memory_bytes() { return ui.wk.FFMWKWebView.webkitMemoryBytes(); }
        @Override public int rigel_wk_cookies_save(String path) { return ui.wk.FFMWKWebView.cookiesSave(path); }
        @Override public int rigel_wk_cookies_restore(String path) { return ui.wk.FFMWKWebView.cookiesRestore(path); }
        @Override public void rigel_wk_disable_resource_load_statistics() {
            ui.wk.FFMWKWebView.disableResourceLoadStatistics();
        }
        @Override public void rigel_wk_set_proxy_port(int p) { ui.wk.FFMWKWebView.setProxyPort(p); }
        @Override public void rigel_wk_set_proxy(int p, int m) { ui.wk.FFMWKWebView.setProxy(p, m); }
        @Override public void rigel_wk_set_av1_normalization(Pointer h, int e) {
            ui.wk.FFMWKWebView.setAv1Normalization(ptr(h), e);
        }
        @Override public void rigel_wk_set_content_blocking(Pointer h, int e) {
            ui.wk.FFMWKWebView.setContentBlocking(ptr(h), e);
        }
        @Override public void rigel_wk_set_download_options(Pointer h, String d, int a) {
            ui.wk.FFMWKWebView.setDownloadOptions(ptr(h), d, a != 0);
        }
        @Override public int rigel_wk_cancel_download(Pointer h, String id) {
            return ui.wk.FFMWKWebView.cancelDownload(ptr(h), id);
        }
        @Override public Pointer rigel_wk_create(long parentView, long token, int port, int mode,
                                                  int unusedMode, long unusedSessionId,
                                                  EventCallback ec, ScriptCallback sc, PolicyCallback pc) {
            long h = handle(parentView, token, port, mode, unusedMode, ec, sc, pc);
            return h == 0 ? null : new Pointer(h);
        }
        @Override public void rigel_wk_destroy(Pointer h) { ui.wk.FFMWKWebView.destroy(ptr(h)); }
        @Override public int rigel_wk_test_simulate_content_process_termination(Pointer h) { return 0; }
        @Override public int rigel_wk_load_url(Pointer h, String url) {
            return ui.wk.FFMWKWebView.loadUrl(ptr(h), url);
        }
        @Override public int rigel_wk_load_html(Pointer h, String html) {
            return ui.wk.FFMWKWebView.loadHtml(ptr(h), html);
        }
        @Override public void rigel_wk_back(Pointer h) { ui.wk.FFMWKWebView.back(ptr(h)); }
        @Override public void rigel_wk_forward(Pointer h) { ui.wk.FFMWKWebView.forward(ptr(h)); }
        @Override public void rigel_wk_reload(Pointer h) { ui.wk.FFMWKWebView.reload(ptr(h)); }
        @Override public void rigel_wk_reload_from_origin(Pointer h) {
            ui.wk.FFMWKWebView.reloadFromOrigin(ptr(h));
        }
        @Override public void rigel_wk_stop(Pointer h) { ui.wk.FFMWKWebView.stop(ptr(h)); }
        @Override public int rigel_wk_can_back(Pointer h) { return ui.wk.FFMWKWebView.canBack(ptr(h)); }
        @Override public int rigel_wk_can_forward(Pointer h) { return ui.wk.FFMWKWebView.canForward(ptr(h)); }
        @Override public void rigel_wk_set_frame(Pointer h, double x, double y, double w, double ht) {
            ui.wk.FFMWKWebView.setFrame(ptr(h), x, y, w, ht);
        }
        @Override public void rigel_wk_set_transparent(Pointer h, int t) {
            ui.wk.FFMWKWebView.setTransparent(ptr(h), t);
        }
        @Override public int rigel_wk_snapshot(Pointer h, String path) {
            return ui.wk.FFMWKWebView.snapshot(ptr(h), path);
        }
        @Override public int rigel_wk_set_page_zoom(Pointer h, double z) {
            return ui.wk.FFMWKWebView.setPageZoom(ptr(h), z);
        }
        @Override public void rigel_wk_focus(Pointer h) { ui.wk.FFMWKWebView.focus(ptr(h)); }
        @Override public int rigel_wk_edit_action(Pointer h, int a) {
            return ui.wk.FFMWKWebView.editAction(ptr(h), a);
        }
        @Override public void rigel_wk_execute(Pointer h, String js) {
            ui.wk.FFMWKWebView.execute(ptr(h), js);
        }
        @Override public int rigel_wk_add_user_script(Pointer h, String js, int m) {
            return ui.wk.FFMWKWebView.addUserScript(ptr(h), js, m);
        }
        @Override public void rigel_wk_evaluate(Pointer h, String js, long token, EvalCallback cb) {
            ui.wk.FFMWKWebView.evaluate(ptr(h), js, token);
        }
        private static long ptr(Pointer p) { return p == null ? 0 : com.sun.jna.Pointer.nativeValue(p); }
    }

    /**
     * Resident bytes across this process's WebContent/Networking/GPU helper
     * processes, measured in-process via libproc (no /bin/ps spawn). Returns
     * -1 when unavailable (e.g. the bridge failed to load) so callers can
     * fall back to the ps-based measurement.
     */
    static long webKitMemoryBytes() {
        try {
            return bridge().rigel_webkit_memory_bytes();
        } catch (Throwable unavailable) {
            return -1;
        }
    }

    /** Disable WebKit's resource-load-statistics sweep on the shared data
     *  store. With it enabled, the store DELETES website data — cookies
     *  included — for every domain without recorded user interaction, which
     *  after a snapshot replay is all of them; observed live: 157 replayed
     *  sign-in cookies present at +0s/+4s were erased by +17s. Call before
     *  {@link #restoreCookies}. */
    static void disableStorageSweeps() {
        try {
            bridge().rigel_wk_disable_resource_load_statistics();
        } catch (Throwable unavailable) {
            // Older dylib without the export: replay still runs, unprotected.
        }
    }

    /**
     * Snapshot the shared WKHTTPCookieStore to a plist at the given absolute
     * path. WebKit only flushes the HTTPStorages jar when the network helper
     * exits cleanly, and this host exits the JVM the moment the last window
     * closes, so without an explicit snapshot site sessions are never written
     * to disk. Must run on the SWT/main thread (the bridge pumps the main
     * runloop for the async store round-trip).
     */
    static boolean saveCookies(Path path) {
        try {
            return bridge().rigel_wk_cookies_save(path.toAbsolutePath().toString()) == 1;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /** Replay a snapshot taken by {@link #saveCookies} into the shared store.
     *  Returns the number of entries replayed (0 when the file is absent or
     *  empty), so callers can fall back to another snapshot copy. */
    static int restoreCookies(Path path) {
        try {
            return bridge().rigel_wk_cookies_restore(path.toAbsolutePath().toString());
        } catch (Throwable unavailable) {
            return 0;
        }
    }

    /**
     * Absolute path of the bundled native bridge dylib. MacVibrancy loads the
     * same library with its own interface to reach the window-level glass
     * entry points; JNA/dyld coalesce the duplicate handle.
     */
    static String bridgeLibraryPath() {
        try {
            return extractBridge().toAbsolutePath().toString();
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not load the WKWebView bridge", failure);
        }
    }

    private static Path extractBridge() throws Exception {
        String resource = "/native/libRigelWKWebView.dylib";
        byte[] bytes;
        try (InputStream input = MacWKWebView.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing " + resource);
            bytes = input.readAllBytes();
        }
        String fullDigest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        String digest = fullDigest.substring(0, 16);
        String source = String.valueOf(MacWKWebView.class.getProtectionDomain().getCodeSource().getLocation());
        String distId = Integer.toHexString(source.hashCode());
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), "rigel-native", distId, digest);
        Path output = directory.resolve("libRigelWKWebView.dylib");
        Files.createDirectories(directory);
        boolean validExisting = Files.exists(output) && Files.size(output) == bytes.length
                && fullDigest.equals(HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(output))));
        if (!validExisting) {
            Path temporary = Files.createTempFile(directory, "libRigelWKWebView.", ".tmp");
            try {
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        output.toFile().setReadable(true, true);
        output.toFile().setExecutable(true, true);
        return output;
    }

    private static String safe(String value) { return value == null ? "" : value; }

    @Override protected void checkSubclass() {}

    private record PendingEval(long viewToken, Display display,
                               BiConsumer<String, String> completion) {
        void dispatch(String value, String error) {
            if (display == null || display.isDisposed()) return;
            Runnable task = () -> {
                if (!display.isDisposed()) completion.accept(value, error);
            };
            // Same re-entrancy rule as MacWKWebView.dispatch(): never run
            // Java completions inside the native evaluation callback.
            display.asyncExec(task);
        }
    }
}
