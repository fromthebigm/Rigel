import java.lang.foreign.*;
import java.lang.invoke.*;

/** Probe: can we create a real Objective-C class + method entirely from Java FFM? */
public class ObjCDynamicTest {
    public static void main(String[] args) throws Throwable {
        Linker linker = Linker.nativeLinker();
        Arena arena = Arena.ofShared();
        SymbolLookup objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", arena);

        MethodHandle allocate = linker.downcallHandle(objc.find("objc_allocateClassPair").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        MethodHandle register = linker.downcallHandle(objc.find("objc_registerClassPair").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        MethodHandle addMethod = linker.downcallHandle(objc.find("class_addMethod").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MethodHandle getClass = linker.downcallHandle(objc.find("objc_getClass").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MethodHandle selReg = linker.downcallHandle(objc.find("sel_registerName").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MethodHandle msgSend = linker.downcallHandle(objc.find("objc_msgSend").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        MemorySegment nsObject = (MemorySegment) getClass.invoke(arena.allocateFrom("NSObject"));
        MemorySegment name = arena.allocateFrom("RigelJavaProbe");
        MemorySegment cls = (MemorySegment) allocate.invoke(nsObject, name, 0L);
        System.out.println("allocated class=" + cls.address());

        MethodHandle impl = MethodHandles.lookup().findStatic(ObjCDynamicTest.class, "probeMethod",
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class));
        MemorySegment imp = linker.upcallStub(impl,
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS), arena);
        MemorySegment selHello = (MemorySegment) selReg.invoke(arena.allocateFrom("hello"));
        boolean added = (boolean) addMethod.invoke(cls, selHello, imp, arena.allocateFrom("v@:"));
        System.out.println("class_addMethod=" + added);
        register.invoke(cls);
        System.out.println("registered");

        MemorySegment allocSel = (MemorySegment) selReg.invoke(arena.allocateFrom("alloc"));
        MemorySegment initSel = (MemorySegment) selReg.invoke(arena.allocateFrom("init"));
        MemorySegment instance = (MemorySegment) msgSend.invoke(cls, allocSel);
        instance = (MemorySegment) msgSend.invoke(instance, initSel);
        MethodHandle callVoid = linker.downcallHandle(objc.find("objc_msgSend").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        callVoid.invoke(instance, selHello);
        System.out.println("called hello on instance=" + instance.address());
    }

    public static void probeMethod(MemorySegment self, MemorySegment cmd) {
        System.out.println("JAVA UPCALL RAN self=" + self.address());
    }
}
