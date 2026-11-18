package ui.objc;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/** Pure-FFM replacement for the window-glass leg of the WK dylib. */
public final class GlassFFM {

    private static final long GLASS_TAG = 0x52494745; // "RIGE"
    private GlassFFM() {}

    public static boolean applyWindowGlass(long windowPtr, boolean enabled, int material,
                                           double tintAlpha, int tintRgb, boolean dark) {
        if (windowPtr == 0) return false;
        try {
            MemorySegment window = MemorySegment.ofAddress(windowPtr);
            MemorySegment content = ObjC.call0(window, "contentView");
            if (content == null || content.address() == 0) return false;
            MemorySegment existing = (MemorySegment) ObjC.sendAddress(1).invoke(content,
                    ObjC.sel("viewWithTag:"), MemorySegment.ofAddress(GLASS_TAG));
            if (existing != null && existing.address() != 0)
                ObjC.callVoid(existing, "removeFromSuperview");
            if (!enabled) return true;

            MemorySegment effect = ObjC.allocInit(ObjC.cls("NSVisualEffectView"));
            MemorySegment bounds = (MemorySegment) ObjC.sendAddress(0).invoke(content, ObjC.sel("bounds"));
            MethodHandle setFrame = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ObjC.CGRect));
            setFrame.invoke(effect, ObjC.sel("setFrame:"), bounds);
            ObjC.callVoidInt(effect, "setAutoresizingMask:", 18);
            ObjC.callVoidInt(effect, "setBlendingMode:", 2); // behindWindow
            ObjC.callVoidInt(effect, "setMaterial:", mappedMaterial(material, dark));
            ObjC.callVoidInt(effect, "setState:", 1); // active
            ObjC.callVoidInt(effect, "setTag:", (int) GLASS_TAG);
            MethodHandle setAlpha = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE));
            setAlpha.invoke(effect, ObjC.sel("setAlphaValue:"), Math.max(0, Math.min(1, tintAlpha)));
            MethodHandle insert = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            insert.invoke(content, ObjC.sel("insertSubview:atIndex:"), effect, 0L);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void dragWindow(long windowPtr) {
        try {
            MemorySegment window = MemorySegment.ofAddress(windowPtr);
            MemorySegment app = ObjC.call0(ObjC.cls("NSApplication"), "sharedApplication");
            MemorySegment event = ObjC.call0(app, "currentEvent");
            ObjC.callVoid(window, "performWindowDragWithEvent:", event);
        } catch (Throwable ignored) {}
    }

    public static void zoomWindow(long windowPtr) {
        try {
            MemorySegment window = MemorySegment.ofAddress(windowPtr);
            ObjC.callVoid(window, "zoom:", MemorySegment.NULL);
        } catch (Throwable ignored) {}
    }

    /** Clear an NSScrollView/NSTextField's own plate over glass, or restore
     *  the explicit theme fill when glass is off. */
    public static boolean styleView(long viewPtr, boolean glass, int backgroundRgb,
                                    double backgroundAlpha, int foregroundRgb) {
        if (viewPtr == 0) return false;
        try {
            MemorySegment view = MemorySegment.ofAddress(viewPtr);
            MethodHandle colorWith = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                            ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE));
            double r, g, b, a;
            if (glass) { r = g = b = 0; a = 0; }
            else {
                r = ((backgroundRgb >> 16) & 0xFF) / 255.0;
                g = ((backgroundRgb >> 8) & 0xFF) / 255.0;
                b = (backgroundRgb & 0xFF) / 255.0;
                a = backgroundAlpha;
            }
            MemorySegment color = (MemorySegment) colorWith.invoke(ObjC.cls("NSColor"),
                    ObjC.sel("colorWithCalibratedRed:green:blue:alpha:"), r, g, b, a);
            ObjC.callVoid(view, "setBackgroundColor:", color);
            if (foregroundRgb != 0) {
                double fr = ((foregroundRgb >> 16) & 0xFF) / 255.0;
                double fg = ((foregroundRgb >> 8) & 0xFF) / 255.0;
                double fb = (foregroundRgb & 0xFF) / 255.0;
                MemorySegment fgColor = (MemorySegment) colorWith.invoke(ObjC.cls("NSColor"),
                        ObjC.sel("colorWithCalibratedRed:green:blue:alpha:"), fr, fg, fb, 1.0);
                ObjC.callVoid(view, "setTextColor:", fgColor);
            }
            return true;
        } catch (Throwable t) { return false; }
    }

    private static int mappedMaterial(int material, boolean dark) {
        // NSVisualEffectMaterial mapping: 0 appearance-based, 3 dark, 4 light,
        // 9 hudWindow. Rigel's material ids are palette-specific; keep the
        // appearance-based default which follows the system automatically.
        return dark ? 3 : 4;
    }
}
