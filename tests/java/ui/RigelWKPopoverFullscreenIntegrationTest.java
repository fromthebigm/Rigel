package ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.internal.cocoa.NSWindow;
import org.eclipse.swt.internal.cocoa.OS;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import proxy.Blocklist;
import proxy.ProxyServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Verifies HTML popovers in Rigel's real WKWebView while the app is fullscreen.
 *
 * <p>The fixture models a video player's settings menu nested inside a short,
 * transformed, overflow-clipped control bar. A working popover is promoted to
 * WebKit's top layer, escapes that clipping ancestor, and remains above its
 * bottom-right trigger inside the fullscreen viewport.</p>
 */
public final class RigelWKPopoverFullscreenIntegrationTest {
    private static final String READY_TITLE = "RIGEL_POPOVER_READY";
    private static final String RESULT_PREFIX = "RIGEL_POPOVER_RESULT;";

    public static void main(String[] args) throws Exception {
        System.setProperty("rigel.browser.backend", "wk");

        AtomicBoolean fixtureRequested = new AtomicBoolean();
        HttpServer origin = HttpServer.create(new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 4);
        origin.createContext("/player", exchange -> {
            fixtureRequested.set(true);
            serveFixture(exchange);
        });
        origin.start();
        ProxyServer proxy = new ProxyServer(new Blocklist(false), availableTcpPort());
        proxy.start();
        RigelBrowser.configureProxy(proxy.getBrowserProxyPort(), RigelBrowser.PROXY_SOCKS5);

        Display display = null;
        Shell shell = null;
        try {
            display = new Display();
            shell = new Shell(display);
            shell.setLayout(new FillLayout());
            shell.setSize(900, 620);

            RigelBrowser browser = new RigelBrowser(shell, SWT.NONE);
            require("wkwebview".equals(browser.getBackendName()),
                    "WKWebView was not selected");

            AtomicReference<String> title = new AtomicReference<>("");
            browser.addTitleListener(title::set);
            require(browser.setUrl("http://127.0.0.1:"
                            + origin.getAddress().getPort() + "/player"),
                    "popover fixture was rejected");
            shell.open();
            MacWindowCaptureProtection.install(shell);
            pumpUntil(display, () -> READY_TITLE.equals(title.get()), 8_000);
            require(READY_TITLE.equals(title.get()),
                    "popover fixture did not finish loading (requested="
                            + fixtureRequested.get() + " url=" + browser.getUrl()
                            + " title=" + title.get() + ")");

            shell.setFullScreen(true);
            shell.layout(true, true);
            pumpUntil(display, shell::getFullScreen, 5_000);
            require(shell.getFullScreen(), "SWT shell did not enter fullscreen");
            // macOS changes Spaces asynchronously after SWT flips the state.
            // Let WKWebView receive the final native frame before measuring.
            pumpFor(display, 1_500);

            title.set("");
            require(browser.execute(popoverProbeScript()),
                    "popover geometry probe was rejected");
            pumpUntil(display, () -> title.get().startsWith(RESULT_PREFIX), 8_000);
            String result = title.get();
            require(result.startsWith(RESULT_PREFIX),
                    "popover geometry probe did not finish (title=" + result + ")");
            requireField(result, "api", "1");
            requireField(result, "opened", "1");
            requireField(result, "visible", "1");
            requireField(result, "inBounds", "1");
            requireField(result, "above", "1");
            requireField(result, "escapedClip", "1");
            requireField(result, "hit", "1");
            requireField(result, "error", "none");

            Shell fullscreenShell = shell;
            pumpUntil(display, () -> windowSharingType(fullscreenShell) == 0L, 3_000);
            require(windowSharingType(shell) == 0L,
                    "fullscreen NSWindow sharingType was not NSWindowSharingNone");

            System.out.println("Rigel WK popover fullscreen integration passed: " + result
                    + " sharingType=0");
        } finally {
            if (shell != null && !shell.isDisposed()) {
                if (shell.getFullScreen()) {
                    shell.setFullScreen(false);
                    if (display != null && !display.isDisposed()) pumpFor(display, 300);
                }
                shell.dispose();
            }
            if (display != null && !display.isDisposed()) display.dispose();
            proxy.stop();
            origin.stop(0);
        }
    }

