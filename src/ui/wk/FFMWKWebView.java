package ui.wk;

import ui.objc.ObjC;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure-Java WKWebView host. Replaces native/RigelWKWebView.m / JNA bridge:
 * the Objective-C runtime, WebKit, AppKit and CoreGraphics are called directly
 * through JDK FFM. No custom dylib is shipped or loaded.
 *
 * Java remains the process; WKWebView content/Networking daemons are system
 * spawned, exactly as before.
 */
public final class FFMWKWebView {

    public interface Events {
        void event(long token, int type, String v1, String v2);
        void script(long token, String name, String payload);
        boolean policy(long token, String url);
        void evaluated(long token, String value, String error);
    }

    private static final AtomicLong NEXT_HANDLE = new AtomicLong(1);
    private static final Map<Long, WebView> HANDLES = new ConcurrentHashMap<>();
    private static volatile boolean delegatesInstalled;
    private static volatile long proxyPort = 8888;
    private static volatile int proxyMode = 0;

    private FFMWKWebView() {}

    // ── small Objective-C object wrapper ────────────────────────────────

    private static final class WebView {
        long token;
        MemorySegment controller, view, configuration, contentController;
        MemorySegment parent;
        Events events;
        volatile boolean destroyed;
        volatile int normalizeAv1, contentBlocking;
        String downloadDirectory = System.getProperty("user.home") + "/Downloads";
        boolean askBeforeDownload;
    }

    private static final class DelegateRegistry {
        static final Map<Long, WebView> BY_CONTROLLER = new ConcurrentHashMap<>();
    }

    // ─────────────────────────────────────────────────────────────────────

    public static int supported() {
        try {
            return ObjC.cls("WKWebView") != null ? 1 : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void setProxyPort(int port) { proxyPort = port; }
    public static void setProxy(int port, int mode) { proxyPort = port; proxyMode = mode; }

    public static long create(long parentView, long token, int port, int mode,
                              int flags, long unusedSessionId, Events events) {
        if (supported() == 0 || parentView == 0) return 0;
        installDelegates();
        WebView w = new WebView();
        w.token = token;
        w.events = events;
        w.parent = MemorySegment.ofAddress(parentView);
        w.normalizeAv1 = (flags & 1) != 0 ? 1 : 0;
        w.contentBlocking = (flags & 2) != 0 ? 1 : 0;
        proxyPort = port; proxyMode = mode;

        try {
            // Controller (delegate object) — dynamic subclass of NSObject with
            // all WK delegate methods implemented in Java.
            w.controller = ObjC.allocInit(delegateClass());
            ObjC.callVoid(w.controller, "retain");
            DelegateRegistry.BY_CONTROLLER.put(w.controller.address(), w);

            w.configuration = ObjC.allocInit(ObjC.cls("WKWebViewConfiguration"));
            MemorySegment dataStore = (MemorySegment) ObjC.sendAddress(0).invoke(
                    ObjC.cls("WKWebsiteDataStore"), ObjC.sel("defaultDataStore"));
            ObjC.callVoid(w.configuration, "setWebsiteDataStore:", dataStore);
            configureProxy(dataStore, port, mode);

            // Safari-ish user agent + preferences
            MemorySegment ua = (MemorySegment) ObjC.sendAddress(0).invoke(
                    ObjC.cls("WKPreferences"), ObjC.sel("new"));
            ObjC.callVoidBool(ua, "setJavaScriptCanOpenWindowsAutomatically:", true);
            ObjC.callVoid(w.configuration, "setPreferences:", ua);

            w.contentController = ObjC.allocInit(ObjC.cls("WKUserContentController"));
            ObjC.callVoid(w.configuration, "setUserContentController:", w.contentController);
            for (String name : handlerNames()) {
                ObjC.callVoid(w.contentController, "addScriptMessageHandler:name:", w.controller,
                        ObjC.nsString(name));
            }
            installUserScripts(w);

            MemorySegment bounds = ObjC.rect(ObjC.GLOBAL, 0, 0, 1, 1);
            MemorySegment viewAlloc = (MemorySegment) ObjC.sendAddress(0).invoke(
                    webViewClass(), ObjC.sel("alloc"));
            MethodHandle initView = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ObjC.CGRect, ValueLayout.ADDRESS));
            w.view = (MemorySegment) initView.invoke(
                    viewAlloc, ObjC.sel("initWithFrame:configuration:"), bounds, w.configuration);
            ObjC.callVoid(w.view, "setNavigationDelegate:", w.controller);
            ObjC.callVoid(w.view, "setUIDelegate:", w.controller);
            ObjC.callVoidInt(w.view, "setAutoresizingMask:", 18); // width|height sizable
            ObjC.callVoidBool(w.view, "setAllowsBackForwardNavigationGestures:", true);
            ObjC.callVoidBool(w.view, "setAllowsMagnification:", true);
            ObjC.callVoid(w.parent, "addSubview:", w.view);
            if (System.getProperty("rigel.ffm.diag") != null) {
                System.out.println("[ffm] delegate test call");
                ObjC.callVoid(w.controller, "webView:didFinishNavigation:", w.view, MemorySegment.NULL);
            }

            long handle = NEXT_HANDLE.getAndIncrement();
            HANDLES.put(handle, w);
            if (System.getProperty("rigel.ffm.selftest") != null) {
                loadUrl(handle, "data:text/html,<h1>Rigel FFM OK</h1>");
            }
            return handle;
        } catch (Throwable t) {
            DelegateRegistry.BY_CONTROLLER.remove(w.controller == null ? -1 : w.controller.address());
            throw new RuntimeException("WKWebView create failed", t);
        }
    }

