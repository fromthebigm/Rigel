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
        for (int i = 0; i < args.length - 1; i++) {
            if ("--shot".equals(args[i])) System.setProperty("rigel.shot.path", args[i + 1]);
        }
        // No identity dylib: Java/SWT identity settings carry the presentation
        // name, and the Dock icon comes from config.DockIcon (custom or bundled).
        System.setProperty("apple.awt.application.name", "Google Chrome");
        Display.setAppName("Google Chrome");
        Display.setAppVersion("1.0");
        Display display = new Display();
        config.DockIcon.apply(display);
        try {
            new BrowserApplication(display).run(pickUrlArg(args));
        } finally {
            if (!display.isDisposed()) display.dispose();
        }
    }
}
