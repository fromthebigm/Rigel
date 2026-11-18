package config;

import ui.objc.ObjC;
import java.io.InputStream;
import java.lang.foreign.*;
import java.lang.invoke.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Applies the persisted Dock icon (or bundled Weather icon) through AppKit.
 *
 *  SWT's TaskBar.setItemImage() on macOS sets the Java app proxy's tile to a
 *  generic folder icon, not the supplied image. NSApplication's
 *  setApplicationIconImage: is the real Dock tile API and works from Java FFM.
 */
public final class DockIcon {
    private static final MethodHandle SET_ICON;
    static {
        try {
            SET_ICON = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        } catch (Throwable t) { throw new ExceptionInInitializerError(t); }
    }

    private DockIcon() {}

    public static void apply() {
        Path file = resolveIconFile();
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            MemorySegment app = ObjC.call0(ObjC.cls("NSApplication"), "sharedApplication");
            MemorySegment cls = ObjC.cls("NSImage");
            MemorySegment alloc = (MemorySegment) ObjC.sendAddress(0).invoke(cls, ObjC.sel("alloc"));
            MethodHandle init = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MemorySegment image = (MemorySegment) init.invoke(alloc,
                    ObjC.sel("initWithContentsOfFile:"), ObjC.nsString(file.toString()));
            if (image != null && image.address() != 0)
                SET_ICON.invoke(app, ObjC.sel("setApplicationIconImage:"), image);
        } catch (Throwable ignored) {
        }
    }

    /** Kept for callers that pass a Display; the icon is applied to NSApp. */
    public static void apply(org.eclipse.swt.widgets.Display display) {
        apply();
    }

    private static Path resolveIconFile() {
        try {
            String custom = new Settings().getDockIconPath();
            if (custom != null && !custom.isBlank()) {
                Path p = Path.of(custom);
                if (Files.isRegularFile(p)) return p;
            }
            Path out = Settings.DIR.resolve("dock-icon-default.png");
            Files.createDirectories(Settings.DIR);
            if (!Files.isRegularFile(out)) {
                try (InputStream in = DockIcon.class.getResourceAsStream("/weather-icon.png")) {
                    if (in != null) Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return Files.isRegularFile(out) ? out : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
