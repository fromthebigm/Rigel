package ui;

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

final class MacVibrancy {
    static final int MATERIAL_TITLEBAR = 3;
    static final int MATERIAL_SIDEBAR = 7;

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
