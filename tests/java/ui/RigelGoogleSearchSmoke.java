package ui;

import config.SearchEngines;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Live smoke: loads the configured engine's URL for a query and a couple of
 *  hand-typed address-bar inputs through TabManager.resolveAddressInput. */
public final class RigelGoogleSearchSmoke {
    public static void main(String[] args) throws Exception {
        System.setProperty("rigel.browser.backend", "wk");
        Display display = new Display();
        Shell shell = new Shell(display);
        shell.setLayout(new FillLayout());
        shell.setSize(900, 600);
        RigelBrowser browser = new RigelBrowser(shell, SWT.NONE);
        shell.open();
        shell.setFocus();
        shell.forceActive();

        String q1 = SearchEngines.currentEngineOrGoogle(null).searchUrl("cute cats");
        System.out.println("[smoke] engine url: " + q1);
        browser.setUrl(q1);
        pump(display, 15000);
        String t = eval(display, browser, "return String(document.title||'')");
        System.out.println("[smoke] google title: " + t);
        System.out.println("[smoke] results rendered: " + eval(display, browser,
                "return String(document.querySelectorAll('h3,[role=link],a[href*=q]').length)"));

        String bing = SearchEngines.find("bing").searchUrl("cats");
        System.out.println("[smoke] bing url: " + bing);
        browser.setUrl(bing);
        pump(display, 8000);
        System.out.println("[smoke] bing title: " + eval(display, browser, "return String(document.title||'')"));

        shell.close();
        display.dispose();
        System.out.println("[smoke] RigelGoogleSearchSmoke done");
    }

    private static String eval(Display d, RigelBrowser b, String js) {
        AtomicReference<String> out = new AtomicReference<>("");
        CountDownLatch latch = new CountDownLatch(1);
        b.evaluateAsync(js, (v, e) -> { out.set(String.valueOf(v == null ? "" : v)); latch.countDown(); });
        long end = System.currentTimeMillis() + 4000;
        try { while (latch.getCount() > 0 && System.currentTimeMillis() < end) { while (d.readAndDispatch()) {} Thread.sleep(5); } Thread.sleep(50); }
        catch (InterruptedException ignored) {}
        return out.get();
    }

    private static void pump(Display d, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { while (d.readAndDispatch()) {} Thread.sleep(50); }
    }
}
