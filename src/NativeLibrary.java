package org.citra.citra_emu;

/**
 * Stub for sdl_fix — that tool looks for this class via JNI.
 * All methods are no-ops so it finds the class, does nothing, and leaves
 * the SWT event loop alone.
 */
public class NativeLibrary {
    public static void KeyEvent(int button, int action) {}
    public static void onGamePadEvent(String device, int button, int action) {}
    public static void onGamePadMoveEvent(String device, int axis, float value) {}
    public static void onTouchEvent(int action, int x, int y) {}
    public static void onTouchMoved(int[] ids, int[] x, int[] y, int count) {}
    public static void Run(String path) {}
    public static void ReloadSettings() {}
    public static boolean IsRunning() { return false; }
    public static void StopEmulation() {}
    public static void SetUserDirectory(String path) {}
    public static String GetUserDirectory() { return ""; }
    public static void CreateConfigFile() {}
    public static int DefaultCPUCore() { return 0; }
    public static void SwapScreens() {}
    public static void SetupEmuThread() {}
    public static void SurfaceChanged(Object surface, int width, int height) {}
    public static void SurfaceDestroyed() {}
    public static void NotifyOrientationChange(int layout, int rotation) {}
    public static void SetInputLayerStatus(boolean inputLayerVisible) {}
}
