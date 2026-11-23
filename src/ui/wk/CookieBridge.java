package ui.wk;

import ui.objc.ObjC;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Pure-FFM port of RigelCookieStoreFetchAll / rigel_wk_cookies_save / restore. */
public final class CookieBridge {

    private static volatile int remaining;
    private static volatile CountDownLatch saveLatch;
    private static volatile int saveOk;
    private static volatile boolean shuttingDown;

    public static void markShuttingDown() { shuttingDown = true; }

    private CookieBridge() {}

    public static int save(String path) {
        if (shuttingDown) return 0; // main queue may be gone; never block exit
        saveLatch = new CountDownLatch(1);
        saveOk = 0;
        if (!dispatchMain(path)) {
            return 0;
        }
        try { saveLatch.await(10, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return 0; }
        if (System.getProperty("rigel.ffm.diag") != null)
            System.out.println("[ffm] cookie save result=" + saveOk + " path=" + path);
        return saveOk;
    }

    /** Runs on the main queue: fetch cookies and write the plist entirely
     *  there, so no autoreleased WebKit object crosses a thread boundary. */
    public static void saveOnMain(String path, MemorySegment block) {
        try {
            MemorySegment store = cookieStore();
            if (store == null) { saveOk = 0; saveLatch.countDown(); return; }
            MemorySegment completion = ObjC.block(
                    java.lang.invoke.MethodHandles.insertArguments(
                            ObjC.staticHandle(CookieBridge.class, "saveDone",
                                    MethodType.methodType(void.class, String.class,
                                            MemorySegment.class, MemorySegment.class)),
                            0, path),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS), ObjC.GLOBAL);
            ObjC.callVoid(store, "getAllCookies:", completion);
        } catch (Throwable t) {
            if (System.getProperty("rigel.ffm.diag") != null) t.printStackTrace();
            saveOk = 0;
            saveLatch.countDown();
        }
    }

