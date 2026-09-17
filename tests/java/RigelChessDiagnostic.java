import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import proxy.Blocklist;
import proxy.ProxyServer;
import ui.RigelBrowser;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A/B diagnostic for chess.com in Rigel's WKWebView: runs once through the
 * loopback proxy stack and once with a direct connection, capturing
 * document-start-hooked page errors (fetch/XHR failures, resource errors,
 * unhandled rejections) plus puzzle/boot state. Run via
 * tests/run-chess-diagnostic.sh.
 */
public final class RigelChessDiagnostic {
    private static final String PAGE = "https://www.chess.com/puzzles";

    private record Probe(String name, String js) {}

    private static Probe websocketProbe(String name, String url) {
        return new Probe(name,
            "new Promise(function(resolve){"
          + "  var done=false;"
          + "  function fin(v){ if (done) return; done=true; resolve(v); }"
          + "  try {"
          + "    var ws = new WebSocket('" + url + "');"
          + "    ws.onopen  = function(){ fin('OPEN'); };"
          + "    ws.onerror = function(){ fin('ERROR'); };"
          + "    ws.onclose = function(e){ fin('CLOSE ' + e.code); };"
          + "    setTimeout(function(){ fin('timeout state=' + ws.readyState); }, 8000);"
          + "  } catch (e) { fin('threw: ' + e); }"
          + "})");
    }

    private static final List<Probe> PROBES = List.of(
        new Probe("page-state",
            "JSON.stringify({"
          + "title: document.title,"
          + "readyState: document.readyState,"
          + "bodyChildren: document.body ? document.body.children.length : -1,"
          + "textLength: document.body ? document.body.innerText.length : -1,"
          + "hasBoard: !!document.querySelector('chess-board,wc-chess-board,.board,[class*=board]'),"
          + "scripts: document.scripts.length"
          + "})"),
        websocketProbe("ws-chess-service", "wss://www.chess.com/service/socket"),
        websocketProbe("ws-sane-postman", "wss://ws.postman-echo.com/raw"),
        new Probe("service-workers",
            "(navigator.serviceWorker"
          + "  ? navigator.serviceWorker.getRegistrations()"
          + "      .then(function(r){ return 'registrations=' + r.length; })"
          + "      .catch(function(e){ return 'error: ' + e; })"
          + "  : Promise.resolve('serviceWorker API missing'))")
    );

    private static final String HOOKS =
        "(function(){"
      + "if (window.__rigelDiagHooked) return;"
      + "window.__rigelDiagHooked = true;"
      + "window.__rigelErrors = [];"
      + "window.__rigelResults = {};"
      + "function log(s){ if (window.__rigelErrors.length < 60) window.__rigelErrors.push(String(s)); }"
      + "window.addEventListener('error', function(e){"
      + "  var t = e.target;"
      + "  if (t && t !== window && (t.src || t.href)) log('resource ' + (t.src || t.href));"
      + "  else log(String(e.message || e.type) + (e.filename ? ' @ ' + e.filename + ':' + e.lineno : ''));"
      + "}, true);"
      + "window.addEventListener('unhandledrejection', function(e){"
      + "  log('unhandledrejection: ' + (e.reason && e.reason.stack || e.reason));"
      + "}, true);"
      + "var origFetch = window.fetch;"
      + "if (origFetch) window.fetch = function(u){"
      + "  return origFetch.apply(this, arguments).catch(function(err){"
      + "    log('fetch FAILED: ' + (typeof u === 'string' ? u : (u && u.url)) + ' :: ' + err);"
      + "    throw err;"
      + "  });"
      + "};"
      + "var origOpen = XMLHttpRequest.prototype.open;"
      + "XMLHttpRequest.prototype.open = function(m, u){"
      + "  this.addEventListener('error', function(){ log('xhr FAILED: ' + u); });"
      + "  this.addEventListener('timeout', function(){ log('xhr TIMEOUT: ' + u); });"
      + "  return origOpen.apply(this, arguments);"
      + "};"
      + "var origSend = WebSocket.prototype.send;"
      + "var origWs = window.WebSocket;"
      + "window.WebSocket = function(url, p){"
      + "  var ws = p ? new origWs(url, p) : new origWs(url);"
      + "  ws.addEventListener('error', function(){ log('websocket FAILED: ' + url); });"
      + "  ws.addEventListener('close', function(e){ if (e.code > 1006) log('websocket closed early: ' + url + ' code=' + e.code); });"
      + "  return ws;"
      + "};"
      + "window.WebSocket.prototype = origWs.prototype;"
      + "})()";

    public static void main(String[] args) throws Exception {
        System.setProperty("rigel.browser.backend", "wk");
        runScenario("PROXIED", true);
        runScenario("DIRECT", false);
    }

