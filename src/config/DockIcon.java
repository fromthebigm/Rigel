package config;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TaskBar;
import org.eclipse.swt.widgets.TaskItem;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Applies the persisted Dock icon (or the bundled Weather icon). */
public final class DockIcon {
    private static Image current;

    private DockIcon() {}

    public static void apply(Display display) {
        Image next = null;
        try {
            String custom = new Settings().getDockIconPath();
            Path file = custom == null || custom.isBlank() ? null : Path.of(custom);
            if (file != null && Files.isRegularFile(file)) next = new Image(display, file.toString());
        } catch (Exception ignored) {
        }
        if (next == null) {
            try (InputStream stream = DockIcon.class.getResourceAsStream("/weather-icon.png")) {
                if (stream != null) next = new Image(display, stream);
            } catch (Exception ignored) {
            }
        }
        if (next != null) {
            TaskBar bar = display.getSystemTaskBar();
            TaskItem item = bar == null ? null : bar.getItem((Shell) null);
            if (item != null) item.setImage(next);
            if (current != null && !current.isDisposed()) current.dispose();
            current = next;
        }
    }
}
