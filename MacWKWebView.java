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
        void rigel_wk_focus(Pointer handle);
        int rigel_wk_edit_action(Pointer handle, int action);
        void rigel_wk_execute(Pointer handle, String javascript);
        void rigel_wk_evaluate(Pointer handle, String javascript, long evalToken,
                               EvalCallback callback);
    }

    static final int LOAD_STARTED = 1;
    static final int LOAD_FINISHED = 2;
    static final int LOAD_FAILED = 3;
    static final int LOCATION = 4;
    static final int CLOSED = 5;
    static final int PROCESS_RESTARTED = 6;

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
        int proxyPort = Integer.getInteger("rigel.proxy.port", 8888);
        int proxyMode = Integer.getInteger("rigel.proxy.mode", RigelBrowser.PROXY_HTTP);
        int mode = (normalizeAv1AllSites ? 1 : 0) | (contentBlockingEnabled ? 2 : 0);
        handle = nativeBridge.rigel_wk_create(view.id, token, proxyPort, proxyMode,
                mode, 0L,
                EVENT_CALLBACK, SCRIPT_CALLBACK, POLICY_CALLBACK);
        if (handle == null) {
            VIEWS.remove(token);
            throw new IllegalStateException("Could not create WKWebView");
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
    boolean canGoBack() { return usable() && bridge().rigel_wk_can_back(handle) != 0; }
    boolean canGoForward() { return usable() && bridge().rigel_wk_can_forward(handle) != 0; }

    boolean execute(String javascript) {
        if (!usable() || javascript == null) return false;
        bridge().rigel_wk_execute(handle, javascript);
        return true;
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
        if (usable()) bridge().rigel_wk_focus(handle);
        return super.setFocus();
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

    private void dispatch(Runnable action) {
        if (isDisposed()) return;
        Display display = getDisplay();
        if (Display.getCurrent() == display) action.run();
        else display.asyncExec(() -> { if (!isDisposed()) action.run(); });
    }

    private static Bridge bridge() {
        Bridge current = bridge;
        if (current != null) return current;
        synchronized (MacWKWebView.class) {
            if (bridge != null) return bridge;
            try {
                Path library = extractBridge();
                bridge = Native.load(library.toAbsolutePath().toString(), Bridge.class);
                return bridge;
            } catch (Throwable failure) {
                loadFailure = failure;
                throw new IllegalStateException("Could not load the WKWebView bridge", failure);
            }
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
            Path temporary = directory.resolve("libRigelWKWebView.dylib.tmp");
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
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
            if (Display.getCurrent() == display) task.run();
            else display.asyncExec(task);
        }
    }
}