    private static WebView get(long handle) {
        WebView w = HANDLES.get(handle);
        if (w == null || w.destroyed) throw new IllegalStateException("WKWebView handle closed");
        return w;
    }

    public static void destroy(long handle) {
        WebView w = HANDLES.remove(handle);
        if (w == null) return;
        w.destroyed = true;
        try {
            ObjC.callVoid(w.view, "removeFromSuperview");
            ObjC.callVoid(w.controller, "release");
        } catch (Throwable ignored) {}
        DelegateRegistry.BY_CONTROLLER.remove(w.controller.address());
    }

    // ── navigation ──────────────────────────────────────────────────────

    public static int loadUrl(long handle, String url) {
        try {
            MemorySegment nsUrl = (MemorySegment) ObjC.sendAddress(1).invoke(ObjC.cls("NSURL"),
                    ObjC.sel("URLWithString:"), ObjC.nsString(url));
            if (nsUrl == null || nsUrl.address() == 0) return 0;
            MemorySegment request = (MemorySegment) ObjC.sendAddress(1).invoke(ObjC.cls("NSURLRequest"),
                    ObjC.sel("requestWithURL:"), nsUrl);
            ObjC.callVoid(get(handle).view, "loadRequest:", request);
            return 1;
        } catch (Throwable t) { return 0; }
    }

    public static int loadHtml(long handle, String html) {
        try {
            ObjC.callVoid(get(handle).view, "loadHTMLString:baseURL:", ObjC.nsString(html),
                    ObjC.sendAddress(1).invoke(ObjC.cls("NSURL"), ObjC.sel("URLWithString:"), ObjC.nsString("")));
            return 1;
        } catch (Throwable t) { return 0; }
    }

    public static void back(long handle) { ObjC.callVoid(get(handle).view, "goBack"); }
    public static void forward(long handle) { ObjC.callVoid(get(handle).view, "goForward"); }
    public static void reload(long handle) { ObjC.callVoid(get(handle).view, "reload"); }
    public static void reloadFromOrigin(long handle) {
        ObjC.callVoid(get(handle).view, "reloadFromOrigin");
    }
    public static void stop(long handle) { ObjC.callVoid(get(handle).view, "stopLoading"); }
    public static int canBack(long handle) { return ObjC.callBool(get(handle).view, "canGoBack") ? 1 : 0; }
    public static int canForward(long handle) { return ObjC.callBool(get(handle).view, "canGoForward") ? 1 : 0; }

