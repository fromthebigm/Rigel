package ui;

import org.eclipse.swt.widgets.Shell;

import java.lang.foreign.*;
import java.lang.invoke.*;

/** AppKit window chrome: transparent full-size title bar (SwiftUI
 *  .windowStyle(.hiddenTitleBar) look) without a custom dylib. */
public final class WindowChrome {
    private static final Linker L = Linker.nativeLinker();
    private static final Arena G = Arena.ofShared();
    private static final SymbolLookup OBJC = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", G);

    private static final MethodHandle CLS = down(OBJC, "objc_getClass",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEL = down(OBJC, "sel_registerName",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEND0 = down(OBJC, "objc_msgSend",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEND1 = down(OBJC, "objc_msgSend",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEND_INT = down(OBJC, "objc_msgSend",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SET_BOOL = down(OBJC, "objc_msgSend",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
    private static final MethodHandle SET_INT = down(OBJC, "objc_msgSend",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    private WindowChrome() {}

    public static void transparentTitleBar(Shell shell) {
        try {
            long ptr = shell.view.window().id;
            if (ptr == 0) return;
            MemorySegment window = MemorySegment.ofAddress(ptr);

            // NSWindowStyleMaskFullSizeContentView = 1 << 15
            int mask = (int) SEND_INT.invoke(window, sel("styleMask"));
            SET_INT.invoke(window, sel("setStyleMask:"), mask | (1 << 15));
            SET_BOOL.invoke(window, sel("setTitlebarAppearsTransparent:"), true);
            SET_BOOL.invoke(window, sel("setOpaque:"), false);
            SET_BOOL.invoke(window, sel("setMovableByWindowBackground:"), true);
            MemorySegment clear = (MemorySegment) SEND0.invoke(
                    (MemorySegment) CLS.invoke(alloc("NSColor")), sel("clearColor"));
            SEND1.invoke(window, sel("setBackgroundColor:"), clear);
        } catch (Throwable ignored) {
        }
    }

    private static MethodHandle down(SymbolLookup l, String n, FunctionDescriptor fd) {
        return L.downcallHandle(l.find(n).orElseThrow(() -> new IllegalStateException("missing " + n)), fd);
    }
    private static MemorySegment alloc(String s) { return G.allocateFrom(s); }
    private static MemorySegment sel(String s) {
        try { return (MemorySegment) SEL.invoke(alloc(s)); } catch (Throwable t) { throw new RuntimeException(t); }
    }
}