    private static void runScenario(String label, boolean useProxy) throws Exception {
        ProxyServer proxy = null;
        Display display = null;
        Shell shell = null;
        try {
            display = new Display();
            if (useProxy) {
                proxy = new ProxyServer(new Blocklist(false), availableTcpPort());
                proxy.start();
                RigelBrowser.configureProxy(proxy.getBrowserProxyPort(), 2);
            } else {
                RigelBrowser.configureProxy(0, 0); // PROXY_DIRECT
            }

            shell = new Shell(display);
            shell.setLayout(new FillLayout());
            shell.setSize(1100, 760);
            shell.setText("Rigel chess diagnostic - " + label);
            RigelBrowser browser = new RigelBrowser(shell, SWT.NONE);

            AtomicBoolean loaded = new AtomicBoolean();
            browser.addProgressListener(new RigelBrowser.ProgressListener() {
                @Override public void completed() { loaded.set(true); }
            });
            shell.open();
            browser.addDocumentStartScript(HOOKS);
            browser.setUrl(PAGE);

            long navDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!loaded.get() && System.nanoTime() < navDeadline) pump(display);

            // Settle time for deferred scripts, the puzzle fetch, sockets.
            long settleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
            while (System.nanoTime() < settleDeadline) pump(display);

            // Dismiss onboarding modals the way a user would.
            browser.execute(
                "(function(){"
              + "var b = Array.from(document.querySelectorAll('button'))"
              + "  .find(function(x){ var t=(x.textContent||'').trim().toLowerCase();"
              + "    return t === 'ok' || t.indexOf('solve puzzles') === 0"
              + "      || t === 'got it' || t === 'continue'; });"
              + "if (b) b.click();"
              + "})()");
            long clickSettle = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < clickSettle) pump(display);

            // Real-pointer move attempt on the board: pointerdown/up on one
            // piece square, then on a destination square, then compare the
            // piece layout to see whether the position accepted input.
            browser.execute(
                "(function(){"
              + "function pieces(){"
              + "  return Array.from(document.querySelectorAll('.board .piece, chess-board .piece, wc-chess-board .piece'))"
              + "    .map(function(p){ return p.className + '|' + (p.style.transform || ''); })"
              + "    .sort().join(';');"
              + "}"
              + "window.__rigelBefore = pieces();"
              + "var board = document.querySelector('chess-board, wc-chess-board, .board, [class*=chessboard]');"
              + "window.__rigelBoardFound = !!board;"
              + "if (!board) return;"
              + "var r = board.getBoundingClientRect();"
              + "function fire(type, x, y){"
              + "  var ev = new PointerEvent(type, {"
              + "    bubbles: true, cancelable: true, composed: true,"
              + "    clientX: x, clientY: y, pointerId: 1, pointerType: 'mouse',"
              + "    isPrimary: true, button: 0, buttons: 1 });"
              + "  document.elementFromPoint(x, y)?.dispatchEvent(ev);"
              + "}"
              + "var x = r.left + r.width * 4.5 / 8, y = r.top + r.height * 6.5 / 8;"
              + "fire('pointerover', x, y); fire('pointerdown', x, y); fire('mousedown', x, y);"
              + "fire('pointerup', x, y); fire('mouseup', x, y); fire('click', x, y);"
              + "var y2 = y - r.height / 8 * 2;"
              + "fire('pointerdown', x, y2); fire('mousedown', x, y2);"
              + "fire('pointerup', x, y2); fire('mouseup', x, y2); fire('click', x, y2);"
              + "setTimeout(function(){"
              + "  window.__rigelAfter = pieces();"
              + "}, 1200);"
              + "})()");

            for (Probe probe : PROBES) {
                browser.execute(
                    "(function(){"
                  + "try { Promise.resolve(" + probe.js() + ")"
                  + "  .then(function(v){ (window.__rigelResults = window.__rigelResults || {})['"
                  + probe.name() + "'] = String(v); })"
                  + "  .catch(function(e){ window.__rigelResults['"
                  + probe.name() + "'] = 'ERR ' + e; });"
                  + "} catch (syncErr) { window.__rigelResults['"
                  + probe.name() + "'] = 'SYNC ' + syncErr; }"
                  + "})()");
            }
            long probeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < probeDeadline) pump(display);

            CopyOnWriteArrayList<String> results = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(3);
            browser.evaluateAsync(
                "return JSON.stringify(window.__rigelResults || {});",
                (value, error) -> { results.add("probes: " + value); done.countDown(); });
            browser.evaluateAsync(
                "return JSON.stringify((window.__rigelErrors || []).slice(0, 40));",
                (value, error) -> { results.add("page errors: " + value); done.countDown(); });
            browser.evaluateAsync(
                "return JSON.stringify({"
              + "boardFound: !!window.__rigelBoardFound,"
              + "before: (window.__rigelBefore || '').length,"
              + "after: (window.__rigelAfter || '').length,"
              + "changed: (window.__rigelBefore !== window.__rigelAfter)"
              + "  && window.__rigelAfter !== undefined,"
              + "modalStillOpen: !!Array.from(document.querySelectorAll('button'))"
              + "    .find(function(x){ return (x.textContent||'').trim().toLowerCase() === 'ok'; })"
              + "});",
                (value, error) -> { results.add("interaction: " + value); done.countDown(); });
            long readDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (done.getCount() > 0 && System.nanoTime() < readDeadline) pump(display);

            System.out.println("==== " + label + " REPORT ====");
            System.out.println("url: " + browser.getUrl());
            results.forEach(System.out::println);
            String snapshotPath = "/tmp/rigel-chess-" + label.toLowerCase() + ".png";
            System.out.println("snapshot: " + snapshotPath + " -> " + browser.snapshot(snapshotPath));
            System.out.println("==== END " + label + " ====");
        } finally {
            if (shell != null && !shell.isDisposed()) shell.dispose();
            if (display != null && !display.isDisposed()) display.dispose();
            if (proxy != null) proxy.stop();
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }
    }

    private static void pump(Display display) {
        long slice = System.currentTimeMillis() + 50;
        while (System.currentTimeMillis() < slice) {
            while (display.readAndDispatch()) { }
            try { Thread.sleep(5); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static int availableTcpPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1,
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            return socket.getLocalPort();
        }
    }
}
