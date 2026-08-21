package ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import proxy.Blocklist;
import proxy.ProxyServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Exercises hidden-process recovery, short-lived connections, and OAuth popups. */
public final class RigelWKLifecycleStressTest {
    private static final int TAB_COUNT = 12;
    private static final int REQUESTS_PER_TAB = 18;

    public static void main(String[] args) throws Exception {
        System.setProperty("rigel.browser.backend", "wk");
        System.setProperty("rigel.tests.allowContentProcessTermination", "true");

        HttpServer origin = HttpServer.create(new InetSocketAddress(
                InetAddress.getLoopbackAddress(), 0), 64);
        origin.createContext("/page", RigelWKLifecycleStressTest::servePage);
        origin.createContext("/player", RigelWKLifecycleStressTest::servePlayer);
        origin.createContext("/resource", RigelWKLifecycleStressTest::serveResource);
        origin.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        origin.start();

        ProxyServer proxy = new ProxyServer(new Blocklist(false), availableTcpPort());
        Display display = null;
        Shell shell = null;
        try {
            display = new Display();
            proxy.start();
            RigelBrowser.configureProxy(proxy.getBrowserProxyPort(), RigelBrowser.PROXY_SOCKS5);

            shell = new Shell(display);
            shell.setSize(880, 620);
            StackLayout stack = new StackLayout();
            shell.setLayout(stack);
            List<Composite> containers = new ArrayList<>();
            List<RigelBrowser> browsers = new ArrayList<>();
            AtomicInteger completedTabs = new AtomicInteger();
            AtomicInteger firstLoadStarts = new AtomicInteger();
            AtomicReference<String> firstTitle = new AtomicReference<>("");
            for (int index = 0; index < TAB_COUNT; index++) {
                Composite container = new Composite(shell, SWT.NONE);
                container.setLayout(new FillLayout());
                RigelBrowser browser = new RigelBrowser(container, SWT.NONE);
                require(TabManager.installKeyboardFocusDocumentScript(browser),
                        "keyboard focus document-start script was rejected");
                int tabIndex = index;
                browser.addTitleListener(title -> {
                    if (tabIndex == 0) firstTitle.set(title);
                    if (("RIGEL_STRESS_" + tabIndex + "_DONE").equals(title)) {
                        completedTabs.incrementAndGet();
                    }
                });
                if (index == 0) {
                    browser.addProgressListener(new RigelBrowser.ProgressListener() {
                        @Override public void changed(int current, int total) {
                            firstLoadStarts.incrementAndGet();
                        }
                    });
                }
                containers.add(container);
                browsers.add(browser);
                stack.topControl = container;
                shell.layout();
                require(browser.setUrl("http://127.0.0.1:"
                                + origin.getAddress().getPort() + "/page?tab=" + index),
                        "stress tab URL was rejected");
            }
            shell.open();
            MacWindowCaptureProtection.install(shell);

            pumpUntil(display, () -> completedTabs.get() == TAB_COUNT, 35_000);
            require(completedTabs.get() == TAB_COUNT,
                    "only " + completedTabs.get() + " of " + TAB_COUNT + " stress tabs completed");
            RigelBrowser hidden = browsers.get(0);

            runInputProbe(display, hidden, firstTitle, "compatibility", "media", "BODY");
            runInputProbe(display, hidden, firstTitle, "compatibility", "control", "CONTROL");
            runInputProbe(display, hidden, firstTitle, "controls", "media", "MEDIA");

            int startsBeforeTermination = firstLoadStarts.get();
            require(hidden.simulateContentProcessTerminationForTesting(),
                    "WebKit content-process termination simulation was unavailable");
            pumpUntil(display, hidden::isContentProcessReloadPendingForTesting, 10_000);
            require(hidden.isContentProcessReloadPendingForTesting(),
                    "hidden WebContent termination was not reported");
            pumpFor(display, 800);
            require(firstLoadStarts.get() == startsBeforeTermination,
                    "hidden tab reloaded before it was activated");

            stack.topControl = containers.get(0);
            shell.layout();
            hidden.resumeAfterContentProcessTermination();
            pumpUntil(display, () -> firstLoadStarts.get() > startsBeforeTermination, 10_000);
            require(firstLoadStarts.get() > startsBeforeTermination,
                    "terminated tab did not reload when activated");

            long beforePopups = proxy.getBrowserConnectRequestCount();
            String accountChooser = "https://accounts.google.com/AccountChooser"
                    + "?service=mail&continue=https%3A%2F%2Fmail.google.com%2Fmail%2F";
            require(hidden.execute("window.__rigelOAuthPopup=window.open('"
                            + accountChooser + "','rigel-oauth-stress','width=900,height=700');"),
                    "Google account popup script was rejected");
            pumpUntil(display,
                    () -> proxy.getBrowserConnectRequestCount() > beforePopups, 15_000);
            require(proxy.getBrowserConnectRequestCount() > beforePopups,
                    "Google account popup did not reach the proxy route");
            pumpFor(display, 5_000);
            hidden.execute("if(window.__rigelOAuthPopup)window.__rigelOAuthPopup.close();");

            require(hidden.execute("(function(){var n=0,t=setInterval(function(){"
                            + "var w=window.open('about:blank','_blank','width=420,height=280');"
                            + "if(w)setTimeout(function(){try{w.close()}catch(e){}},35);"
                            + "if(++n===30)clearInterval(t);},70);})();"),
                    "popup churn script was rejected");
            pumpFor(display, 5_000);
            require(!shell.isDisposed(), "Rigel shell closed during popup/tab stress");

            // The earlier hidden-process simulation is the first termination
            // in the rolling window. Two visible retries remain; the next one
            // must stop instead of creating an endless crash/reload loop.
            for (int attempt = 0; attempt < 3; attempt++) {
                int startsBefore = firstLoadStarts.get();
                require(hidden.simulateContentProcessTerminationForTesting(),
                        "visible WebContent termination simulation failed");
                if (attempt < 2) {
                    pumpUntil(display, () -> firstLoadStarts.get() > startsBefore, 6_000);
                    require(firstLoadStarts.get() > startsBefore,
                            "bounded visible WebContent retry did not reload");
                    pumpFor(display, 300);
                } else {
                    pumpFor(display, 2_000);
                    require(firstLoadStarts.get() == startsBefore,
                            "repeated WebContent crashes created an unbounded reload loop");
                }
            }

            System.out.println("Rigel WK lifecycle stress passed: tabs=" + TAB_COUNT
                    + " shortRequests=" + (TAB_COUNT * REQUESTS_PER_TAB)
                    + " hiddenReloadDeferred=true googleAccountPopup=true popupChurn=30"
                    + " crossFrameInputModes=true visibleRestartBounded=true"
                    + " socksConnects=" + proxy.getBrowserConnectRequestCount());
        } finally {
            if (shell != null && !shell.isDisposed()) shell.dispose();
            if (display != null && !display.isDisposed()) display.dispose();
            proxy.stop();
            origin.stop(0);
        }
    }

