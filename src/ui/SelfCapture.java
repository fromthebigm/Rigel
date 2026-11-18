package ui;

import org.eclipse.swt.widgets.Shell;

import java.lang.foreign.*;
import java.lang.invoke.*;

/** In-process window screenshot via CoreGraphics FFM. Works without TCC
 *  because the process captures its own window. Diagnostic mode only. */
public final class SelfCapture {
    private static final Linker L = Linker.nativeLinker();
    private static final Arena G = Arena.ofShared();
    private static final SymbolLookup OBJC = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", G);
    private static final SymbolLookup CG = SymbolLookup.libraryLookup(
            "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", G);

    private static final MethodHandle CLS = down(OBJC, "objc_getClass",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEL = down(OBJC, "sel_registerName",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEND0 = down(OBJC, "objc_msgSend",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEND1 = down(OBJC, "objc_msgSend",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEND_COUNT = down(OBJC, "objc_msgSend",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEND_BOOL = down(OBJC, "objc_msgSend",
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
    private static final MethodHandle REPR = down(OBJC, "objc_msgSend",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

    private static final MemoryLayout RECT = MemoryLayout.structLayout(
            ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
            ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE);
    private static final MethodHandle COPY_INFO = down(CG, "CGWindowListCopyWindowInfo",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle CREATE_IMAGE = down(CG, "CGWindowListCreateImage",
            FunctionDescriptor.of(ValueLayout.ADDRESS, RECT, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    private SelfCapture() {}

    private static long findWindowNumber(String title) {
        try {
            MemorySegment array = (MemorySegment) COPY_INFO.invoke(1, 0);
            if (array == null || array.address() == 0) return 0;
            long count = (long) SEND_COUNT.invoke(array, sel("count"));
            MethodHandle objectAt = down(OBJC, "objc_msgSend",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            for (long i = 0; i < count; i++) {
                MemorySegment dict = (MemorySegment) objectAt.invoke(array, sel("objectAtIndex:"), i);
                if (dict == null || dict.address() == 0) continue;
                MemorySegment name = (MemorySegment) SEND1.invoke(dict, sel("objectForKey:"), ns("kCGWindowName"));
                String n = name == null || name.address() == 0 ? "" : javaString(name);
                MemorySegment numObj = (MemorySegment) SEND1.invoke(dict, sel("objectForKey:"), ns("kCGWindowNumber"));
                if (numObj == null || numObj.address() == 0) continue;
                long num = (long) down(OBJC, "objc_msgSend", FunctionDescriptor.of(
                        ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
                        .invoke(numObj, sel("longLongValue"));
                if (n.equals(title)) return num;
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static String javaString(MemorySegment seg) {
        if (seg == null || seg.address() == 0) return "";
        try {
            MemorySegment utf8 = (MemorySegment) SEND0.invoke(seg, sel("UTF8String"));
            if (utf8 == null || utf8.address() == 0) return "";
            return utf8.reinterpret(1 << 16).getString(0);
        } catch (Throwable t) { return ""; }
    }

    public static boolean capture(Shell shell, String path) {
        long id = findWindowNumber(shell.getText());
        System.out.println("[shot] windowId=" + id + " for " + shell.getText());
        return capture(id, path);
    }

    public static boolean captureScreen(String path) {
        return capture(0, path);
    }

    private static boolean capture(long windowId, String path) {
        try {
            MemorySegment rect = G.allocate(RECT);
            rect.set(ValueLayout.JAVA_DOUBLE, 0L, 0.0);
            rect.set(ValueLayout.JAVA_DOUBLE, 8L, 0.0);
            rect.set(ValueLayout.JAVA_DOUBLE, 16L, 1470.0);
            rect.set(ValueLayout.JAVA_DOUBLE, 24L, 800.0);
            // Option 1 = on-screen windows; windowID as NSWindow pointer works
            // when the caller owns the window (verified in-app).
            MemorySegment img = (MemorySegment) CREATE_IMAGE.invoke(rect, windowId == 0 ? 1 : (1 << 3), (int) windowId, 0);
            System.out.println("[shot] cgImage=" + (img == null ? -1 : img.address()));
            if (img == null || img.address() == 0) return false;

            MemorySegment repClass = (MemorySegment) CLS.invoke(alloc("NSBitmapImageRep"));
            MemorySegment rep = (MemorySegment) SEND0.invoke(repClass, sel("alloc"));
            rep = (MemorySegment) SEND1.invoke(rep, sel("initWithCGImage:"), img);
            MemorySegment empty = (MemorySegment) SEND0.invoke(
                    (MemorySegment) CLS.invoke(alloc("NSDictionary")), sel("dictionary"));
            MemorySegment png = (MemorySegment) REPR.invoke(rep,
                    sel("representationUsingType:properties:"), 4L, empty);
            System.out.println("[shot] png=" + (png == null ? -1 : png.address()));
            if (png == null || png.address() == 0) return false;
            return (boolean) SEND_BOOL.invoke(png, sel("writeToFile:atomically:"), ns(path), true);
        } catch (Throwable t) {
            return false;
        }
    }

    private static MethodHandle down(SymbolLookup l, String n, FunctionDescriptor fd) {
        return L.downcallHandle(l.find(n).orElseThrow(() -> new IllegalStateException("missing " + n)), fd);
    }
    private static MemorySegment alloc(String s) { return G.allocateFrom(s); }
    private static MemorySegment sel(String s) {
        try { return (MemorySegment) SEL.invoke(alloc(s)); } catch (Throwable t) { throw new RuntimeException(t); }
    }
    private static MemorySegment ns(String s) {
        try {
            return (MemorySegment) SEND1.invoke((MemorySegment) CLS.invoke(alloc("NSString")),
                    sel("stringWithUTF8String:"), alloc(s));
        } catch (Throwable t) { throw new RuntimeException(t); }
    }
}