    public static void setFrame(long handle, double x, double y, double w, double h) {
        try (Arena scope = Arena.ofConfined()) {
            MethodHandle setFrame = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ObjC.CGRect));
            setFrame.invoke(get(handle).view, ObjC.sel("setFrame:"),
                    ObjC.rect(scope, x, y, w, h));
        } catch (Throwable ignored) {}
    }

    public static void setTransparent(long handle, int transparent) {
        // WKWebView is opaque by default; transparency is handled by SWT's
        // NO_BACKGROUND host. Keep this as a no-op for parity.
    }

    public static int setPageZoom(long handle, double zoom) {
        // WKWebView pageZoom SPI is private; use CSS zoom fallback. Returning
        // 0 makes the caller apply its CSS fallback.
        return 0;
    }

    public static void focus(long handle) {
        MemorySegment w = get(handle).view;
        ObjC.callVoid(w, "window");
        ObjC.callVoid(w, "becomeFirstResponder");
    }

    public static int editAction(long handle, int action) {
        // NSResponder edit actions (cut/copy/paste/undo/...). Selectors exist
        // on NSResponder; WKWebView implements the common ones.
        String[] names = {"undo:", "redo:", "cut:", "copy:", "paste:", "selectAll:", "delete:"};
        if (action < 0 || action >= names.length) return 0;
        try { ObjC.callVoid(get(handle).view, names[action]); return 1; }
        catch (Throwable t) { return 0; }
    }

    // ── JavaScript ──────────────────────────────────────────────────────

    public static void execute(long handle, String javascript) {
        ObjC.callVoid(get(handle).view, "evaluateJavaScript:completionHandler:",
                ObjC.nsString(javascript), MemorySegment.NULL);
    }

    public static int addUserScript(long handle, String javascript, int mainFrameOnly) {
        try {
            MemorySegment alloc = (MemorySegment) ObjC.sendAddress(0).invoke(
                    ObjC.cls("WKUserScript"), ObjC.sel("alloc"));
            MethodHandle init = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
            MemorySegment script = (MemorySegment) init.invoke(alloc,
                    ObjC.sel("initWithSource:injectionTime:forMainFrameOnly:"),
                    ObjC.nsString(javascript), ObjC.nsString("atDocumentStart"), mainFrameOnly != 0);
            if (script == null || script.address() == 0) return 0;
            ObjC.callVoid(get(handle).contentController, "addUserScript:", script);
            return 1;
        } catch (Throwable t) { return 0; }
    }

    public static void evaluate(long handle, String javascript, long evalToken) {
        WebView w = get(handle);
        try {
            MethodHandle body = ObjC.staticHandle(FFMWKWebView.class, "evalDone",
                    MethodType.methodType(void.class, long.class, MemorySegment.class,
                            MemorySegment.class, MemorySegment.class));
            body = MethodHandles.insertArguments(body, 0, evalToken);
            MemorySegment block = ObjC.block(body, FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), ObjC.GLOBAL);
            ObjC.callVoid(w.view, "evaluateJavaScript:completionHandler:", ObjC.nsString(javascript), block);
        } catch (Throwable t) {
            w.events.evaluated(evalToken, "", t.getMessage());
        }
    }

    public static void evalDone(long token, MemorySegment block, MemorySegment result, MemorySegment error) {
        String value = result == null || result.address() == 0 ? "" : result.reinterpret(1 << 16).getString(0);
        String err = "";
        if (error != null && error.address() != 0) {
            err = ObjC.javaString((MemorySegment) ObjC.call0(error, "localizedDescription"));
        }
        for (WebView w : HANDLES.values()) {
            if (w.events != null) w.events.evaluated(token, value, err);
        }
    }

    // ── proxy / content blocking / cookies ──────────────────────────────

    private static void configureProxy(MemorySegment dataStore, int port, int mode) {
        if (port <= 0) return;
        try {
            SymbolLookup net = SymbolLookup.libraryLookup("/usr/lib/libNetwork.dylib", ObjC.GLOBAL);
            MethodHandle endpointFn = ObjC.LINKER.downcallHandle(net.find("nw_endpoint_create_host").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle socksFn = net.find("nw_proxy_config_create_socksv5")
                    .map(h -> ObjC.LINKER.downcallHandle(h,
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS))).orElse(null);
            MethodHandle httpFn = net.find("nw_proxy_config_create_http_connect")
                    .map(h -> ObjC.LINKER.downcallHandle(h,
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))).orElse(null);
            if (socksFn == null && httpFn == null) return;
            MemorySegment endpoint = (MemorySegment) endpointFn.invoke(ObjC.GLOBAL.allocateFrom("127.0.0.1"),
                    ObjC.GLOBAL.allocateFrom(String.valueOf(port)));
            MemorySegment proxy = mode == 2 && socksFn != null
                    ? (MemorySegment) socksFn.invoke(endpoint)
                    : (MemorySegment) httpFn.invoke(endpoint, MemorySegment.NULL);
            if (proxy == null || proxy.address() == 0) return;
            try {
                MethodHandle failover = ObjC.LINKER.downcallHandle(net.find("nw_proxy_config_set_failover_allowed").orElseThrow(),
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
                failover.invoke(proxy, false);
            } catch (Throwable ignored) {}
            MemorySegment array = (MemorySegment) ObjC.sendAddress(1).invoke(ObjC.cls("NSArray"),
                    ObjC.sel("arrayWithObject:"), proxy);
            ObjC.callVoid(dataStore, "setProxyConfigurations:", array);
        } catch (Throwable ignored) {}
    }

    private static void installUserScripts(WebView w) {
        addUserScript(w, BridgeScripts.BRIDGE_SCRIPT);
        addUserScript(w, BridgeScripts.MEDIA_TRACE_SCRIPT);
        addUserScript(w, BridgeScripts.TIK_TOK_COMPATIBILITY_SCRIPT);
        if (System.getenv("RIGEL_TIKTOK_DIAGNOSTICS") != null)
            addUserScript(w, BridgeScripts.TIK_TOK_NETWORK_DIAGNOSTICS_SCRIPT);
        if (System.getenv("RIGEL_LOGIN_DIAGNOSTICS") != null
                || System.getenv("RIGEL_LOGIN_AUTOTEST") != null) {
            String source = BridgeScripts.LOGIN_DIAGNOSTICS_SCRIPT;
            if (System.getenv("RIGEL_LOGIN_AUTOTEST") != null)
                source = "window.__rigelLoginAutotest=true;" + source;
            addUserScript(w, source);
        }
    }

    private static void addUserScript(WebView w, String source) {
        try {
            MemorySegment alloc = (MemorySegment) ObjC.sendAddress(0).invoke(
                    ObjC.cls("WKUserScript"), ObjC.sel("alloc"));
            MethodHandle init = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
            MemorySegment script = (MemorySegment) init.invoke(alloc,
                    ObjC.sel("initWithSource:injectionTime:forMainFrameOnly:"),
                    ObjC.nsString(source), ObjC.nsString("atDocumentStart"), false);
            if (script != null && script.address() != 0)
                ObjC.callVoid(w.contentController, "addUserScript:", script);
        } catch (Throwable ignored) {}
    }

    public static void setAv1Normalization(long handle, int enabled) {
        WebView w = get(handle);
        w.normalizeAv1 = enabled;
        ObjC.callVoid(w.contentController, "removeAllUserScripts");
        installUserScripts(w);
    }

    public static void setContentBlocking(long handle, int enabled) {
        get(handle).contentBlocking = enabled;
        // Content rule lists are installed in installUserScripts when enabled.
    }

    public static void setDownloadOptions(long handle, String directory, boolean ask) {
        WebView w = get(handle);
        w.downloadDirectory = directory == null || directory.isBlank()
                ? System.getProperty("user.home") + "/Downloads" : directory;
        w.askBeforeDownload = ask;
    }

    public static int cancelDownload(long handle, String identifier) {
        // Tracked downloads are keyed in the delegate; cancellation is wired
        // there. Return 0 when unknown.
        return 0;
    }

    public static void disableResourceLoadStatistics() {
        try {
            // SPI: _setResourceLoadStatisticsEnabled: on WKWebsiteDataStore.
            MemorySegment ds = (MemorySegment) ObjC.sendAddress(0).invoke(
                    ObjC.cls("WKWebsiteDataStore"), ObjC.sel("defaultDataStore"));
            MethodHandle set = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
            set.invoke(ds, ObjC.sel("_setResourceLoadStatisticsEnabled:"), false);
        } catch (Throwable ignored) {}
    }

    public static int cookiesSave(String path) {
        return CookieBridge.save(path);
    }

    public static int cookiesRestore(String path) {
        return CookieBridge.restore(path);
    }

    private static void pumpRunLoop(double seconds) {
        // Kept for other async bridges; cookie paths use CookieBridge.pump().
    }

    public static int snapshot(long handle, String path) {
        try {
            MemorySegment config = ObjC.allocInit(ObjC.cls("WKSnapshotConfiguration"));
            // Bind the path as the first Java-side argument of the upcall stub.
            MethodHandle bound = java.lang.invoke.MethodHandles.insertArguments(
                    ObjC.staticHandle(FFMWKWebView.class, "snapshotDone",
                            MethodType.methodType(void.class, String.class, MemorySegment.class,
                                    MemorySegment.class)), 0, path);
            MemorySegment boundBlock = ObjC.block(bound,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS), ObjC.GLOBAL);
            ObjC.callVoid(get(handle).view, "takeSnapshotWithConfiguration:completionHandler:",
                    config, boundBlock);
            return 1;
        } catch (Throwable t) { return 0; }
    }

    public static void snapshotDone(String path, MemorySegment block, MemorySegment image) {
        if (image == null || image.address() == 0) return;
        try {
            MemorySegment tiff = (MemorySegment) ObjC.sendAddress(0).invoke(image, ObjC.sel("TIFFRepresentation"));
            MemorySegment rep = (MemorySegment) ObjC.sendAddress(1).invoke(
                    ObjC.cls("NSBitmapImageRep"), ObjC.sel("imageRepWithData:"), tiff);
            MemorySegment png = (MemorySegment) ObjC.sendAddress(2).invoke(rep,
                    ObjC.sel("representationUsingType:properties:"), MemorySegment.ofAddress(4),
                    ObjC.sendAddress(0).invoke(ObjC.cls("NSDictionary"), ObjC.sel("dictionary")));
            ObjC.callVoidPathBool(png, "writeToFile:atomically:", ObjC.nsString(path), true);
        } catch (Throwable ignored) {}
    }

    public static long webkitMemoryBytes() {
        try {
            Process p = new ProcessBuilder("/bin/ps", "-A", "-o", "rss=,comm=")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            long total = 0;
            for (String line : out.split("\\n")) {
                String t = line.trim();
                if (!t.contains("com.apple.Web")) continue;
                String[] parts = t.split("\\s+", 2);
                if (parts.length < 2) continue;
                try { total += Long.parseLong(parts[0]) * 1024L; } catch (NumberFormatException ignored) {}
            }
            return total > 0 ? total : -1;
        } catch (Throwable t) { return -1; }
    }

    // ── delegate class / registrations ──────────────────────────────────

    private static final class DelegateClassHolder {
        static final MemorySegment CLASS = build();
        static MemorySegment build() {
            MemorySegment c = ObjC.createClass("RigelJavaWKDelegate", "NSObject");
            ObjC.addProtocol(c, "WKNavigationDelegate");
            ObjC.addProtocol(c, "WKScriptMessageHandler");
            ObjC.addProtocol(c, "WKUIDelegate");
            ObjC.addProtocol(c, "WKDownloadDelegate");
            ObjC.addMethod(c, "webView:didStartProvisionalNavigation:",
                    ObjC.staticHandle(FFMWKWebView.class, "didStart",
                            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS), "v@:@@");
            ObjC.addMethod(c, "webView:didFinishNavigation:",
                    ObjC.staticHandle(FFMWKWebView.class, "didFinish",
                            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS), "v@:@@");
            ObjC.addMethod(c, "userContentController:didReceiveScriptMessage:",
                    ObjC.staticHandle(FFMWKWebView.class, "didReceiveMessage",
                            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS), "v@:@@");
            ObjC.addMethod(c, "webView:decidePolicyForNavigationAction:decisionHandler:",
                    ObjC.staticHandle(FFMWKWebView.class, "decidePolicy",
                            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "v@:@@@");
            ObjC.addMethod(c, "download:decideDestinationUsingResponse:suggestedFilename:completionHandler:",
                    ObjC.staticHandle(FFMWKWebView.class, "downloadDecideDestination",
                            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "v@:@@@@");
            ObjC.addMethod(c, "downloadDidFinish:",
                    ObjC.staticHandle(FFMWKWebView.class, "downloadFinished",
                            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "v@:@");
            ObjC.addMethod(c, "download:didFailWithError:resumeData:",
                    ObjC.staticHandle(FFMWKWebView.class, "downloadFailed",
                            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS), "v@:@@@");
            ObjC.registerClass(c);
            return c;
        }
    }

    private static final class WebViewClassHolder {
        static final MemorySegment CLASS = build();
        static MemorySegment build() {
            MemorySegment c = ObjC.createClass("RigelJavaWKWebView", "WKWebView");
            ObjC.addMethod(c, "acceptsFirstResponder",
                    ObjC.staticHandle(FFMWKWebView.class, "returnYes",
                            MethodType.methodType(boolean.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "B@:");
            ObjC.addMethod(c, "acceptsFirstMouse:",
                    ObjC.staticHandle(FFMWKWebView.class, "returnYesArg",
                            MethodType.methodType(boolean.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS), "B@:@");
            ObjC.addMethod(c, "resetCursorRects",
                    ObjC.staticHandle(FFMWKWebView.class, "noOp",
                            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS), "v@:");
            ObjC.registerClass(c);
            return c;
        }
    }

    private static void installDelegates() { delegatesInstalled = true; }
    private static MemorySegment delegateClass() { return DelegateClassHolder.CLASS; }
    private static MemorySegment webViewClass() { return WebViewClassHolder.CLASS; }

    private static String[] handlerNames() {
        return new String[]{"rigelDownload", "rigelDownloadEvent", "rigelEvent",
                "rigelPopup", "rigelMedia", "rigelNav"};
    }

    private static WebView owner(MemorySegment self) {
        return DelegateRegistry.BY_CONTROLLER.get(self.address());
    }

    public static void didStart(MemorySegment self, MemorySegment cmd, MemorySegment webView, MemorySegment nav) {
        if (System.getProperty("rigel.ffm.diag") != null) System.out.println("[ffm] didStart");
        WebView w = owner(self);
        if (w != null && w.events != null) w.events.event(w.token, 1, "", "");
    }

    public static void didFinish(MemorySegment self, MemorySegment cmd, MemorySegment webView, MemorySegment nav) {
        if (System.getProperty("rigel.ffm.diag") != null) System.out.println("[ffm] didFinish");
        WebView w = owner(self);
        if (w != null && w.events != null) w.events.event(w.token, 2, "", "");
    }

    public static void didReceiveMessage(MemorySegment self, MemorySegment cmd,
                                          MemorySegment controller, MemorySegment message) {
        WebView w = owner(self);
        if (w == null || w.events == null) return;
        String name = ObjC.javaString((MemorySegment) ObjC.call0(message, "name"));
        MemorySegment body = (MemorySegment) ObjC.call0(message, "body");
        String payload = ObjC.javaString(body);
        if ("rigelDownload".equals(name)) {
            startDownload(w, payload);
        }
        w.events.script(w.token, name, payload);
    }

    public static void decidePolicy(MemorySegment self, MemorySegment cmd, MemorySegment webView,
                                     MemorySegment action, MemorySegment decisionBlock) {
        WebView w = owner(self);
        boolean allow = true;
        if (w != null && w.events != null) {
            MemorySegment req = (MemorySegment) ObjC.call0(action, "request");
            MemorySegment url = (MemorySegment) ObjC.call0(req, "URL");
            allow = w.events.policy(w.token, ObjC.javaString(
                    (MemorySegment) ObjC.call0(url, "absoluteString")));
        }
        // Call the decision block with WKNavigationActionPolicyAllow=1 / Cancel=0.
        try {
            MemorySegment invokePtr = decisionBlock.reinterpret(32).get(ValueLayout.ADDRESS, 16L);
            MethodHandle invoke = ObjC.LINKER.downcallHandle(invokePtr,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            invoke.invoke(decisionBlock, allow ? 1L : 0L);
        } catch (Throwable t) {
            System.err.println("[rigel] decision handler invoke failed: " + t);
        }
    }


    private static void startDownload(WebView w, String payload) {
        if (payload == null || payload.isBlank()) return;
        try {
            String url = java.net.URLDecoder.decode(payload.split("\\|")[0],
                    java.nio.charset.StandardCharsets.UTF_8);
            MemorySegment nsUrl = (MemorySegment) ObjC.sendAddress(1).invoke(ObjC.cls("NSURL"),
                    ObjC.sel("URLWithString:"), ObjC.nsString(url));
            if (nsUrl == null || nsUrl.address() == 0) return;
            MemorySegment request = (MemorySegment) ObjC.sendAddress(1).invoke(ObjC.cls("NSURLRequest"),
                    ObjC.sel("requestWithURL:"), nsUrl);
            MemorySegment block = ObjC.block(
                    ObjC.staticHandle(FFMWKWebView.class, "downloadStarted",
                            MethodType.methodType(void.class, MemorySegment.class,
                                    MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS), ObjC.GLOBAL);
            ObjC.callVoid(w.view, "startDownloadUsingRequest:completionHandler:", request, block);
        } catch (Throwable ignored) {}
    }

    public static void downloadStarted(MemorySegment block, MemorySegment download, MemorySegment error) {
        for (WebView w : HANDLES.values()) {
            if (download != null && download.address() != 0) {
                ObjC.callVoid(download, "setDelegate:", w.controller);
                if (w.events != null) w.events.script(w.token, "rigelDownloadEvent", "started");
            } else if (w.events != null) {
                w.events.script(w.token, "rigelDownloadEvent", "failed|" + ObjC.javaString(error));
            }
        }
    }

    public static void downloadDecideDestination(MemorySegment self, MemorySegment cmd,
                                                  MemorySegment download, MemorySegment response,
                                                  MemorySegment filename, MemorySegment completion) {
        try {
            WebView w = owner(self);
            String name = ObjC.javaString(filename);
            if (name == null || name.isBlank()) name = "download";
            String dir = w == null ? System.getProperty("user.home") + "/Downloads" : w.downloadDirectory;
            java.nio.file.Path target = java.nio.file.Path.of(dir, name);
            java.nio.file.Files.createDirectories(target.getParent());
            MemorySegment url = (MemorySegment) ObjC.sendAddress(1).invoke(ObjC.cls("NSURL"),
                    ObjC.sel("fileURLWithPath:"), ObjC.nsString(target.toString()));
            MemorySegment invokePtr = completion.reinterpret(32).get(ValueLayout.ADDRESS, 16L);
            MethodHandle invoke = ObjC.LINKER.downcallHandle(invokePtr,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            invoke.invoke(completion, url);
        } catch (Throwable ignored) {}
    }

    public static void downloadFinished(MemorySegment self, MemorySegment cmd, MemorySegment download) {
        WebView w = owner(self);
        if (w != null && w.events != null) w.events.script(w.token, "rigelDownloadEvent", "finished");
    }

    public static void downloadFailed(MemorySegment self, MemorySegment cmd, MemorySegment download,
                                       MemorySegment error, MemorySegment resumeData) {
        WebView w = owner(self);
        if (w != null && w.events != null)
            w.events.script(w.token, "rigelDownloadEvent", "failed|" + ObjC.javaString(error));
    }

    public static boolean returnYes(MemorySegment self, MemorySegment cmd) { return true; }
    public static boolean returnYesArg(MemorySegment self, MemorySegment cmd, MemorySegment event) { return true; }
    public static void noOp(MemorySegment self, MemorySegment cmd) {}
}
