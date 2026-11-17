package ui.objc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal, dependency-free Objective-C bridge built entirely on the JDK
 * Foreign Function & Memory API. This replaces the JNA/native dylib bridge:
 * no custom native binary is shipped or loaded — only Apple's own frameworks.
 */
public final class ObjC {

    public static final Linker LINKER = Linker.nativeLinker();
    public static final Arena GLOBAL = Arena.ofShared();
    private static final SymbolLookup OBJC = SymbolLookup.libraryLookup(
            "/usr/lib/libobjc.A.dylib", GLOBAL);

    private static final Map<String, MemorySegment> SELECTORS = new ConcurrentHashMap<>();
    private static final Map<String, MemorySegment> CLASSES = new ConcurrentHashMap<>();

    private static final MethodHandle SEL_REGISTER = down(OBJC, "sel_registerName",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle OBJC_GET_CLASS = down(OBJC, "objc_getClass",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle ALLOCATE_CLASS = down(OBJC, "objc_allocateClassPair",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    private static final MethodHandle REGISTER_CLASS = down(OBJC, "objc_registerClassPair",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    private static final MethodHandle ADD_METHOD = down(OBJC, "class_addMethod",
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle ADD_PROTOCOL = down(OBJC, "class_addProtocol",
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    public static final MemorySegment MSG_SEND = OBJC.find("objc_msgSend").orElseThrow();

    private ObjC() {}

    public static MethodHandle down(SymbolLookup lookup, String name, FunctionDescriptor fd) {
        return LINKER.downcallHandle(lookup.find(name).orElseThrow(() ->
                new IllegalStateException("Missing native symbol: " + name)), fd);
    }

    public static MemorySegment sel(String name) {
        return SELECTORS.computeIfAbsent(name, n -> {
            try (Arena scope = Arena.ofConfined()) {
                return (MemorySegment) SEL_REGISTER.invoke(scope.allocateFrom(n));
            } catch (Throwable t) { throw new RuntimeException(t); }
        });
    }

    public static MethodHandle staticHandle(Class<?> owner, String name, MethodType type) {
        try {
            return MethodHandles.lookup().findStatic(owner, name, type);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static MemorySegment call0(MemorySegment receiver, String selector) {
        try {
            return (MemorySegment) sendAddress(0).invoke(receiver, sel(selector));
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static MemorySegment cls(String name) {
        return CLASSES.computeIfAbsent(name, n -> {
            try (Arena scope = Arena.ofConfined()) {
                MemorySegment result = (MemorySegment) OBJC_GET_CLASS.invoke(scope.allocateFrom(n));
                if (result == null || result.address() == 0)
                    throw new IllegalStateException("ObjC class not found: " + n);
                return result;
            } catch (Throwable t) { throw new RuntimeException(t); }
        });
    }

    // ── specialized msgSend handles ─────────────────────────────────────

    private static final Map<String, MethodHandle> SEND_CACHE = new ConcurrentHashMap<>();

    private static MethodHandle send(String key, java.util.function.Supplier<FunctionDescriptor> fd) {
        return SEND_CACHE.computeIfAbsent(key, k -> LINKER.downcallHandle(MSG_SEND, fd.get()));
    }

    public static MethodHandle sendVoid(int args) {
        return send("v" + args, () -> {
            ValueLayout[] layouts = new ValueLayout[args + 2];
            layouts[0] = ValueLayout.ADDRESS; layouts[1] = ValueLayout.ADDRESS;
            for (int i = 2; i < layouts.length; i++) layouts[i] = ValueLayout.ADDRESS;
            return FunctionDescriptor.ofVoid(layouts);
        });
    }

    public static MethodHandle sendAddress(int args) {
        return send("a" + args, () -> {
            ValueLayout[] layouts = new ValueLayout[args + 2];
            layouts[0] = ValueLayout.ADDRESS; layouts[1] = ValueLayout.ADDRESS;
            for (int i = 2; i < layouts.length; i++) layouts[i] = ValueLayout.ADDRESS;
            return FunctionDescriptor.of(ValueLayout.ADDRESS, layouts);
        });
    }

    public static MethodHandle sendInt(int args) {
        return send("i" + args, () -> {
            ValueLayout[] layouts = new ValueLayout[args + 2];
            layouts[0] = ValueLayout.ADDRESS; layouts[1] = ValueLayout.ADDRESS;
            for (int i = 2; i < layouts.length; i++) layouts[i] = ValueLayout.ADDRESS;
            return FunctionDescriptor.of(ValueLayout.JAVA_INT, layouts);
        });
    }

    public static MethodHandle sendLong(int args) {
        return send("l" + args, () -> {
            ValueLayout[] layouts = new ValueLayout[args + 2];
            layouts[0] = ValueLayout.ADDRESS; layouts[1] = ValueLayout.ADDRESS;
            for (int i = 2; i < layouts.length; i++) layouts[i] = ValueLayout.ADDRESS;
            return FunctionDescriptor.of(ValueLayout.JAVA_LONG, layouts);
        });
    }

    public static MethodHandle sendDouble(int args) {
        return send("d" + args, () -> {
            ValueLayout[] layouts = new ValueLayout[args + 2];
            layouts[0] = ValueLayout.ADDRESS; layouts[1] = ValueLayout.ADDRESS;
            for (int i = 2; i < layouts.length; i++) layouts[i] = ValueLayout.ADDRESS;
            return FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, layouts);
        });
    }

    public static MethodHandle sendBool(int args) {
        return send("b" + args, () -> {
            ValueLayout[] layouts = new ValueLayout[args + 2];
            layouts[0] = ValueLayout.ADDRESS; layouts[1] = ValueLayout.ADDRESS;
            for (int i = 2; i < layouts.length; i++) layouts[i] = ValueLayout.ADDRESS;
            return FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, layouts);
        });
    }

    // ── convenience calls ───────────────────────────────────────────────

    public static MemorySegment call(MemorySegment receiver, String selector, Object... args) {
        try {
            Object[] all = new Object[args.length + 2];
            all[0] = receiver; all[1] = sel(selector);
            System.arraycopy(args, 0, all, 2, args.length);
            return (MemorySegment) sendAddress(args.length).invokeWithArguments(all);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static void callVoid(MemorySegment receiver, String selector, Object... args) {
        try {
            Object[] all = new Object[args.length + 2];
            all[0] = receiver; all[1] = sel(selector);
            System.arraycopy(args, 0, all, 2, args.length);
            sendVoid(args.length).invokeWithArguments(all);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static void callVoidInt(MemorySegment receiver, String selector, int value) {
        try {
            MethodHandle h = LINKER.downcallHandle(MSG_SEND,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            h.invoke(receiver, sel(selector), value);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static void callVoidPathBool(MemorySegment receiver, String selector,
                                        MemorySegment path, boolean value) {
        try {
            MethodHandle h = LINKER.downcallHandle(MSG_SEND,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
            h.invoke(receiver, sel(selector), path, value);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static void callVoidBool(MemorySegment receiver, String selector, boolean value) {
        try {
            MethodHandle h = LINKER.downcallHandle(MSG_SEND,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
            h.invoke(receiver, sel(selector), value);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static int callInt(MemorySegment receiver, String selector, Object... args) {
        try {
            Object[] all = new Object[args.length + 2];
            all[0] = receiver; all[1] = sel(selector);
            System.arraycopy(args, 0, all, 2, args.length);
            return (int) sendInt(args.length).invokeWithArguments(all);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static long callLong(MemorySegment receiver, String selector, Object... args) {
        try {
            Object[] all = new Object[args.length + 2];
            all[0] = receiver; all[1] = sel(selector);
            System.arraycopy(args, 0, all, 2, args.length);
            return (long) sendLong(args.length).invokeWithArguments(all);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static double callDouble(MemorySegment receiver, String selector, Object... args) {
        try {
            Object[] all = new Object[args.length + 2];
            all[0] = receiver; all[1] = sel(selector);
            System.arraycopy(args, 0, all, 2, args.length);
            return (double) sendDouble(args.length).invokeWithArguments(all);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static boolean callBool(MemorySegment receiver, String selector, Object... args) {
        try {
            Object[] all = new Object[args.length + 2];
            all[0] = receiver; all[1] = sel(selector);
            System.arraycopy(args, 0, all, 2, args.length);
            return (boolean) sendBool(args.length).invokeWithArguments(all);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    // ── NSString / layout helpers ───────────────────────────────────────

    public static MemorySegment nsString(String value) {
        if (value == null) value = "";
        try (Arena scope = Arena.ofConfined()) {
            MemorySegment result = (MemorySegment) sendAddress(1).invoke(cls("NSString"),
                    sel("stringWithUTF8String:"), scope.allocateFrom(value));
            if (result == null || result.address() == 0) return GLOBAL.allocateFrom("");
            return result;
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    /** NSString -> Java String. Allocates native UTF8 buffer in GLOBAL. */
    public static String javaString(MemorySegment nsString) {
        if (nsString == null || nsString.address() == 0) return "";
        try {
            MemorySegment utf8 = (MemorySegment) sendAddress(0).invoke(nsString, sel("UTF8String"));
            if (utf8 == null || utf8.address() == 0) return "";
            return utf8.reinterpret(1 << 20).getString(0);
        } catch (Throwable t) { return ""; }
    }

    public static final MemoryLayout CGRect = MemoryLayout.structLayout(
            ValueLayout.JAVA_DOUBLE.withName("x"), ValueLayout.JAVA_DOUBLE.withName("y"),
            ValueLayout.JAVA_DOUBLE.withName("w"), ValueLayout.JAVA_DOUBLE.withName("h"));

    public static MemorySegment rect(Arena arena, double x, double y, double w, double h) {
        MemorySegment r = arena.allocate(CGRect);
        r.set(ValueLayout.JAVA_DOUBLE, 0, x); r.set(ValueLayout.JAVA_DOUBLE, 8, y);
        r.set(ValueLayout.JAVA_DOUBLE, 16, w); r.set(ValueLayout.JAVA_DOUBLE, 24, h);
        return r;
    }

    // ── blocks ──────────────────────────────────────────────────────────

    private static final MemoryLayout BLOCK = MemoryLayout.structLayout(
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    private static final MemoryLayout BLOCK_DESC = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    private static final MemorySegment STACK_BLOCK_ISA = LINKER.defaultLookup()
            .find("_NSConcreteStackBlock").orElseThrow();

    /** Create a stack Objective-C block whose invoke is a Java upcall stub. */
    public static MemorySegment block(MethodHandle body, FunctionDescriptor signature, Arena owner) {
        MemorySegment imp = LINKER.upcallStub(body, signature, owner);
        MemorySegment desc = owner.allocate(BLOCK_DESC);
        desc.set(ValueLayout.JAVA_LONG, 8L, 32L); // sizeof block
        MemorySegment b = owner.allocate(BLOCK);
        b.set(ValueLayout.ADDRESS, 0L, STACK_BLOCK_ISA);
        b.set(ValueLayout.JAVA_INT, 8L, 0);
        b.set(ValueLayout.ADDRESS, 16L, imp);
        b.set(ValueLayout.ADDRESS, 24L, desc);
        return b;
    }

    // ── dynamic classes (delegates) ─────────────────────────────────────

    public static MemorySegment createClass(String name, String superclass) {
        try (Arena scope = Arena.ofConfined()) {
            MemorySegment result = (MemorySegment) ALLOCATE_CLASS.invoke(
                    cls(superclass), scope.allocateFrom(name), 0L);
            if (result == null || result.address() == 0)
                throw new IllegalStateException("Could not allocate ObjC class " + name);
            return result;
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static void addMethod(MemorySegment clazz, String selector, MethodHandle impl,
                                 FunctionDescriptor signature, String objcTypes) {
        try (Arena scope = Arena.ofConfined()) {
            MemorySegment imp = LINKER.upcallStub(impl, signature, GLOBAL);
            boolean ok = (boolean) ADD_METHOD.invoke(clazz, sel(selector), imp, scope.allocateFrom(objcTypes));
            if (!ok) throw new IllegalStateException("class_addMethod failed for " + selector);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static void addProtocol(MemorySegment clazz, String protocol) {
        try {
            ADD_PROTOCOL.invoke(clazz, objc_getProtocol(protocol));
        } catch (Throwable ignored) {
        }
    }

    private static MemorySegment objc_getProtocol(String name) throws Throwable {
        MethodHandle fn = down(OBJC, "objc_getProtocol",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        try (Arena scope = Arena.ofConfined()) {
            return (MemorySegment) fn.invoke(scope.allocateFrom(name));
        }
    }

    public static void registerClass(MemorySegment clazz) {
        try { REGISTER_CLASS.invoke(clazz); } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static MemorySegment allocInit(MemorySegment clazz) {
        try {
            MemorySegment instance = (MemorySegment) sendAddress(0).invoke(clazz, sel("alloc"));
            return (MemorySegment) sendAddress(0).invoke(instance, sel("init"));
        } catch (Throwable t) { throw new RuntimeException(t); }
    }
}