    private static void serveFixture(HttpExchange exchange) throws java.io.IOException {
        byte[] body = fixtureHtml().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static int availableTcpPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1,
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            return socket.getLocalPort();
        }
    }

    private static String fixtureHtml() {
        return """
                <!doctype html>
                <html>
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <title>RIGEL_POPOVER_LOADING</title>
                  <style>
                    * { box-sizing: border-box; }
                    html, body { width: 100%; height: 100%; margin: 0; overflow: hidden; }
                    body { background: #09090b; color: white; font: 15px system-ui; }
                    .videojs-player {
                      position: fixed;
                      inset: 0;
                      overflow: hidden;
                      transform: translateZ(0);
                      background: linear-gradient(#18181b, #09090b);
                    }
                    .controls-clip {
                      position: absolute;
                      inset: auto 0 0;
                      height: 56px;
                      overflow: hidden;
                      transform: translateZ(0);
                      background: rgba(24, 24, 27, .96);
                    }
                    #settings {
                      position: absolute;
                      right: 16px;
                      bottom: 8px;
                      width: 48px;
                      height: 40px;
                    }
                    #settings-menu {
                      position: fixed;
                      inset: auto 16px 72px auto;
                      width: min(280px, calc(100vw - 32px));
                      height: min(260px, calc(100dvh - 112px));
                      margin: 0;
                      padding: 16px;
                      overflow: auto;
                      border: 1px solid rgba(255, 255, 255, .22);
                      border-radius: 12px;
                      background: #27272a;
                      color: white;
                    }
                  </style>
                </head>
                <body>
                  <main class="videojs-player">
                    <div class="controls-clip" id="controls-clip">
                      <button id="settings" popovertarget="settings-menu">Settings</button>
                      <section id="settings-menu" popover aria-label="Player settings">
                        <strong>Player settings</strong>
                        <p>Quality</p><p>Playback speed</p><p>Subtitles</p>
                      </section>
                    </div>
                  </main>
                  <script>
                    addEventListener('DOMContentLoaded', function() {
                      setTimeout(function() { document.title = 'RIGEL_POPOVER_READY'; }, 0);
                    });
                  </script>
                </body>
                </html>
                """;
    }

    private static String popoverProbeScript() {
        return """
                (function(){
                  var menu = document.getElementById('settings-menu');
                  var button = document.getElementById('settings');
                  var clip = document.getElementById('controls-clip');
                  var api = !!menu && typeof menu.showPopover === 'function';
                  var error = 'none';
                  if (api) {
                    try { menu.showPopover(); }
                    catch (e) { error = (e && e.name) ? e.name : 'exception'; }
                  }
                  requestAnimationFrame(function(){requestAnimationFrame(function(){
                    var r = menu.getBoundingClientRect();
                    var b = button.getBoundingClientRect();
                    var c = clip.getBoundingClientRect();
                    var opened = false;
                    try { opened = menu.matches(':popover-open'); } catch (e) {}
                    var visible = getComputedStyle(menu).display !== 'none' &&
                                  r.width > 0 && r.height > 0;
                    var epsilon = 1;
                    var inBounds = r.left >= -epsilon && r.top >= -epsilon &&
                                   r.right <= innerWidth + epsilon &&
                                   r.bottom <= innerHeight + epsilon;
                    var above = r.bottom <= b.top - 4;
                    var escapedClip = r.top < c.top - 4;
                    var x = r.left + r.width / 2;
                    var y = Math.min(r.bottom - 2, c.top - 8);
                    var atPoint = document.elementFromPoint(x, y);
                    var hit = !!atPoint && (atPoint === menu || menu.contains(atPoint));
                    var fields = [
                      'api=' + (api ? 1 : 0),
                      'opened=' + (opened ? 1 : 0),
                      'visible=' + (visible ? 1 : 0),
                      'inBounds=' + (inBounds ? 1 : 0),
                      'above=' + (above ? 1 : 0),
                      'escapedClip=' + (escapedClip ? 1 : 0),
                      'hit=' + (hit ? 1 : 0),
                      'error=' + error,
                      'viewport=' + innerWidth + 'x' + innerHeight,
                      'menu=' + Math.round(r.left) + ',' + Math.round(r.top) + ',' +
                                Math.round(r.right) + ',' + Math.round(r.bottom),
                      'buttonTop=' + Math.round(b.top),
                      'clipTop=' + Math.round(c.top)
                    ];
                    document.title = 'RIGEL_POPOVER_RESULT;' + fields.join(';');
                  });});
                })();
                """;
    }

    private static long windowSharingType(Shell shell) {
        try {
            if (shell == null || shell.isDisposed() || shell.view == null || shell.view.id == 0)
                return -1L;
            NSWindow window = shell.view.window();
            if (window == null || window.id == 0) return -1L;
            long selector = OS.sel_registerName("sharingType");
            return selector == 0 ? -1L : OS.objc_msgSend(window.id, selector);
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static void requireField(String result, String name, String expected) {
        String field = name + "=" + expected;
        for (String part : result.split(";")) {
            if (field.equals(part)) return;
        }
        throw new AssertionError("expected " + field + " in popover result: " + result);
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
