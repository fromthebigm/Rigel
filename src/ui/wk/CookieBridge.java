package ui.wk;

import ui.objc.ObjC;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;

/** Pure-FFM port of RigelCookieStoreFetchAll / rigel_wk_cookies_save / restore. */
public final class CookieBridge {

    private static volatile MemorySegment fetchedCookies;
    private static volatile int remaining;

    private CookieBridge() {}

    static int save(String path) {
        try {
            MemorySegment store = cookieStore();
            if (store == null) return 0;
            MemorySegment blockImp = ObjC.LINKER.upcallStub(
                    ObjC.staticHandle(CookieBridge.class, "fetchDone",
                            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS), ObjC.GLOBAL);
            MemorySegment completion = makeBlock(blockImp);
            fetchedCookies = null;
            ObjC.callVoid(store, "getAllCookies:", completion);
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (fetchedCookies == null && System.nanoTime() < deadline) pump(0.05);
            if (System.getProperty("rigel.ffm.diag") != null)
                System.out.println("[ffm] cookies fetched=" + (fetchedCookies == null ? -1 : fetchedCookies.address()));
            if (fetchedCookies == null || fetchedCookies.address() == 0) return 0;

            MemorySegment entries = (MemorySegment) ObjC.sendAddress(0).invoke(
                    ObjC.cls("NSMutableArray"), ObjC.sel("array"));
            long count = ObjC.callLong(fetchedCookies, "count");
            for (long i = 0; i < count; i++) {
                MemorySegment cookie = (MemorySegment) ObjC.sendAddress(1).invoke(
                        fetchedCookies, ObjC.sel("objectAtIndex:"), i);
                MemorySegment props = (MemorySegment) ObjC.sendAddress(0).invoke(cookie, ObjC.sel("properties"));
                if (props != null && props.address() != 0)
                    ObjC.callVoid(entries, "addObject:", props);
            }
            MethodHandle write = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
            boolean ok = (boolean) write.invoke(entries, ObjC.sel("writeToFile:atomically:"),
                    ObjC.nsString(path), true);
            return ok ? 1 : 0;
        } catch (Throwable t) {
            if (System.getProperty("rigel.ffm.diag") != null) t.printStackTrace();
            return 0;
        }
    }

    static int restore(String path) {
        try {
            MemorySegment entries = (MemorySegment) ObjC.sendAddress(1).invoke(
                    ObjC.cls("NSArray"), ObjC.sel("arrayWithContentsOfFile:"), ObjC.nsString(path));
            if (entries == null || entries.address() == 0) return 0;
            long count = ObjC.callLong(entries, "count");
            if (count == 0) return 0;
            MemorySegment store = cookieStore();
            if (store == null) return 0;

            MemorySegment blockImp = ObjC.LINKER.upcallStub(
                    ObjC.staticHandle(CookieBridge.class, "setDone",
                            MethodType.methodType(void.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), ObjC.GLOBAL);
            MemorySegment completion = makeBlock(blockImp);

            remaining = (int) count;
            for (long i = 0; i < count; i++) {
                MemorySegment props = (MemorySegment) ObjC.sendAddress(1).invoke(
                        entries, ObjC.sel("objectAtIndex:"), i);
                MemorySegment cookie = (MemorySegment) ObjC.sendAddress(1).invoke(
                        ObjC.cls("NSHTTPCookie"), ObjC.sel("cookieWithProperties:"), props);
                if (cookie == null || cookie.address() == 0) {
                    remaining--;
                    continue;
                }
                ObjC.callVoid(store, "setCookie:completionHandler:", cookie, completion);
            }
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (remaining > 0 && System.nanoTime() < deadline) pump(0.05);
            return (int) count;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void fetchDone(MemorySegment block, MemorySegment cookies) {
        fetchedCookies = cookies;
    }

    public static void setDone(MemorySegment block) {
        remaining--;
    }

    private static MemorySegment cookieStore() {
        try {
            MemorySegment ds = (MemorySegment) ObjC.sendAddress(0).invoke(
                    ObjC.cls("WKWebsiteDataStore"), ObjC.sel("defaultDataStore"));
            return (MemorySegment) ObjC.sendAddress(0).invoke(ds, ObjC.sel("httpCookieStore"));
        } catch (Throwable t) { return null; }
    }

    private static MemorySegment makeBlock(MemorySegment invoke) {
        MemoryLayout layout = MemoryLayout.structLayout(
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        MemoryLayout descLayout = MemoryLayout.structLayout(
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        MemorySegment isa = ObjC.LINKER.defaultLookup().find("_NSConcreteStackBlock").orElseThrow();
        MemorySegment desc = ObjC.GLOBAL.allocate(descLayout);
        desc.set(ValueLayout.JAVA_LONG, 8L, 32L);
        MemorySegment b = ObjC.GLOBAL.allocate(layout);
        b.set(ValueLayout.ADDRESS, 0L, isa);
        b.set(ValueLayout.JAVA_INT, 8L, 0);
        b.set(ValueLayout.ADDRESS, 16L, invoke);
        b.set(ValueLayout.ADDRESS, 24L, desc);
        return b;
    }

    private static void pump(double seconds) {
        try {
            SymbolLookup appkit = SymbolLookup.libraryLookup(
                    "/System/Library/Frameworks/AppKit.framework/AppKit", ObjC.GLOBAL);
            MethodHandle mainRunLoop = ObjC.LINKER.downcallHandle(
                    appkit.find("NSRunLoopMain").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
            MemorySegment loop = (MemorySegment) mainRunLoop.invoke();
            MethodHandle dateWith = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE));
            MemorySegment date = (MemorySegment) dateWith.invoke(ObjC.cls("NSDate"),
                    ObjC.sel("dateWithTimeIntervalSinceNow:"), seconds);
            ObjC.callVoid(loop, "runMode:beforeDate:", ObjC.nsString("NSDefaultRunLoopMode"), date);
        } catch (Throwable ignored) {}
    }
}
