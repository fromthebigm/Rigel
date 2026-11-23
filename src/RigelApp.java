import org.eclipse.swt.widgets.Display;
import ui.BrowserApplication;

public class RigelApp {
    /** First http/https-ish argv, for launcher deep links ("open THIS page"). */
    private static String pickUrlArg(String[] args) {
        if (args == null) return null;
        for (String arg : args) {
            if (arg == null) continue;
            String trimmed = arg.trim();
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed;
            if (trimmed.matches("[A-Za-z0-9.-]+\\.[A-Za-z]{2,}([/?#].*)?")) return "https://" + trimmed;
        }
        return null;
    }

    public static void main(String[] args) {
        // One bad worker/channel must never take the whole browser down with
        // it. Log and keep the UI/proxy alive; per-connection isolation is in
        // the proxy handlers themselves.
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            System.err.println("[Rigel] uncaught on " + t.getName() + ": " + ex);
            ex.printStackTrace();
        });
        for (int i = 0; i < args.length - 1; i++) {
            if ("--shot".equals(args[i])) System.setProperty("rigel.shot.path", args[i + 1]);
        }
        // No identity dylib: Java/SWT identity settings carry the presentation
        // name, and the Dock icon comes from config.DockIcon (custom or bundled).
        System.setProperty("apple.awt.application.name", "Rigel");
        Display.setAppName("Rigel");
        Display.setAppVersion("portable");
        // Apply the Dock icon and regular activation policy before AWT/Display
        // initializes AppKit — Apple docs say late changes leave the generic
        // executable tile on some unbundled Java processes.
        config.DockIcon.apply();
        Display display = new Display();
        try {
            new BrowserApplication(display).run(pickUrlArg(args));
        } finally {
            if (!display.isDisposed()) display.dispose();
        }
    }
}
