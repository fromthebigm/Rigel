package ui;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.internal.cocoa.NSColor;
import org.eclipse.swt.internal.cocoa.NSAppearance;
import org.eclipse.swt.internal.cocoa.NSRect;
import org.eclipse.swt.internal.cocoa.NSString;
import org.eclipse.swt.internal.cocoa.NSView;
import org.eclipse.swt.internal.cocoa.NSWindow;
import org.eclipse.swt.internal.cocoa.OS;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

final class MacVibrancy {
    static final int MATERIAL_TITLEBAR = 3;
    static final int MATERIAL_SIDEBAR = 7;
    /** Whole-window frosted material — draws the desktop, blurred, behind
     *  every non-painting view (Arc/Zen-style liquid glass). */
    static final int MATERIAL_UNDER_WINDOW = 21;

    private static final String KEY = "rigel.nativeVibrancy";
    private static final long NS_WINDOW_BELOW = -1;
    private static final long NS_VIEW_WIDTH_SIZABLE = 2;
    private static final long NS_VIEW_HEIGHT_SIZABLE = 16;
    private static final long BLENDING_BEHIND_WINDOW = 0;
    private static final long STATE_FOLLOWS_WINDOW = 0;

    private MacVibrancy() {}

    static boolean isSupported() {
        if (!"cocoa".equals(SWT.getPlatform())) return false;
        try {
            return OS.objc_getClass("NSVisualEffectView") != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    static void prepareWindow(Shell shell, boolean enabled) {
        if (shell == null || shell.isDisposed() || !isSupported()) return;
        try {
            NSWindow window = shell.view.window();
            if (window == null) return;
            window.setOpaque(!enabled);
            window.setBackgroundColor(enabled
                    ? NSColor.clearColor()
                    : NSColor.windowBackgroundColor());
        } catch (Throwable ignored) {
        }
    }

    static boolean apply(Shell shell, Composite target, boolean enabled, int material,
                         double alpha, boolean darkAppearance) {
        if (target == null || target.isDisposed()) return false;
        remove(target);
        if (!enabled || !isSupported()) return false;
        prepareWindow(shell, true);
        long effect = 0;
        boolean installed = false;
        try {
            long cls = OS.objc_getClass("NSVisualEffectView");
            if (cls == 0 || target.view == null) return false;

            NSRect frame = target.view.bounds();
            effect = OS.objc_msgSend(cls, OS.sel_alloc);
            effect = OS.objc_msgSend(effect, OS.sel_initWithFrame_, frame);
            if (effect == 0) return false;

            OS.objc_msgSend(effect, OS.sel_registerName("setMaterial:"), (long) material);
            OS.objc_msgSend(effect, OS.sel_registerName("setBlendingMode:"), BLENDING_BEHIND_WINDOW);
            OS.objc_msgSend(effect, OS.sel_setState_, STATE_FOLLOWS_WINDOW);
            NSAppearance appearance = NSAppearance.appearanceNamed(NSString.stringWith(
                    darkAppearance ? "NSAppearanceNameVibrantDark"
                            : "NSAppearanceNameVibrantLight"));
            if (appearance != null)
                OS.objc_msgSend(effect, OS.sel_setAppearance_, appearance.id);
            OS.objc_msgSend(effect, OS.sel_setAlphaValue_, Math.max(0.0, Math.min(1.0, alpha)));
            OS.objc_msgSend(effect, OS.sel_setAutoresizingMask_, NS_VIEW_WIDTH_SIZABLE | NS_VIEW_HEIGHT_SIZABLE);

            target.view.addSubview(new NSView(effect), NS_WINDOW_BELOW, null);
            State state = new State(effect, e -> remove(target));
            target.setData(KEY, state);
            target.addControlListener(state);
            target.addDisposeListener(state.disposeListener);
            updateFrame(target);
            installed = true;
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (!installed && effect != 0) {
                try {
                    OS.objc_msgSend(effect, OS.sel_removeFromSuperview);
                } catch (Throwable ignored) {
                }
                try {
                    OS.objc_msgSend(effect, OS.sel_release);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    static void remove(Control target) {
        if (target == null || target.isDisposed()) return;
        Object value = target.getData(KEY);
        if (!(value instanceof State state)) return;
        target.setData(KEY, null);
        try {
            target.removeControlListener(state);
        } catch (Throwable ignored) {
        }
        try {
            target.removeDisposeListener(state.disposeListener);
        } catch (Throwable ignored) {
        }
        try {
            OS.objc_msgSend(state.effectView, OS.sel_removeFromSuperview);
        } catch (Throwable ignored) {
        }
        try {
            OS.objc_msgSend(state.effectView, OS.sel_release);
        } catch (Throwable ignored) {
        }
    }

    static void updateFrame(Composite target) {
        if (target == null || target.isDisposed()) return;
        Object value = target.getData(KEY);
        if (!(value instanceof State state)) return;
        try {
            if (target.view == null) return;
            NSRect frame = target.view.bounds();
            OS.objc_msgSend(state.effectView, OS.sel_setFrame_, frame);
        } catch (Throwable ignored) {
        }
    }

    // ── Window-level liquid glass (native bridge) ───────────────────────────
    //
    // These entry points live in the same bundled dylib as the WKWebView
    // bridge (libRigelWKWebView.dylib). One NSVisualEffectView spans the whole
    // contentView, the titlebar is made transparent and the content area
    // extends under it, and a theme-tinted overlay keeps the palette visible
    // through the blur. Anything the Java layout does not paint — chrome
    // strips, sidebar, and the transparent WKWebView hosting rigel://home —
    // shows native frosted glass instead of flat theme colours.

    private interface GlassBridge extends Library {
        int rigel_window_set_glass(Pointer window, int enabled, int material,
                                   double tintAlpha, double r, double g, double b, int dark);
        void rigel_window_drag(Pointer window);
        void rigel_window_zoom(Pointer window);
        int  rigel_window_snapshot(Pointer window, String path);
        void rigel_field_set_glass(Pointer field, int enabled,
                                   double r, double g, double b, double alpha,
                                   double fr, double fg, double fb);
    }

    /** Visual smoke test: pixel snapshot of the whole window (TCC-free). */
    static boolean windowSnapshot(Shell shell, String path) {
        GlassBridge bridge = bridge();
        Pointer window = windowPointer(shell);
        if (bridge == null || window == null || path == null) return false;
        try {
            return bridge.rigel_window_snapshot(window, path) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static volatile GlassBridge glassBridge;
    private static volatile boolean glassBridgeFailed;

    private static GlassBridge bridge() {
        GlassBridge current = glassBridge;
        if (current != null || glassBridgeFailed) return current;
        synchronized (MacVibrancy.class) {
            if (glassBridge != null || glassBridgeFailed) return glassBridge;
            try {
                glassBridge = Native.load(MacWKWebView.bridgeLibraryPath(), GlassBridge.class);
            } catch (Throwable failure) {
                glassBridgeFailed = true;
            }
            return glassBridge;
        }
    }

    private static Pointer windowPointer(Shell shell) {
        if (shell == null || shell.isDisposed()) return null;
        try {
            NSWindow window = shell.view.window();
            if (window == null || window.id == 0) return null;
            return new Pointer(window.id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Installs (or, when {@code enabled} is false, removes) the window-wide
     * vibrancy material. Re-applying updates material/tint in place so theme
     * switches re-tint without rebuilding the native view.
     * Returns true only when glass was requested and is now active.
     */
    static boolean applyWindowGlass(Shell shell, boolean enabled, int material,
                                    double tintAlpha, int tintRgb, boolean dark) {
        GlassBridge bridge = bridge();
        Pointer window = windowPointer(shell);
        if (bridge == null || window == null) return false;
        double r = ((tintRgb >> 16) & 0xFF) / 255.0;
        double g = ((tintRgb >> 8) & 0xFF) / 255.0;
        double b = (tintRgb & 0xFF) / 255.0;
        try {
            int ok = bridge.rigel_window_set_glass(window, enabled ? 1 : 0,
                    material, tintAlpha, r, g, b, dark ? 1 : 0);
            return enabled && ok != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Titlebar-style window move started from a custom drag strip. Must run
     *  inside a SWT MouseDown listener so AppKit still has the NSEvent. */
    static void dragWindow(Shell shell) {
        GlassBridge bridge = bridge();
        Pointer window = windowPointer(shell);
        if (bridge == null || window == null) return;
        try { bridge.rigel_window_drag(window); } catch (Throwable ignored) {}
    }

    /** Standard macOS titlebar double-click behaviour. */
    static void zoomWindow(Shell shell) {
        GlassBridge bridge = bridge();
        Pointer window = windowPointer(shell);
        if (bridge == null || window == null) return;
        try { bridge.rigel_window_zoom(window); } catch (Throwable ignored) {}
    }

    /** Gives the address field a translucent fill over the glass material.
     *  With {@code glass} false the solid theme fill is restored instead. */
    static void styleAddressField(Text field, boolean glass,
                                  int backgroundRgb, double backgroundAlpha,
                                  int foregroundRgb) {
        GlassBridge bridge = bridge();
        if (bridge == null || field == null || field.isDisposed()) return;
        try {
            if (field.view == null || field.view.id == 0) return;
            double r = ((backgroundRgb >> 16) & 0xFF) / 255.0;
            double g = ((backgroundRgb >> 8) & 0xFF) / 255.0;
            double b = (backgroundRgb & 0xFF) / 255.0;
            double fr = ((foregroundRgb >> 16) & 0xFF) / 255.0;
            double fg = ((foregroundRgb >> 8) & 0xFF) / 255.0;
            double fb = (foregroundRgb & 0xFF) / 255.0;
            bridge.rigel_field_set_glass(new Pointer(field.view.id), glass ? 1 : 0,
                    r, g, b, backgroundAlpha, fr, fg, fb);
        } catch (Throwable ignored) {
        }
    }

    private static final class State extends ControlAdapter {
        final long effectView;
        final DisposeListener disposeListener;

        State(long effectView, DisposeListener disposeListener) {
            this.effectView = effectView;
            this.disposeListener = disposeListener;
        }

        @Override public void controlResized(ControlEvent e) {
            if (e.widget instanceof Composite composite) updateFrame(composite);
        }
    }
}
