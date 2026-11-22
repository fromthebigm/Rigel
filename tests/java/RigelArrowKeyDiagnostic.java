import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import ui.RigelBrowser;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reproduces the "arrow key pastes an illegal character" bug: loads an input
 * exactly like the home page, focuses it, posts real left/right arrow HID
 * events, and prints the resulting field contents.
 */
public final class RigelArrowKeyDiagnostic {
    private interface CoreGraphics extends Library {
        CoreGraphics INSTANCE = Native.load("CoreGraphics", CoreGraphics.class);
        Pointer CGEventCreateKeyboardEvent(Pointer source, short keyCode, boolean keyDown);
        void CGEventPost(int tapLocation, Pointer event);
    }

    private interface CoreFoundation extends Library {
        CoreFoundation INSTANCE = Native.load("CoreFoundation", CoreFoundation.class);
        void CFRelease(Pointer cf);
    }

    private static final short KEY_LEFT = 123;
    private static final short KEY_RIGHT = 124;

    public static void main(String[] args) throws Exception {
        System.setProperty("rigel.browser.backend", "wk");
        RigelBrowser.configureProxy(0, 0);

        Display display = new Display();
        Shell shell = new Shell(display);
        try {
            shell.setLayout(new FillLayout());
            shell.setSize(700, 400);
            shell.setText("Arrow key diagnostic");
            RigelBrowser browser = new RigelBrowser(shell, SWT.NONE);
            shell.open();
            shell.forceActive();

            String html = "<!doctype html><html><body style='background:#222'>"
                    + "<input id='q' placeholder='Search' autocomplete='off' "
                    + "style='font-size:24px;width:400px' value='hello'>"
                    + "</body></html>";
            boolean bodyReady = loadHtmlAndWait(browser, display, html);
            System.out.println("body ready: " + bodyReady);

            // Focus the input at end of value, then post arrows.
            browser.execute("var q=document.getElementById('q'); q.focus();"
                    + "q.setSelectionRange(q.value.length, q.value.length);");
            settle(display, 400);
            browser.setFocus();
            settle(display, 400);

            postKey((short) 7); // 'x' — proves synthetic keys reach the web input
            settle(display, 300);
            postArrow(KEY_LEFT);
            postArrow(KEY_LEFT);
            postArrow(KEY_RIGHT);

            settle(display, 800);

            AtomicReference<String> report = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            browser.evaluateAsync(
                "var q = document.getElementById('q');"
              + "var chars = Array.from(q.value).map(function(c){ return 'U+'"
              + "  + c.codePointAt(0).toString(16).toUpperCase(); }).join(' ');"
              + "return JSON.stringify({value: q.value, codes: chars, selection: q.selectionStart});",
                (value, error) -> { report.set(String.valueOf(value)); latch.countDown(); });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (latch.getCount() > 0 && System.nanoTime() < deadline) pump(display);

            System.out.println("==== ARROW KEY REPORT ====");
            System.out.println("after 2x LEFT + 1x RIGHT with caret at end of 'hello':");
            System.out.println(report.get());
            CountDownLatch preLatch = new CountDownLatch(1);
            browser.evaluateAsync(
                "return JSON.stringify({url: location.href, hasFocus: document.hasFocus(),"
              + "active: document.activeElement && document.activeElement.tagName,"
              + "valueNow: (document.getElementById('q')||{}).value});",
                (v, e) -> { System.out.println("state: " + v + (e != null ? " err=" + e : "")); preLatch.countDown(); });
            long pd = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (preLatch.getCount() > 0 && System.nanoTime() < pd) pump(display);
            // The 'x' posted above proves synthetic keys reach the web input;
            // expected caret after 2xLEFT + 1xRIGHT from the end of "hellox": 5.
            boolean clean = report.get() != null && report.get().contains("\"value\":\"hellox\"")
                    && report.get().contains("\"selection\":5");
            System.out.println(clean
                    ? "PASS: no illegal characters inserted"
                    : "FAIL: arrow keys corrupted the field contents");
            System.out.println("==== END ====");
        } finally {
            shell.dispose();
            display.dispose();
        }
    }

    private static void postArrow(short keyCode) {
        postKey(keyCode);
    }

    private static void postKey(short keyCode) {
        Pointer down = CoreGraphics.INSTANCE.CGEventCreateKeyboardEvent(null, keyCode, true);
        Pointer up = CoreGraphics.INSTANCE.CGEventCreateKeyboardEvent(null, keyCode, false);
        if (down != null) {
            CoreGraphics.INSTANCE.CGEventPost(0, down);
            CoreFoundation.INSTANCE.CFRelease(down);
        }
        if (up != null) {
            CoreGraphics.INSTANCE.CGEventPost(0, up);
            CoreFoundation.INSTANCE.CFRelease(up);
        }
    }

    /** Waits until a document with our input marker exists. */
    private static boolean loadHtmlAndWait(RigelBrowser browser, Display display,
                                           String html) {
        browser.setText(html, true);
        AtomicReference<Boolean> ok = new AtomicReference<>(false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            pump(display);
            CountDownLatch probe = new CountDownLatch(1);
            browser.evaluateAsync(
                "return !!document.getElementById('q');",
                (v, e) -> { ok.set("true".equals(String.valueOf(v)) || Boolean.TRUE.equals(v)); probe.countDown(); });
            long wait = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (probe.getCount() > 0 && System.nanoTime() < wait) pump(display);
            if (ok.get()) return true;
        }
        return false;
    }

    private static String jsString(String s) {
        return "JSON.parse(\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n") + "\")";
    }

    private static void pump(Display display) {
        long slice = System.currentTimeMillis() + 40;
        while (System.currentTimeMillis() < slice) {
            while (display.readAndDispatch()) { }
            try { Thread.sleep(5); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static void settle(Display display, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) pump(display);
    }
}
