import java.io.InputStream;
import java.nio.file.Path;

import com.sun.jna.Library;
import com.sun.jna.Native;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.TaskBar;
import org.eclipse.swt.widgets.TaskItem;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import ui.BrowserApplication;

public class RigelApp {
    private interface DockIdentity extends Library {
        void rigel_install_dock_identity(String bundlePath);
    }

    private static DockIdentity dockIdentity;

    /** First http/https-ish argv, for launcher deep links ("open THIS page").
     *  A bare host gets an https scheme; anything else is not a URL arg. */
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
        try {
            Path root = Path.of(System.getProperty("rigel.root", ".")).toAbsolutePath();
            dockIdentity = Native.load(root.resolve("dock_identity.dylib").toString(), DockIdentity.class);
            dockIdentity.rigel_install_dock_identity(root.resolve("Weather.app").toString());
        } catch (Throwable ignored) {
        }
        System.setProperty("apple.awt.application.name", "Google Chrome");
        Display.setAppName("Google Chrome");
        Display.setAppVersion("1.0");
        Display display = new Display();
        Image dockIcon = null;
        try {
                try (InputStream stream = RigelApp.class.getResourceAsStream("/weather-icon.png")) {
                if (stream != null) {
                    dockIcon = new Image(display, stream);
                    TaskBar taskBar = display.getSystemTaskBar();
                    TaskItem appItem = taskBar == null ? null : taskBar.getItem((Shell) null);
                    if (appItem != null) appItem.setImage(dockIcon);
                }
            } catch (Exception ignored) {
            }
            new BrowserApplication(display).run(pickUrlArg(args));
        } finally {
            if (dockIcon != null && !dockIcon.isDisposed()) dockIcon.dispose();
            if (!display.isDisposed()) display.dispose();
        }
    }
}
