package ui.objc;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline FFM test: real libobjc, no GUI/WebKit process required. */
public final class ObjCBlockLayoutTest {
    private static final AtomicInteger CALLS = new AtomicInteger();

    public static void invoked(MemorySegment block) {
        CALLS.incrementAndGet();
    }

    public static void main(String[] args) throws Throwable {
        MethodHandle body = ObjC.staticHandle(ObjCBlockLayoutTest.class, "invoked",
                MethodType.methodType(void.class, MemorySegment.class));
        MemorySegment block = ObjC.transientBlock(body,
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        MemorySegment invoke = ObjC.blockInvoke(block);
        if (invoke == null || invoke.address() == 0)
            throw new AssertionError("block invoke pointer is null");
        MethodHandle call = ObjC.LINKER.downcallHandle(invoke,
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        call.invoke(block);
        if (CALLS.get() != 1) throw new AssertionError("upcall count=" + CALLS.get());
        ObjC.closeBlock(block);
        ObjC.closeBlock(block); // idempotent
        System.out.println("ObjC block layout/lifetime checks passed");
    }
}