    public static void saveDone(String path, MemorySegment block, MemorySegment cookies) {
        try {
            if (cookies == null || cookies.address() == 0) { saveOk = 0; return; }
            MemorySegment entries = (MemorySegment) ObjC.sendAddress(0).invoke(
                    ObjC.cls("NSMutableArray"), ObjC.sel("array"));
            long count = ObjC.callLong(cookies, "count");
            MethodHandle objectAt = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            long ok = 0, skipped = 0;
            for (long i = 0; i < count; i++) {
                try {
                    MemorySegment cookie = (MemorySegment) objectAt.invoke(
                            cookies, ObjC.sel("objectAtIndex:"), i);
                    if (cookie == null || cookie.address() == 0) { skipped++; continue; }
                    MemorySegment props = (MemorySegment) ObjC.sendAddress(0).invoke(cookie, ObjC.sel("properties"));
                    if (props != null && props.address() != 0) {
                        ObjC.callVoid(entries, "addObject:", props);
                        ok++;
                    } else {
                        skipped++;
                        if (System.getProperty("rigel.ffm.diag") != null)
                            System.out.println("[ffm] cookie save: skipped malformed cookie at " + i);
                    }
                } catch (Throwable one) {
                    // One poisoned cookie must not abort the whole snapshot.
                    skipped++;
                    if (System.getProperty("rigel.ffm.diag") != null)
                        System.out.println("[ffm] cookie save: skipped failed cookie at " + i + ": " + one);
                }
            }
            MethodHandle write = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
            boolean wrote = (boolean) write.invoke(entries, ObjC.sel("writeToFile:atomically:"),
                    ObjC.nsString(path), true);
            saveOk = wrote ? 1 : 0;
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(path + ".manifest.json"),
                        "{\"count\":" + count + ",\"ok\":" + ok + ",\"skipped\":" + skipped
                                + ",\"wrote\":" + wrote + "}\n");
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            if (System.getProperty("rigel.ffm.diag") != null) t.printStackTrace();
            saveOk = 0;
        } finally {
            saveLatch.countDown();
        }
    }

    private static volatile String pendingSavePath;
    private static volatile MemorySegment cookieSelectorTarget;

    private static boolean dispatchMain(String path) {
        try {
            pendingSavePath = path;
            if (cookieSelectorTarget == null) {
                MemorySegment cls = ObjC.createClass("RigelJavaCookieSaver", "NSObject");
                ObjC.addMethod(cls, "rigelSaveCookiesNow",
                        ObjC.staticHandle(CookieBridge.class, "saveNow",
                                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class)),
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS), "v@:");
                ObjC.registerClass(cls);
                cookieSelectorTarget = ObjC.allocInit(cls);
                ObjC.callVoid(cookieSelectorTarget, "retain");
            }
            MethodHandle perform = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
            perform.invoke(cookieSelectorTarget,
                    ObjC.sel("performSelectorOnMainThread:withObject:waitUntilDone:"),
                    ObjC.sel("rigelSaveCookiesNow"), MemorySegment.NULL, false);
            return true;
        } catch (Throwable t) {
            if (System.getProperty("rigel.ffm.diag") != null)
                System.out.println("[ffm] main-thread dispatch failed: " + t);
            return false;
        }
    }

    public static void saveNow(MemorySegment self, MemorySegment cmd) {
        saveOnMain(pendingSavePath, MemorySegment.NULL);
    }

    public static int restore(String path) {
        try {
            MemorySegment entries = (MemorySegment) ObjC.sendAddress(1).invoke(
                    ObjC.cls("NSArray"), ObjC.sel("arrayWithContentsOfFile:"), ObjC.nsString(path));
            if (entries == null || entries.address() == 0) return 0;
            long count = ObjC.callLong(entries, "count");
            if (System.getProperty("rigel.ffm.diag") != null)
                System.out.println("[ffm] cookie restore entries=" + count);
            if (count == 0) return 0;
            MemorySegment store = cookieStore();
            if (store == null) return 0;

            MemorySegment blockImp = ObjC.LINKER.upcallStub(
                    ObjC.staticHandle(CookieBridge.class, "setDone",
                            MethodType.methodType(void.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), ObjC.GLOBAL);
            MemorySegment completion = makeBlock(blockImp);

            remaining = (int) count;
            java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
            MethodHandle objectAt = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            for (long i = 0; i < count; i++) {
                try {
                    MemorySegment props = (MemorySegment) objectAt.invoke(
                            entries, ObjC.sel("objectAtIndex:"), i);
                    MemorySegment cookie = (MemorySegment) ObjC.sendAddress(1).invoke(
                            ObjC.cls("NSHTTPCookie"), ObjC.sel("cookieWithProperties:"), props);
                    if (cookie == null || cookie.address() == 0) {
                        if (System.getProperty("rigel.ffm.diag") != null)
                            System.out.println("[ffm] cookie restore: cookieWithProperties failed at " + i);
                        remaining--;
                        continue;
                    }
                    final long idx = i;
                    MemorySegment doneBlock = makeBlock(ObjC.LINKER.upcallStub(
                            ObjC.staticHandle(CookieBridge.class, "restoreDone",
                                    MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class)),
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS), ObjC.GLOBAL));
                    ObjC.callVoid(store, "setCookie:completionHandler:", cookie, doneBlock);
                    ok.incrementAndGet();
                } catch (Throwable one) {
                    remaining--;
                    if (System.getProperty("rigel.ffm.diag") != null)
                        System.out.println("[ffm] cookie restore: failed at " + i + ": " + one);
                }
            }
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (remaining > 0 && System.nanoTime() < deadline) pump(0.05);
            if (System.getProperty("rigel.ffm.diag") != null)
                System.out.println("[ffm] cookie restore applied=" + ok.get() + "/" + count);
            return ok.get();
        } catch (Throwable t) {
            if (System.getProperty("rigel.ffm.diag") != null) {
                System.out.println("[ffm] cookie restore failed: " + t);
                t.printStackTrace();
            }
            return 0;
        }
    }

    public static void setDone(MemorySegment block) {
        remaining--;
    }

    public static void restoreDone(MemorySegment self, MemorySegment block) {
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
