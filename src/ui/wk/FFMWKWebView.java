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
    private static final Map<Long, java.util.ArrayDeque<Long>> TERMINATIONS = new ConcurrentHashMap<>();
    private static volatile boolean delegatesInstalled;
    private static volatile long proxyPort = 8888;
    private static volatile int proxyMode = 0;
    private static volatile String proxyHost;
    private static volatile boolean proxyConfigured;

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
        /** Destination path chosen in downloadDecideDestination, used by
         *  downloadPayload so the "destination" event carries the real path
         *  (deduped against existing files). */
        final Map<String, String> destTargets = new ConcurrentHashMap<>();
        /** Shared WKWebsiteDataStore backing this view (owned by us). */
        MemorySegment dataStore;
        final Map<String, MemorySegment> downloads = new ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicInteger downloadSeq = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger contentRuleGeneration = new java.util.concurrent.atomic.AtomicInteger();
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

    public static int testSimulateContentProcessTermination(long handle) {
        if (System.getenv("RIGEL_WK_LIFECYCLE_TEST") == null) return 0;
        try {
            WebView w = get(handle);
            ObjC.callVoid(w.controller, "webViewWebContentProcessDidTerminate:", w.view);
            return 1;
        } catch (Throwable t) { return 0; }
    }

    public static void setProxyPort(int port) { proxyPort = port; proxyConfigured = true; }
    public static void setProxy(int port, int mode) { setProxyForAllViews(proxyHost, port, mode); }
    public static void setProxyHost(String host) { proxyHost = host; }

    /** Pushes a new proxy config onto every live view's WKWebsiteDataStore
     *  (live-update on Settings change; parity with rigel_wk_set_proxy) and
     *  refreshes the statics used when creating new views. */
    public static void setProxyForAllViews(String host, int port, int mode) {
        proxyHost = host;
        proxyPort = port;
        proxyMode = mode;
        proxyConfigured = port > 0;
        for (WebView w : HANDLES.values())
            try {
                if (w.dataStore != null && w.dataStore.address() != 0)
                    configureProxy(w.dataStore, port, mode);
            } catch (Throwable ignored) {}
    }

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
        if (!proxyConfigured) { proxyPort = port; proxyMode = mode; }
        int effectivePort = (int) proxyPort;
        int effectiveMode = proxyMode;

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
            w.dataStore = dataStore;
            configureProxy(dataStore, effectivePort, effectiveMode);

            // Safari-ish user agent + shared process pool (native parity:
            // SafariApplicationName() / SharedProcessPool()).
            String uaName = safariApplicationName();
            ObjC.callVoid(w.configuration, "setApplicationNameForUserAgent:", ObjC.nsString(uaName));
            if (System.getProperty("rigel.ffm.diag") != null)
                System.out.println("[ffm] userAgent=" + uaName);
            ObjC.callVoid(w.configuration, "setProcessPool:", sharedProcessPool());
            ObjC.callVoidBool(w.configuration, "setSuppressesIncrementalRendering:", false);
            ObjC.callVoidBool(w.configuration, "setAllowsAirPlayForMediaPlayback:", true);
            if (ObjC.callBool(w.configuration, "respondsToSelector:",
                    ObjC.sel("setLimitsNavigationsToAppBoundDomains:")))
                ObjC.callVoidBool(w.configuration, "setLimitsNavigationsToAppBoundDomains:", false);
            if (ObjC.callBool(w.configuration, "respondsToSelector:",
                    ObjC.sel("setMediaTypesRequiringUserActionForPlayback:")))
                ObjC.callVoidInt(w.configuration, "setMediaTypesRequiringUserActionForPlayback:", 0);
            if (ObjC.callBool(w.view != null ? w.view : w.configuration, "respondsToSelector:",
                    ObjC.sel("setInspectable:"))) { /* applied after view creation below */ }

            MemorySegment ua = (MemorySegment) ObjC.sendAddress(0).invoke(
                    ObjC.cls("WKPreferences"), ObjC.sel("new"));
            ObjC.callVoidBool(ua, "setJavaScriptCanOpenWindowsAutomatically:", true);
            enablePopoverAttribute(ua);
            if (ObjC.callBool(ua, "respondsToSelector:", ObjC.sel("setElementFullscreenEnabled:")))
                ObjC.callVoidBool(ua, "setElementFullscreenEnabled:", true);
            if (ObjC.callBool(ua, "respondsToSelector:", ObjC.sel("setSiteSpecificQuirksModeEnabled:")))
                ObjC.callVoidBool(ua, "setSiteSpecificQuirksModeEnabled:", true);
            if (ObjC.callBool(ua, "respondsToSelector:", ObjC.sel("setTextInteractionEnabled:")))
                ObjC.callVoidBool(ua, "setTextInteractionEnabled:", true);
            ObjC.callVoid(w.configuration, "setPreferences:", ua);

            w.contentController = ObjC.allocInit(ObjC.cls("WKUserContentController"));
            ObjC.callVoid(w.configuration, "setUserContentController:", w.contentController);
            for (String name : handlerNames()) {
                ObjC.callVoid(w.contentController, "addScriptMessageHandler:name:", w.controller,
                        ObjC.nsString(name));
            }
            installUserScripts(w);
            if (w.contentBlocking != 0) configureContentBlocking(w, true);

            MemorySegment bounds = ObjC.rect(ObjC.GLOBAL, 0, 0, 1, 1);
            MemorySegment viewAlloc = (MemorySegment) ObjC.sendAddress(0).invoke(
                    webViewClass(), ObjC.sel("alloc"));
            MethodHandle initView = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ObjC.CGRect, ValueLayout.ADDRESS));
            w.view = (MemorySegment) initView.invoke(
                    viewAlloc, ObjC.sel("initWithFrame:configuration:"), bounds, w.configuration);
            if (ObjC.callBool(w.view, "respondsToSelector:", ObjC.sel("setInspectable:")))
                ObjC.callVoidBool(w.view, "setInspectable:", true);
            ObjC.callVoid(w.view, "setNavigationDelegate:", w.controller);
            ObjC.callVoid(w.view, "setUIDelegate:", w.controller);
            ObjC.callVoidInt(w.view, "setAutoresizingMask:", 18); // width|height sizable
            ObjC.callVoidBool(w.view, "setAllowsBackForwardNavigationGestures:", true);
            ObjC.callVoidBool(w.view, "setAllowsMagnification:", true);
            ObjC.callVoid(w.parent, "addSubview:", w.view);
            if (System.getProperty("rigel.ffm.diag") != null) {
                System.out.println("[ffm] script handler responds="
                    + ObjC.callBool(w.controller, "respondsToSelector:",
                        ObjC.sel("userContentController:didReceiveScriptMessage:")));
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
        try {
            MemorySegment view = get(handle).view;
            if (!ObjC.callBool(view, "respondsToSelector:", ObjC.sel("setPageZoom:"))) return 0;
            MethodHandle set = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE));
            set.invoke(view, ObjC.sel("setPageZoom:"), zoom);
            return 1;
        } catch (Throwable t) { return 0; }
    }

    public static void focus(long handle) {
        MemorySegment w = get(handle).view;
        ObjC.callVoid(w, "window");
        ObjC.callVoid(w, "becomeFirstResponder");
    }

    public static int editAction(long handle, int action) {
        // Never send NSResponder edit selectors to WKWebView from a bare java
        // process: an unimplemented selector raises an ObjC NSException that
        // aborts the whole JVM (no hs_err). Drive editing through JS instead,
        // which is safe for contenteditable fields and no-ops elsewhere.
        String[] cmds = {"undo", "redo", "cut", "copy", "paste", "selectAll", "delete"};
        if (action < 0 || action >= cmds.length) return 0;
        try {
            execute(handle, "try{document.execCommand('" + cmds[action] + "');}catch(e){}");
            return 1;
        } catch (Throwable t) { return 0; }
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
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_BOOLEAN));
            MemorySegment script = (MemorySegment) init.invoke(alloc,
                    ObjC.sel("initWithSource:injectionTime:forMainFrameOnly:"),
                    ObjC.nsString(javascript), 0L, mainFrameOnly != 0);
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
        String value = "";
        if (result != null && result.address() != 0) {
            try {
                if (ObjC.callBool(result, "isKindOfClass:", ObjC.cls("NSString"))) {
                    value = ObjC.javaString(result);
                } else {
                    MethodHandle jsonData = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
                    boolean valid = (boolean) jsonData.invoke(ObjC.cls("NSJSONSerialization"),
                            ObjC.sel("isValidJSONObject:"), result);
                    if (valid) {
                        MemorySegment data = (MemorySegment) jsonData.invoke(
                                ObjC.cls("NSJSONSerialization"),
                                ObjC.sel("dataWithJSONObject:options:error:"),
                                result, 0L, MemorySegment.NULL);
                        if (data != null && data.address() != 0) {
                            MemorySegment alloc = (MemorySegment) ObjC.sendAddress(0).invoke(
                                    ObjC.cls("NSString"), ObjC.sel("alloc"));
                            MethodHandle initStr = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
                            MemorySegment ns = (MemorySegment) initStr.invoke(alloc,
                                    ObjC.sel("initWithData:encoding:"), data, 4L);
                            value = ObjC.javaString(ns);
                        }
                    } else {
                        value = ObjC.javaString(ObjC.call0(result, "description"));
                    }
                }
            } catch (Throwable t) {
                value = ObjC.javaString(ObjC.call0(result, "description"));
            }
        }
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
        if (System.getProperty("rigel.ffm.diag") != null)
            System.out.println("[ffm] configureProxy enter port=" + port + " mode=" + mode);
        if (port <= 0) {
            // Proxy off: reset statics and clear any per-view proxy configs.
            proxyHost = null;
            proxyMode = 0;
            try {
                MemorySegment empty = (MemorySegment) ObjC.sendAddress(1).invoke(
                        ObjC.cls("NSArray"), ObjC.sel("array"));
                ObjC.callVoid(dataStore, "setProxyConfigurations:", empty);
            } catch (Throwable ignored) {}
            return;
        }
        try {
            SymbolLookup net;
            try { net = SymbolLookup.libraryLookup("/usr/lib/libNetwork.dylib", ObjC.GLOBAL); }
            catch (Throwable e) {
                try { net = SymbolLookup.libraryLookup(
                        "/System/Library/Frameworks/Network.framework/Versions/A/Network", ObjC.GLOBAL); }
                catch (Throwable e2) {
                    if (System.getProperty("rigel.ffm.diag") != null)
                        System.out.println("[ffm] Network.framework lookup failed: " + e2);
                    return;
                }
            }
            if (System.getProperty("rigel.ffm.diag") != null)
                System.out.println("[ffm] proxy symbols: endpoint=" + net.find("nw_endpoint_create_host").isPresent()
                    + " socks=" + net.find("nw_proxy_config_create_socksv5").isPresent()
                    + " http=" + net.find("nw_proxy_config_create_http_connect").isPresent());
            MethodHandle endpointFn = ObjC.LINKER.downcallHandle(net.find("nw_endpoint_create_host").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle socksFn = net.find("nw_proxy_config_create_socksv5")
                    .map(h -> ObjC.LINKER.downcallHandle(h,
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS))).orElse(null);
            MethodHandle httpFn = net.find("nw_proxy_config_create_http_connect")
                    .map(h -> ObjC.LINKER.downcallHandle(h,
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))).orElse(null);
            if (socksFn == null && httpFn == null) return;
            String host = (proxyHost == null || proxyHost.isBlank()) ? "127.0.0.1" : proxyHost;
            MemorySegment endpoint = (MemorySegment) endpointFn.invoke(ObjC.GLOBAL.allocateFrom(host),
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
            if (System.getProperty("rigel.ffm.diag") != null)
                System.out.println("[ffm] proxy configured port=" + port + " mode=" + mode);
        } catch (Throwable ignored) {}
    }

    private static volatile MemorySegment sharedPool;

    private static synchronized MemorySegment sharedProcessPool() {
        if (sharedPool == null) {
            MemorySegment pool = ObjC.allocInit(ObjC.cls("WKProcessPool"));
            ObjC.callVoid(pool, "retain");
            sharedPool = pool;
        }
        return sharedPool;
    }

    private static String safariApplicationName() {
        try {
            MemorySegment dict = (MemorySegment) ObjC.sendAddress(1).invoke(
                    ObjC.cls("NSDictionary"), ObjC.sel("dictionaryWithContentsOfFile:"),
                    ObjC.nsString("/Applications/Safari.app/Contents/Info.plist"));
            if (dict != null && dict.address() != 0) {
                String version = ObjC.javaString((MemorySegment) ObjC.sendAddress(1).invoke(
                        dict, ObjC.sel("objectForKey:"),
                        ObjC.nsString("CFBundleShortVersionString")));
                if (version != null && !version.isBlank())
                    return "Version/" + version + " Safari/605.1.15";
            }
        } catch (Throwable ignored) {}
        return "Version/18.0 Safari/605.1.15";
    }

    private static void enablePopoverAttribute(MemorySegment preferences) {
        try {
            MemorySegment cls = ObjC.cls("WKPreferences");
            boolean classFeatures = ObjC.callBool(cls, "respondsToSelector:", ObjC.sel("_features"));
            if (System.getProperty("rigel.ffm.diag") != null)
                System.out.println("[ffm] classFeatures=" + classFeatures);
            if (!classFeatures) return;
            if (!ObjC.callBool(preferences, "respondsToSelector:",
                    ObjC.sel("_setEnabled:forFeature:"))) return;
            MemorySegment features = (MemorySegment) ObjC.sendAddress(0).invoke(cls, ObjC.sel("_features"));
            if (System.getProperty("rigel.ffm.diag") != null)
                System.out.println("[ffm] features=" + (features == null ? -1 : features.address()));
            if (features == null || features.address() == 0) return;
            long count = ObjC.callLong(features, "count");
            for (long i = 0; i < count; i++) {
                MethodHandle objectAt = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
                MemorySegment feature = (MemorySegment) objectAt.invoke(
                        features, ObjC.sel("objectAtIndex:"), i);
                if (!ObjC.callBool(feature, "respondsToSelector:", ObjC.sel("key"))) continue;
                String key = ObjC.javaString(ObjC.call0(feature, "key"));
                if (System.getProperty("rigel.ffm.diag") != null)
                    System.out.println("[ffm] feature key=" + key);
                if (!"PopoverAttributeEnabled".equals(key)) continue;
                MethodHandle setFeature = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS));
                setFeature.invoke(preferences, ObjC.sel("_setEnabled:forFeature:"), true, feature);
                return;
            }
        } catch (Throwable t) {
            if (System.getProperty("rigel.ffm.diag") != null)
                System.out.println("[ffm] popover enable failed: " + t);
        }
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
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_BOOLEAN));
            MemorySegment script = (MemorySegment) init.invoke(alloc,
                    ObjC.sel("initWithSource:injectionTime:forMainFrameOnly:"),
                    ObjC.nsString(source), 0L, false);
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
        WebView w = get(handle);
        w.contentBlocking = enabled;
        configureContentBlocking(w, enabled != 0);
    }

    private static void configureContentBlocking(WebView w, boolean enabled) {
        try {
            MemorySegment rules = (MemorySegment) ObjC.sendAddress(0).invoke(
                    w.contentController, ObjC.sel("removeAllContentRuleLists"));
            if (!enabled) return;
            int generation = w.contentRuleGeneration.incrementAndGet();
            MemorySegment store = (MemorySegment) ObjC.sendAddress(0).invoke(
                    ObjC.cls("WKContentRuleListStore"), ObjC.sel("defaultStore"));
            MemorySegment block = ObjC.block(
                    ObjC.staticHandle(FFMWKWebView.class, "contentRulesCompiled",
                            MethodType.methodType(void.class, int.class, MemorySegment.class,
                                    MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS), ObjC.GLOBAL);
            // Bind generation and view token into the upcall.
            java.lang.invoke.MethodHandle bound = java.lang.invoke.MethodHandles.insertArguments(
                    ObjC.staticHandle(FFMWKWebView.class, "contentRulesCompiled",
                            MethodType.methodType(void.class, int.class, long.class,
                                    MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                    0, generation, w.token);
            MemorySegment boundBlock = ObjC.block(bound,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS), ObjC.GLOBAL);
            ObjC.callVoid(store, "compileContentRuleListForIdentifier:encodedContentRuleList:completionHandler:",
                    ObjC.nsString("com.rigel.builtin-content-blocker.v1"),
                    ObjC.nsString(CONTENT_RULES_JSON), boundBlock);
        } catch (Throwable ignored) {}
    }

    public static void contentRulesCompiled(int generation, long token,
                                            MemorySegment block, MemorySegment ruleList,
                                            MemorySegment error) {
        WebView w = HANDLES.values().stream().filter(v -> v.token == token).findFirst().orElse(null);
        if (w == null || w.contentBlocking == 0 || w.contentRuleGeneration.get() != generation
                || w.destroyed || ruleList == null || ruleList.address() == 0) return;
        try {
            ObjC.callVoid(w.contentController, "addContentRuleList:", ruleList);
            ObjC.callVoid(w.view, "reload");
        } catch (Throwable ignored) {}
    }

    private static final String CONTENT_RULES_JSON = buildContentRules();

    private static String buildContentRules() {
        String[] domains = {"*2mdn.net", "*adform.net", "*adnxs.com", "*ads-twitter.com",
                "*adsafeprotected.com", "*adsrvr.org", "*adzerk.net", "*advertising.com",
                "*amazon-adsystem.com", "*app-measurement.com", "*bidswitch.net", "*bluekai.com",
                "*casalemedia.com", "*chartbeat.com", "*clarity.ms", "*criteo.com", "*criteo.net",
                "*demdex.net", "*doubleclick.net", "*everesttech.net", "*eyeota.net",
                "*flashtalking.com", "*hotjar.com", "*imrworldwide.com", "*krxd.net",
                "*lijit.com", "*mathtag.com", "*media.net", "*mixpanel.com", "*moatads.com",
                "*newrelic.com", "*openx.net", "*optimizely.com", "*outbrain.com",
                "*pubmatic.com", "*quantserve.com", "*rlcdn.com", "*rubiconproject.com",
                "*scorecardresearch.com", "*segment.com", "*segment.io", "*serving-sys.com",
                "*sharethrough.com", "*smartadserver.com", "*spotxchange.com", "*taboola.com",
                "*tapad.com", "*teads.tv", "*tradedoubler.com", "*undertone.com",
                "*yieldmanager.com", "*yieldmo.com", "*zedo.com"};
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < domains.length; i++) {
            if (i > 0) d.append(',');
            d.append('"').append(domains[i]).append('"');
        }
        return "[{\"trigger\":{\"url-filter\":\".*\",\"if-domain\":[" + d + "]},"
                + "\"action\":{\"type\":\"block\"}},"
                + "{\"trigger\":{\"url-filter\":\".*\"},\"action\":{\"type\":\"css-display-none\","
                + "\"selector\":\".adsbygoogle,[data-ad-client],[id^='google_ads_'],"
                + "[id^='div-gpt-ad']\"}}]";
    }

    public static void setDownloadOptions(long handle, String directory, boolean ask) {
        WebView w = get(handle);
        w.downloadDirectory = directory == null || directory.isBlank()
                ? System.getProperty("user.home") + "/Downloads" : directory;
        w.askBeforeDownload = ask;
    }

    public static int cancelDownload(long handle, String identifier) {
        WebView w = get(handle);
        MemorySegment download = w.downloads.remove(identifier);
        if (download == null || download.address() == 0) return 0;
        try {
            ObjC.callVoid(download, "cancel");
            w.events.script(w.token, "rigelDownloadEvent",
                    downloadPayload(w, "cancelled", identifier, download, ""));
            return 1;
        } catch (Throwable t) { return 0; }
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
            ObjC.addMethod(c, "webView:createWebViewWithConfiguration:forNavigationAction:windowFeatures:",
                    ObjC.staticHandle(FFMWKWebView.class, "createPopup",
                            MethodType.methodType(MemorySegment.class, MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    "@@:@@@@");
            ObjC.addMethod(c, "webView:runOpenPanelWithParameters:initiatedByFrame:completionHandler:",
                    ObjC.staticHandle(FFMWKWebView.class, "runOpenPanel",
                            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "v@:@@@@");
            ObjC.addMethod(c, "webViewWebContentProcessDidTerminate:",
                    ObjC.staticHandle(FFMWKWebView.class, "processTerminated",
                            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "v@:@");
            ObjC.addMethod(c, "webViewDidClose:",
                    ObjC.staticHandle(FFMWKWebView.class, "webViewDidClose",
                            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "v@:@");
            ObjC.addMethod(c, "webView:runJavaScriptAlertPanelWithMessage:initiatedByFrame:completionHandler:",
                    ObjC.staticHandle(FFMWKWebView.class, "runJavaScriptAlert",
                            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class, MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    "v@:@@@@");
            ObjC.addMethod(c, "webView:runJavaScriptConfirmPanelWithMessage:initiatedByFrame:completionHandler:",
                    ObjC.staticHandle(FFMWKWebView.class, "runJavaScriptConfirm",
                            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class, MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    "v@:@@@@");
            ObjC.addMethod(c, "webView:runJavaScriptTextInputPanelWithPrompt:defaultText:initiatedByFrame:completionHandler:",
                    ObjC.staticHandle(FFMWKWebView.class, "runJavaScriptTextInput",
                            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class, MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    "v@:@@@@@");
            ObjC.addMethod(c, "requestMediaCapturePermissionForOrigin:decisionBlock:",
                    ObjC.staticHandle(FFMWKWebView.class, "requestMediaCapture",
                            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS), "v@:@@");
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
        return new String[]{"javaEnterFullscreen", "javaExitFullscreen", "rigelHomeNavigate",
                "rigelClipboardWrite", "rigelDownload", "rigelEvent", "rigelDiagnostics",
                "rigelContext", "rigelOpen"};
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
            java.lang.invoke.MethodHandle boundStart = java.lang.invoke.MethodHandles.insertArguments(
                    ObjC.staticHandle(FFMWKWebView.class, "downloadStarted",
                            MethodType.methodType(void.class, long.class, MemorySegment.class,
                                    MemorySegment.class, MemorySegment.class)), 0, w.token);
            MemorySegment block = ObjC.block(boundStart,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS), ObjC.GLOBAL);
            ObjC.callVoid(w.view, "startDownloadUsingRequest:completionHandler:", request, block);
        } catch (Throwable ignored) {}
    }

    /** WKUIDelegate file-upload panel: <input type="file"> support. */
    public static void runOpenPanel(MemorySegment self, MemorySegment cmd, MemorySegment webView,
                                    MemorySegment parameters, MemorySegment frame,
                                    MemorySegment completion) {
        try {
            MemorySegment panelClass = (MemorySegment) ObjC.sendAddress(0).invoke(
                    ObjC.cls("NSOpenPanel"), ObjC.sel("openPanel"));
            ObjC.callVoidBool(panelClass, "setCanChooseFiles:", true);
            ObjC.callVoidBool(panelClass, "setCanChooseDirectories:", false);
            if (parameters != null && parameters.address() != 0) {
                boolean multiple = ObjC.callBool(parameters, "allowsMultipleSelection");
                ObjC.callVoidBool(panelClass, "setAllowsMultipleSelection:", multiple);
            }
            int result = ObjC.callInt(panelClass, "runModal");
            MemorySegment urls = (MemorySegment) ObjC.sendAddress(0).invoke(panelClass, ObjC.sel("URLs"));
            if (result != 1 || urls == null || urls.address() == 0) urls = ObjC.call0(
                    (MemorySegment) ObjC.sendAddress(0).invoke(ObjC.cls("NSArray"), ObjC.sel("array")),
                    "self");
            MemorySegment invokePtr = completion.reinterpret(32).get(ValueLayout.ADDRESS, 16L);
            MethodHandle invoke = ObjC.LINKER.downcallHandle(invokePtr,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            invoke.invoke(completion, urls);
        } catch (Throwable t) {
            // Never leave a file chooser completion uncalled — WebKit throws
            // an internal exception if it never receives the callback.
            try {
                MemorySegment invokePtr = completion.reinterpret(32).get(ValueLayout.ADDRESS, 16L);
                MethodHandle invoke = ObjC.LINKER.downcallHandle(invokePtr,
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                invoke.invoke(completion, MemorySegment.NULL);
            } catch (Throwable ignored) {}
        }
    }

    public static MemorySegment createPopup(MemorySegment self, MemorySegment cmd,
                                             MemorySegment webView, MemorySegment configuration,
                                             MemorySegment action, MemorySegment features) {
        WebView w = owner(self);
        boolean windowed = isWindowedPopup(features);
        if (w != null && w.events != null && !windowed) {
            String url = "";
            try {
                MemorySegment req = ObjC.call0(action, "request");
                url = ObjC.javaString(ObjC.call0(ObjC.call0(req, "URL"), "absoluteString"));
            } catch (Throwable ignored) {}
            w.events.event(w.token, 7, url, "");
        }
        try {
            MemorySegment alloc = (MemorySegment) ObjC.sendAddress(0).invoke(ObjC.cls("WKWebView"), ObjC.sel("alloc"));
            MethodHandle initPopup = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ObjC.CGRect, ValueLayout.ADDRESS));
            MemorySegment popup = (MemorySegment) initPopup.invoke(alloc,
                    ObjC.sel("initWithFrame:configuration:"),
                    ObjC.rect(ObjC.GLOBAL, 0, 0, 1, 1), configuration);
            if (popup != null && popup.address() != 0) ObjC.callVoid(popup, "retain");
            // Windowed popups (explicit width/height) keep native behavior:
            // return the view. Otherwise FFM creates a hidden view + opens
            // the popup as a Rigel tab — same as native when it cannot detect
            // windowed popups (the NEW_WINDOW event above fires in that case).
            return windowed ? popup : MemorySegment.NULL;
        } catch (Throwable t) { return MemorySegment.NULL; }
    }

    private static boolean isWindowedPopup(MemorySegment features) {
        try {
            if (features == null || features.address() == 0) return false;
            for (String sel : new String[]{"width", "height"}) {
                MemorySegment v = ObjC.call0(features, sel);
                if (v != null && v.address() != 0 && ObjC.callDouble(v, "doubleValue") > 0) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static void processTerminated(MemorySegment self, MemorySegment cmd, MemorySegment webView) {
        WebView w = owner(self);
        if (w == null || w.events == null) return;
        String url = ObjC.javaString(ObjC.call0(ObjC.call0(webView, "URL"), "absoluteString"));
        w.events.event(w.token, 6, url, "");
        boolean hidden = false;
        try {
            MemorySegment window = ObjC.call0(webView, "window");
            hidden = window == null || window.address() == 0
                    || ObjC.callBool(webView, "isHiddenOrHasHiddenAncestor");
        } catch (Throwable ignored) {}
        long now = System.currentTimeMillis();
        var history = TERMINATIONS.computeIfAbsent(webView.address(), k -> new java.util.ArrayDeque<>());
        while (!history.isEmpty() && now - history.peekFirst() > 60_000) history.removeFirst();
        history.addLast(now);
        if (!hidden) {
            if (history.size() <= 3) {
                long delay = 250L * (1L << Math.min(2, history.size() - 1));
                final long target = webView.address();
                Thread t = new Thread(() -> {
                    try {
                        Thread.sleep(delay);
                        WebView current = HANDLES.values().stream()
                                .filter(v -> v.view != null && v.view.address() == target)
                                .findFirst().orElse(null);
                        if (current != null && !current.destroyed)
                            ObjC.callVoid(current.view, "reload");
                    } catch (Throwable ignored) {}
                }, "rigel-termination-reload");
                t.setDaemon(true);
                t.start();
            } else {
                w.events.event(w.token, 3, url, "Web content process repeatedly crashed; automatic reload stopped");
            }
        }
    }

    // ── WKUI dialogs + close parity ──────────────────────────────────────

    /** Mirrors RigelWKWebView.m webViewDidClose: emit CLOSED (type 5) on the
     *  owning view's event stream; TabManager closes the tab (popup or
     *  dedicated) via RigelBrowser's native-closed listeners. */
    public static void webViewDidClose(MemorySegment self, MemorySegment cmd, MemorySegment webView) {
        WebView w = owner(self);
        if (w != null && w.events != null && !w.destroyed)
            w.events.event(w.token, 5, "", ""); // MacWKWebView.CLOSED
    }

    public static void runJavaScriptAlert(MemorySegment self, MemorySegment cmd, MemorySegment webView,
                                          MemorySegment message, MemorySegment frame, MemorySegment completion) {
        try {
            MemorySegment alert = ObjC.allocInit(ObjC.cls("NSAlert"));
            ObjC.callVoid(alert, "setMessageText:", ObjC.nsString(safeString(ObjC.javaString(message))));
            ObjC.callVoidInt(alert, "setAlertStyle:", 2); // NSAlertStyleWarning
            ObjC.callVoid(alert, "addButtonWithTitle:", ObjC.nsString("OK"));
            ObjC.callInt(alert, "runModal");
            invokeBlock(completion);
        } catch (Throwable t) { System.err.println("[ffm] JS alert failed: " + t); }
    }

    public static void runJavaScriptConfirm(MemorySegment self, MemorySegment cmd, MemorySegment webView,
                                            MemorySegment message, MemorySegment frame, MemorySegment completion) {
        try {
            MemorySegment alert = ObjC.allocInit(ObjC.cls("NSAlert"));
            ObjC.callVoid(alert, "setMessageText:", ObjC.nsString(safeString(ObjC.javaString(message))));
            ObjC.callVoidInt(alert, "setAlertStyle:", 2);
            ObjC.callVoid(alert, "addButtonWithTitle:", ObjC.nsString("OK"));
            ObjC.callVoid(alert, "addButtonWithTitle:", ObjC.nsString("Cancel"));
            int result = ObjC.callInt(alert, "runModal");
            invokeBlock(completion, result == 1000); // NSAlertFirstButtonReturn
        } catch (Throwable t) { System.err.println("[ffm] JS confirm failed: " + t); }
    }

    public static void runJavaScriptTextInput(MemorySegment self, MemorySegment cmd, MemorySegment webView,
                                              MemorySegment prompt, MemorySegment defaultText,
                                              MemorySegment frame, MemorySegment completion) {
        try {
            MemorySegment alert = ObjC.allocInit(ObjC.cls("NSAlert"));
            ObjC.callVoid(alert, "setMessageText:", ObjC.nsString(safeString(ObjC.javaString(prompt))));
            ObjC.callVoidInt(alert, "setAlertStyle:", 2);
            MemorySegment field = (MemorySegment) ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ObjC.CGRect))
                    .invoke(ObjC.sendAddress(0).invoke(ObjC.cls("NSTextField"), ObjC.sel("alloc")),
                            ObjC.sel("initWithFrame:"), ObjC.rect(ObjC.GLOBAL, 0, 0, 320, 24));
            ObjC.callVoid(field, "setStringValue:", ObjC.nsString(safeString(ObjC.javaString(defaultText))));
            ObjC.callVoid(alert, "setAccessoryView:", field);
            ObjC.callVoid(alert, "addButtonWithTitle:", ObjC.nsString("OK"));
            ObjC.callVoid(alert, "addButtonWithTitle:", ObjC.nsString("Cancel"));
            int result = ObjC.callInt(alert, "runModal");
            String text = "";
            if (result == 1000) text = safeString(ObjC.javaString(ObjC.call0(field, "stringValue")));
            invokeBlock(completion, ObjC.nsString(text));
        } catch (Throwable t) { System.err.println("[ffm] JS text input failed: " + t); }
    }

    /** AVCaptureDevice + user decisionBlock. On the WK thread we block in a
     *  small poll loop waiting for the authorization state to settle; the
     *  decision is then invoked as Allow(1)/Deny(0)/Timeout(2). */
    public static void requestMediaCapture(MemorySegment self, MemorySegment cmd,
                                           MemorySegment origin, MemorySegment decisionBlock) {
        try {
            MemorySegment videoType = ObjC.nsString("vide");
            MemorySegment audioType = ObjC.nsString("soun");
            long deadline = System.currentTimeMillis() + 5_000;
            while (ObjC.callInt(ObjC.cls("AVCaptureDevice"), "authorizationStatusForMediaType:", videoType) == 0
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            while (ObjC.callInt(ObjC.cls("AVCaptureDevice"), "authorizationStatusForMediaType:", audioType) == 0
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            int videoStatus = ObjC.callInt(ObjC.cls("AVCaptureDevice"), "authorizationStatusForMediaType:", videoType);
            int audioStatus = ObjC.callInt(ObjC.cls("AVCaptureDevice"), "authorizationStatusForMediaType:", audioType);
            // AVAuthorizationStatus: 0 notDetermined, 1 restricted, 2 denied, 3 authorized.
            boolean videoOk = videoStatus == 3, audioOk = audioStatus == 3;
            boolean videoDenied = videoStatus == 1 || videoStatus == 2;
            boolean audioDenied = audioStatus == 1 || audioStatus == 2;
            // Do not require BOTH camera and mic: a camera-only page used to be
            // denied because mic stayed notDetermined. Allow if the requested
            // capability is authorized (or macOS will still prompt at capture
            // time); deny only when everything relevant is denied/restricted.
            long decision = (videoOk || audioOk) ? 1L : (videoDenied && audioDenied) ? 0L : 1L;
            invokeBlock(decisionBlock, decision);
        } catch (Throwable t) { System.err.println("[ffm] media capture request failed: " + t); }
    }

    private static String safeString(String s) { return s == null ? "" : s; }

    /** Invokes a void*-returning completion block (Objective-C blocks are
     *  id-typed, so the first descriptor arg is ADDRESS) with optional args.
     *  Never lets a failure leave a completion uncalled. */
    private static void invokeBlock(MemorySegment block) {
        try {
            ObjC.LINKER.downcallHandle(invokePtr(block),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)).invoke(block);
        } catch (Throwable t) { System.err.println("[ffm] block invoke failed: " + t); }
    }

    private static void invokeBlock(MemorySegment block, boolean value) {
        try {
            ObjC.LINKER.downcallHandle(invokePtr(block),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN)).invoke(block, value);
        } catch (Throwable t) { System.err.println("[ffm] block invoke failed: " + t); }
    }

    private static void invokeBlock(MemorySegment block, long value) {
        try {
            ObjC.LINKER.downcallHandle(invokePtr(block),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)).invoke(block, value);
        } catch (Throwable t) { System.err.println("[ffm] block invoke failed: " + t); }
    }

    private static void invokeBlock(MemorySegment block, MemorySegment value) {
        try {
            ObjC.LINKER.downcallHandle(invokePtr(block),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)).invoke(block, value);
        } catch (Throwable t) { System.err.println("[ffm] block invoke failed: " + t); }
    }

    private static MemorySegment invokePtr(MemorySegment block) {
        return block.reinterpret(32).get(ValueLayout.ADDRESS, 16L);
    }

    public static void downloadStarted(long token, MemorySegment block,
                                       MemorySegment download, MemorySegment error) {
        WebView w = HANDLES.values().stream().filter(v -> v.token == token).findFirst().orElse(null);
        if (w == null) return;
        if (download != null && download.address() != 0) {
            String id = "dl-" + w.downloadSeq.incrementAndGet();
            w.downloads.put(id, download);
            ObjC.callVoid(download, "setDelegate:", w.controller);
            if (w.events != null)
                w.events.script(w.token, "rigelDownloadEvent",
                        downloadPayload(w, "started", id, download, ""));
            startProgressPoller(w, id, download);
        } else if (w.events != null) {
            w.events.script(w.token, "rigelDownloadEvent",
                    payload("failed", "", "", "", "", "0", "0", ObjC.javaString(error)));
        }
    }

    private static String downloadPayload(WebView w, String event, String id,
                                          MemorySegment download, String extra) {
        String name = "", url = "", path = "";
        long completed = 0, total = 0;
        try {
            MemorySegment request = ObjC.call0(download, "originalRequest");
            MemorySegment nsUrl = ObjC.call0(request, "URL");
            url = ObjC.javaString(ObjC.call0(nsUrl, "absoluteString"));
            name = ObjC.javaString(ObjC.call0(nsUrl, "lastPathComponent"));
            MemorySegment progress = ObjC.call0(download, "progress");
            completed = (long) ObjC.callDouble(progress, "completedUnitCount");
            total = (long) ObjC.callDouble(progress, "totalUnitCount");
            if ("destination".equals(event) && w != null)
                path = w.destTargets.getOrDefault(id, "");
        } catch (Throwable ignored) {}
        return payload(event, id, name, url, path, String.valueOf(completed),
                String.valueOf(total), extra);
    }

    private static String payload(String... parts) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) b.append('|');
            b.append(java.net.URLEncoder.encode(parts[i] == null ? "" : parts[i],
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        return b.toString();
    }

    private static void startProgressPoller(WebView w, String id, MemorySegment download) {
        Thread t = new Thread(() -> {
            while (!w.destroyed && w.downloads.containsKey(id)) {
                try {
                    MemorySegment progress = ObjC.call0(download, "progress");
                    if (progress != null && progress.address() != 0) {
                        double fraction = ObjC.callDouble(progress, "fractionCompleted");
                        if (w.events != null)
                            w.events.script(w.token, "rigelDownloadEvent",
                                    downloadPayload(w, "progress", id, download, ""));
                    }
                    Thread.sleep(500);
                } catch (Throwable ignored) { break; }
            }
        }, "rigel-download-progress");
        t.setDaemon(true);
        t.start();
    }

    private static String downloadId(WebView fallback, MemorySegment download) {
        if (fallback != null) {
            for (var e : fallback.downloads.entrySet())
                if (e.getValue().address() == download.address()) return e.getKey();
        }
        for (WebView w : HANDLES.values())
            for (var e : w.downloads.entrySet())
                if (e.getValue().address() == download.address()) return e.getKey();
        return "";
    }

    public static void downloadDecideDestination(MemorySegment self, MemorySegment cmd,
                                                  MemorySegment download, MemorySegment response,
                                                  MemorySegment filename, MemorySegment completion) {
        try {
            WebView w = owner(self);
            String id = downloadId(w, download);
            if (w != null && w.events != null)
                w.events.script(w.token, "rigelDownloadEvent",
                        downloadPayload(w, "destination", id, download, ""));
            String name = ObjC.javaString(filename);
            if (name == null || name.isBlank()) name = "download";
            String dir = w == null ? System.getProperty("user.home") + "/Downloads" : w.downloadDirectory;
            java.nio.file.Path original = java.nio.file.Path.of(dir, name);
            // Dedupe: append " (N)" before the extension until free.
            java.nio.file.Path target = original;
            for (int n = 1; java.nio.file.Files.exists(target) && n <= 1000; n++) {
                String base = original.toString();
                int dot = base.lastIndexOf('.');
                int sep = base.lastIndexOf('/');
                if (dot > sep && dot > 0) target = java.nio.file.Path.of(base.substring(0, dot) + " (" + n + ")" + base.substring(dot));
                else target = java.nio.file.Path.of(base + " (" + n + ")");
            }
            java.nio.file.Files.createDirectories(target.getParent());
            if (w != null) w.destTargets.put(id, target.toString());
            // Ask-before: modal Yes/No alert; on No still complete with nil
            // (WebKit reports "cancelled" — same as native cancel path).
            if (w != null && w.askBeforeDownload && !showAskBeforeDownloadDialog(name)) {
                invokeDestination(completion, null);
                return;
            }
            invokeDestination(completion, target.toString());
        } catch (Throwable ignored) {}
    }

    private static boolean showAskBeforeDownloadDialog(String fileName) {
        try {
            MemorySegment alert = ObjC.allocInit(ObjC.cls("NSAlert"));
            ObjC.callVoid(alert, "setMessageText:", ObjC.nsString("Download " + safeString(fileName) + "?"));
            ObjC.callVoid(alert, "addButtonWithTitle:", ObjC.nsString("Yes"));
            ObjC.callVoid(alert, "addButtonWithTitle:", ObjC.nsString("No"));
            int result = ObjC.callInt(alert, "runModal");
            return result == 1000; // NSAlertFirstButtonReturn
        } catch (Throwable t) {
            System.err.println("[ffm] ask-before dialog failed: " + t);
            return true;
        }
    }

    private static void invokeDestination(MemorySegment completion, String path) {
        try {
            MemorySegment url = path == null
                    ? MemorySegment.NULL
                    : (MemorySegment) ObjC.sendAddress(1).invoke(ObjC.cls("NSURL"),
                            ObjC.sel("fileURLWithPath:"), ObjC.nsString(path));
            invokeBlock(completion, url);
        } catch (Throwable t) { System.err.println("[ffm] destination invoke failed: " + t); }
    }

    public static void downloadFinished(MemorySegment self, MemorySegment cmd, MemorySegment download) {
        WebView w = owner(self);
        String id = downloadId(w, download);
        if (w != null && w.events != null)
            w.events.script(w.token, "rigelDownloadEvent",
                    downloadPayload(w, "finished", id, download, ""));
        if (w != null) w.downloads.remove(id);
    }

    public static void downloadFailed(MemorySegment self, MemorySegment cmd, MemorySegment download,
                                       MemorySegment error, MemorySegment resumeData) {
        WebView w = owner(self);
        String id = downloadId(w, download);
        if (w != null && w.events != null)
            w.events.script(w.token, "rigelDownloadEvent",
                    downloadPayload(w, "failed", id, download, ObjC.javaString(error)));
        if (w != null) w.downloads.remove(id);
    }

    public static boolean returnYes(MemorySegment self, MemorySegment cmd) { return true; }
    public static boolean returnYesArg(MemorySegment self, MemorySegment cmd, MemorySegment event) { return true; }
    public static void noOp(MemorySegment self, MemorySegment cmd) {}
}
