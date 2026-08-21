package ui;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.internal.cocoa.NSWindow;
import org.eclipse.swt.internal.cocoa.OS;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Applies capture exclusion to Rigel's Cocoa windows without changing WebKit's
 * fullscreen/compositing path.
 *
 * A window is protected once per native pointer/window-number pair. Reapplying
 * the private compositor call every few hundred milliseconds can itself cause
 * video flicker, so background sweeps only discover newly created AppKit or
 * WebKit windows. A short transition burst catches native fullscreen windows
 * as they are created.
 */
final class MacWindowCaptureProtection {
    private static final String INSTALLED_KEY = "rigel.captureProtectionInstalled";
    private static final String GENERATION_KEY = "rigel.captureProtectionGeneration";
    private static final long NS_WINDOW_SHARING_NONE = 0L;
    private static final int SETTLE_DELAY_MS = 280;
    private static final int WINDOW_DISCOVERY_INTERVAL_MS = 1200;
    private static final int[] NATIVE_FULLSCREEN_RETRY_DELAYS_MS = {
            0, 50, 140, 320, 700, 1300, 2200
    };

    /** windowNumber -> NSWindow pointer already protected */
    private static final Map<Long, Long> PROTECTED_WINDOWS = new ConcurrentHashMap<>();
    /** windowNumber -> NSWindow pointer queued for private compositor protection */
    private static final Map<Long, Long> PENDING_WINDOWS = new ConcurrentHashMap<>();

