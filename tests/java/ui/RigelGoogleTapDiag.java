package ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import ui.wk.BridgeScripts;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Live smoke: loads music.google.com with the login-diagnostics + Google GSI
 *  probe installed, polls window.__rigelGoogleMusicTap, prints the reported
 *  state, and exercises the autotest-click path. */
public final class RigelGoogleTapDiag {
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

        browser.setUrl("https://music.google.com/");
        pump(display, 10000);
        // Install diagnostics (same as the app does when RIGEL_LOGIN_DIAGNOSTICS is set)
        browser.execute(BridgeScripts.LOGIN_DIAGNOSTICS_SCRIPT);
        // Ask the page to auto-click the GSI One Tap bubble when activity shows
        eval(display, browser, "return String(document.title||'')");
        browser.execute("window.__rigelGoogleMusicAutotest=true");
        browser.execute(BridgeScripts.GOOGLE_TAP_DIAGNOSTICS_SCRIPT);

        for (int i = 0; i < 15; i++) {
            pump(display, 2000);
            String s = eval(display, browser, "return JSON.stringify(window.__rigelGoogleMusicTap||null)");
            String state = eval(display, browser,
                    "try{return JSON.stringify((window.webkit&&window.webkit.messageHandlers)?{h:true}:{h:false})}" +
                    "catch(e){return 'ERR'}");
            System.out.println("[diag " + i + "] " + s + " | " + state);
        }
        System.out.println("[diag] clicked flags: " + eval(display, browser,
                "return String((window.__rigelGoogleMusicTap&&window.__rigelGoogleMusicTap.clicked)||0)"));
        shell.close();
        display.dispose();
        System.out.println("[diag] RigelGoogleTapDiag done");
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
