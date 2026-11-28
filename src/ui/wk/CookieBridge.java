package ui.wk;

import ui.objc.ObjC;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Pure-FFM cookie snapshot/replay bridge. Every async operation owns its
 *  countdown and completion state; late WebKit callbacks can never complete a
 *  newer save/restore by accident. */
public final class CookieBridge {

    private static final class SaveOperation {
        final String path;
        final CountDownLatch done = new CountDownLatch(1);
        volatile int ok;
        SaveOperation(String path) { this.path = path; }
    }
    private static final class RestoreOperation {
        final AtomicInteger remaining;
        RestoreOperation(int count) { remaining = new AtomicInteger(count); }
    }

    private static final ConcurrentLinkedQueue<SaveOperation> PENDING_SAVES = new ConcurrentLinkedQueue<>();
    private static volatile boolean shuttingDown;
    private static volatile MemorySegment cookieSelectorTarget;

    public static void markShuttingDown() { shuttingDown = true; }

    private CookieBridge() {}

    /** Called only by the daemon autosave/shutdown worker, never by the SWT
     *  close path. The bounded wait cannot beachball window disposal. */
    public static synchronized int save(String path) {
        if (shuttingDown) return 0;
        SaveOperation operation = new SaveOperation(path);
        if (!dispatchMain(operation)) return 0;
        try { operation.done.await(3, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return 0; }
        if (System.getProperty("rigel.ffm.diag") != null)
            System.out.println("[ffm] cookie save result=" + operation.ok + " path=" + path);
        return operation.ok;
    }

    /** Runs on the main queue: fetch and write entirely there, so no
     *  autoreleased WebKit object crosses a thread boundary. */
    private static void saveOnMain(SaveOperation operation) {
        MemorySegment completion = null;
        try {
            MemorySegment store = cookieStore();
            if (store == null) { operation.done.countDown(); return; }
            completion = ObjC.transientBlock(
                    MethodHandles.insertArguments(
                            ObjC.staticHandle(CookieBridge.class, "saveDone",
                                    MethodType.methodType(void.class, SaveOperation.class,
                                            MemorySegment.class, MemorySegment.class)),
                            0, operation),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            ObjC.callVoid(store, "getAllCookies:", completion);
        } catch (Throwable t) {
            if (System.getProperty("rigel.ffm.diag") != null) t.printStackTrace();
            operation.ok = 0;
            operation.done.countDown();
            if (completion != null) ObjC.closeBlockSoon(completion);
        }
    }

    public static void saveDone(SaveOperation operation, MemorySegment block, MemorySegment cookies) {
        try {
            if (cookies == null || cookies.address() == 0) { operation.ok = 0; return; }
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
                    } else skipped++;
                } catch (Throwable one) {
                    skipped++; // one malformed cookie must not poison the snapshot
                }
            }
            MethodHandle write = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
            boolean wrote = (boolean) write.invoke(entries, ObjC.sel("writeToFile:atomically:"),
                    ObjC.nsString(operation.path), true);
            operation.ok = wrote ? 1 : 0;
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(operation.path + ".manifest.json"),
                        "{\"count\":" + count + ",\"ok\":" + ok + ",\"skipped\":" + skipped
                                + ",\"wrote\":" + wrote + "}\n");
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            if (System.getProperty("rigel.ffm.diag") != null) t.printStackTrace();
            operation.ok = 0;
        } finally {
            operation.done.countDown();
            ObjC.closeBlockSoon(block);
        }
    }

    private static boolean dispatchMain(SaveOperation operation) {
        try {
            PENDING_SAVES.add(operation);
            if (cookieSelectorTarget == null) {
                MemorySegment cls = ObjC.createClass("RigelJavaCookieSaver", "NSObject");
                ObjC.addMethod(cls, "rigelSaveCookiesNow",
                        ObjC.staticHandle(CookieBridge.class, "saveNow",
                                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class)),
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS), "v@:");
                ObjC.registerClass(cls);
                cookieSelectorTarget = ObjC.allocInit(cls);
                ObjC.callVoid(cookieSelectorTarget, "retain"); // process-lifetime selector target
            }
            MethodHandle perform = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
            perform.invoke(cookieSelectorTarget,
                    ObjC.sel("performSelectorOnMainThread:withObject:waitUntilDone:"),
                    ObjC.sel("rigelSaveCookiesNow"), MemorySegment.NULL, false);
            return true;
        } catch (Throwable t) {
            PENDING_SAVES.remove(operation);
            operation.done.countDown();
            return false;
        }
    }

    public static void saveNow(MemorySegment self, MemorySegment cmd) {
        SaveOperation operation = PENDING_SAVES.poll();
        if (operation == null) return;
        if (shuttingDown) { operation.done.countDown(); return; }
        saveOnMain(operation);
    }

    public static synchronized int restore(String path) {
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

            RestoreOperation operation = new RestoreOperation((int) count);
            AtomicInteger accepted = new AtomicInteger();
            MethodHandle objectAt = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            for (long i = 0; i < count; i++) {
                MemorySegment doneBlock = null;
                try {
                    MemorySegment props = (MemorySegment) objectAt.invoke(
                            entries, ObjC.sel("objectAtIndex:"), i);
                    MemorySegment cookie = (MemorySegment) ObjC.sendAddress(1).invoke(
                            ObjC.cls("NSHTTPCookie"), ObjC.sel("cookieWithProperties:"), props);
                    if (cookie == null || cookie.address() == 0) {
                        operation.remaining.decrementAndGet();
                        continue;
                    }
                    doneBlock = ObjC.transientBlock(
                            MethodHandles.insertArguments(
                                    ObjC.staticHandle(CookieBridge.class, "restoreDone",
                                            MethodType.methodType(void.class, RestoreOperation.class,
                                                    MemorySegment.class, MemorySegment.class)), 0, operation),
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                    ObjC.callVoid(store, "setCookie:completionHandler:", cookie, doneBlock);
                    accepted.incrementAndGet();
                } catch (Throwable one) {
                    operation.remaining.decrementAndGet();
                    if (doneBlock != null) ObjC.closeBlock(doneBlock);
                }
            }
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (operation.remaining.get() > 0 && System.nanoTime() < deadline) pump(0.05);
            if (System.getProperty("rigel.ffm.diag") != null)
                System.out.println("[ffm] cookie restore accepted=" + accepted.get() + "/" + count
                        + " pending=" + operation.remaining.get());
            return accepted.get();
        } catch (Throwable t) {
            if (System.getProperty("rigel.ffm.diag") != null) t.printStackTrace();
            return 0;
        }
    }

    public static void restoreDone(RestoreOperation operation, MemorySegment self, MemorySegment block) {
        operation.remaining.decrementAndGet();
        ObjC.closeBlockSoon(block);
    }

    private static MemorySegment cookieStore() {
        try {
            MemorySegment ds = (MemorySegment) ObjC.sendAddress(0).invoke(
                    ObjC.cls("WKWebsiteDataStore"), ObjC.sel("defaultDataStore"));
            return (MemorySegment) ObjC.sendAddress(0).invoke(ds, ObjC.sel("httpCookieStore"));
        } catch (Throwable t) { return null; }
    }

    private static volatile MethodHandle mainRunLoop;
    private static volatile MethodHandle dateWith;

    private static void pump(double seconds) {
        try {
            if (mainRunLoop == null) {
                SymbolLookup appkit = SymbolLookup.libraryLookup(
                        "/System/Library/Frameworks/AppKit.framework/AppKit", ObjC.GLOBAL);
                mainRunLoop = ObjC.LINKER.downcallHandle(
                        appkit.find("NSRunLoopMain").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.ADDRESS));
                dateWith = ObjC.LINKER.downcallHandle(ObjC.MSG_SEND,
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE));
            }
            MemorySegment loop = (MemorySegment) mainRunLoop.invoke();
            MemorySegment date = (MemorySegment) dateWith.invoke(ObjC.cls("NSDate"),
                    ObjC.sel("dateWithTimeIntervalSinceNow:"), seconds);
            ObjC.callVoid(loop, "runMode:beforeDate:", ObjC.nsString("NSDefaultRunLoopMode"), date);
        } catch (Throwable ignored) {}
    }
}
