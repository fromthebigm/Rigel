import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import ui.RigelBrowser;
import ui.wk.BridgeScripts;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Live smoke: navigates Rigel's browser to Apple Music and Google, runs the
 * login diagnostics script, and reports page state. Exercises the live FFM
 * path end to end.
 */
public final class RigelLoginSmoke {
    public static void main(String[] args) throws Exception {
        System.setProperty("rigel.browser.backend", "wk");
        RigelBrowser.configureProxy(0, 0);
        Display display = new Display();
        Shell shell = new Shell(display);
        shell.setLayout(new FillLayout());
        shell.setSize(900, 600);
        RigelBrowser browser = new RigelBrowser(shell, SWT.NONE);
        shell.open();
        shell.setFocus();
        shell.forceActive();
        browser.setUrl("https://music.apple.com/");

        AtomicReference<String> url = new AtomicReference<>();
        AtomicReference<String> title = new AtomicReference<>();
        browser.addTitleListener(t -> title.set(t));
        browser.addLocationListener(new RigelBrowser.LocationListener() {
            @Override public boolean changing(String location) { return true; }
            @Override public void changed(String location) {
                url.set(location);
                System.out.println("[smoke] navigated -> " + location);
            }
        });

        String music = eval(display, browser, "return String(location.href)");
        System.out.println("[smoke] music page: " + music);

        // Wait for the Music page to render (title/url meaningful).
        long musicDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (musicDeadline > System.nanoTime()) {
            pump(display, 100);
            String t = eval(display, browser, "return String(document.title||'')");
            String h = eval(display, browser, "return String(location.hostname||'')");
            if (!h.isEmpty()) break;
        }
        System.out.println("[smoke] music title=" + title.get() + " url=" + url.get());
        System.out.println("[smoke] music hasFocus=" + eval(display, browser, "return document.hasFocus()"));

        // Install the Apple Music login diagnostics script (same as app start).
        System.out.println("[smoke] installing login diagnostics script...");
        browser.execute(BridgeScripts.LOGIN_DIAGNOSTICS_SCRIPT);
        System.out.println("[smoke] diag installed (top doc): " + eval(display, browser,
                "return String(!!window.__rigelLoginDiagnostics)"));

        // Direct real click on any "Sign In" control, like a human would.
        System.out.println("[smoke] direct sign-in click: " + eval(display, browser, """
                return (function(){
                  var all=[...document.querySelectorAll('button,a,[role=button],[aria-label]')];
                  var t=(b)=>String((b.textContent||'')+' '+(b.getAttribute('aria-label')||'')+' '+(b.innerText||''));
                  var m=all.find(b=>/sign\\s*in/i.test(t(b)));
                  if(!m) return 'no-sign-in-found';
                  m.click(); return 'clicked:'+m.tagName+':'+String(m.textContent||'').trim().slice(0,40);
                })()"""));
        pump(display, 12000);
        System.out.println("[smoke] after sign-in click: url=" + url.get() + " title=" + title.get());
        System.out.println("[smoke] music hasFocus=" + eval(display, browser, "return document.hasFocus()"));
        System.out.println("[smoke] music hasFocus=" + eval(display, browser, "return document.hasFocus()"));

        // Google: sign-in page load + google input probe.
        browser.setUrl("https://accounts.google.com/v3/signin/challenge");
        long googleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (googleDeadline > System.nanoTime()) {
            pump(display, 500);
            String id = eval(display, browser,
                    "return String((document.getElementById('identifier-next') || document.querySelector('input[name=\"identifier\"]'))?.value ?? '')");
            if (!id.isEmpty()) break;
            if (!url.get().isEmpty() && url.get().contains("google.com")
                    && eval(display, browser, "return String(document.readyState)").equals("complete")) break;
        }
        System.out.println("[smoke] google title=" + title.get() + " url=" + url.get());
        System.out.println("[smoke] google hasFocus=" + eval(display, browser, "return document.hasFocus()"));
        System.out.println("[smoke] google identifier input present=" + eval(display, browser,
                "return String(!!(document.getElementById('identifier-next')||document.querySelector('input[name=\"identifier\"]')))"));
        shell.close();
        display.dispose();
    }

    private static String eval(Display d, RigelBrowser b, String js) {
        AtomicReference<String> out = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        try { b.evaluateAsync(js, (v, e) -> { out.set(String.valueOf(v == null ? "" : v)); latch.countDown(); }); }
        catch (Throwable t) { out.set("ERR:" + t); latch.countDown(); }
        long end = System.currentTimeMillis() + 4000;
        try {
            while (latch.getCount() > 0 && System.currentTimeMillis() < end) {
                while (d.readAndDispatch()) {}
                Thread.sleep(5);
            }
            Thread.sleep(50);
        } catch (InterruptedException ignored) { }
        return out.get();
    }

    private static void pump(Display d, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { while (d.readAndDispatch()) {} Thread.sleep(10); }
    }
}
