import java.lang.foreign.*;
import java.lang.invoke.*;

public class ObjCBlockCallTest {
    public static void main(String[] args) throws Throwable {
        Linker linker = Linker.nativeLinker();
        Arena arena = Arena.ofShared();
        SymbolLookup objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", arena);
        MethodHandle selReg = linker.downcallHandle(objc.find("sel_registerName").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MethodHandle getClass = linker.downcallHandle(objc.find("objc_getClass").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MethodHandle msgSend = linker.downcallHandle(objc.find("objc_msgSend").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MethodHandle msgSend3 = linker.downcallHandle(objc.find("objc_msgSend").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        MemorySegment isa = Linker.nativeLinker().defaultLookup().find("_NSConcreteStackBlock").orElseThrow();
        MemoryLayout blockLayout = MemoryLayout.structLayout(
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        MemoryLayout descLayout = MemoryLayout.structLayout(
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        MemorySegment desc = arena.allocate(descLayout);
        desc.set(ValueLayout.JAVA_LONG, 8L, 32L); // size

        MethodHandle body = MethodHandles.lookup().findStatic(ObjCBlockCallTest.class, "body",
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                        long.class, MemorySegment.class));
        MemorySegment invoke = linker.upcallStub(body,
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG, ValueLayout.ADDRESS), arena);
        MemorySegment block = arena.allocate(blockLayout);
        block.set(ValueLayout.ADDRESS, 0L, isa);
        block.set(ValueLayout.JAVA_INT, 8L, 0);
        block.set(ValueLayout.ADDRESS, 16L, invoke);
        block.set(ValueLayout.ADDRESS, 24L, desc);

        MemorySegment nsArrayClass = (MemorySegment) getClass.invoke(arena.allocateFrom("NSMutableArray"));
        MemorySegment allocSel = (MemorySegment) selReg.invoke(arena.allocateFrom("alloc"));
        MemorySegment initSel = (MemorySegment) selReg.invoke(arena.allocateFrom("init"));
        MemorySegment arr = (MemorySegment) msgSend.invoke(nsArrayClass, allocSel);
        arr = (MemorySegment) msgSend.invoke(arr, initSel);
        MemorySegment nsStringClass = (MemorySegment) getClass.invoke(arena.allocateFrom("NSString"));
        MethodHandle msgSend2 = linker.downcallHandle(objc.find("objc_msgSend").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MemorySegment str = (MemorySegment) msgSend2.invoke(nsStringClass,
                (MemorySegment) selReg.invoke(arena.allocateFrom("stringWithUTF8String:")),
                arena.allocateFrom("hello"));
        MethodHandle addVoid = linker.downcallHandle(objc.find("objc_msgSend").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        addVoid.invoke(arr, (MemorySegment) selReg.invoke(arena.allocateFrom("addObject:")), str);
        MemorySegment enumSel = (MemorySegment) selReg.invoke(arena.allocateFrom("enumerateObjectsUsingBlock:"));
        msgSend3.invoke(arr, enumSel, block);
        System.out.println("enumerate done");
    }

    public static void body(MemorySegment block, MemorySegment obj, long idx, MemorySegment stop) {
        System.out.println("block invoked idx=" + idx);
    }
}