    private static void servePage(HttpExchange exchange) throws java.io.IOException {
        int tab = queryValue(exchange, "tab", 0);
        String player = tab == 0
                ? "<iframe id='rigel-player-frame' src='http://localhost:"
                    + exchange.getLocalAddress().getPort() + "/player'></iframe>"
                : "";
        String html = "<!doctype html><title>RIGEL_STRESS_" + tab + "</title>" + player
                + "<script>"
                + "addEventListener('message',function(e){var d=e.data||{};"
                + "if(d.rigelInputResult)document.title='RIGEL_INPUT_'+d.target+'_'+d.active});"
                + "Promise.all(Array.from({length:" + REQUESTS_PER_TAB + "},function(_,i){"
                + "return fetch('/resource?tab=" + tab + "&i='+i,{cache:'no-store'}).then(function(r){return r.text()})"
                + "})).then(function(){document.title='RIGEL_STRESS_" + tab + "_DONE'})"
                + ".catch(function(){document.title='RIGEL_STRESS_" + tab + "_FAILED'});</script>";
        send(exchange, html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
    }

    private static void servePlayer(HttpExchange exchange) throws java.io.IOException {
        String html = "<!doctype html><body><div class='html5-video-player' tabindex='-1'>"
                + "<video id='media' tabindex='-1'></video><button id='control'>Control</button>"
                + "</div><script>addEventListener('message',function(e){var d=e.data||{};"
                + "if(e.source!==parent||!d.rigelInputProbe)return;"
                + "var el=document.getElementById(d.target);if(!el)return;el.focus();"
                + "var E=typeof PointerEvent==='function'?PointerEvent:MouseEvent;"
                + "el.dispatchEvent(new E(typeof PointerEvent==='function'?'pointerdown':'mousedown',"
                + "{bubbles:true,composed:true,button:0}));setTimeout(function(){"
                + "var a=document.activeElement;parent.postMessage({rigelInputResult:true,"
                + "target:String(d.target).toUpperCase(),active:String(a&&(a.id||a.tagName)||'NONE').toUpperCase()},'*')},40)"
                + "});</script>";
        send(exchange, html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
    }

    private static void runInputProbe(Display display, RigelBrowser browser,
                                      AtomicReference<String> title, String mode,
                                      String target, String expectedActive) throws Exception {
        String expectedTitle = "RIGEL_INPUT_" + target.toUpperCase() + "_" + expectedActive;
        title.set("");
        require(browser.execute("(function(){if(typeof window.__rigelSetWebInputMode!=='function')"
                        + "{document.title='RIGEL_INPUT_MISSING';return;}"
                        + "window.__rigelSetWebInputMode('" + mode + "');"
                        + "var f=document.getElementById('rigel-player-frame');"
                        + "if(f&&f.contentWindow)f.contentWindow.postMessage({rigelInputProbe:true,target:'"
                        + target + "'},'*');})();"),
                "cross-frame input probe script was rejected");
        pumpUntil(display, () -> expectedTitle.equals(title.get()), 8_000);
        require(expectedTitle.equals(title.get()), "input mode " + mode + " on " + target
                + " focused " + title.get() + " instead of " + expectedTitle);
    }

    private static void serveResource(HttpExchange exchange) throws java.io.IOException {
        send(exchange, "ok".getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static void send(HttpExchange exchange, byte[] body, String contentType)
            throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static int queryValue(HttpExchange exchange, String key, int fallback) {
        String prefix = key + "=";
        for (String part : exchange.getRequestURI().getRawQuery().split("&")) {
            if (part.startsWith(prefix)) {
                try { return Integer.parseInt(part.substring(prefix.length())); }
                catch (NumberFormatException ignored) { return fallback; }
            }
        }
        return fallback;
    }

    private static int availableTcpPort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(
                0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static void pumpUntil(Display display, BooleanSupplier condition, long timeoutMs)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            while (display.readAndDispatch()) { }
            Thread.sleep(10);
        }
    }

    private static void pumpFor(Display display, long durationMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMs);
        while (System.nanoTime() < deadline) {
            while (display.readAndDispatch()) { }
            Thread.sleep(10);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