    /*
     * SkyLight calls can wait on WindowServer. They must never run on SWT's
     * event thread, because that same thread owns WebKit input, painting, and
     * the native window controls. A single daemon worker preserves call order
     * without keeping Rigel alive during shutdown.
     */
    private static final ExecutorService CAPTURE_WORKER =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "Rigel Capture Protection");
                thread.setDaemon(true);
                return thread;
            });

    private static volatile boolean functionsResolved;
    private static volatile boolean functionsAvailable;
    private static Function mainConnection;
    private static Function regionCreate;
    private static Function captureExclude;
    private static Function cfRelease;

    @Structure.FieldOrder({"x", "y"})
    public static class CGPoint extends Structure {
        public double x;
        public double y;
    }

    @Structure.FieldOrder({"width", "height"})
    public static class CGSize extends Structure {
        public double width;
        public double height;
    }

    @Structure.FieldOrder({"origin", "size"})
    public static class CGRect extends Structure implements Structure.ByValue {
        public CGPoint origin;
        public CGSize size;

        CGRect(double x, double y, double width, double height) {
            origin = new CGPoint();
            origin.x = x;
            origin.y = y;
            size = new CGSize();
            size.width = width;
            size.height = height;
            write();
        }
    }

    private MacWindowCaptureProtection() {}

    static boolean diagnosticCaptureEnabled() {
        String value = System.getenv("RIGEL_DIAGNOSTIC_CAPTURE");
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    static void install(Shell shell) {
        // Normal Rigel keeps its capture exclusion. aitest.sh opts a
        // diagnostic process out so screenshots and recordings can inspect it.
        if (diagnosticCaptureEnabled()) return;
        if (!isUsableShell(shell) || Boolean.TRUE.equals(shell.getData(INSTALLED_KEY))) return;
        shell.setData(INSTALLED_KEY, Boolean.TRUE);
        shell.addControlListener(new ControlAdapter() {
            @Override public void controlResized(ControlEvent event) {
                protectSoon(shell);
            }

            @Override public void controlMoved(ControlEvent event) {
                protectSoon(shell);
            }
        });
        protectSoon(shell);
        scheduleWindowDiscovery(shell, 0);
    }

    /** Called immediately before a website is allowed to enter native fullscreen. */
    static void prepareNativeFullscreen(Shell shell) {
        if (diagnosticCaptureEnabled()) return;
        if (!isUsableShell(shell)) return;
        Display display = shell.getDisplay();
        for (int delay : NATIVE_FULLSCREEN_RETRY_DELAYS_MS) {
            display.timerExec(delay, () -> {
                if (isUsableShell(shell)) protectApplicationWindows(shell);
            });
        }
    }

    private static void scheduleWindowDiscovery(Shell shell, int delayMs) {
        if (!isUsableShell(shell)) return;
        shell.getDisplay().timerExec(delayMs, () -> {
            if (!isUsableShell(shell)) return;
            protectApplicationWindows(shell);
            scheduleWindowDiscovery(shell, WINDOW_DISCOVERY_INTERVAL_MS);
        });
    }

    static void protectSoon(Shell shell) {
        if (!isUsableShell(shell)) return;
        Display display = shell.getDisplay();
        long generation = nextGeneration(shell);
        display.timerExec(SETTLE_DELAY_MS, () -> {
            if (!isUsableShell(shell) || !isCurrentGeneration(shell, generation)) return;
            protectNow(shell);
        });
    }

    private static boolean isCurrentGeneration(Shell shell, long generation) {
        Object current = shell.getData(GENERATION_KEY);
        return current instanceof Long && ((Long) current).longValue() == generation;
    }

    private static long nextGeneration(Shell shell) {
        Object value = shell.getData(GENERATION_KEY);
        long next = value instanceof Long ? ((Long) value).longValue() + 1L : 1L;
        shell.setData(GENERATION_KEY, Long.valueOf(next));
        return next;
    }

    private static boolean isUsableShell(Shell shell) {
        return shell != null
                && !shell.isDisposed()
                && "cocoa".equals(SWT.getPlatform())
                && shell.getDisplay() != null
                && !shell.getDisplay().isDisposed();
    }

    private static void protectNow(Shell shell) {
        try {
            if (shell.view == null || shell.view.id == 0) return;
            NSWindow window = shell.view.window();
            if (window == null || window.id == 0) return;
            long windowNumber = window.windowNumber();
            if (windowNumber > 0) protectWindowOnce(window.id, windowNumber);
        } catch (Throwable ignored) {
            // Capture protection is best-effort and must never destabilize SWT.
        }
    }

    private static void protectApplicationWindows(Shell shell) {
        if (!isUsableShell(shell)) return;
        try {
            protectNow(shell);
            long application = OS.objc_msgSend(OS.class_NSApplication, OS.sel_sharedApplication);
            if (application == 0) return;
            long windows = OS.objc_msgSend(application, OS.sel_registerName("windows"));
            if (windows == 0) return;
            long count = OS.objc_msgSend(windows, OS.sel_registerName("count"));
            long objectAtIndex = OS.sel_registerName("objectAtIndex:");
            long windowNumberSelector = OS.sel_registerName("windowNumber");
            Map<Long, Long> liveWindows = new HashMap<>();
            for (long index = 0; index < count; index++) {
                long window = OS.objc_msgSend(windows, objectAtIndex, index);
                if (window == 0) continue;
                long windowNumber = OS.objc_msgSend(window, windowNumberSelector);
                if (windowNumber > 0) {
                    liveWindows.put(Long.valueOf(windowNumber), Long.valueOf(window));
                    protectWindowOnce(window, windowNumber);
                }
            }
            PROTECTED_WINDOWS.entrySet().removeIf(entry ->
                    !entry.getValue().equals(liveWindows.get(entry.getKey())));
            PENDING_WINDOWS.entrySet().removeIf(entry ->
                    !entry.getValue().equals(liveWindows.get(entry.getKey())));
        } catch (Throwable ignored) {
            // Native fullscreen can replace a temporary window during this loop;
            // the bounded transition retries will discover its replacement.
        }
    }

    private static void protectWindowOnce(long window, long windowNumber) {
        Long previous = PROTECTED_WINDOWS.get(Long.valueOf(windowNumber));
        if (previous != null && previous.longValue() == window) return;

        // NSWindow access stays on SWT's event thread. This public setting is
        // applied immediately and remains the first line of capture protection.
        try {
            long selector = OS.sel_registerName("setSharingType:");
            if (selector != 0) OS.objc_msgSend(window, selector, NS_WINDOW_SHARING_NONE);
        } catch (Throwable ignored) {
            // The private compositor protection below can still succeed.
        }

        Long key = Long.valueOf(windowNumber);
        Long pointer = Long.valueOf(window);
        Long pending = PENDING_WINDOWS.put(key, pointer);
        if (pending != null && pending.longValue() == window) return;

        CAPTURE_WORKER.execute(() -> {
            boolean completed = applyPrivateProtection(windowNumber);
            if (completed && pointer.equals(PENDING_WINDOWS.get(key))) {
                PROTECTED_WINDOWS.put(key, pointer);
            }
            PENDING_WINDOWS.remove(key, pointer);
        });
    }

    /** Runs only on CAPTURE_WORKER; never call this from SWT's event thread. */
    private static boolean applyPrivateProtection(long windowNumber) {
        try {
            // If private APIs are unavailable, the public NSWindow sharing
            // setting above is still active. Treat that as a completed attempt
            // so the discovery timer does not continuously queue useless work.
            if (!resolveFunctions()) return true;

            CGRect bounds = new CGRect(0.0, 0.0, 32768.0, 32768.0);
            Pointer region = (Pointer) regionCreate.invoke(
                    Pointer.class, new Object[]{bounds});
            if (region == null || Pointer.nativeValue(region) == 0L) return false;
            try {
                int connection = ((Number) mainConnection.invoke(
                        Integer.class, new Object[0])).intValue();
                captureExclude.invoke(Integer.class,
                        new Object[]{connection, windowNumber, region});
                return true;
            } finally {
                cfRelease.invoke(Void.class, new Object[]{region});
            }
        } catch (Throwable ignored) {
            // Leave the window uncached so a later discovery pass can retry.
            return false;
        }
    }

    private static synchronized boolean resolveFunctions() {
        if (functionsResolved) return functionsAvailable;
        functionsResolved = true;

        try {
            NativeLibrary process = NativeLibrary.getProcess();
            mainConnection = findFirstFunction(process,
                    "SLSMainConnectionID", "CGSMainConnectionID");
            regionCreate = findFunction(process, "CGRegionCreateWithRect");
            captureExclude = findFirstFunction(process,
                    "SLSSetWindowCaptureExcludeShape", "CGSSetWindowCaptureExcludeShape");
            cfRelease = findFunction(process, "CFRelease");

            if (mainConnection == null || regionCreate == null
                    || captureExclude == null || cfRelease == null) {
                NativeLibrary coreGraphics = tryLoad(
                        "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics");
                NativeLibrary skyLight = tryLoad(
                        "/System/Library/PrivateFrameworks/SkyLight.framework/SkyLight");
                NativeLibrary coreFoundation = tryLoad(
                        "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation");

                if (mainConnection == null) {
                    mainConnection = findFirstFunction(skyLight,
                            "SLSMainConnectionID", "CGSMainConnectionID");
                }
                if (regionCreate == null) {
                    regionCreate = findFunction(coreGraphics, "CGRegionCreateWithRect");
                }
                if (captureExclude == null) {
                    captureExclude = findFirstFunction(skyLight,
                            "SLSSetWindowCaptureExcludeShape", "CGSSetWindowCaptureExcludeShape");
                }
                if (cfRelease == null) cfRelease = findFunction(coreFoundation, "CFRelease");
            }

            functionsAvailable = mainConnection != null && regionCreate != null
                    && captureExclude != null && cfRelease != null;
        } catch (Throwable ignored) {
            functionsAvailable = false;
        }
        return functionsAvailable;
    }

    private static NativeLibrary tryLoad(String path) {
        try {
            return NativeLibrary.getInstance(path);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Function findFirstFunction(NativeLibrary library, String... names) {
        if (library == null || names == null) return null;
        for (String name : names) {
            Function function = findFunction(library, name);
            if (function != null) return function;
        }
        return null;
    }

    private static Function findFunction(NativeLibrary library, String name) {
        if (library == null) return null;
        try {
            return library.getFunction(name);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
