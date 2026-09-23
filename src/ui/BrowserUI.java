package ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleActionAdapter;
import org.eclipse.swt.accessibility.AccessibleActionEvent;
import org.eclipse.swt.accessibility.AccessibleControlAdapter;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.*;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import proxy.ProxyServer;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * BrowserUI — Rigel Browser main window.
 *
 * Two layout modes, toggled via the layout button or Cmd+Shift+S:
 *
 *   TOP-BAR mode (default):
 *     shell → chromeComposite → [tabBand / navRow] → contentArea
 *
 *   SIDEBAR mode:
 *     shell → navBarComposite → SashForm → [sidebarComposite | contentArea]
 *
 * Fullscreen hides all chrome. Esc / Cmd+Shift+F / Ctrl+Cmd+F restores.
 * Proxy config, layout toggle, and theme picker live in the settings dialog.
 */
public class BrowserUI {
    private static final int TAB_ROW_HEIGHT = 42;
    private static final int TOP_TAB_STRIP_HEIGHT = 34;
    private static final int NAV_BUTTON_SIZE = 32;
    private static final int NAV_ROW_HEIGHT = 46;
    /** Reserved band under the traffic lights when glass turns the window
     *  into a full-size-content view. Matches the native titlebar height. */
    private static final int GLASS_TITLE_STRIP_HEIGHT = 28;
    // Chrome paint alpha while glass is active (~38%): the material behind
    // must dominate, with the theme only tinting it.
    private static final int GLASS_CHROME_TINT_ALPHA = 96;
    private static final int GLASS_SIDEBAR_TINT_ALPHA = 118;
    private static final String DATA_ACTIVE_WINDOW = "rigel.activeWindow";
    private static final String DATA_SYSTEM_MENU_WIRED = "rigel.systemMenuWired";
    private static final String DATA_ACCESSIBLE_NAME = "rigel.accessibleName";
    private static final String DATA_ACCESSIBLE_NAME_WIRED = "rigel.accessibleNameWired";
    private static final String DATA_TAB_MODEL = "rigel.tabModel";
    private static final String DATA_TAB_INDEX = "rigel.tabIndex";
    private static final String DATA_PENDING_SETTINGS_LABEL = "rigel.pendingSettingsLabel";
    private static final java.net.http.HttpClient DOWNLOAD_CLIENT =
            java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .connectTimeout(java.time.Duration.ofSeconds(20))
                    .build();

    // ── Themes ───────────────────────────────────────────────────────────────

    /**
     * Each theme is a complete, carefully tuned palette.  All colors are
     * derived from two "anchor" values (deep background + accent) so every
     * theme feels coherent rather than arbitrary.
     *
     * Fields map 1-to-1 to the colXxx fields below — applyTheme() copies
     * them into SWT Color objects.
     */
    public enum RigelTheme {
        // id           display name     icon   chromeDeep      navBg         sidebarDeep     tabActive       tabHover        tabAccent       addrBg          addrFg          textPrimary     textMuted       separator       btnHover
        AUTOMATIC("Automatic", "\u25D0",  0x151513, 0x1C1C19, 0x10100F, 0x2A2925, 0x24231F, 0xD79A72, 0x282824, 0xF4F1E8, 0xF4F1E8, 0x9D9A92, 0x383732, 0x302F2B),
        ATELIER ("Atelier",  "\u2726",  0x151513, 0x1C1C19, 0x10100F, 0x2A2925, 0x24231F, 0xD79A72, 0x282824, 0xF4F1E8, 0xF4F1E8, 0x9D9A92, 0x383732, 0x302F2B),
        NEBULA  ("Nebula",   "\u2726",  0x0D0E13, 0x15171F, 0x090A0E, 0x242730, 0x1D2028, 0x8FA8FF, 0x242731, 0xEDF0F7, 0xEDF0F7, 0x8B91A2, 0x343844, 0x2B2F3A),
        VOLCANIC("Volcanic", "\u25C6",  0x110A08, 0x1C1008, 0x0C0704, 0x3A1A0A, 0x2C1208, 0xFF8A5C, 0x281408, 0xF0DDD0, 0xF0DDD0, 0xA78170, 0x2A1408, 0x4A2010),
        FOREST  ("Forest",   "\u25B2",  0x080F0A, 0x0D1A10, 0x050C07, 0x162A1A, 0x102014, 0x63C889, 0x102014, 0xD0EDD8, 0xD0EDD8, 0x77A887, 0x142018, 0x1E4028),
        SAND    ("Sand",     "\u25C7",  0xC8B48A, 0xD4BE96, 0xBEA87A, 0xE0CCAA, 0xD8C49E, 0x60410C, 0xEDD8B0, 0x3A2E18, 0x3A2E18, 0x564322, 0xD0BA8E, 0xC4A86A),
        OCEAN   ("Ocean",    "\u25CB",  0x080E14, 0x0A1620, 0x050A0F, 0x102232, 0x0C1A28, 0x52C7FA, 0x0E1C2E, 0xCCE8F8, 0xCCE8F8, 0x72A5C2, 0x102030, 0x183850),
        DUSK    ("Dusk",     "\u25C8",  0x120C18, 0x1A1024, 0x0C0810, 0x2E1A3E, 0x221430, 0xD09BFF, 0x201430, 0xEDD8F8, 0xEDD8F8, 0xA58AB5, 0x201630, 0x3C1E5A),
        MIDNIGHT("Midnight", "\u25CF",  0x030303, 0x080808, 0x010101, 0x111111, 0x0C0C0C, 0xE8E8E8, 0x0A0A0A, 0xF5F5F5, 0xF5F5F5, 0x9B9B9B, 0x181818, 0x202020),
        SAKURA  ("Sakura",   "\u273F",  0x140C10, 0x1E1018, 0x0E080C, 0x361A26, 0x281218, 0xFA8EC7, 0x281018, 0xF8D8E8, 0xF8D8E8, 0xB58A9D, 0x281420, 0x4A1E34);

        public final String name;
        public final String icon;
        // raw ints so enum constants stay terse
        final int chromeDeep, navBg, sidebarDeep,
                  tabActive, tabHover, tabAccent,
                  addrBg, addrFg,
                  textPrimary, textMuted,
                  separator, btnHover;

        RigelTheme(String name, String icon,
                   int chromeDeep, int navBg, int sidebarDeep,
                   int tabActive,  int tabHover,  int tabAccent,
                   int addrBg,     int addrFg,
                   int textPrimary, int textMuted,
                   int separator,   int btnHover) {
            this.name        = name;
            this.icon        = icon;
            this.chromeDeep  = chromeDeep;
            this.navBg       = navBg;
            this.sidebarDeep = sidebarDeep;
            this.tabActive   = tabActive;
            this.tabHover    = tabHover;
            this.tabAccent   = tabAccent;
            this.addrBg      = addrBg;
            this.addrFg      = addrFg;
            this.textPrimary = textPrimary;
            this.textMuted   = textMuted;
            this.separator   = separator;
            this.btnHover    = btnHover;
        }

        /** Unpack a packed 0xRRGGBB int into an RGB. */
        static RGB rgb(int packed) {
            return new RGB((packed >> 16) & 0xFF, (packed >> 8) & 0xFF, packed & 0xFF);
        }

        /** Lighten a packed color by mixing toward white. ratio 0–1. */
        static int lighten(int packed, float ratio) {
            int r = (packed >> 16) & 0xFF, g = (packed >> 8) & 0xFF, b = packed & 0xFF;
            r = Math.min(255, r + (int)((255 - r) * ratio));
            g = Math.min(255, g + (int)((255 - g) * ratio));
            b = Math.min(255, b + (int)((255 - b) * ratio));
            return (r << 16) | (g << 8) | b;
        }

        public static RigelTheme fromName(String n) {
            for (RigelTheme t : values()) if (t.name.equalsIgnoreCase(n)) return t;
            return ATELIER;
        }

        /**
         * Resolves this theme for painting. AUTOMATIC hands the decision to
         * the live macOS appearance: Atelier in Dark Mode, Sand in Light Mode.
         * Everything else (picker selection, persistence, change detection)
         * still sees the AUTOMATIC identity; only drawing resolves it.
         */
        public RigelTheme effective() {
            if (this != AUTOMATIC) return this;
            return MacVibrancy.systemPrefersDarkAppearance() ? ATELIER : SAND;
        }
    }

    // ── UI refs ──────────────────────────────────────────────────────────────

    Shell shell;

    // Top-bar mode
    private Composite chromeComposite;

    // Sidebar mode
    private Composite navBarComposite;
    private Composite sidebarComposite;
    private SashForm  sashForm;

    // Shared — rebuilt on mode switch
    private org.eclipse.swt.custom.ScrolledComposite tabScroll;
    private Composite  tabStripRow;
    private Text       addressBar;
    private RoundedIconLabel backBtn, fwdBtn, reloadBtn, homeBtn, layoutBtn, settingsBtn;
    private Composite  contentArea;
    private TabDeckLayout stackLayout;
    private Canvas loadProgressBar;

    // ── State ────────────────────────────────────────────────────────────────

    private TabManager  tabManager;
    private ProxyServer proxyServer;
    private NewWindowOpener newWindowAction = (url, x, y) -> {};
    private Runnable quitAction = () -> {};
    private Runnable windowClosedAction = () -> {};
    private Runnable settingsChangedAction = () -> {};
    private boolean restoreSessionOnOpen;
    private String  initialTabUrl;
    private int     initialWindowX = -1;
    private int     initialWindowY = -1;
    boolean             isFullscreen            = false;
    private boolean     sidebarMode             = false;
    private boolean     shellWasAlreadyFullscreen = false;
    private RigelBrowser fullscreenOwner;
    private int[]       sidebarWeights = {20, 80};
    private int         sidebarSaveGeneration = 0;
    private RigelTheme  currentTheme            = RigelTheme.ATELIER;
    private TabManager.TabData requestedTabFocus;
    private int         loadProgressPercent     = 0;
    private long        loadProgressGeneration  = 0;
    private int         statusGeneration        = 0;
    private boolean     statusVisible           = false;
    private boolean     windowGlassApplied      = false;
    private boolean     appliedGlassChrome      = true;
    private int         tabResizeGeneration     = 0;
    private final List<Shell> utilityShells = new ArrayList<>();

    // Persistent stores
    private config.Settings  settings;
    private config.Bookmarks bookmarks;
    private config.History   history;
    private final java.util.List<DownloadRecord> downloads = new java.util.ArrayList<>();

    // Find bar
    private Composite findBar;
    private Text      findField;
    private Label     findResult;
    private Listener  keyFilter;
    private int       findSearchGeneration;

    private static class DownloadRecord {
        final long time = System.currentTimeMillis();
        final String id;
        final RigelBrowser owner;
        volatile String name;
        volatile String url;
        volatile String path;
        volatile long completedBytes;
        volatile long totalBytes = -1;
        volatile String status = "Downloading";

        DownloadRecord(String id, RigelBrowser owner, String name, String url, String path) {
            this.id = id;
            this.owner = owner;
            this.name = name;
            this.url = url;
            this.path = path;
        }

        boolean isActive() { return status.startsWith("Downloading") || "Cancelling".equals(status); }
    }

    /** Keeps the in-memory download history bounded: records otherwise
     *  accumulate for the whole browser session. Newest entries sit at the
     *  head, so trimming drops from the tail, never an active transfer. */
    private static final int MAX_DOWNLOAD_RECORDS = 100;

    private void trimDownloadHistory() {
        // Oldest-first scan from the tail: drop inactive records beyond the
        // cap. A single stuck-active record must not permanently defeat the
        // cap (it used to break the loop), so inactive entries beyond it are
        // still removed; the active one is never dropped.
        for (int i = downloads.size() - 1; i >= MAX_DOWNLOAD_RECORDS; i--) {
            DownloadRecord candidate = downloads.get(i);
            if (candidate.isActive()) continue;   // never drop a live transfer
            downloads.remove(i);
        }
    }

    // ── Palette (SWT Color objects, allocated from currentTheme) ─────────────

    private Color colChromeDeep;
    private Color colNavBg;
    private Color colSidebarBg;     // = chromeDeep
    private Color colSidebarDeep;
    private Color colTabActive;
    private Color colTabHover;
    private Color colTabInactive;   // = chromeDeep
    private Color colTabAccent;
    private Color colAddrBg;
    private Color colAddrFg;
    private Color colTextPrimary;
    private Color colTextMuted;
    private Color colTextAccent;    // = accent
    private Color colSeparator;
    private Color colBtnHover;
    private Color colProxyOn;

    // ── Fonts ────────────────────────────────────────────────────────────────

    private Font fontNav;
    private Font fontTab;
    private Font fontSideTab;
    private Font fontSectionLabel;

    // ── Entry ────────────────────────────────────────────────────────────────

    public void open(Display display, config.Settings sharedSettings,
                     config.Bookmarks sharedBookmarks, config.History sharedHistory,
                     ProxyServer sharedProxy, NewWindowOpener newWindowAction,
                     Runnable quitAction, Runnable windowClosedAction,
                     Runnable settingsChangedAction,
                     boolean restoreSession,
                     String initialTabUrl, int initialX, int initialY) {
        settings = sharedSettings;
        bookmarks = sharedBookmarks;
        history = sharedHistory;
        proxyServer = sharedProxy;
        this.newWindowAction = newWindowAction;
        this.initialTabUrl = initialTabUrl;
        this.initialWindowX = initialX;
        this.initialWindowY = initialY;
        this.quitAction = quitAction;
        this.windowClosedAction = windowClosedAction;
        this.settingsChangedAction = settingsChangedAction == null ? () -> {} : settingsChangedAction;
        this.restoreSessionOnOpen = restoreSession;
        sidebarMode  = settings.isSidebar();
        int sidebarPercent = settings.getSidebarPercent();
        sidebarWeights = new int[]{sidebarPercent, 100 - sidebarPercent};
        currentTheme = RigelTheme.fromName(settings.getTheme());
        appliedGlassChrome = settings.isGlassChromeEnabled();
        ensureWebProxyRouting();

        allocColors(display);
        allocFonts(display);
        // A final SWT paint can occur while the Display tears down its native
        // views. Keep theme resources alive until that teardown is complete.
        display.disposeExec(this::disposeUiResources);

        shell = new Shell(display, SWT.SHELL_TRIM);

        try {
            long windowId = shell.view.window().id;
            long sharingType = MacWindowCaptureProtection.diagnosticCaptureEnabled() ? 1L : 0L;
            org.eclipse.swt.internal.cocoa.OS.objc_msgSend(windowId,
                    org.eclipse.swt.internal.cocoa.OS.sel_registerName("setSharingType:"), sharingType);
            if (sharingType == 1L) {
                System.out.println("[Rigel] AI test capture mode: window sharing enabled");
            }
        } catch (Throwable t) {
            // Ignore if not supported or fails
        }

        shell.setText(windowTitle());
        shell.setSize(1280, 820);
        if (initialWindowX >= 0 || initialWindowY >= 0) {
            // Tab tear-off: put the new window where the tab was dropped.
            org.eclipse.swt.graphics.Rectangle bounds = shell.getMonitor().getBounds();
            int w = 1280, h = 820;
            int x = initialWindowX >= 0
                    ? Math.max(bounds.x, Math.min(bounds.x + bounds.width - w, initialWindowX - w / 2))
                    : bounds.x + (bounds.width - w) / 2;
            int y = initialWindowY >= 0
                    ? Math.max(bounds.y, Math.min(bounds.y + bounds.height - 60, initialWindowY - 24))
                    : bounds.y + (bounds.height - h) / 2;
            shell.setLocation(x, y);
        }
        shell.setBackground(colChromeDeep);

        GridLayout root = new GridLayout(1, false);
        root.marginWidth = root.marginHeight = root.verticalSpacing = 0;
        shell.setLayout(root);

        if (sidebarMode) buildSidebarLayout();
        else             buildTopBarLayout();

        tabManager = new TabManager(contentArea, stackLayout, this);
        tabManager.setInitialZoom(settings.getZoomPercent());
        tabManager.setGameControllerEnabled(settings.isControllerBridgeEnabled());
        tabManager.setControllerFaceLayout(settings.getControllerFaceLayout());
        tabManager.setNativeClipboardEnabled(settings.isNativeClipboardEnabled());
        openInitialTabs();
        buildMenuBar();
        markActiveWindow(display);
        installSystemMenu(display);

        shell.addShellListener(new ShellAdapter() {
            @Override public void shellActivated(ShellEvent e) {
                markActiveWindow(display);
                if (tabManager != null) tabManager.setAppActive(true);
            }

            @Override public void shellDeactivated(ShellEvent e) {
                if (tabManager != null) tabManager.setAppActive(false);
            }
        });

        // OS-level fullscreen exit detection
        shell.addControlListener(new ControlAdapter() {
            @Override public void controlResized(ControlEvent e) {
                if (isFullscreen && !shell.getFullScreen()) {
                    exitFullscreenInternal(true, false);
                }
            }
        });

        bindKeys(display);

        shell.addDisposeListener(e -> {
            saveSession();
            cancelTabDrag();
            if (keyFilter != null) display.removeFilter(SWT.KeyDown, keyFilter);
            if (display.getData(DATA_ACTIVE_WINDOW) == this) display.setData(DATA_ACTIVE_WINDOW, null);
            Runnable closed = windowClosedAction;
            if (!display.isDisposed()) display.asyncExec(this::disposeAndReleaseClosedWindow);
            closed.run();
        });
        shell.open();
        // A command-line Java/SWT launch may show the shell without activating
        // the application. WKWebView then receives native NSEvents in a non-key
        // window but correctly withholds them from the page. Finish the normal
        // window-opening path by activating the shell and its web content.
        shell.forceActive();
        tabManager.setAppActive(true);
        tabManager.focusActiveBrowser();
        MacWindowCaptureProtection.install(shell);
        // If the NSWindow id was not yet materialised when the layout builders
        // ran (pre-open), the glass call above was a no-op; the shell is now
        // fully opened, so apply once more. The native side is idempotent.
        applyNativeGlass();
        if (glassChrome() && windowGlassApplied) {
            shell.setBackground(null);
        }

        // Visual smoke test hook: RIGEL_SNAPSHOT_PATH=/tmp/home.png captures
        // the active page (with alpha) once the UI has settled and logs the
        // glass state. Used by tests/ scripts; inert in normal launches.
        String snapshotPath = System.getenv("RIGEL_SNAPSHOT_PATH");
        if (snapshotPath != null && !snapshotPath.isBlank()) {
            // Optional: simulate pressing the layout toggle mid-run so the
            // smoke test reproduces the sidebar-button path, then capture.
            boolean toggle = System.getenv("RIGEL_SMOKE_TOGGLE_SIDEBAR") != null;
            // Optional: navigate to a target page first (real-page smoke).
            String targetUrl = System.getenv("RIGEL_SMOKE_URL");
            long settleMs = 5000;
            try {
                settleMs = Long.parseLong(
                        System.getenv().getOrDefault("RIGEL_SMOKE_SETTLE_MS", "5000"));
            } catch (NumberFormatException ignored) {
            }
            int settleInt = (int) Math.min(Integer.MAX_VALUE, settleMs);
            if (targetUrl != null && !targetUrl.isBlank() && tabManager != null) {
                tabManager.navigate(targetUrl);
            }
            if (toggle) display.timerExec(3400, () -> dumpLayoutDebug("before-toggle"));
            if (toggle) display.timerExec(3500, this::toggleLayout);
            // Optional: run a JS probe in the settled page and log its JSON.
            // Lets aitest.sh interrogate real pages (DOM state, injected-
            // script errors, canvas health) without a debugger attached.
            String evalFile = System.getenv("RIGEL_SMOKE_EVAL_FILE");
            if (evalFile != null && !evalFile.isBlank() && tabManager != null) {
                // A second probe pass runs earlier and may stash async results
                // on window; the primary file (read at settle) collects them.
                String evalFileEarly = System.getenv("RIGEL_SMOKE_EVAL_FILE_EARLY");
                if (evalFileEarly != null && !evalFileEarly.isBlank()) {
                    int earlyAt = Math.max(3000, settleInt - 8000);
                    display.timerExec(earlyAt, () -> runSmokeEval(evalFileEarly));
                }
                int evalAt = Math.max(1000, settleInt - 2000);
                display.timerExec(evalAt, () -> runSmokeEval(evalFile));
            }
            display.timerExec(toggle ? 7000 : settleInt, () -> {
                if (tabManager != null) tabManager.snapshotActive(snapshotPath);
                // Whole-window capture beside the page snapshot: verifies the
                // chrome glass geometry (drag strip, tab row, sidebar).
                String windowPath = snapshotPath.endsWith(".png")
                        ? snapshotPath.substring(0, snapshotPath.length() - 4) + ".window.png"
                        : snapshotPath + ".window.png";
                boolean windowed = MacVibrancy.windowSnapshot(shell, windowPath);
                System.out.println("[Rigel] smoke: glass=" + windowGlassApplied
                        + " snapshot=" + snapshotPath + " window=" + windowed);
                if (toggle) dumpLayoutDebug("after-toggle");
            });
        }
    }

    /** Smoke-test helper: evaluate a script file in the active page and log. */
    private void runSmokeEval(String evalFile) {
        if (tabManager == null) return;
        String script;
        try {
            script = java.nio.file.Files.readString(java.nio.file.Path.of(evalFile));
        } catch (Exception failure) {
            System.out.println("[Rigel] smoke-eval: unreadable file " + failure);
            return;
        }
        System.out.println("[Rigel] smoke-eval: running " + evalFile);
        tabManager.evaluateActive(script, (value, error) -> {
            if (error != null) {
                System.out.println("[Rigel] smoke-eval: error " + error.getMessage());
            } else {
                System.out.println("[Rigel] smoke-eval: "
                        + (value == null ? "null" : value.toString()));
            }
        });
    }

    private void openInitialTabs() {
        if (initialTabUrl != null && !initialTabUrl.isBlank()) {
            // Torn-off tab: open exactly the handed-over page.
            tabManager.createTab("New Tab", initialTabUrl);
            return;
        }
        if (restoreSessionOnOpen && settings.isRestoreSessionEnabled()) {
            List<String> urls = settings.getSessionUrls();
            if (!urls.isEmpty()) {
                for (String url : urls) {
                    tabManager.createTab("Restored Tab", url);
                }
                tabManager.switchTo(Math.max(0, Math.min(settings.getSessionActiveIndex(), urls.size() - 1)));
                return;
            }
        }
        tabManager.createTab("New Tab", settings.getHomepage());
    }

    private void saveSession() {
        if (!restoreSessionOnOpen || settings == null || tabManager == null) return;
        if (settings.isRestoreSessionEnabled()) {
            settings.setSession(tabManager.getOpenUrls(), tabManager.getActiveIndex());
        } else {
            settings.clearSession();
        }
    }

    // ── Color allocation ─────────────────────────────────────────────────────

    private void allocColors(Display d) {
        RigelTheme t = currentTheme.effective();
        colChromeDeep  = rgb(d, t.chromeDeep);
        colNavBg       = rgb(d, t.navBg);
        colSidebarBg   = rgb(d, t.chromeDeep);
        colSidebarDeep = rgb(d, t.sidebarDeep);
        colTabActive   = rgb(d, t.tabActive);
        colTabHover    = rgb(d, t.tabHover);
        colTabInactive = rgb(d, t.chromeDeep);
        colTabAccent   = rgb(d, t.tabAccent);
        colAddrBg      = rgb(d, t.addrBg);
        colAddrFg      = rgb(d, t.addrFg);
        colTextPrimary = rgb(d, t.textPrimary);
        colTextMuted   = rgb(d, t.textMuted);
        colTextAccent  = rgb(d, t.tabAccent);
        colSeparator   = rgb(d, t.separator);
        colBtnHover    = rgb(d, t.btnHover);
        colProxyOn     = new Color(d, 76, 175, 80);
    }

    private Color[] allocatedColors() {
        return new Color[]{ colChromeDeep, colNavBg, colSidebarBg, colSidebarDeep,
            colTabActive, colTabHover, colTabInactive, colTabAccent,
            colAddrBg, colAddrFg, colTextPrimary, colTextMuted, colTextAccent,
            colSeparator, colBtnHover, colProxyOn };
    }

    private void disposeColors(Color[] cols) {
        if (cols == null) return;
        for (Color c : cols) if (c != null && !c.isDisposed()) c.dispose();
    }

    /** Restores a retired color back into its field by palette index —
     *  the inverse of allocatedColors()'s ordering; used only by the
     *  applyAppearance failure path. */
    private void assignColorByIndex(int index, Color color) {
        switch (index) {
            case 0 -> colChromeDeep = color;
            case 1 -> colNavBg = color;
            case 2 -> colSidebarBg = color;
            case 3 -> colSidebarDeep = color;
            case 4 -> colTabActive = color;
            case 5 -> colTabHover = color;
            case 6 -> colTabInactive = color;
            case 7 -> colTabAccent = color;
            case 8 -> colAddrBg = color;
            case 9 -> colAddrFg = color;
            case 10 -> colTextPrimary = color;
            case 11 -> colTextMuted = color;
            case 12 -> colTextAccent = color;
            case 13 -> colSeparator = color;
            case 14 -> colBtnHover = color;
            case 15 -> colProxyOn = color;
        }
    }

    private void disposeColors() {
        disposeColors(allocatedColors());
    }

    private void disposeUiResources() {
        disposeColors();
        disposeFonts();
    }

    private void disposeAndReleaseClosedWindow() {
        closeUtilityShells();
        disposeUiResources();
        chromeComposite = null;
        navBarComposite = null;
        sidebarComposite = null;
        sashForm = null;
        tabScroll = null;
        tabStripRow = null;
        addressBar = null;
        backBtn = fwdBtn = reloadBtn = homeBtn = layoutBtn = settingsBtn = null;
        contentArea = null;
        stackLayout = null;
        fullscreenOwner = null;
        loadProgressBar = null;
        findBar = null;
        findField = null;
        findResult = null;
        keyFilter = null;
        tabManager = null;
        shell = null;
        proxyServer = null;
        settings = null;
        bookmarks = null;
        history = null;
        downloads.clear();
        newWindowAction = (url, x, y) -> {};
        quitAction = () -> {};
        windowClosedAction = () -> {};
        settingsChangedAction = () -> {};
        colChromeDeep = colNavBg = colSidebarBg = colSidebarDeep = null;
        colTabActive = colTabHover = colTabInactive = colTabAccent = null;
        colAddrBg = colAddrFg = colTextPrimary = colTextMuted = colTextAccent = null;
        colSeparator = colBtnHover = colProxyOn = null;
        fontNav = fontTab = fontSideTab = fontSectionLabel = null;
    }

    private static Color rgb(Display d, int packed) {
        return new Color(d, (packed>>16)&0xFF, (packed>>8)&0xFF, packed&0xFF);
    }

    // ── Theme switching ──────────────────────────────────────────────────────

    private void applyTheme(RigelTheme theme) {
        applyAppearance(theme, sidebarMode);
    }

    /** Applies the shared preference snapshot to this live window. */
    void applySharedSettings() {
        if (shell == null || shell.isDisposed() || settings == null) return;
        closeUtilityShells();
        RigelTheme wantedTheme = RigelTheme.fromName(settings.getTheme());
        boolean wantedSidebar = settings.isSidebar();
        boolean wantedGlass = settings.isGlassChromeEnabled();
        boolean appearanceChanged = wantedTheme != currentTheme
                || wantedSidebar != sidebarMode
                || wantedGlass != appliedGlassChrome;

        appliedGlassChrome = wantedGlass;
        if (appearanceChanged) applyAppearance(wantedTheme, wantedSidebar);
        else if (tabManager != null) tabManager.refreshActiveHomeTab();

        if (tabManager != null) {
            tabManager.setNativeClipboardEnabled(settings.isNativeClipboardEnabled());
            tabManager.setGameControllerEnabled(settings.isControllerBridgeEnabled());
            tabManager.setControllerFaceLayout(settings.getControllerFaceLayout());
            tabManager.refreshWebInputModes();
        }
        MacWindowCaptureProtection.protectSoon(shell);
    }

    private void applyAppearance(RigelTheme theme, boolean useSidebar) {
        closeUtilityShells();
        String suspendedFind = suspendFindBar();
        Color[] retiredColors = allocatedColors();
        currentTheme = theme;
        sidebarMode = useSidebar;
        Display d = shell.getDisplay();
        allocColors(d);
        try {
            // Full UI rebuild so every widget picks up new colors
            if (sidebarMode) buildSidebarLayout();
            else            buildTopBarLayout();
            if (tabManager != null) tabManager.reattach(contentArea, stackLayout);
            refreshTabStrip();
            syncAddressAndNavigation();
            // Re-render the active tab's home page if it is showing rigel://home,
            // so the new theme colours are visible immediately without a manual reload.
            // The active home tab updates immediately; background home tabs are
            // versioned and update lazily when selected. Real pages are untouched.
            if (tabManager != null) tabManager.refreshActiveHomeTab();
            shell.setBackground(colChromeDeep);
            if (contentArea != null && !contentArea.isDisposed()) {
                contentArea.redraw();
            }
            enforceFullscreenChromeState();
            shell.layout(true, true);
            shell.redraw();
            restoreFindBar(suspendedFind);
            // Final glass re-apply after all view surgery: the rebuild above can
            // reorder the native effect view over the chrome; this re-pins it.
            applyNativeGlass();
            // Retire the old palette only now: a throw above may have left
            // some widgets painting with the old colors, and disposing them
            // would convert one transient error into a permanently broken
            // window (every paint throws).
            disposeColors(retiredColors);
        } catch (Throwable rebuildFailure) {
            // Rebuild failed mid-way. The retired palette is what the
            // un-rebuilt widgets still hold, so restore the field
            // references to it and dispose the freshly allocated set
            // instead — then every surviving widget keeps painting
            // with live colors.
            Color[] fresh = allocatedColors();
            Color[] retired = retiredColors;
            // Undo the field swap: retired[] is ordered identically to the
            // freshly allocated fields (allocatedColors() order).
            if (retired.length == fresh.length) {
                for (int i = 0; i < fresh.length; i++) {
                    if (retired[i] != null && !retired[i].isDisposed()) {
                        assignColorByIndex(i, retired[i]);
                    }
                }
            }
            disposeColors(fresh);
            throw rebuildFailure;
        }
        MacWindowCaptureProtection.protectSoon(shell);
    }

    private boolean glassChrome() {
        return appliedGlassChrome;
    }

    private Color chromeWidgetBackground(Color opaqueFallback) {
        return glassChrome() && MacVibrancy.isSupported() ? null : opaqueFallback;
    }

    /** Tab-strip surfaces: the deeper chrome tone (so the strip reads as a
     *  darker band above the lighter nav row, Chrome-style). Null in glass. */
    private Color chromeDeepWidgetBackground() {
        return chromeWidgetBackground(colChromeDeep);
    }

    private void paintChromeGradient(GC gc, Point size, Color top, Color bottom,
                                     int glassAlpha, boolean nativeGlassApplied) {
        if (gc == null || gc.isDisposed() || top == null || top.isDisposed()
                || bottom == null || bottom.isDisposed()) return;
        if (glassChrome() && nativeGlassApplied) gc.setAlpha(glassAlpha);
        gc.setForeground(top);
        gc.setBackground(bottom);
        gc.fillGradientRectangle(0, 0, size.x, size.y, true);
        gc.setAlpha(255);
    }

    /**
     * Whole-window liquid glass. One native NSVisualEffectView spans the
     * content view; the titlebar becomes transparent and the layout extends
     * beneath it. Chrome strips and the sidebar are left unpainted apart from
     * a faint theme-tinted gradient, and rigel://home renders with a
     * transparent WKWebView, so the frosted desktop material shows through
     * the surrounding chrome rather than a couple of strips.
     *
     * The native call additionally carries a low-alpha overlay in the
     * theme's deep colour so the glass reads as "the current theme" instead
     * of a generic desktop blur.
     */
    private void applyNativeGlass() {
        boolean enabled = glassChrome() && MacVibrancy.isSupported();
        RigelTheme effectiveTheme = currentTheme.effective();
        boolean darkAppearance = effectiveTheme != RigelTheme.SAND;
        // Light mode: SAND's deep tone is a warm brown; layered at 18% over
        // the bright under-window material it reads muddy and cheap. Use a
        // neutral near-white overlay instead so light glass stays clean.
        double tintAlpha = darkAppearance ? 0.34 : 0.22;
        int tintRgb = darkAppearance ? effectiveTheme.chromeDeep : 0xF7F5F8;
        windowGlassApplied = MacVibrancy.applyWindowGlass(shell, enabled,
                MacVibrancy.MATERIAL_UNDER_WINDOW, tintAlpha,
                tintRgb, darkAppearance);

        // If Cocoa supports vibrancy but installing the native view fails,
        // force an opaque inherited surface so no control falls back to an
        // OS-default light background in the middle of a dark theme.
        if (enabled && !windowGlassApplied) {
            if (chromeComposite != null && !chromeComposite.isDisposed())
                chromeComposite.setBackground(colChromeDeep);
            if (navBarComposite != null && !navBarComposite.isDisposed())
                navBarComposite.setBackground(colNavBg);
            if (sidebarComposite != null && !sidebarComposite.isDisposed())
                sidebarComposite.setBackground(colSidebarBg);
        }
        restyleAddressField();
        // The tab-strip scroller's internal NSClipView paints opaque grey
        // beyond SWT's control — without this the gaps between tabs stay
        // solid while the rest of the chrome goes glass.
        if (tabScroll != null && !tabScroll.isDisposed())
            MacVibrancy.styleTabScroller(tabScroll, glassChrome() && windowGlassApplied,
                    sidebarMode ? effectiveTheme.chromeDeep : effectiveTheme.navBg);
        if (chromeComposite != null && !chromeComposite.isDisposed()) chromeComposite.redraw();
        if (navBarComposite != null && !navBarComposite.isDisposed()) navBarComposite.redraw();
        if (sidebarComposite != null && !sidebarComposite.isDisposed()) sidebarComposite.redraw();
        if (contentArea != null && !contentArea.isDisposed()) contentArea.redraw();
    }

    /** Translucent search field over the glass material; solid theme fill
     *  is restored by the same call when glass is off. */
    private void restyleAddressField() {
        if (addressBar == null || addressBar.isDisposed()) return;
        RigelTheme effectiveTheme = currentTheme.effective();
        MacVibrancy.styleAddressField(addressBar, glassChrome() && windowGlassApplied,
                effectiveTheme.addrBg, 0.42, effectiveTheme.addrFg);
    }

    // ── Font allocation ──────────────────────────────────────────────────────

    private void allocFonts(Display d) {
        String name = d.getSystemFont().getFontData()[0].getName();
        fontNav          = new Font(d, new FontData(name, 12, SWT.NORMAL));
        fontTab          = new Font(d, new FontData(name, 11, SWT.NORMAL));
        fontSideTab      = new Font(d, new FontData(name, 12, SWT.NORMAL));
        fontSectionLabel = new Font(d, new FontData(name, 10, SWT.BOLD));
    }

    private void disposeFonts() {
        if (fontNav          != null) fontNav.dispose();
        if (fontTab          != null) fontTab.dispose();
        if (fontSideTab      != null) fontSideTab.dispose();
        if (fontSectionLabel != null) fontSectionLabel.dispose();
    }

    // ── Layout builders ──────────────────────────────────────────────────────

    private void buildTopBarLayout() {
        disposeSidebarWidgets();
        disposeTopBarWidgets();

        chromeComposite = new Composite(shell, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        chromeComposite.setBackground(glassChrome() ? null : colChromeDeep);
        chromeComposite.setBackgroundMode(SWT.INHERIT_FORCE);
        // Ensure chrome is first in shell's z-order so GridLayout puts it at top.
        // disposeSidebarWidgets() reparents contentArea back to shell before we
        // get here, so without this move chrome ends up BELOW the content area.
        if (contentArea != null && !contentArea.isDisposed())
            chromeComposite.moveAbove(contentArea);
        // Gradient background across the full chrome strip. With glass active
        // this is only a light tint; the native material dominates.
        // Chrome-style elevation: the tab strip sits on the deeper tone and
        // the nav row on the lighter one; the active tab merges into it.
        chromeComposite.addPaintListener(e -> {
            Point sz = chromeComposite.getSize();
            paintChromeGradient(e.gc, sz, colChromeDeep, colNavBg,
                    GLASS_CHROME_TINT_ALPHA, windowGlassApplied);
        });
        GridLayout cl = new GridLayout(1, false);
        cl.marginWidth = cl.marginHeight = cl.verticalSpacing = 0;
        chromeComposite.setLayout(cl);
        chromeComposite.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        buildGlassDragStrip(chromeComposite);

        // Tabs get their own quiet band. Keeping navigation on a separate row
        // gives the address field room to breathe and makes the hierarchy read
        // like a purpose-built browser instead of a crowded utility toolbar.
        Composite tabBand = new Composite(chromeComposite, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        tabBand.setBackground(chromeDeepWidgetBackground());
        tabBand.setBackgroundMode(SWT.INHERIT_FORCE);
        GridData tabBandData = new GridData(SWT.FILL, SWT.TOP, true, false);
        tabBandData.heightHint = TAB_ROW_HEIGHT;
        tabBand.setLayoutData(tabBandData);
        GridLayout trl = new GridLayout(2, false);
        trl.marginWidth = 12;
        trl.marginTop = trl.marginBottom = 4;
        trl.horizontalSpacing = 6;
        tabBand.setLayout(trl);

        tabScroll = new org.eclipse.swt.custom.ScrolledComposite(
                tabBand, SWT.H_SCROLL | SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        tabScroll.setBackground(chromeDeepWidgetBackground());
        tabScroll.setBackgroundMode(SWT.INHERIT_FORCE);
        tabScroll.setExpandHorizontal(false);
        tabScroll.setExpandVertical(false);
        tabScroll.getHorizontalBar().setVisible(false);
        GridData tabScrollData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        tabScrollData.heightHint = TOP_TAB_STRIP_HEIGHT;
        tabScroll.setLayoutData(tabScrollData);

        Composite tabActions = new Composite(
                tabBand, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        tabActions.setBackground(chromeDeepWidgetBackground());
        RowLayout actionLayout = new RowLayout(SWT.HORIZONTAL);
        actionLayout.marginLeft = actionLayout.marginRight = 0;
        actionLayout.marginTop = actionLayout.marginBottom = 0;
        actionLayout.spacing = 4;
        actionLayout.center = true;
        tabActions.setLayout(actionLayout);
        tabActions.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));

        RoundedIconLabel plus = makeIconLabel(
                tabActions, "plus", "New Tab (Cmd+T)", chromeDeepWidgetBackground());
        plus.setLayoutData(new RowData(34, 30));
        plus.addMouseListener(clickOn(() -> tabManager.createTab("New Tab", "rigel://home")));
        RoundedIconLabel overview = makeIconLabel(
                tabActions, "down", "Show all tabs", chromeDeepWidgetBackground());
        overview.setLayoutData(new RowData(30, 30));
        overview.addMouseListener(clickOn(() -> showTabOverview(overview)));

        tabStripRow = new Composite(tabScroll, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        tabStripRow.setBackground(chromeDeepWidgetBackground());
        tabStripRow.setBackgroundMode(SWT.INHERIT_FORCE);

        RowLayout rl = new RowLayout(SWT.HORIZONTAL);
        rl.marginTop = rl.marginBottom = 0;
        rl.marginLeft = rl.marginRight = 0;
        rl.spacing = 5;
        rl.wrap = false;
        rl.center = true;
        tabStripRow.setLayout(rl);

        tabScroll.setContent(tabStripRow);
        tabScroll.addControlListener(new ControlAdapter() {
            @Override public void controlResized(ControlEvent e) {
                int generation = ++tabResizeGeneration;
                tabScroll.getDisplay().timerExec(90, () -> {
                    if (generation == tabResizeGeneration && tabManager != null)
                        refreshTabStrip();
                });
            }
        });

        tabScroll.addListener(SWT.MouseWheel, e -> {
            ScrollBar hbar = tabScroll.getHorizontalBar();
            int delta = -e.count * 32;
            int max = hbar.getMaximum() - hbar.getThumb();
            int newX = Math.max(0, Math.min(max, hbar.getSelection() + delta));
            hbar.setSelection(newX);
            tabScroll.setOrigin(newX, 0);
        });

        // Navigation occupies a full second row so the omnibox can expand.
        buildNavRow(chromeComposite, false);

        buildHRule(chromeComposite);
        ensureContentArea(shell);
        shell.layout(true, true);
        applyNativeGlass();
    }

    private void buildSidebarLayout() {
        disposeTopBarWidgets();
        disposeSidebarWidgets();

        navBarComposite = new Composite(shell, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        navBarComposite.setBackground(glassChrome() ? null : colNavBg);
        navBarComposite.addPaintListener(e -> {
            Point sz = navBarComposite.getSize();
            paintChromeGradient(e.gc, sz, colNavBg, colChromeDeep,
                    GLASS_CHROME_TINT_ALPHA, windowGlassApplied);
        });
        GridLayout nl = new GridLayout(1, false);
        nl.marginWidth = nl.marginHeight = nl.verticalSpacing = 0;
        navBarComposite.setLayout(nl);
        navBarComposite.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        buildGlassDragStrip(navBarComposite);
        buildNavRow(navBarComposite, true);
        buildHRule(navBarComposite);

        // NO_BACKGROUND: an opaque SashForm fill would sit between the window
        // glass and the unpainted sidebar/content chain and quietly block the
        // effect. The sash divider is re-filled explicitly when glass is off.
        sashForm = new SashForm(shell, SWT.HORIZONTAL | SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        sashForm.addPaintListener(e -> {
            if (glassChrome() && windowGlassApplied) return;
            Point sz = sashForm.getSize();
            e.gc.setBackground(colSidebarBg);
            e.gc.fillRectangle(0, 0, sz.x, sz.y);
        });
        sashForm.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        sashForm.addListener(SWT.Selection, e -> {
            rememberSidebarWeights();
            int generation = ++sidebarSaveGeneration;
            shell.getDisplay().timerExec(350, () -> {
                if (shell == null || shell.isDisposed() || generation != sidebarSaveGeneration) return;
                settings.setSidebarPercent(sidebarWeights[0]);
            });
        });

        sidebarComposite = new Composite(sashForm, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        sidebarComposite.setBackground(glassChrome() ? null : colSidebarBg);
        sidebarComposite.setBackgroundMode(SWT.INHERIT_FORCE);
        // Subtle gradient down the sidebar (a light tint once glass is active)
        sidebarComposite.addPaintListener(e -> {
            Point sz = sidebarComposite.getSize();
            paintChromeGradient(e.gc, sz, colNavBg, colSidebarBg,
                    GLASS_SIDEBAR_TINT_ALPHA, windowGlassApplied);
        });
        GridLayout sl = new GridLayout(1, false);
        sl.marginWidth = sl.marginHeight = sl.verticalSpacing = 0;
        sidebarComposite.setLayout(sl);

        Composite sideHeader = new Composite(
                sidebarComposite, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        sideHeader.setBackground(chromeWidgetBackground(colSidebarBg));
        sideHeader.setBackgroundMode(SWT.INHERIT_FORCE);
        sideHeader.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout shl = new GridLayout(2, false);
        shl.marginLeft = 16;
        shl.marginRight = 10;
        shl.marginTop = shl.marginBottom = 7;
        shl.horizontalSpacing = 6;
        sideHeader.setLayout(shl);

        Label sideTitle = new Label(sideHeader, SWT.NONE);
        sideTitle.setText("TABS");
        sideTitle.setFont(fontSectionLabel);
        sideTitle.setForeground(colTextMuted);
        sideTitle.setBackground(chromeWidgetBackground(colSidebarBg));
        sideTitle.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        RoundedIconLabel plusSide = makeIconLabel(sideHeader, "plus", "New Tab (Cmd+T)",
                chromeWidgetBackground(colSidebarBg));
        plusSide.addMouseListener(clickOn(() -> tabManager.createTab("New Tab", "rigel://home")));

        tabScroll = new org.eclipse.swt.custom.ScrolledComposite(
                sidebarComposite, SWT.V_SCROLL | SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        tabScroll.setBackground(chromeWidgetBackground(colSidebarBg));
        tabScroll.setBackgroundMode(SWT.INHERIT_DEFAULT);
        tabScroll.setExpandHorizontal(true);
        tabScroll.setExpandVertical(false);
        tabScroll.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        tabStripRow = new Composite(tabScroll, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        tabStripRow.setBackgroundMode(SWT.INHERIT_DEFAULT);
        GridLayout tl = new GridLayout(1, false);
        tl.marginWidth = 8; tl.marginTop = 2;
        tl.marginHeight = 8; tl.verticalSpacing = 3;
        tabStripRow.setLayout(tl);
        tabScroll.setContent(tabStripRow);

        ensureContentArea(sashForm);
        sashForm.setWeights(sidebarWeights);
        shell.layout(true, true);
        applyNativeGlass();
    }

    private void buildNavRow(Composite parent, boolean isSidebar) {
        Composite nav = new Composite(parent, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        nav.setBackground(chromeWidgetBackground(colNavBg));
        nav.setBackgroundMode(SWT.INHERIT_FORCE);
        GridLayout nl = new GridLayout(9, false);
        nl.marginWidth = 14;
        nl.marginHeight = 7;
        nl.horizontalSpacing = 4;
        nav.setLayout(nl);
        GridData navData = new GridData(SWT.FILL, SWT.TOP, true, false);
        navData.heightHint = NAV_ROW_HEIGHT;
        nav.setLayoutData(navData);

        Color navControlBg = chromeWidgetBackground(colNavBg);
        backBtn   = makeIconLabel(nav, "back", "Back",           navControlBg);
        fwdBtn    = makeIconLabel(nav, "forward", "Forward",     navControlBg);
        reloadBtn = makeIconLabel(nav, "reload", "Reload (Cmd+R)", navControlBg);
        homeBtn   = makeIconLabel(nav, "home", "Home (Cmd+Shift+H)", navControlBg);
        backBtn.setEnabled(false);
        fwdBtn.setEnabled(false);

        backBtn  .addMouseListener(clickOn(() -> { if (tabManager != null) tabManager.back(); }));
        fwdBtn   .addMouseListener(clickOn(() -> { if (tabManager != null) tabManager.forward(); }));
        reloadBtn.addMouseListener(clickOn(() -> {
            if (tabManager == null) return;
            if (tabManager.isActiveLoading()) tabManager.stopLoading();
            else                              tabManager.reload();
        }));
        homeBtn  .addMouseListener(clickOn(() -> { if (tabManager != null) tabManager.navigate("rigel://home"); }));

        Label gap1 = spacer(nav, navControlBg, 8);
        gap1.setLayoutData(new GridData(8, SWT.DEFAULT));

        Text addrPill = buildAddressPill(nav);
        GridData ag = new GridData(SWT.FILL, SWT.CENTER, true, false);
        ag.heightHint = 34;
        ag.minimumWidth = 180;
        addrPill.setLayoutData(ag);

        Label gap2 = spacer(nav, navControlBg, 8);
        gap2.setLayoutData(new GridData(8, SWT.DEFAULT));

        layoutBtn = makeIconLabel(nav, isSidebar ? "topbar" : "sidebar",
                "Toggle sidebar/top-bar (Cmd+Shift+S)", navControlBg);
        layoutBtn.addMouseListener(clickOn(this::toggleLayout));

        settingsBtn = makeIconLabel(nav, "settings", "Settings", navControlBg);
        settingsBtn.addMouseListener(clickOn(this::showSettingsDialog));
    }

    private Text buildAddressPill(Composite parent) {
        // Cocoa's native search field paints and clips the bezel, editor, focus
        // ring, and selection as one control. This avoids the square NSTextField
        // layer showing through a separately painted rounded Composite.
        addressBar = new Text(parent,
                SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
        addressBar.setBackground(colAddrBg);
        addressBar.setForeground(colAddrFg);
        addressBar.setFont(fontNav);
        addressBar.setMessage("Search the web or enter a URL");
        setAccessibleName(addressBar, "Address and search");
        addressBar.addListener(SWT.DefaultSelection, e -> commitAddress());
        addressBar.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                addressBar.selectAll();
            }

            @Override public void focusLost(FocusEvent e) {
                if (!addressBar.isDisposed())
                    addressBar.setText(displayAddress(currentUrl()));
            }
        });
        return addressBar;
    }

    private void buildHRule(Composite parent) {
        Canvas sep = new Canvas(parent, SWT.NO_BACKGROUND);
        loadProgressBar = sep;
        sep.addPaintListener(e -> {
            Point size = sep.getSize();
            e.gc.setBackground(colSeparator);
            e.gc.fillRectangle(0, 0, size.x, size.y);
            if (loadProgressPercent > 0) {
                int width = Math.max(2,
                        Math.min(size.x, size.x * loadProgressPercent / 100));
                e.gc.setBackground(colTabAccent);
                e.gc.fillRectangle(0, 0, width, size.y);
            }
        });
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false);
        gd.heightHint = 1;
        sep.setLayoutData(gd);
    }

    /**
     * Full-width, non-interactive strip reserved where macOS draws the
     * traffic-light buttons once glass turns the window into a
     * full-size-content view (the window title is hidden, so the strip is
     * visually just more glass). It doubles as the guaranteed window-drag
     * surface — no tab, button, or field ever sits under the lights — and
     * double-clicking it zooms the window, exactly like the real titlebar.
     */
    private void buildGlassDragStrip(Composite parent) {
        // Built in every mode now: with the unified (full-size-content)
        // titlebar the traffic lights always overlay this row, glass or not,
        // so it doubles as their clearance strip and the window-drag surface.
        Composite strip = new Composite(parent, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        strip.setData("rigel.purpose", "window-drag-strip");
        GridData data = new GridData(SWT.FILL, SWT.TOP, true, false);
        data.heightHint = GLASS_TITLE_STRIP_HEIGHT;
        strip.setLayoutData(data);
        setAccessibleName(strip, "Window drag area");
        strip.addListener(SWT.MouseDown, e -> {
            if (e.button == 1) MacVibrancy.dragWindow(shell);
        });
        strip.addListener(SWT.MouseDoubleClick, e -> {
            if (e.button == 1) MacVibrancy.zoomWindow(shell);
        });
    }

    private void ensureContentArea(Composite parent) {
        if (contentArea == null || contentArea.isDisposed()) {
            // NO_BACKGROUND so the window glass shows wherever the page
            // (or the transparent rigel://home webview) leaves slack.
            contentArea  = new Composite(parent, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
            contentArea.setBackgroundMode(SWT.INHERIT_FORCE);
            contentArea.addPaintListener(e -> {
                // While glass is active the frosted material is the
                // background; otherwise fake the old solid canvas with a
                // theme fill (NO_BACKGROUND composites paint nothing on
                // their own).
                if (glassChrome() && windowGlassApplied) return;
                Point size = contentArea.getSize();
                e.gc.setBackground(colChromeDeep);
                e.gc.fillRectangle(0, 0, size.x, size.y);
            });
            stackLayout  = new TabDeckLayout();
            contentArea.setLayout(stackLayout);
            contentArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        } else {
            contentArea.setParent(parent);
            contentArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        }
    }

    // ── Layout toggle ────────────────────────────────────────────────────────

    private void dumpLayoutDebug(String tag) {
        StringBuilder out = new StringBuilder("[Rigel] layout-dump ").append(tag);
        out.append(" shell=").append(shell == null || shell.isDisposed() ? "dead" : shell.getSize());
        if (shell != null && !shell.isDisposed()) {
            out.append(" shellChildren=").append(shell.getChildren().length);
            for (Control child : shell.getChildren())
                out.append(" [").append(child.getClass().getSimpleName())
                   .append(" b=").append(child.getBounds())
                   .append(child.isVisible() ? " vis" : " HIDDEN")
                   .append("]");
        }
        if (sashForm != null && !sashForm.isDisposed()) {
            for (Control child : sashForm.getChildren())
                out.append(" sashchild=[").append(child.getClass().getSimpleName())
                   .append(" b=").append(child.getBounds()).append("]");
        }
        System.out.println(out);
    }

    void toggleLayout() {
        settings.setSidebar(!sidebarMode);
        Display display = shell.getDisplay();
        display.asyncExec(() -> {
            if (shell != null && !shell.isDisposed()) settingsChangedAction.run();
        });
    }

    private void disposeTopBarWidgets() {
        if (chromeComposite != null && !chromeComposite.isDisposed()) {
            chromeComposite.dispose();
            chromeComposite = null;
            tabScroll = null;
            tabStripRow = null;
        }
    }

    private void disposeSidebarWidgets() {
        rememberSidebarWeights();
        if (navBarComposite != null && !navBarComposite.isDisposed()) {
            navBarComposite.dispose();
            navBarComposite = null;
        }
        if (sashForm != null && !sashForm.isDisposed()) {
            if (contentArea != null && !contentArea.isDisposed())
                contentArea.setParent(shell);
            sashForm.dispose();
            sashForm = null;
            sidebarComposite = null;
            tabScroll = null;
            tabStripRow = null;
        }
    }

    private void rememberSidebarWeights() {
        if (sashForm == null || sashForm.isDisposed()) return;
        int[] weights = sashForm.getWeights();
        if (weights.length < 2) return;
        int total = weights[0] + weights[1];
        if (total <= 0 || weights[0] <= 0) return;
        int left = Math.max(12, Math.min(40,
                Math.round(weights[0] * 100f / total)));
        sidebarWeights = new int[]{left, 100 - left};
    }

    // ── Tab strip ────────────────────────────────────────────────────────────

    public void refreshTabStrip() {
        if (tabManager == null || tabStripRow == null || tabStripRow.isDisposed()) return;

        Runnable r = () -> {
            if (tabStripRow == null || tabStripRow.isDisposed()) return;
            // Never rebuild the strip while a tab drag is live: the control
            // under the pointer is being dragged and must stay alive.
            if (tabDragActive || dragArmedTab != null) return;
            TabManager.TabData focusedTab = null;
            Control focused = tabStripRow.getDisplay().getFocusControl();
            if (focused != null && !focused.isDisposed()
                    && focused.getData(DATA_TAB_MODEL) instanceof TabManager.TabData model) {
                focusedTab = model;
            }
            TabManager.TabData focusAfter = requestedTabFocus != null
                    ? requestedTabFocus : focusedTab;
            for (Control c : tabStripRow.getChildren()) c.dispose();

            List<TabManager.TabData> tabs = tabManager.getTabs();
            int active = tabManager.getActiveIndex();
            int topTabWidth = 220;
            if (!sidebarMode && !tabs.isEmpty()) {
                int viewport = tabScroll == null || tabScroll.isDisposed()
                        ? shell.getClientArea().width
                        : tabScroll.getClientArea().width;
                if (viewport <= 0) viewport = shell.getClientArea().width - 32;
                int available = Math.max(120, viewport - Math.max(0, tabs.size() - 1) * 5);
                topTabWidth = Math.max(120, Math.min(220, available / tabs.size()));
            }

            for (int i = 0; i < tabs.size(); i++) {
                if (sidebarMode) renderSidebarTab(tabs.get(i), i == active);
                else             renderTopBarTab(tabs.get(i), i == active, topTabWidth);
            }
            Control[] stripChildren = tabStripRow.getChildren();
            for (int i = 0; i < stripChildren.length; i++)
                stripChildren[i].setData(DATA_TAB_INDEX, i);

            tabStripRow.layout(true, true);
            if (tabScroll != null && !tabScroll.isDisposed()) {
                if (sidebarMode) {
                    // Sidebar: vertical scroll — fix width to client area, height to preferred
                    int w = tabScroll.getClientArea().width;
                    org.eclipse.swt.graphics.Point pref = tabStripRow.computeSize(w, SWT.DEFAULT);
                    tabStripRow.setSize(pref);
                    tabScroll.setMinSize(pref);
                } else {
                    // Top-bar: horizontal scroll only. Keep height stable so
                    // refreshes do not make the chrome row grow downward.
                    org.eclipse.swt.graphics.Point pref = tabStripRow.computeSize(SWT.DEFAULT, SWT.DEFAULT);
                    int width = Math.max(pref.x, tabScroll.getClientArea().width);
                    tabStripRow.setSize(width, TOP_TAB_STRIP_HEIGHT);
                    tabScroll.setMinSize(width, TOP_TAB_STRIP_HEIGHT);

                    int activeIdx = tabManager.getActiveIndex();
                    Control[] children = tabStripRow.getChildren();
                    if (activeIdx >= 0 && activeIdx < children.length) {
                        org.eclipse.swt.graphics.Rectangle tb = children[activeIdx].getBounds();
                        int visW = tabScroll.getClientArea().width;
                        int origin = tabScroll.getOrigin().x;

                        if (tb.x < origin) {
                            tabScroll.setOrigin(tb.x, 0);
                        } else if (tb.x + tb.width > origin + visW) {
                            tabScroll.setOrigin(tb.x + tb.width - visW, 0);
                        }
                    }
                }
            }
            if (chromeComposite  != null && !chromeComposite.isDisposed())  chromeComposite.layout(true, true);
            if (sidebarComposite != null && !sidebarComposite.isDisposed()) sidebarComposite.layout(true, true);
            if (focusAfter != null && !tabManager.getTabs().contains(focusAfter)) {
                // The requested tab closed before this rebuild ran; never pin
                // its TabData (and through it the disposed browser) forever.
                if (requestedTabFocus == focusAfter) requestedTabFocus = null;
            }
            if (focusAfter != null && tabManager.getTabs().contains(focusAfter)) {
                for (Control candidate : tabStripRow.getChildren()) {
                    if (candidate.getData(DATA_TAB_MODEL) == focusAfter) {
                        candidate.setFocus();
                        if (requestedTabFocus == focusAfter) requestedTabFocus = null;
                        break;
                    }
                }
            }
        };

        if (Display.getCurrent() != null) r.run();
        else shell.getDisplay().asyncExec(r);
    }

    private String tabMark(TabManager.TabData tab) {
        String url = tab == null ? "" : tab.url;
        if (url != null && url.startsWith("rigel://")) return "R";
        if (url != null && !url.isBlank()) {
            try {
                String host = URI.create(url).getHost();
                if (host != null && !host.isBlank()) {
                    String clean = host.startsWith("www.") ? host.substring(4) : host;
                    if (!clean.isBlank()) {
                        int codePoint = Character.toUpperCase(clean.codePointAt(0));
                        return new String(Character.toChars(codePoint));
                    }
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        String title = tab == null ? "" : tab.title;
        if (title == null || title.isBlank()) return "R";
        int codePoint = Character.toUpperCase(title.strip().codePointAt(0));
        return new String(Character.toChars(codePoint));
    }

    private void showTabOverview(Control anchor) {
        if (anchor == null || anchor.isDisposed() || tabManager == null) return;
        Display display = anchor.getDisplay();
        Menu menu = new Menu(shell, SWT.POP_UP);
        List<TabManager.TabData> tabs = List.copyOf(tabManager.getTabs());
        TabManager.TabData active = tabManager.getActiveIndex() >= 0
                && tabManager.getActiveIndex() < tabs.size()
                ? tabs.get(tabManager.getActiveIndex()) : null;
        for (TabManager.TabData tab : tabs) {
            MenuItem item = new MenuItem(menu, SWT.RADIO);
            String title = tab.title == null || tab.title.isBlank() ? "New Tab" : tab.title;
            item.setText(trimMiddle(title, 54));
            item.setSelection(tab == active);
            item.addListener(SWT.Selection, e -> {
                if (item.getSelection()) tabManager.switchTo(tab);
            });
        }
        menu.addListener(SWT.Hide, e -> display.asyncExec(() -> {
            if (!menu.isDisposed()) menu.dispose();
        }));
        Point location = anchor.toDisplay(0, anchor.getSize().y + 4);
        menu.setLocation(location);
        menu.setVisible(true);
    }

    // ── Tab drag: Chrome-style reorder + tear-off into a new window ─────

    private static final int TAB_DRAG_THRESHOLD = 8;
    private static final int TEAR_OFF_MARGIN = 24;

    private TabManager.TabData dragArmedTab;
    private Canvas dragArmedControl;
    private org.eclipse.swt.graphics.Point dragStartDisplayPoint;
    private boolean tabDragActive;
    private int tabDragInsertion = -1;
    private boolean dragClickConsumed;
    private Listener tabDragFilter;
    private Display dragFilterDisplay;
    // Chrome-style slide animation: control -> remaining x-offset (px) from
    // its settled slot. Painted transiently, never affects layout/hit-tests.
    private final java.util.Map<Control, Integer> tabSlideOffsets = new java.util.IdentityHashMap<>();
    private long tabSlideAnimStartedNanos;
    private boolean tabSlideAnimRunning;
    // Pointer-follow offset for the dragged tab (does not participate in
    // the slide); recomputed on every drag move event.
    private int draggedPaintOffsetX;
    private int dragGrabOffsetX;

    /** Call from a tab control's mouseDown; arms (but does not start) a drag. */
    private void armTabDrag(Canvas control, TabManager.TabData tab) {
        if (tabManager == null || tab == null || control == null || control.isDisposed()) return;
        dragArmedTab = tab;
        dragArmedControl = control;
        dragClickConsumed = false;
        dragStartDisplayPoint = control.getDisplay().getCursorLocation();
        // Unhook any previous registration FIRST: removeTabDragFilters()
        // intentionally nulls the filter field afterwards, so arming must
        // (re)create the listener after this call, never before.
        removeTabDragFilters();
        if (tabDragFilter == null) {
            // Display-level filters keep tracking while the pointer is over
            // other controls or outside the window entirely, which a control
            // listener cannot do.
            tabDragFilter = event -> {
                if (event.type == SWT.MouseMove) updateTabDrag();
                else if (event.type == SWT.MouseUp) finishTabDrag(event);
            };
        }
        dragFilterDisplay = control.getDisplay();
        dragFilterDisplay.addFilter(SWT.MouseMove, tabDragFilter);
        dragFilterDisplay.addFilter(SWT.MouseUp, tabDragFilter);
    }

    private void updateTabDrag() {
        if (dragArmedTab == null || dragArmedControl == null || dragArmedControl.isDisposed()
                || tabManager == null || !tabManager.getTabs().contains(dragArmedTab)) {
            cancelTabDrag();
            return;
        }
        org.eclipse.swt.graphics.Point here = dragArmedControl.getDisplay().getCursorLocation();
        if (!tabDragActive) {
            int dx = here.x - dragStartDisplayPoint.x;
            int dy = here.y - dragStartDisplayPoint.y;
            if (Math.abs(dx) < TAB_DRAG_THRESHOLD && Math.abs(dy) < TAB_DRAG_THRESHOLD) return;
            tabDragActive = true;
            // Raise the dragged tab, exactly like Chrome does.
            tabManager.switchTo(dragArmedTab);
            // Where inside the tab the press happened, so the card tracks
            // the pointer without jumping.
            try {
                org.eclipse.swt.graphics.Point origin = dragArmedControl.toDisplay(0, 0);
                dragGrabOffsetX = here.x - origin.x;
            } catch (Throwable ignored) {
                dragGrabOffsetX = 0;
            }
        }
        // The dragged card follows the pointer directly; its paint offset is
        // the distance from the slot the live reorder currently grants it.
        if (!sidebarMode) {
            try {
                org.eclipse.swt.graphics.Point stripOrigin = dragArmedControl.getParent().toDisplay(0, 0);
                org.eclipse.swt.graphics.Point slotOrigin = dragArmedControl.toDisplay(0, 0);
                int pointerInStrip = here.x - stripOrigin.x;
                int slotStartInStrip = slotOrigin.x - stripOrigin.x;
                draggedPaintOffsetX = clamp(pointerInStrip - dragGrabOffsetX
                        - slotStartInStrip, -slotStartInStrip,
                        Math.max(0, dragArmedControl.getParent().getSize().x
                                - slotStartInStrip - dragArmedControl.getSize().x));
            } catch (Throwable ignored) {
                draggedPaintOffsetX = 0;
            }
            dragArmedControl.redraw();
        }
        int insertion = sidebarMode ? computeTabInsertion(here) : computeLiveDropIndex(here);
        if (sidebarMode && insertion < 0) return;
        if (insertion != tabDragInsertion && insertion >= 0) {
            tabDragInsertion = insertion;
            if (sidebarMode) {
                redrawTabStrip();
            } else {
                liveReorderStrip(dragArmedControl, insertion);
            }
        }
    }

    /** Current paint offset for a tab mid slide-animation (0 when settled). */
    private int slideOffsetX(Control control) {
        Integer offset = tabSlideOffsets.get(control);
        return offset == null ? 0 : offset;
    }

    /**
     * Chrome-style live reorder while dragging: the dragged canvas moves to
     * its final slot immediately (and the model with it), while displaced
     * siblings slide into their new slots with a short eased animation.
     */
    private void liveReorderStrip(Control dragged, int dropIndex) {
        if (tabStripRow == null || tabStripRow.isDisposed()) return;
        Control[] children = tabStripRow.getChildren();
        int from = -1;
        int tabCount = 0;
        for (int i = 0; i < children.length; i++) {
            if (children[i].isDisposed() || children[i].getData(DATA_TAB_MODEL) == null) continue;
            if (children[i] == dragged) from = tabCount;
            tabCount++;
        }
        if (from < 0 || tabCount < 2) return;
        int to = clamp(dropIndex, 0, tabCount - 1);
        if (to == from) return;
        // Snapshot positions so each tab can slide from where it was.
        java.util.Map<Control, Integer> beforeX = new java.util.IdentityHashMap<>();
        for (Control child : children) beforeX.put(child, child.getBounds().x);
        // Visual reorder: RowLayout follows child order. Place dragged so
        // that exactly `to` non-dragged tabs precede it.
        Control anchor = null;
        int passed = 0;
        for (Control child : children) {
            if (child == dragged || child.isDisposed()
                    || child.getData(DATA_TAB_MODEL) == null) continue;
            if (passed == to) { anchor = child; break; }
            passed++;
        }
        if (anchor != null) dragged.moveAbove(anchor);
        else dragged.moveBelow(null);
        // Keep the model in the same order; moveTab counts an insertion slot
        // including the dragged tab itself, so rightward moves shift by one.
        if (tabManager.getTabs().contains(dragArmedTab)) {
            tabManager.moveTab(dragArmedTab, to > from ? to + 1 : to);
        }
        tabStripRow.layout(true, true);
        // Re-index for hit tests/marker math elsewhere.
        Control[] after = tabStripRow.getChildren();
        for (int i = 0; i < after.length; i++) after[i].setData(DATA_TAB_INDEX, i);
        // Compute slide distances and animate everyone except the dragged tab
        // (which is gliding under the pointer already).
        boolean any = false;
        for (Control child : after) {
            Integer oldX = beforeX.get(child);
            if (oldX == null || child == dragged) continue;
            int delta = oldX - child.getBounds().x;
            if (delta != 0) {
                tabSlideOffsets.put(child, delta);
                any = true;
            }
        }
        if (any) startTabSlideAnimation();
        redrawTabStrip();
    }

    private void startTabSlideAnimation() {
        if (tabSlideAnimRunning) return;
        // Snapshot the starting distances; each frame decays them with an
        // ease-out cubic until every offset reaches zero.
        java.util.Map<Control, Integer> startOffsets =
                new java.util.IdentityHashMap<>(tabSlideOffsets);
        tabSlideAnimRunning = true;
        tabSlideAnimStartedNanos = System.nanoTime();
        Display display = shell == null || shell.isDisposed() ? Display.getCurrent() : shell.getDisplay();
        if (display == null) { tabSlideAnimRunning = false; tabSlideOffsets.clear(); return; }
        Runnable[] frame = new Runnable[1];
        frame[0] = () -> {
            if (display.isDisposed()) return;
            double t = Math.min(1.0, (System.nanoTime() - tabSlideAnimStartedNanos) / 140_000_000.0);
            double remaining = Math.pow(1.0 - t, 3.0);   // ease-out decay factor
            java.util.List<Control> done = new java.util.ArrayList<>();
            for (java.util.Map.Entry<Control, Integer> entry : startOffsets.entrySet()) {
                Control control = entry.getKey();
                if (control == null || control.isDisposed() || t >= 1.0) { done.add(control); continue; }
                tabSlideOffsets.put(control, (int) Math.round(entry.getValue() * remaining));
                control.redraw();
            }
            for (Control control : done) tabSlideOffsets.remove(control);
            if (t < 1.0 && tabSlideAnimRunning) {
                display.timerExec(16, frame[0]);
            } else {
                tabSlideOffsets.clear();
                tabSlideAnimRunning = false;
                redrawTabStrip();
            }
        };
        display.timerExec(16, frame[0]);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Insertion slot (in model order) for a display point, or -1. */
    private int computeTabInsertion(org.eclipse.swt.graphics.Point here) {
        if (tabStripRow == null || tabStripRow.isDisposed()) return -1;
        Control[] children = tabStripRow.getChildren();
        int index = 0;
        for (Control child : children) {
            if (child.isDisposed() || child.getData(DATA_TAB_MODEL) == null) continue;
            org.eclipse.swt.graphics.Rectangle b = child.getBounds();
            org.eclipse.swt.graphics.Point origin = child.toDisplay(0, 0);
            boolean beforeSlot = sidebarMode
                    ? here.y < origin.y + b.height / 2
                    : here.x < origin.x + b.width / 2;
            if (beforeSlot) return index;
            index++;
        }
        return index == 0 ? -1 : index;
    }

    /**
     * Final strip index the dragged tab should own for this pointer position:
     * how many non-dragged tabs sit left of the cursor. This is the ordering
     * convention Chrome drags use (the dragged tab occupies no gap itself).
     */
    private int computeLiveDropIndex(org.eclipse.swt.graphics.Point here) {
        if (tabStripRow == null || tabStripRow.isDisposed()) return -1;
        Control[] children = tabStripRow.getChildren();
        int before = 0;
        int total = 0;
        for (Control child : children) {
            if (child.isDisposed() || child.getData(DATA_TAB_MODEL) == null) continue;
            if (child == dragArmedControl) continue;
            total++;
            org.eclipse.swt.graphics.Rectangle b = child.getBounds();
            org.eclipse.swt.graphics.Point origin = child.toDisplay(0, 0);
            if (here.x >= origin.x + b.width / 2) before++;
        }
        return total == 0 ? -1 : before;
    }

    private boolean isOutsideTabStrip(org.eclipse.swt.graphics.Point here) {
        if (tabStripRow == null || tabStripRow.isDisposed()) return true;
        org.eclipse.swt.graphics.Rectangle b = tabStripRow.getBounds();
        org.eclipse.swt.graphics.Point origin = tabStripRow.toDisplay(0, 0);
        return here.x < origin.x - TEAR_OFF_MARGIN
                || here.y < origin.y - TEAR_OFF_MARGIN
                || here.x > origin.x + b.width + TEAR_OFF_MARGIN
                || here.y > origin.y + b.height + TEAR_OFF_MARGIN;
    }

    private void finishTabDrag(Event event) {
        TabManager.TabData dragged = dragArmedTab;
        boolean wasActive = tabDragActive;
        int insertion = tabDragInsertion;
        org.eclipse.swt.graphics.Point here = shell == null || shell.isDisposed()
                ? new org.eclipse.swt.graphics.Point(event.x, event.y)
                : shell.getDisplay().getCursorLocation();
        removeTabDragFilters();
        dragArmedTab = null;
        dragArmedControl = null;
        tabDragActive = false;
        tabDragInsertion = -1;
        draggedPaintOffsetX = 0;
        dragGrabOffsetX = 0;
        tabSlideOffsets.clear();
        dragClickConsumed = wasActive;   // plain clicks fall through untouched
        redrawTabStrip();
        // Rebuild before any early return: drags interrupted by non-primary
        // buttons or a vanished tab still suppressed rebuilds mid-flight.
        if (wasActive) refreshTabStripAfterDrag();
        if (!wasActive || dragged == null || tabManager == null
                || !tabManager.getTabs().contains(dragged)) return;
        if (event.button != 1) return;
        if (isOutsideTabStrip(here)) {
            detachTabToNewWindow(dragged, here.x, here.y);
        } else if (insertion >= 0 && sidebarMode) {
            // Top-strip drags already live-reordered the model; the sidebar
            // keeps the classic drop-time insertion.
            tabManager.moveTab(dragged, insertion);
        }
    }

    /** Ensure the expensive strip rebuild runs once a drag is fully over. */
    private void refreshTabStripAfterDrag() {
        Display display = shell == null || shell.isDisposed() ? null : shell.getDisplay();
        if (display != null) display.asyncExec(this::refreshTabStrip);
    }

    private void cancelTabDrag() {
        removeTabDragFilters();
        dragArmedTab = null;
        dragArmedControl = null;
        boolean wasActive = tabDragActive;
        tabDragActive = false;
        tabDragInsertion = -1;
        draggedPaintOffsetX = 0;
        dragGrabOffsetX = 0;
        tabSlideOffsets.clear();
        if (wasActive) redrawTabStrip();
        // Some drag teardowns happen because the tab set changed mid-press
        // (e.g. Cmd+W closed the dragged tab) while refreshTabStrip was
        // suppressed — replay the rebuild or the strip shows ghost tabs.
        if (wasActive) refreshTabStripAfterDrag();
    }

    private void removeTabDragFilters() {
        // Always detach via the remembered Display: if the window closed
        // mid-drag the filter must still come off, or it leaks (capturing
        // this whole BrowserUI) and keeps firing on every mouse event.
        Display display = dragFilterDisplay;
        if (tabDragFilter == null || display == null) {
            tabDragFilter = null;
            dragFilterDisplay = null;
            return;
        }
        try {
            display.removeFilter(SWT.MouseMove, tabDragFilter);
            display.removeFilter(SWT.MouseUp, tabDragFilter);
        } catch (Throwable ignored) {
        }
        tabDragFilter = null;
        dragFilterDisplay = null;
    }

    /** True when the just-finished press was consumed by a tab drag. */
    private boolean dragClickWasConsumed() {
        boolean consumed = dragClickConsumed;
        dragClickConsumed = false;
        return consumed;
    }

    private void redrawTabStrip() {
        if (tabStripRow == null || tabStripRow.isDisposed()) return;
        for (Control child : tabStripRow.getChildren()) {
            if (!child.isDisposed()) child.redraw();
        }
    }

    /** Chrome-style tear-off: the tab becomes the first tab of a new window. */
    private void detachTabToNewWindow(TabManager.TabData tab, int displayX, int displayY) {
        if (tab == null || tabManager == null) return;
        String url = tabManager.detachTab(tab);
        if (url != null) newWindowAction.openWindow(url, displayX, displayY);
    }

    /** Paints the insertion indicator on tab canvases while a drag is live. */
    private void drawInsertionMarker(GC gc, org.eclipse.swt.graphics.Point size, Control control) {
        if (!tabDragActive || tabDragInsertion < 0 || tabManager == null) return;
        Object indexData = control.getData(DATA_TAB_INDEX);
        if (!(indexData instanceof Integer index)) return;
        int count = tabManager.getTabs().size();
        boolean before = index.intValue() == tabDragInsertion;
        boolean after = tabDragInsertion == count && index.intValue() == count - 1;
        if (!before && !after) return;
        gc.setForeground(colTabAccent);
        gc.setLineWidth(2);
        if (sidebarMode) {
            int y = before ? 1 : size.y - 1;
            gc.drawLine(2, y, size.x - 2, y);
        } else {
            int x = before ? 1 : size.x - 1;
            gc.drawLine(x, 2, x, size.y - 2);
        }
        gc.setLineWidth(1);
    }

    private void installTabContextMenu(TabManager.TabData tab, Control... controls) {
        if (controls == null || controls.length == 0 || controls[0] == null) return;
        Menu menu = new Menu(controls[0]);
        addMenuItem(menu, "New Tab", () -> tabManager.createTab("New Tab", "rigel://home"));
        addMenuItem(menu, "Duplicate Tab", () -> tabManager.duplicateTab(tab));
        new MenuItem(menu, SWT.SEPARATOR);
        addMenuItem(menu, "Close Tab", () -> tabManager.closeTab(tab));
        MenuItem closeOthers = new MenuItem(menu, SWT.PUSH);
        closeOthers.setText("Close Other Tabs");
        closeOthers.addListener(SWT.Selection, e -> tabManager.closeOtherTabs(tab));
        new MenuItem(menu, SWT.SEPARATOR);
        addMenuItem(menu, "Reopen Closed Tab", tabManager::reopenClosedTab);
        menu.addListener(SWT.Show, e -> closeOthers.setEnabled(tabManager.getTabs().size() > 1
                && tabManager.getTabs().contains(tab)));
        for (Control control : controls) {
            if (control != null && !control.isDisposed()) control.setMenu(menu);
        }
    }

    private volatile long webKitMemoryCachedAt;
    private volatile long webKitMemoryCachedMb = -1;
    private static final java.util.concurrent.ExecutorService MEMORY_PROBE =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "rigel-memory-probe");
                t.setDaemon(true);
                return t;
            });

    /** Combined resident memory of the WebKit helper processes in MB, or -1
     *  when unknown. WKWebView does not expose per-tab memory; this total
     *  across WebContent/Networking/GPU is what tab hover shows. The value is
     *  refreshed on a background thread — a shell-out from the paint path
     *  would hitch the SWT event loop every cache expiry. */
    private long webKitMemoryMb() {
        long now = System.nanoTime();
        if (now - webKitMemoryCachedAt < 5_000_000_000L) return webKitMemoryCachedMb;
        webKitMemoryCachedAt = now;  // stamp first so concurrent callers queue once
        MEMORY_PROBE.execute(() -> {
            webKitMemoryCachedMb = measureWebKitMemoryMb();
            // New value: repaint any hovered tab so the label updates live.
            if (shell != null && !shell.isDisposed()
                    && !shell.getDisplay().isDisposed()) {
                shell.getDisplay().asyncExec(() -> {
                    if (tabStripRow != null && !tabStripRow.isDisposed()) redrawTabStrip();
                });
            }
        });
        return webKitMemoryCachedMb;
    }

    private static long measureWebKitMemoryMb() {
        // Native libproc path first: no process spawn, no text parsing, and
        // scoped to our own helper tree (the ps walk counted every WebKit
        // process on the machine). ps is retained as the fallback only.
        long nativeBytes = MacWKWebView.webKitMemoryBytes();
        if (nativeBytes > 0) return nativeBytes / (1024 * 1024);
        try {
            Process probe = new ProcessBuilder("/bin/ps", "-A", "-o", "rss,comm")
                    .redirectErrorStream(true).start();
            long totalKb = 0;
            int count = 0;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(probe.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.contains("WebKit")) continue;
                    int space = trimmed.indexOf(' ');
                    if (space <= 0) continue;
                    long rssKb;
                    try {
                        rssKb = Long.parseLong(trimmed.substring(0, space).trim());
                    } catch (NumberFormatException badLine) {
                        continue;
                    }
                    String name = trimmed.substring(space + 1);
                    if (!name.contains("WebKit.WebContent")
                            && !name.contains("WebKit.Networking")
                            && !name.contains("WebKit.GPU")) continue;
                    totalKb += rssKb;
                    count++;
                }
            }
            // Do not waitFor() after EOF — the reader already drained the
            // process, and a wedged ps must never put a thread to sleep here.
            return count > 0 ? totalKb / 1024 : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private String webKitMemorySummary() {
        long mb = webKitMemoryMb();
        return mb < 0 ? "WebKit memory: unavailable" : "WebKit memory: " + mb + " MB";
    }

    private void installTabAccessibility(Canvas tabControl, TabManager.TabData tab,
                                         boolean active, boolean vertical) {
        tabControl.setData(DATA_TAB_MODEL, tab);
        String title = tab.title == null || tab.title.isBlank() ? "New Tab" : tab.title;
        setAccessibleName(tabControl, "Tab: " + title);
        tabControl.setToolTipText(title
                + ((tab.url == null || tab.url.isBlank()) ? "" : "\n" + tab.url)
                + "\n" + webKitMemorySummary());
        tabControl.getAccessible().addAccessibleControlListener(new AccessibleControlAdapter() {
            @Override public void getRole(AccessibleControlEvent e) {
                e.detail = ACC.ROLE_TABITEM;
            }

            @Override public void getState(AccessibleControlEvent e) {
                e.detail = ACC.STATE_FOCUSABLE | ACC.STATE_SELECTABLE;
                if (active) e.detail |= ACC.STATE_SELECTED;
                if (tabControl.isFocusControl()) e.detail |= ACC.STATE_FOCUSED;
                if (tab.loading) e.detail |= ACC.STATE_BUSY;
            }
        });
        tabControl.getAccessible().addAccessibleActionListener(new AccessibleActionAdapter() {
            @Override public void getActionCount(AccessibleActionEvent e) { e.count = 2; }

            @Override public void getName(AccessibleActionEvent e) {
                if (e.index == 0) e.result = "select";
                else if (e.index == 1) e.result = "close";
            }

            @Override public void doAction(AccessibleActionEvent e) {
                if (tabControl.isDisposed()) return;
                if (e.index == 0) {
                    tabManager.switchTo(tab);
                    e.result = ACC.OK;
                } else if (e.index == 1) {
                    tabManager.closeTab(tab);
                    e.result = ACC.OK;
                }
            }
        });
        tabControl.addListener(SWT.FocusIn, e -> tabControl.redraw());
        tabControl.addListener(SWT.FocusOut, e -> tabControl.redraw());
        tabControl.addListener(SWT.KeyDown, e -> {
            if (e.keyCode == SWT.TAB) {
                tabControl.traverse((e.stateMask & SWT.SHIFT) != 0
                        ? SWT.TRAVERSE_TAB_PREVIOUS : SWT.TRAVERSE_TAB_NEXT);
                e.doit = false;
            } else if (e.keyCode == SWT.CR || e.character == ' ') {
                tabManager.switchTo(tab);
                e.doit = false;
            } else if ((!vertical && e.keyCode == SWT.ARROW_LEFT)
                    || (vertical && e.keyCode == SWT.ARROW_UP)) {
                tabManager.cycleTabs(-1);
                requestActiveTabFocus();
                e.doit = false;
            } else if ((!vertical && e.keyCode == SWT.ARROW_RIGHT)
                    || (vertical && e.keyCode == SWT.ARROW_DOWN)) {
                tabManager.cycleTabs(1);
                requestActiveTabFocus();
                e.doit = false;
            } else if (e.keyCode == SWT.DEL) {
                tabManager.closeTab(tab);
                e.doit = false;
            }
        });
    }

    private static String ellipsize(GC gc, String value, int maxWidth) {
        String text = value == null ? "" : value;
        if (maxWidth <= 0) return "";
        if (gc.textExtent(text).x <= maxWidth) return text;
        String suffix = "…";
        int codePoints = text.codePointCount(0, text.length());
        int low = 0;
        int high = codePoints;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            int end = text.offsetByCodePoints(0, mid);
            String candidate = text.substring(0, end) + suffix;
            if (gc.textExtent(candidate).x <= maxWidth) low = mid;
            else high = mid - 1;
        }
        int end = text.offsetByCodePoints(0, low);
        return text.substring(0, end) + suffix;
    }

    private static void drawCloseGlyph(GC gc, int cx, int cy) {
        gc.setLineWidth(1);
        gc.setLineCap(SWT.CAP_ROUND);
        gc.drawLine(cx - 4, cy - 4, cx + 4, cy + 4);
        gc.drawLine(cx + 4, cy - 4, cx - 4, cy + 4);
    }

    private void renderTopBarTab(TabManager.TabData tab, boolean active, int tabWidth) {
        Canvas control = new Canvas(tabStripRow, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        control.setLayoutData(new RowData(tabWidth, TOP_TAB_STRIP_HEIGHT));
        Color passiveBg = chromeDeepWidgetBackground();
        boolean[] hover = {false};
        boolean[] closeHot = {false};

        installTabAccessibility(control, tab, active, false);
        control.addPaintListener(e -> {
            GC gc = e.gc;
            gc.setAntialias(SWT.ON);
            Point size = control.getSize();
            boolean glass = glassChrome() && windowGlassApplied;
            boolean dragged = tabDragActive && control == dragArmedControl;
            int ox = slideOffsetX(control) + (dragged ? draggedPaintOffsetX : 0);
            if (passiveBg != null) {
                // Clear the slot itself (without the transient offsets) so a
                // sliding tab never smears over the band behind it.
                gc.setBackground(passiveBg);
                gc.fillRectangle(0, 0, size.x, size.y);
            }
            if (active) {
                if (glass) {
                    gc.setBackground(colTabActive);
                    gc.fillRoundRectangle(ox + 1, 1, size.x - 2, size.y - 2, 14, 14);
                    gc.setForeground(colSeparator);
                    gc.drawRoundRectangle(ox + 1, 1, size.x - 3, size.y - 3, 14, 14);
                } else {
                    // Chrome: the active tab shares the nav row's tone and is
                    // rounded only on top, so it reads as one surface with it.
                    gc.setBackground(colNavBg);
                    gc.fillRoundRectangle(ox, 3, size.x, size.y - 1, 14, 14);
                    gc.fillRectangle(ox, size.y - 7, size.x, 7);
                }
            } else if (hover[0] && !dragged) {
                gc.setBackground(colTabHover);
                gc.fillRoundRectangle(ox + 1, 4, size.x - 2, size.y - 6, 10, 10);
            } else if (!active && !glass) {
                // Hairline divider between resting inactive tabs, Chrome-style:
                // only when neither this tab nor its left neighbour wants the
                // visual weight of a card.
                Object idx = control.getData(DATA_TAB_INDEX);
                if (idx instanceof Integer && (Integer) idx > 0) {
                    gc.setForeground(colSeparator);
                    gc.drawLine(ox, 9, ox, size.y - 9);
                }
            }
            if (dragged) {
                gc.setForeground(colTabAccent);
                gc.setLineWidth(1);
                gc.drawRoundRectangle(ox + 1, 3, size.x - 3, size.y - 5, 12, 12);
            }
            if (control.isFocusControl()) {
                gc.setForeground(colTabAccent);
                gc.setLineWidth(1);
                gc.drawRoundRectangle(ox + 2, 4, size.x - 5, size.y - 8, 12, 12);
            }
            if (tab.loading) {
                int progressWidth = Math.max(4,
                        (size.x - 16) * Math.max(4, Math.min(96, tab.progress)) / 100);
                gc.setBackground(colTabAccent);
                gc.fillRoundRectangle(ox + 8, size.y - 3, progressWidth, 2, 2, 2);
            }

            gc.setFont(fontSectionLabel);
            gc.setForeground(active ? colTextAccent : colTextMuted);
            String mark = tabMark(tab);
            Point markSize = gc.textExtent(mark);
            gc.drawText(mark, ox + 12, Math.max(0, (size.y - markSize.y) / 2), true);

            // Hover memory readout (Chrome hovercard-equivalent): painted
            // into the tab itself so it is impossible to miss, taking width
            // away from the title only while hovered.
            String memoryLabel = null;
            int memoryWidth = 0;
            if (hover[0] && !dragged) {
                long mb = webKitMemoryMb();
                if (mb >= 0) {
                    memoryLabel = mb + " MB";
                    gc.setFont(fontSectionLabel);
                    memoryWidth = gc.textExtent(memoryLabel).x + 8;
                }
            }

            gc.setFont(fontTab);
            gc.setForeground(tab.sleeping ? colTextMuted
                    : active ? colTextPrimary : colTextMuted);
            String raw = tab.title == null || tab.title.isBlank() ? "New Tab" : tab.title;
            String fitted = ellipsize(gc, raw, Math.max(20, size.x - 76 - memoryWidth));
            Point textSize = gc.textExtent(fitted);
            gc.drawText(fitted, ox + 34, Math.max(0, (size.y - textSize.y) / 2), true);

            if (memoryLabel != null) {
                gc.setFont(fontSectionLabel);
                gc.setForeground(colTextMuted);
                Point ms = gc.textExtent(memoryLabel);
                gc.drawText(memoryLabel, ox + size.x - 40 - ms.x,
                        Math.max(0, (size.y - ms.y) / 2), true);
            }

            if (active || hover[0]) {
                gc.setForeground(closeHot[0] ? colTextPrimary : colTextMuted);
                drawCloseGlyph(gc, ox + size.x - 18, size.y / 2);
            }
        });
        control.addMouseTrackListener(new MouseTrackAdapter() {
            @Override public void mouseEnter(MouseEvent e) {
                hover[0] = true;
                Object model = control.getData(DATA_TAB_MODEL);
                if (model instanceof TabManager.TabData) {
                    // Refresh the tooltip so the WebKit memory line is current.
                    updateTabPresentation(control, (TabManager.TabData) model);
                }
                control.redraw();
            }

            @Override public void mouseExit(MouseEvent e) {
                hover[0] = false;
                closeHot[0] = false;
                control.redraw();
            }
        });
        control.addMouseMoveListener(e -> {
            boolean hot = e.x >= control.getSize().x - 36;
            if (hot != closeHot[0]) {
                closeHot[0] = hot;
                control.redraw();
            }
        });
        control.addMouseListener(new MouseAdapter() {
            @Override public void mouseDown(MouseEvent e) {
                if (e.button == 1) {
                    control.setFocus();
                    armTabDrag(control, tab);
                }
            }

            @Override public void mouseUp(MouseEvent e) {
                if (dragClickWasConsumed()) return;
                if (e.button == 2) {
                    tabManager.closeTab(tab);
                } else if (e.button == 1) {
                    if (e.x >= control.getSize().x - 36) tabManager.closeTab(tab);
                    else tabManager.switchTo(tab);
                }
            }
        });
        installTabContextMenu(tab, control);
    }

    private void renderSidebarTab(TabManager.TabData tab, boolean active) {
        Canvas control = new Canvas(tabStripRow, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        GridData data = new GridData(SWT.FILL, SWT.TOP, true, false);
        data.heightHint = 42;
        control.setLayoutData(data);
        boolean[] hover = {false};
        boolean[] closeHot = {false};

        installTabAccessibility(control, tab, active, true);
        control.addPaintListener(e -> {
            GC gc = e.gc;
            gc.setAntialias(SWT.ON);
            Point size = control.getSize();
            if (active) {
                gc.setBackground(colTabActive);
                gc.fillRoundRectangle(4, 2, size.x - 8, size.y - 4, 12, 12);
                gc.setForeground(colSeparator);
                gc.drawRoundRectangle(4, 2, size.x - 9, size.y - 5, 12, 12);
                gc.setBackground(colTabAccent);
                gc.fillRoundRectangle(3, size.y / 2 - 8, 3, 16, 3, 3);
            } else if (hover[0]) {
                gc.setBackground(colTabHover);
                gc.fillRoundRectangle(4, 2, size.x - 8, size.y - 4, 12, 12);
            }
            if (control.isFocusControl()) {
                gc.setForeground(colTabAccent);
                gc.setLineWidth(1);
                gc.drawRoundRectangle(5, 3, size.x - 11, size.y - 7, 10, 10);
            }

            gc.setFont(fontSectionLabel);
            gc.setForeground(active ? colTextAccent : colTextMuted);
            String mark = tabMark(tab);
            Point markSize = gc.textExtent(mark);
            gc.drawText(mark, 16, Math.max(0, (size.y - markSize.y) / 2), true);
            if (tab.loading) {
                gc.setBackground(colTabAccent);
                gc.fillOval(29, size.y / 2 - 2, 4, 4);
            }

            gc.setFont(fontSideTab);
            gc.setForeground(tab.sleeping ? colTextMuted
                    : active ? colTextPrimary : colTextMuted);
            String raw = tab.title == null || tab.title.isBlank() ? "New Tab" : tab.title;
            String fitted = ellipsize(gc, raw, Math.max(24, size.x - 82));
            Point textSize = gc.textExtent(fitted);
            gc.drawText(fitted, 43, Math.max(0, (size.y - textSize.y) / 2), true);

            if (active || hover[0]) {
                gc.setForeground(closeHot[0] ? colTextPrimary : colTextMuted);
                drawCloseGlyph(gc, size.x - 19, size.y / 2);
            }
            drawInsertionMarker(gc, size, control);
        });
        control.addMouseTrackListener(new MouseTrackAdapter() {
            @Override public void mouseEnter(MouseEvent e) {
                hover[0] = true;
                Object model = control.getData(DATA_TAB_MODEL);
                if (model instanceof TabManager.TabData) {
                    // Refresh the tooltip so the WebKit memory line is current.
                    updateTabPresentation(control, (TabManager.TabData) model);
                }
                control.redraw();
            }

            @Override public void mouseExit(MouseEvent e) {
                hover[0] = false;
                closeHot[0] = false;
                control.redraw();
            }
        });
        control.addMouseMoveListener(e -> {
            boolean hot = e.x >= control.getSize().x - 38;
            if (hot != closeHot[0]) {
                closeHot[0] = hot;
                control.redraw();
            }
        });
        control.addMouseListener(new MouseAdapter() {
            @Override public void mouseDown(MouseEvent e) {
                if (e.button == 1) {
                    control.setFocus();
                    armTabDrag(control, tab);
                }
            }

            @Override public void mouseUp(MouseEvent e) {
                if (dragClickWasConsumed()) return;
                if (e.button == 2) {
                    tabManager.closeTab(tab);
                } else if (e.button == 1) {
                    if (e.x >= control.getSize().x - 38) tabManager.closeTab(tab);
                    else tabManager.switchTo(tab);
                }
            }
        });
        installTabContextMenu(tab, control);
    }

    // ── Callbacks from TabManager ────────────────────────────────────────────

    private static String displayAddress(String url) {
        if (url == null || url.isBlank() || "rigel://home".equals(url)
                || "about:blank".equals(url)) return "";
        return url;
    }

    private void setNavigationButtonState(RoundedIconLabel button, boolean enabled) {
        if (button == null || button.isDisposed()) return;
        button.setEnabled(enabled);
        button.setForeground(enabled ? colTextMuted : colSeparator);
        button.redraw();
    }

    private void syncAddressAndNavigation() {
        if (shell == null || shell.isDisposed()) return;
        if (addressBar != null && !addressBar.isDisposed()
                && shell.getDisplay().getFocusControl() != addressBar) {
            addressBar.setText(displayAddress(currentUrl()));
        }

        RigelBrowser active = tabManager == null ? null : tabManager.getActiveBrowser();
        boolean canBack = false;
        boolean canForward = false;
        if (active != null && !active.isDisposed()) {
            try {
                canBack = active.isBackEnabled();
                canForward = active.isForwardEnabled();
            } catch (org.eclipse.swt.SWTException ignored) {
            }
        }
        setNavigationButtonState(backBtn, canBack);
        setNavigationButtonState(fwdBtn, canForward);
        if (tabManager != null)
            onActiveTabLoadState(tabManager.isActiveLoading(), tabManager.getActiveProgress());
    }

    private int addressSyncGeneration;

    public void onUrlChanged(String url) {
        if (shell == null || shell.isDisposed()) return;
        Display display = shell.getDisplay();
        int generation = ++addressSyncGeneration;
        display.asyncExec(() -> {
            if (shell == null || shell.isDisposed()) return;
            syncAddressAndNavigation();
            display.timerExec(90, () -> {
                if (shell == null || shell.isDisposed()
                        || generation != addressSyncGeneration) return;
                syncAddressAndNavigation();
            });
        });
    }

    public void onTabsChanged() {
        if (shell == null || shell.isDisposed()) return;
        shell.getDisplay().asyncExec(() -> {
            if (shell != null && !shell.isDisposed()) refreshTabStrip();
        });
    }

    public void onActiveTabChanged() {
        int generation = ++findSearchGeneration;
        if (findBar == null || findBar.isDisposed() || findField == null
                || findField.isDisposed() || findResult == null || findResult.isDisposed()) return;
        String query = findField.getText();
        if (query.isBlank()) {
            findResult.setText("");
            return;
        }
        findResult.setText("Searching…");
        runFind(query, true, findResult, generation);
    }

    public void onActiveTabLoadState(boolean loading, int progress) {
        if (shell == null || shell.isDisposed()) return;
        loadProgressGeneration++;
        loadProgressPercent = loading ? Math.max(4, Math.min(96, progress)) : 0;
        if (loadProgressBar != null && !loadProgressBar.isDisposed()) loadProgressBar.redraw();
        if (reloadBtn != null && !reloadBtn.isDisposed()) {
            reloadBtn.setText(loading ? "close" : "reload");
            reloadBtn.setToolTipText(loading ? "Stop loading (Cmd+.)" : "Reload (Cmd+R)");
            setAccessibleName(reloadBtn, loading ? "Stop loading" : "Reload");
            reloadBtn.redraw();
        }
        redrawActiveTabControl();
    }

    public void onTitleChanged(String title) {
        if (shell == null || shell.isDisposed()) return;
        if (!statusVisible) shell.setText(windowTitle());
        redrawTabPresentations();
    }

    void redrawTabControl(TabManager.TabData tab, boolean stateChanged) {
        if (tab == null || tabStripRow == null || tabStripRow.isDisposed()) return;
        for (Control control : tabStripRow.getChildren()) {
            if (control.getData(DATA_TAB_MODEL) != tab) continue;
            updateTabPresentation(control, tab);
            if (stateChanged) control.getAccessible().sendEvent(ACC.EVENT_STATE_CHANGED, null);
            return;
        }
    }

    private void redrawTabPresentations() {
        if (tabStripRow == null || tabStripRow.isDisposed()) return;
        for (Control control : tabStripRow.getChildren()) {
            if (control.getData(DATA_TAB_MODEL) instanceof TabManager.TabData tab)
                updateTabPresentation(control, tab);
        }
    }

    private void updateTabPresentation(Control control, TabManager.TabData tab) {
        if (control == null || control.isDisposed() || tab == null) return;
        String title = tab.title == null || tab.title.isBlank() ? "New Tab" : tab.title;
        setAccessibleName(control, "Tab: " + title);
        String sleepNote = tab.sleeping ? "\nSleeping — switch here to reload" : "";
        control.setToolTipText(title
                + ((tab.url == null || tab.url.isBlank()) ? "" : "\n" + tab.url)
                + sleepNote
                + "\n" + webKitMemorySummary());
        control.redraw();
    }

    /** True while the given browser has a live transfer; tab sleep must
     *  never discard the page underneath an in-flight download. */
    boolean hasActiveDownload(RigelBrowser browser) {
        synchronized (downloads) {
            for (DownloadRecord record : downloads) {
                if (record.owner == browser && record.isActive()) return true;
            }
        }
        return false;
    }

    private void requestActiveTabFocus() {
        if (tabManager == null) return;
        int active = tabManager.getActiveIndex();
        List<TabManager.TabData> tabs = tabManager.getTabs();
        requestedTabFocus = active >= 0 && active < tabs.size() ? tabs.get(active) : null;
    }

    public void onLoadProgress(int current, int total, boolean completed) {
        if (shell == null || shell.isDisposed()) return;
        long generation = ++loadProgressGeneration;
        int percent = completed ? 100
                : total <= 0 ? 12 : Math.max(4, Math.min(96, current * 100 / total));
        loadProgressPercent = percent;
        if (reloadBtn != null && !reloadBtn.isDisposed()) {
            reloadBtn.setText(completed ? "reload" : "close");
            reloadBtn.setToolTipText(completed ? "Reload (Cmd+R)" : "Stop loading (Cmd+.)");
            setAccessibleName(reloadBtn, completed ? "Reload" : "Stop loading");
            reloadBtn.redraw();
        }
        if (loadProgressBar != null && !loadProgressBar.isDisposed()) loadProgressBar.redraw();
        redrawActiveTabControl();
        if (completed) syncAddressAndNavigation();
        if (completed) {
            shell.getDisplay().timerExec(180, () -> {
                if (shell == null || shell.isDisposed()
                        || generation != loadProgressGeneration) return;
                loadProgressPercent = 0;
                if (loadProgressBar != null && !loadProgressBar.isDisposed())
                    loadProgressBar.redraw();
            });
        }
    }

    private void redrawActiveTabControl() {
        if (tabStripRow == null || tabStripRow.isDisposed() || tabManager == null) return;
        int activeIndex = tabManager.getActiveIndex();
        List<TabManager.TabData> tabs = tabManager.getTabs();
        if (activeIndex < 0 || activeIndex >= tabs.size()) return;
        TabManager.TabData active = tabs.get(activeIndex);
        for (Control control : tabStripRow.getChildren()) {
            if (control.getData(DATA_TAB_MODEL) == active) {
                control.redraw();
                break;
            }
        }
    }

    public void onZoomChanged(int percent) {
        if (shell == null || shell.isDisposed()) return;
        Display display = shell.getDisplay();
        display.asyncExec(() -> {
            if (shell == null || shell.isDisposed()) return;
            settings.setZoomPercent(percent);
            presentStatus(percent == 100 ? "Actual size" : "Zoom " + percent + "%");
        });
    }

    public void recordHistory(String title, String url) {
        if (history != null && (settings == null || settings.isHistoryEnabled())) history.record(title, url);
    }

    public void attachPageContextMenu(RigelBrowser browser) {
        if (!Boolean.getBoolean("rigel.page.contextMenu.enabled")) return;

        Menu menu = new Menu(browser);
        browser.setMenu(menu);

        addMenuItem(menu, "Back", () -> tabManager.back());
        addMenuItem(menu, "Forward", () -> tabManager.forward());
        addMenuItem(menu, "Reload", () -> tabManager.reload());
        addMenuItem(menu, "Stop Loading", () -> tabManager.stopLoading());
        new MenuItem(menu, SWT.SEPARATOR);
        addMenuItem(menu, "New Tab", () -> tabManager.createTab("New Tab", "rigel://home"));
        addMenuItem(menu, "Duplicate Tab", () -> tabManager.duplicateActiveTab());
        addMenuItem(menu, "Reopen Closed Tab", () -> tabManager.reopenClosedTab());
        new MenuItem(menu, SWT.SEPARATOR);
        addMenuItem(menu, "Bookmark Page", this::toggleBookmark);
        addMenuItem(menu, "Copy Page URL", () -> copyToClipboard(currentUrl()));
        addMenuItem(menu, "Find in Page", this::showFindBar);
        new MenuItem(menu, SWT.SEPARATOR);
        addMenuItem(menu, "History", this::showHistoryDialog);
        addMenuItem(menu, "Bookmarks", this::showBookmarksDialog);
        addMenuItem(menu, "Downloads", this::showDownloadsDialog);
        addMenuItem(menu, "Print", () -> tabManager.printActive());
    }

    private MenuItem addMenuItem(Menu menu, String text, Runnable action) {
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(text);
        item.addListener(SWT.Selection, e -> action.run());
        return item;
    }

    private void buildMenuBar() {
        Menu bar = new Menu(shell, SWT.BAR);
        shell.setMenuBar(bar);

        MenuItem fileHeader = new MenuItem(bar, SWT.CASCADE);
        fileHeader.setText("File");
        Menu file = new Menu(shell, SWT.DROP_DOWN);
        fileHeader.setMenu(file);
        addMenuItem(file, "New Window", SWT.MOD1 | 'N', () -> newWindowAction.openWindow(null, -1, -1));
        addMenuItem(file, "New Tab", SWT.MOD1 | 'T', () -> tabManager.createTab("New Tab", "rigel://home"));
        addMenuItem(file, "Open File...", SWT.MOD1 | 'O', this::openFile);
        addMenuItem(file, "Save Page As...", SWT.MOD1 | 'S', this::savePageAs);
        addMenuItem(file, "Print...", SWT.MOD1 | 'P', () -> tabManager.printActive());
        new MenuItem(file, SWT.SEPARATOR);
        addMenuItem(file, "Close Tab", SWT.MOD1 | 'W', () -> tabManager.closeTab(tabManager.getActiveIndex()));
        addMenuItem(file, "Close Window", SWT.MOD1 | SWT.SHIFT | 'W', () -> shell.close());

        MenuItem editHeader = new MenuItem(bar, SWT.CASCADE);
        editHeader.setText("Edit");
        Menu edit = new Menu(shell, SWT.DROP_DOWN);
        editHeader.setMenu(edit);
        addMenuItem(edit, "Cut", SWT.MOD1 | 'X', () -> performEditAction(RigelBrowser.EditAction.CUT));
        addMenuItem(edit, "Copy", SWT.MOD1 | 'C', () -> performEditAction(RigelBrowser.EditAction.COPY));
        addMenuItem(edit, "Paste", SWT.MOD1 | 'V', () -> performEditAction(RigelBrowser.EditAction.PASTE));
        addMenuItem(edit, "Select All", SWT.MOD1 | 'A', () -> performEditAction(RigelBrowser.EditAction.SELECT_ALL));
        new MenuItem(edit, SWT.SEPARATOR);
        addMenuItem(edit, "Copy Page URL", SWT.MOD1 | SWT.SHIFT | 'C', () -> copyToClipboard(currentUrl()));
        addMenuItem(edit, "Find...", SWT.MOD1 | 'F', this::showFindBar);
        addMenuItem(edit, "Find Next", SWT.MOD1 | 'G', () -> {
            if (findBar != null && !findBar.isDisposed() && findField != null)
                tabManager.findInPage(findField.getText(), true);
        });
        addMenuItem(edit, "Find Previous", SWT.MOD1 | SWT.SHIFT | 'G', () -> {
            if (findBar != null && !findBar.isDisposed() && findField != null)
                tabManager.findInPage(findField.getText(), false);
        });

        MenuItem viewHeader = new MenuItem(bar, SWT.CASCADE);
        viewHeader.setText("View");
        Menu view = new Menu(shell, SWT.DROP_DOWN);
        viewHeader.setMenu(view);
        addMenuItem(view, "Reload", SWT.MOD1 | 'R', () -> tabManager.reload());
        addMenuItem(view, "Reload Ignoring Cache", SWT.MOD1 | SWT.SHIFT | 'R', () -> tabManager.reloadIgnoringCache());
        MenuItem stopItem = addMenuItem(view, "Stop Loading", SWT.MOD1 | '.', () -> tabManager.stopLoading());
        new MenuItem(view, SWT.SEPARATOR);
        MenuItem backItem = addMenuItem(view, "Back", SWT.MOD1 | '[', () -> tabManager.back());
        MenuItem forwardItem = addMenuItem(view, "Forward", SWT.MOD1 | ']', () -> tabManager.forward());
        addMenuItem(view, "Home", SWT.MOD1 | SWT.SHIFT | 'H', () -> tabManager.navigate("rigel://home"));
        new MenuItem(view, SWT.SEPARATOR);
        addMenuItem(view, "Zoom In", SWT.MOD1 | '+', () -> tabManager.zoomIn());
        addMenuItem(view, "Zoom Out", SWT.MOD1 | '-', () -> tabManager.zoomOut());
        addMenuItem(view, "Actual Size", SWT.MOD1 | '0', () -> tabManager.zoomReset());
        new MenuItem(view, SWT.SEPARATOR);
        addMenuItem(view, "Toggle Sidebar", SWT.MOD1 | SWT.SHIFT | 'S', this::toggleLayout);
        addMenuItem(view, "Fullscreen", SWT.MOD1 | SWT.CTRL | 'F', this::toggleFullscreen);
        view.addListener(SWT.Show, e -> {
            RigelBrowser active = tabManager == null ? null : tabManager.getActiveBrowser();
            boolean canBack = false;
            boolean canForward = false;
            if (active != null && !active.isDisposed()) {
                try {
                    canBack = active.isBackEnabled();
                    canForward = active.isForwardEnabled();
                } catch (org.eclipse.swt.SWTException ignored) {
                }
            }
            backItem.setEnabled(canBack);
            forwardItem.setEnabled(canForward);
            stopItem.setEnabled(tabManager != null && tabManager.isActiveLoading());
        });

        MenuItem historyHeader = new MenuItem(bar, SWT.CASCADE);
        historyHeader.setText("History");
        Menu historyMenu = new Menu(shell, SWT.DROP_DOWN);
        historyHeader.setMenu(historyMenu);
        addMenuItem(historyMenu, "Show History", SWT.MOD1 | 'Y', this::showHistoryDialog);
        MenuItem reopenItem = addMenuItem(historyMenu, "Reopen Closed Tab",
                SWT.MOD1 | SWT.SHIFT | 'T', () -> tabManager.reopenClosedTab());
        historyMenu.addListener(SWT.Show,
                e -> reopenItem.setEnabled(tabManager != null && tabManager.hasClosedTabs()));

        MenuItem bookmarksHeader = new MenuItem(bar, SWT.CASCADE);
        bookmarksHeader.setText("Bookmarks");
        Menu bookmarksMenu = new Menu(shell, SWT.DROP_DOWN);
        bookmarksHeader.setMenu(bookmarksMenu);
        addMenuItem(bookmarksMenu, "Show Bookmarks", SWT.MOD1 | 'B', this::showBookmarksDialog);
        addMenuItem(bookmarksMenu, "Bookmark This Page", SWT.MOD1 | 'D', this::toggleBookmark);
        addMenuItem(bookmarksMenu, "Duplicate Tab", SWT.MOD1 | SWT.SHIFT | 'D', () -> tabManager.duplicateActiveTab());

        MenuItem toolsHeader = new MenuItem(bar, SWT.CASCADE);
        toolsHeader.setText("Tools");
        Menu tools = new Menu(shell, SWT.DROP_DOWN);
        toolsHeader.setMenu(tools);
        addMenuItem(tools, "Page Info", SWT.MOD1 | 'I', this::showPageInfoDialog);
        new MenuItem(tools, SWT.SEPARATOR);
        addMenuItem(tools, "Web Inspector…", SWT.MOD1 | SWT.ALT | 'I', this::showWebInspectorDialog);
        addMenuItem(tools, "JavaScript Console…", SWT.MOD1 | SWT.ALT | 'J', this::showJavaScriptConsole);
        addMenuItem(tools, "Media Diagnostics…", SWT.MOD1 | SWT.ALT | 'M', this::showMediaDiagnostics);
        MenuItem webInputHeader = new MenuItem(tools, SWT.CASCADE);
        webInputHeader.setText("Web Input for This Site");
        Menu webInputMenu = new Menu(shell, SWT.DROP_DOWN);
        webInputHeader.setMenu(webInputMenu);
        MenuItem inputAutomatic = webInputModeItem(webInputMenu, "Automatic (recommended)", "automatic");
        MenuItem inputCompatibility = webInputModeItem(webInputMenu, "Compatibility (player UI)", "compatibility");
        MenuItem inputControls = webInputModeItem(webInputMenu, "Controls First (buttons and dialogs)", "controls");
        webInputMenu.addListener(SWT.Show, e -> {
            String host = currentSiteHost();
            boolean available = !host.isEmpty();
            inputAutomatic.setEnabled(available);
            inputCompatibility.setEnabled(available);
            inputControls.setEnabled(available);
            String mode = available ? settings.getWebInputModeForHost(host) : "automatic";
            inputAutomatic.setSelection("automatic".equals(mode));
            inputCompatibility.setSelection("compatibility".equals(mode));
            inputControls.setSelection("controls".equals(mode));
        });
        new MenuItem(tools, SWT.SEPARATOR);
        addMenuItem(tools, "Downloads", SWT.MOD1 | SWT.SHIFT | 'J', this::showDownloadsDialog);
        addMenuItem(tools, "Settings...", SWT.MOD1 | ',', this::showSettingsDialog);

        MenuItem windowHeader = new MenuItem(bar, SWT.CASCADE);
        windowHeader.setText("Window");
        Menu window = new Menu(shell, SWT.DROP_DOWN);
        windowHeader.setMenu(window);
        addMenuItem(window, "Minimize", SWT.MOD1 | 'M', () -> shell.setMinimized(true));
        addMenuItem(window, "Zoom", 0, () -> shell.setMaximized(!shell.getMaximized()));
        new MenuItem(window, SWT.SEPARATOR);
        addMenuItem(window, "Bring to Front", 0, () -> {
            shell.setMinimized(false);
            shell.forceActive();
        });

        MenuItem helpHeader = new MenuItem(bar, SWT.CASCADE);
        helpHeader.setText("Help");
        Menu help = new Menu(shell, SWT.DROP_DOWN);
        helpHeader.setMenu(help);
        addMenuItem(help, "About Rigel", 0, this::showAboutDialog);
    }

    private MenuItem webInputModeItem(Menu menu, String label, String mode) {
        MenuItem item = new MenuItem(menu, SWT.RADIO);
        item.setText(label);
        item.addListener(SWT.Selection, e -> {
            if (!item.getSelection()) return;
            String host = currentSiteHost();
            if (host.isEmpty()) return;
            settings.setWebInputModeForHost(host, mode);
            if (tabManager != null) tabManager.refreshWebInputModes();
            settingsChangedAction.run();
            String description = switch (mode) {
                case "compatibility" -> "Compatibility";
                case "controls" -> "Controls First";
                default -> "Automatic";
            };
            presentStatus("Web input for " + host + ": " + description);
        });
        return item;
    }

    private String currentSiteHost() {
        String url = currentUrl();
        if (url == null || url.isBlank()) return "";
        try {
            String host = java.net.URI.create(url).getHost();
            if (host == null) return "";
            String clean = host.toLowerCase(java.util.Locale.ROOT);
            if (clean.startsWith("www.")) clean = clean.substring(4);
            return clean;
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    String webInputModeForUrl(String url) {
        return settings == null ? "automatic" : settings.getWebInputModeForUrl(url);
    }

    private MenuItem addMenuItem(Menu menu, String text, int accelerator, Runnable action) {
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(text);
        item.setAccelerator(accelerator);
        item.addListener(SWT.Selection, e -> action.run());
        return item;
    }

    private void markActiveWindow(Display display) {
        if (display != null && !display.isDisposed()) display.setData(DATA_ACTIVE_WINDOW, this);
        if (shell != null && !shell.isDisposed()) shell.setData(DATA_ACTIVE_WINDOW, this);
    }

    private void installSystemMenu(Display display) {
        if (display == null || display.isDisposed()) return;
        if (Boolean.TRUE.equals(display.getData(DATA_SYSTEM_MENU_WIRED))) return;

        Menu systemMenu = display.getSystemMenu();
        if (systemMenu == null || systemMenu.isDisposed()) return;

        display.setData(DATA_SYSTEM_MENU_WIRED, Boolean.TRUE);
        wireSystemMenuItem(display, systemMenu, SWT.ID_ABOUT, "About Rigel", BrowserUI::showAboutDialog);
        wireSystemMenuItem(display, systemMenu, SWT.ID_PREFERENCES, "Settings…", BrowserUI::showSettingsDialog);
        wireSystemMenuItem(display, systemMenu, SWT.ID_QUIT, "Quit Rigel", ui -> ui.quitAction.run());
    }

    private static void wireSystemMenuItem(Display display, Menu systemMenu, int id,
                                           String text,
                                           java.util.function.Consumer<BrowserUI> action) {
        MenuItem item = findSystemMenuItem(systemMenu, id);
        if (item == null || item.isDisposed()) {
            item = new MenuItem(systemMenu, SWT.PUSH);
            item.setID(id);
        }
        item.setText(text);
        item.addListener(SWT.Selection, e -> {
            BrowserUI active = activeWindow(display);
            if (active != null) action.accept(active);
        });
    }

    private static MenuItem findSystemMenuItem(Menu menu, int id) {
        if (menu == null || menu.isDisposed()) return null;
        for (MenuItem item : menu.getItems()) {
            if (item != null && !item.isDisposed() && item.getID() == id) return item;
        }
        return null;
    }

    private static BrowserUI activeWindow(Display display) {
        if (display == null || display.isDisposed()) return null;
        Object value = display.getData(DATA_ACTIVE_WINDOW);
        if (value instanceof BrowserUI ui && ui.shell != null && !ui.shell.isDisposed()) return ui;
        Shell activeShell = display.getActiveShell();
        if (activeShell != null) {
            Object shellData = activeShell.getData(DATA_ACTIVE_WINDOW);
            if (shellData instanceof BrowserUI ui && ui.shell != null && !ui.shell.isDisposed()) return ui;
        }
        return null;
    }

    private void performEditAction(RigelBrowser.EditAction action) {
        if (action == null || shell == null || shell.isDisposed()) return;

        Control focus = shell.getDisplay().getFocusControl();
        if (performTextEditAction(focus, action)) return;

        RigelBrowser active = tabManager == null ? null : tabManager.getActiveBrowser();
        if (active == null || active.isDisposed()) return;
        active.setFocus();
        active.performEditAction(action);
    }

    private boolean performTextEditAction(Control focus, RigelBrowser.EditAction action) {
        if (!(focus instanceof Text text) || text.isDisposed()) return false;
        switch (action) {
            case CUT -> text.cut();
            case COPY -> text.copy();
            case PASTE -> text.paste();
            case SELECT_ALL -> text.selectAll();
        }
        return true;
    }

    // ── Downloads ────────────────────────────────────────────────────────────

    public void attachDownloadHandler(RigelBrowser browser) {
        browser.configureDownloads(expandUserPath(settings.getDownloadDir()),
                settings.isAskBeforeDownload());
        if (browser.usesNativeDownloads()) {
            browser.addScriptFunction("rigelDownloadEvent", args -> {
                handleNativeDownloadEvent(browser, args);
                return null;
            });
            return;
        }
        browser.addScriptFunction("rigelDownload", args -> {
            if (args != null && args.length > 0 && args[0] != null)
                startDownload(args[0].toString());
            return null;
        });
        browser.addProgressListener(new RigelBrowser.ProgressListener() {
            @Override public void completed() { safeExecute(browser, DOWNLOAD_HOOK_JS); }
        });
    }

    private void handleNativeDownloadEvent(RigelBrowser browser, Object[] args) {
        if (args == null || args.length < 2) return;
        String event = stringArg(args, 0);
        String id = stringArg(args, 1);
        if (id.isBlank()) return;
        DownloadRecord record;
        synchronized (downloads) {
            record = downloads.stream().filter(item -> id.equals(item.id)).findFirst().orElse(null);
            if (record == null) {
                record = new DownloadRecord(id, browser, stringArg(args, 2), stringArg(args, 3),
                        stringArg(args, 4));
                downloads.add(0, record);
                trimDownloadHistory();
            }
        }
        if (args.length > 2 && !stringArg(args, 2).isBlank()) record.name = stringArg(args, 2);
        if (args.length > 3 && !stringArg(args, 3).isBlank()) record.url = stringArg(args, 3);
        if (args.length > 4 && !stringArg(args, 4).isBlank()) record.path = stringArg(args, 4);
        record.completedBytes = longArg(args, 5, record.completedBytes);
        record.totalBytes = longArg(args, 6, record.totalBytes);
        switch (event) {
            case "started", "progress" -> record.status = downloadProgress(record);
            case "finished" -> {
                record.status = "Finished";
                presentStatus("Downloaded " + (record.name.isBlank() ? "file" : record.name));
            }
            case "cancelled" -> record.status = "Cancelled";
            case "failed" -> {
                String error = stringArg(args, 7);
                record.status = error.isBlank() ? "Failed" : "Failed: " + error;
                presentStatus("Download failed" + (error.isBlank() ? "" : ": " + error));
            }
            default -> { }
        }
    }

    private static String stringArg(Object[] args, int index) {
        return args != null && index >= 0 && index < args.length && args[index] != null
                ? args[index].toString() : "";
    }

    private static long longArg(Object[] args, int index, long fallback) {
        try { return Long.parseLong(stringArg(args, index)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static String downloadProgress(DownloadRecord record) {
        if (record.totalBytes > 0) {
            long percent = Math.min(100, Math.max(0,
                    Math.round(record.completedBytes * 100.0 / record.totalBytes)));
            return "Downloading " + percent + "%";
        }
        if (record.completedBytes > 0)
            return "Downloading " + formatBytes(record.completedBytes);
        return "Downloading";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static final String DOWNLOAD_HOOK_JS = """
(function () {
    if (window.__rigelDl) return; window.__rigelDl = true;
    document.addEventListener('click', function (ev) {
        var a = ev.target && ev.target.closest ? ev.target.closest('a[href]') : null;
        if (!a) return;
        var href = a.href || '';
        var hasDownloadAttr = a.hasAttribute('download');
        var ext = (href.split('?')[0].split('#')[0].split('.').pop() || '').toLowerCase();
        var dlExts = ['zip','dmg','pkg','exe','msi','tar','gz','7z','rar','pdf',
                      'mp3','mp4','mov','avi','mkv','iso','apk','deb','jar','bin',
                      'csv','xlsx','docx','pptx','png','jpg','jpeg','gif','svg'];
        if (hasDownloadAttr || dlExts.indexOf(ext) !== -1) {
            if (typeof window.rigelDownload === 'function') {
                var accepted = window.rigelDownload(href);
                if (accepted !== false) ev.preventDefault();
            }
        }
    }, true);
})();
""";

    private void startDownload(String url) {
        String fileName = url.replaceFirst("[?#].*$", "");
        int slash = fileName.lastIndexOf('/');
        fileName = (slash >= 0 && slash < fileName.length() - 1)
                ? fileName.substring(slash + 1) : "download";
        final String suggested = safeFileName(fileName);

        String targetPath;
        if (settings.isAskBeforeDownload()) {
            FileDialog fd = new FileDialog(shell, SWT.SAVE);
            fd.setFileName(suggested);
            fd.setFilterPath(expandUserPath(settings.getDownloadDir()));
            fd.setOverwrite(true);
            targetPath = fd.open();
            if (targetPath == null) return;
        } else {
            try {
                targetPath = java.nio.file.Paths.get(
                        expandUserPath(settings.getDownloadDir()), suggested).toString();
            } catch (java.nio.file.InvalidPathException badPath) {
                showError(shell, "Could not create a file name for this download.");
                return;
            }
        }
        final String dest = targetPath;
        DownloadRecord record = new DownloadRecord("", null, suggested, url, dest);
        synchronized (downloads) {
            downloads.add(0, record);
            trimDownloadHistory();
        }

        Thread t = new Thread(() -> {
            try {
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url))
                        .timeout(java.time.Duration.ofSeconds(30))
                        .build();
                java.nio.file.Path out = java.nio.file.Paths.get(dest);
                if (out.getParent() != null) java.nio.file.Files.createDirectories(out.getParent());
                var response = DOWNLOAD_CLIENT.send(
                        req, java.net.http.HttpResponse.BodyHandlers.ofFile(out));
                int statusCode = response.statusCode();
                if (statusCode < 200 || statusCode >= 300) {
                    // An error page must not masquerade as a finished download.
                    java.nio.file.Files.deleteIfExists(out);
                    record.status = "Failed: HTTP " + statusCode;
                    runOnUi(() -> showError(shell,
                            "Download failed: server answered HTTP " + statusCode + "."));
                    return;
                }
                record.status = "Finished";
                runOnUi(() -> presentStatus("Downloaded " + suggested));
            } catch (Exception ex) {
                record.status = "Failed: " + ex.getMessage();
                runOnUi(() -> showError(shell, "Download failed: " + ex.getMessage()));
            }
        }, "rigel-download");
        t.setDaemon(true);
        t.start();
    }

    // ── Bookmarks ────────────────────────────────────────────────────────────

    private void toggleBookmark() {
        String url = currentUrl();
        if (url.isEmpty() || url.equals("rigel://home")) return;
        if (bookmarks.contains(url)) bookmarks.remove(url);
        else                         bookmarks.add(currentTitle(), url);
        presentStatus(bookmarks.contains(url) ? "Bookmarked" : "Bookmark removed");
    }

    private String currentUrl() {
        if (tabManager != null) {
            int idx = tabManager.getActiveIndex();
            List<TabManager.TabData> tabs = tabManager.getTabs();
            if (idx >= 0 && idx < tabs.size()) {
                String url = tabs.get(idx).url;
                if (url != null && !url.isBlank()) return url;
            }
        }
        return addressBar != null && !addressBar.isDisposed() ? addressBar.getText().trim() : "";
    }

    private String currentTitle() {
        if (tabManager != null) {
            int idx = tabManager.getActiveIndex();
            List<TabManager.TabData> tabs = tabManager.getTabs();
            if (idx >= 0 && idx < tabs.size()) {
                String title = tabs.get(idx).title;
                if (title != null && !title.isBlank()) return title;
            }
        }
        String url = currentUrl();
        return url.isBlank() ? "New Tab" : url;
    }

    private void copyToClipboard(String text) {
        if (text == null) text = "";
        Clipboard clipboard = new Clipboard(shell.getDisplay());
        try {
            clipboard.setContents(new Object[]{ text }, new Transfer[]{ TextTransfer.getInstance() });
            presentStatus("Copied");
        } finally {
            clipboard.dispose();
        }
    }

    private void openFile() {
        FileDialog fd = new FileDialog(shell, SWT.OPEN);
        fd.setText("Open File");
        fd.setFilterExtensions(new String[]{"*.html;*.htm;*.txt;*.pdf;*.*"});
        fd.setFilterNames(new String[]{"Web/Text/PDF Files", "All Files"});
        String path = fd.open();
        if (path == null || path.isBlank()) return;
        tabManager.navigate(java.nio.file.Paths.get(path).toUri().toString());
    }

    private void savePageAs() {
        RigelBrowser browser = tabManager != null ? tabManager.getActiveBrowser() : null;
        if (browser == null || browser.isDisposed()) return;
        FileDialog fd = new FileDialog(shell, SWT.SAVE);
        fd.setText("Save Page As");
        fd.setFileName(safeFileName(currentTitle()) + ".html");
        fd.setFilterPath(expandUserPath(settings.getDownloadDir()));
        fd.setFilterExtensions(new String[]{"*.html", "*.*"});
        fd.setFilterNames(new String[]{"HTML", "All Files"});
        fd.setOverwrite(true);
        String path = fd.open();
        if (path == null || path.isBlank()) return;
        browser.evaluateAsync("""
            return '<!DOCTYPE html>\\n' + (document.documentElement ? document.documentElement.outerHTML : document.body.innerHTML);
            """, (value, failure) -> {
            if (failure != null) {
                runOnUi(() -> showError(shell, "Could not save page: " + failure.getMessage()));
                return;
            }
            String html = value == null ? "" : value.toString();
            Thread writer = new Thread(() -> {
                try {
                java.nio.file.Path out = java.nio.file.Paths.get(path);
                if (out.getParent() != null) java.nio.file.Files.createDirectories(out.getParent());
                java.nio.file.Files.writeString(out, html, java.nio.charset.StandardCharsets.UTF_8);
                    runOnUi(() -> presentStatus("Page saved"));
                } catch (Exception ex) {
                    runOnUi(() -> showError(shell, "Could not save page: " + ex.getMessage()));
                }
            }, "rigel-save-page");
            writer.setDaemon(true);
            writer.start();
        });
    }

    private void showPageInfoDialog() {
        Shell d = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        trackUtilityShell(d);
        d.setText("Page Info");
        d.setBackground(colNavBg);
        d.setSize(520, 330);
        GridLayout dl = new GridLayout(2, false);
        dl.marginWidth = 18; dl.marginHeight = 16; dl.horizontalSpacing = 10; dl.verticalSpacing = 10;
        d.setLayout(dl);

        String url = currentUrl();
        String host = "";
        String scheme = "";
        try {
            java.net.URI uri = java.net.URI.create(url);
            host = uri.getHost() == null ? "" : uri.getHost();
            scheme = uri.getScheme() == null ? "" : uri.getScheme();
        } catch (Exception ignored) {}

        pageInfoRow(d, "Title", currentTitle());
        pageInfoRow(d, "URL", url);
        pageInfoRow(d, "Host", host);
        pageInfoRow(d, "Scheme", scheme);
        RigelBrowser activeBrowser = tabManager == null ? null : tabManager.getActiveBrowser();
        pageInfoRow(d, "Renderer", activeBrowser == null ? "Unknown" : activeBrowser.getBackendName());
        pageInfoRow(d, "Bookmarked", bookmarks.contains(url) ? "Yes" : "No");
        pageInfoRow(d, "Zoom", tabManager.getZoomPercent() + "%");

        Composite buttons = new Composite(d, SWT.NONE);
        buttons.setBackground(colNavBg);
        buttons.setLayoutData(new GridData(SWT.RIGHT, SWT.BOTTOM, true, true, 2, 1));
        RowLayout rl = new RowLayout(SWT.HORIZONTAL);
        rl.spacing = 8;
        buttons.setLayout(rl);
        Button copy = new Button(buttons, SWT.PUSH);
        copy.setText("Copy URL");
        copy.addListener(SWT.Selection, e -> copyToClipboard(url));
        Button close = new Button(buttons, SWT.PUSH);
        close.setText("Close");
        close.addListener(SWT.Selection, e -> d.close());
        openDialogCentered(d);
    }

    private void showWebInspectorDialog() {
        MessageBox mb = new MessageBox(shell, SWT.ICON_INFORMATION | SWT.OK);
        mb.setText("Web Inspector");
        mb.setMessage(
                "Web inspection is enabled for Rigel's WKWebView.\n\n" +
                "To use Apple's full inspector:\n" +
                "1. Open Safari → Settings → Advanced.\n" +
                "2. Enable ‘Show features for web developers’.\n" +
                "3. In Safari, open Develop → your Mac → the Rigel page.\n\n" +
                "You can also right-click a page and choose Inspect Element " +
                "when macOS exposes that item.\n\n" +
                "For quick JavaScript tests inside Rigel, use Tools → " +
                "JavaScript Console (⌘⌥J)."
        );
        mb.open();
    }

    private void showJavaScriptConsole() {
        RigelBrowser browser = tabManager == null ? null : tabManager.getActiveBrowser();
        if (browser == null || browser.isDisposed()) {
            showError(shell, "No active page is available.");
            return;
        }

        Shell d = new Shell(shell, SWT.DIALOG_TRIM | SWT.RESIZE);
        trackUtilityShell(d);
        d.setText("JavaScript Console — " + trimMiddle(currentTitle(), 42));
        d.setBackground(colNavBg);
        d.setSize(760, 520);
        GridLayout dl = new GridLayout(1, false);
        dl.marginWidth = 14;
        dl.marginHeight = 14;
        dl.verticalSpacing = 8;
        d.setLayout(dl);

        Label hint = new Label(d, SWT.WRAP);
        hint.setText("Runs JavaScript in the active tab. The value of the final expression is shown below.");
        hint.setBackground(colNavBg);
        hint.setForeground(colTextMuted);
        hint.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Text source = new Text(d, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        source.setBackground(colAddrBg);
        source.setForeground(colAddrFg);
        source.setFont(fontNav);
        source.setText("document.createElement('video').canPlayType('video/mp4; codecs=\"av01.0.05M.08\"')");
        GridData sourceData = new GridData(SWT.FILL, SWT.FILL, true, true);
        sourceData.heightHint = 190;
        source.setLayoutData(sourceData);

        Label outputLabel = new Label(d, SWT.NONE);
        outputLabel.setText("Result");
        outputLabel.setBackground(colNavBg);
        outputLabel.setForeground(colTextMuted);

        Text output = new Text(d, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        output.setBackground(colAddrBg);
        output.setForeground(colAddrFg);
        output.setFont(fontNav);
        output.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite buttons = dialogButtonRow(d);
        Button run = new Button(buttons, SWT.PUSH);
        run.setText("Run");
        Button clear = new Button(buttons, SWT.PUSH);
        clear.setText("Clear");
        Button close = new Button(buttons, SWT.PUSH);
        close.setText("Close");
        d.setDefaultButton(run);
        Display dialogDisplay = d.getDisplay();

        Runnable evaluate = () -> {
            String script = source.getText();
            if (script.isBlank()) return;
            output.setText("Running…");
            run.setEnabled(false);
            browser.evaluateAsync("return (0, eval)(" + jsStringLiteral(script) + ");", (value, failure) -> {
                if (dialogDisplay.isDisposed()) return;
                dialogDisplay.asyncExec(() -> {
                    if (d.isDisposed()) return;
                    run.setEnabled(true);
                    if (failure != null) {
                        output.setText("Error: " + failure.getMessage());
                    } else {
                        output.setText(value == null || value.toString().isBlank()
                                ? "undefined"
                                : value.toString());
                    }
                });
            });
        };

        run.addListener(SWT.Selection, e -> evaluate.run());
        clear.addListener(SWT.Selection, e -> output.setText(""));
        close.addListener(SWT.Selection, e -> d.close());

        openDialogCentered(d);
        source.setFocus();
        source.selectAll();
    }

    private void showMediaDiagnostics() {
        RigelBrowser browser = tabManager == null ? null : tabManager.getActiveBrowser();
        if (browser == null || browser.isDisposed()) {
            showError(shell, "No active page is available.");
            return;
        }

        Shell d = new Shell(shell, SWT.DIALOG_TRIM | SWT.RESIZE);
        trackUtilityShell(d);
        d.setText("Media Diagnostics — " + trimMiddle(currentTitle(), 42));
        d.setBackground(colNavBg);
        d.setSize(760, 560);
        GridLayout dl = new GridLayout(1, false);
        dl.marginWidth = 14; dl.marginHeight = 14; dl.verticalSpacing = 8;
        d.setLayout(dl);

        Label note = new Label(d, SWT.WRAP);
        note.setText("Reports WebKit codec capabilities, playback errors, buffering, and dropped frames. " +
                "Diagnostics are passive by default so protected and MSE players retain their native APIs; " +
                "AV1 compatibility mode adds targeted buffer tracing when enabled.");
        note.setBackground(colNavBg);
        note.setForeground(colTextMuted);
        note.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Text output = new Text(d, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        output.setBackground(colAddrBg);
        output.setForeground(colAddrFg);
        output.setFont(fontNav);
        output.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite buttons = dialogButtonRow(d);
        Button refresh = new Button(buttons, SWT.PUSH);
        refresh.setText("Refresh");
        Button copy = new Button(buttons, SWT.PUSH);
        copy.setText("Copy");
        Button close = new Button(buttons, SWT.PUSH);
        close.setText("Close");
        Display dialogDisplay = d.getDisplay();

        Runnable collect = () -> {
            output.setText("Collecting…");
            refresh.setEnabled(false);
            browser.evaluateAsync("""
                var videos = Array.from(document.querySelectorAll('video')).map(function(v, i) {
                    var q = null;
                    try { q = v.getVideoPlaybackQuality ? v.getVideoPlaybackQuality() : null; } catch (e) {}
                    var ahead = 0;
                    try {
                        for (var n = 0; n < v.buffered.length; n++) {
                            if (v.currentTime >= v.buffered.start(n) && v.currentTime <= v.buffered.end(n)) {
                                ahead = Math.max(0, v.buffered.end(n) - v.currentTime);
                                break;
                            }
                        }
                    } catch (e) {}
                    return {
                        index: i, currentTime: Number(v.currentTime || 0).toFixed(3),
                        duration: Number.isFinite(v.duration) ? Number(v.duration).toFixed(3) : String(v.duration),
                        paused: v.paused, ended: v.ended, playbackRate: v.playbackRate,
                        readyState: v.readyState, networkState: v.networkState,
                        dimensions: v.videoWidth + 'x' + v.videoHeight,
                        bufferedAheadSeconds: Number(ahead).toFixed(3),
                        totalFrames: q ? q.totalVideoFrames : (v.webkitDecodedFrameCount || 0),
                        droppedFrames: q ? q.droppedVideoFrames : (v.webkitDroppedFrameCount || 0)
                    };
                });
                return JSON.stringify({
                    url: location.href, visibility: document.visibilityState,
                    userAgent: navigator.userAgent,
                    mediaSource: typeof MediaSource !== 'undefined',
                    av1Mp4: document.createElement('video').canPlayType('video/mp4; codecs="av01.0.05M.08"'),
                    videos: videos,
                    mediaTrace: window.__rigelMediaTrace || null
                }, null, 2);
                """, (value, failure) -> {
                    if (dialogDisplay.isDisposed()) return;
                    dialogDisplay.asyncExec(() -> {
                    if (d.isDisposed()) return;
                    refresh.setEnabled(true);
                    output.setText(failure == null
                            ? (value == null || value.toString().isBlank() ? "No video elements found." : value.toString())
                            : "Error: " + failure.getMessage());
                    });
                });
        };

        refresh.addListener(SWT.Selection, e -> collect.run());
        copy.addListener(SWT.Selection, e -> copyToClipboard(output.getText()));
        close.addListener(SWT.Selection, e -> d.close());
        openDialogCentered(d);
        collect.run();
    }

    private static String jsStringLiteral(String value) {
        if (value == null) return "''";
        return "'" + value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029") + "'";
    }

    private void showAboutDialog() {
        RigelBrowser activeBrowser = tabManager == null ? null : tabManager.getActiveBrowser();
        String renderer = activeBrowser == null ? "Unknown" : activeBrowser.getBackendName();
        MessageBox mb = new MessageBox(shell, SWT.ICON_INFORMATION | SWT.OK);
        mb.setText("About Rigel");
        mb.setMessage("Rigel\n\nRenderer: " + renderer +
                "\nProxy: 127.0.0.1:" + proxyServer.getLocalPort() +
                "\n\nA local SWT/WKWebView browser.");
        mb.open();
    }

    private void pageInfoRow(Composite parent, String label, String value) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(label);
        l.setForeground(colTextMuted);
        l.setBackground(colNavBg);
        Text v = new Text(parent, SWT.READ_ONLY | SWT.BORDER);
        v.setText(value == null ? "" : value);
        v.setBackground(colAddrBg);
        v.setForeground(colAddrFg);
        v.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private static String safeFileName(String name) {
        String s = name == null || name.isBlank() ? "page" : name;
        s = s.replaceAll("[\\\\/:*?\"<>|]+", "_").trim();
        if (s.length() > 80) s = s.substring(0, 80);
        // Truncation can split a surrogate pair; a dangling half blows up
        // Paths.get with InvalidPathException.
        while (!s.isEmpty() && Character.isHighSurrogate(s.charAt(s.length() - 1))) {
            s = s.substring(0, s.length() - 1);
        }
        // Control characters are also rejected by Paths on some filesystems.
        s = s.replaceAll("[\\p{Cntrl}]", "");
        return s.isBlank() ? "page" : s;
    }

    private static String expandUserPath(String path) {
        if (path == null || path.isBlank()) return path;
        if (path.equals("~")) return System.getProperty("user.home");
        if (path.startsWith("~/")) {
            return java.nio.file.Paths.get(System.getProperty("user.home"), path.substring(2)).toString();
        }
        return path;
    }

    private void showHistoryDialog() {
        Shell d = new Shell(shell, SWT.DIALOG_TRIM | SWT.RESIZE);
        trackUtilityShell(d);
        d.setText("History");
        d.setBackground(colNavBg);
        d.setSize(780, 540);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 16;
        layout.marginHeight = 14;
        layout.verticalSpacing = 10;
        d.setLayout(layout);

        Text filter = settingsField(d, "Search history");
        setAccessibleName(filter, "Search history");
        Table table = createDataTable(d,
                new String[]{"Date", "Title", "Address"},
                new int[]{126, 240, 360});
        Label empty = dataTableEmptyLabel(d);

        java.text.SimpleDateFormat dateFormat =
                new java.text.SimpleDateFormat("MMM d, h:mm a");
        Runnable refresh = () -> {
            if (table.isDisposed()) return;
            String query = filter.getText().trim().toLowerCase(java.util.Locale.ROOT);
            table.removeAll();
            for (config.History.Entry entry : history.recent()) {
                String title = entry.title == null || entry.title.isBlank()
                        ? entry.url : entry.title;
                String haystack = (title + " " + entry.url)
                        .toLowerCase(java.util.Locale.ROOT);
                if (!query.isEmpty() && !haystack.contains(query)) continue;
                TableItem item = new TableItem(table, SWT.NONE);
                item.setText(new String[]{
                        dateFormat.format(new java.util.Date(entry.time)),
                        title,
                        entry.url
                });
                item.setData(entry.url);
            }
            empty.setText(table.getItemCount() == 0
                    ? (query.isEmpty() ? "No browsing history yet." : "No matching history.")
                    : "");
            empty.getParent().layout(true, true);
        };

        Composite buttons = dialogButtonRow(d);
        Button open = new Button(buttons, SWT.PUSH);
        open.setText("Open");
        Button copy = new Button(buttons, SWT.PUSH);
        copy.setText("Copy URL");
        Button clear = new Button(buttons, SWT.PUSH);
        clear.setText("Clear History");
        Button close = new Button(buttons, SWT.PUSH);
        close.setText("Close");

        java.util.function.Supplier<String> selectedUrl = () -> {
            TableItem[] selection = table.getSelection();
            return selection.length == 0 || selection[0].getData() == null
                    ? null : selection[0].getData().toString();
        };
        Runnable updateButtons = () -> {
            boolean selected = table.getSelectionCount() > 0;
            open.setEnabled(selected);
            copy.setEnabled(selected);
        };
        Runnable openSelected = () -> {
            String url = selectedUrl.get();
            if (url == null) return;
            tabManager.navigate(url);
            d.close();
        };

        table.addListener(SWT.Selection, e -> updateButtons.run());
        table.addListener(SWT.DefaultSelection, e -> openSelected.run());
        open.addListener(SWT.Selection, e -> openSelected.run());
        copy.addListener(SWT.Selection, e -> {
            String url = selectedUrl.get();
            if (url != null) copyToClipboard(url);
        });
        clear.addListener(SWT.Selection, e -> {
            MessageBox confirm = new MessageBox(d,
                    SWT.ICON_WARNING | SWT.YES | SWT.NO | SWT.APPLICATION_MODAL);
            confirm.setText("Clear History");
            confirm.setMessage("Remove all browsing history? This cannot be undone.");
            if (confirm.open() != SWT.YES) return;
            history.clear();
            refresh.run();
            updateButtons.run();
            presentStatus("History cleared");
        });
        close.addListener(SWT.Selection, e -> d.close());
        filter.addModifyListener(e -> {
            refresh.run();
            updateButtons.run();
        });

        refresh.run();
        updateButtons.run();
        openDialogCentered(d);
        filter.setFocus();
    }

    private void showBookmarksDialog() {
        Shell d = new Shell(shell, SWT.DIALOG_TRIM | SWT.RESIZE);
        trackUtilityShell(d);
        d.setText("Bookmarks");
        d.setBackground(colNavBg);
        d.setSize(740, 480);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 16;
        layout.marginHeight = 14;
        layout.verticalSpacing = 10;
        d.setLayout(layout);

        Table table = createDataTable(d,
                new String[]{"Title", "Address"},
                new int[]{250, 450});
        Label empty = dataTableEmptyLabel(d);

        Runnable refresh = () -> {
            if (table.isDisposed()) return;
            table.removeAll();
            for (config.Bookmarks.Entry entry : bookmarks.all()) {
                TableItem item = new TableItem(table, SWT.NONE);
                item.setText(new String[]{
                        entry.title == null || entry.title.isBlank() ? entry.url : entry.title,
                        entry.url
                });
                item.setData(entry.url);
            }
            empty.setText(table.getItemCount() == 0
                    ? "No bookmarks yet. Add the current page to start a collection."
                    : "");
            empty.getParent().layout(true, true);
        };

        Composite buttons = dialogButtonRow(d);
        Button open = new Button(buttons, SWT.PUSH);
        open.setText("Open");
        Button addCurrent = new Button(buttons, SWT.PUSH);
        addCurrent.setText("Add Current");
        Button remove = new Button(buttons, SWT.PUSH);
        remove.setText("Remove");
        Button copy = new Button(buttons, SWT.PUSH);
        copy.setText("Copy URL");
        Button close = new Button(buttons, SWT.PUSH);
        close.setText("Close");

        java.util.function.Supplier<String> selectedUrl = () -> {
            TableItem[] selection = table.getSelection();
            return selection.length == 0 || selection[0].getData() == null
                    ? null : selection[0].getData().toString();
        };
        Runnable updateButtons = () -> {
            boolean selected = table.getSelectionCount() > 0;
            open.setEnabled(selected);
            remove.setEnabled(selected);
            copy.setEnabled(selected);
            String current = currentUrl();
            addCurrent.setEnabled(!current.isBlank()
                    && !"rigel://home".equals(current)
                    && !bookmarks.contains(current));
        };
        Runnable openSelected = () -> {
            String url = selectedUrl.get();
            if (url == null) return;
            tabManager.navigate(url);
            d.close();
        };

        table.addListener(SWT.Selection, e -> updateButtons.run());
        table.addListener(SWT.DefaultSelection, e -> openSelected.run());
        open.addListener(SWT.Selection, e -> openSelected.run());
        addCurrent.addListener(SWT.Selection, e -> {
            String url = currentUrl();
            if (url.isBlank() || "rigel://home".equals(url)) return;
            bookmarks.add(currentTitle(), url);
            refresh.run();
            updateButtons.run();
        });
        remove.addListener(SWT.Selection, e -> {
            String url = selectedUrl.get();
            if (url == null) return;
            bookmarks.remove(url);
            refresh.run();
            updateButtons.run();
        });
        copy.addListener(SWT.Selection, e -> {
            String url = selectedUrl.get();
            if (url != null) copyToClipboard(url);
        });
        close.addListener(SWT.Selection, e -> d.close());

        refresh.run();
        updateButtons.run();
        openDialogCentered(d);
        table.setFocus();
    }

    private void showDownloadsDialog() {
        Shell d = new Shell(shell, SWT.DIALOG_TRIM | SWT.RESIZE);
        trackUtilityShell(d);
        d.setText("Downloads");
        d.setBackground(colNavBg);
        d.setSize(920, 500);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 16;
        layout.marginHeight = 14;
        layout.verticalSpacing = 10;
        d.setLayout(layout);

        Table table = createDataTable(d,
                new String[]{"Date", "Status", "File", "Location"},
                new int[]{124, 150, 190, 400});
        Label empty = dataTableEmptyLabel(d);
        java.text.SimpleDateFormat dateFormat =
                new java.text.SimpleDateFormat("MMM d, h:mm a");

        Composite buttons = dialogButtonRow(d);
        Button copyPath = new Button(buttons, SWT.PUSH);
        copyPath.setText("Copy Path");
        Button openFile = new Button(buttons, SWT.PUSH);
        openFile.setText("Open File");
        Button openFolder = new Button(buttons, SWT.PUSH);
        openFolder.setText("Open Folder");
        Button cancel = new Button(buttons, SWT.PUSH);
        cancel.setText("Cancel");
        Button clear = new Button(buttons, SWT.PUSH);
        clear.setText("Clear List");
        Button close = new Button(buttons, SWT.PUSH);
        close.setText("Close");

        java.util.function.Supplier<DownloadRecord> selectedRecord = () -> {
            TableItem[] selection = table.getSelection();
            return selection.length > 0 && selection[0].getData() instanceof DownloadRecord record
                    ? record : null;
        };
        Runnable updateButtons = () -> {
            DownloadRecord selected = selectedRecord.get();
            copyPath.setEnabled(selected != null && !selected.path.isBlank());
            openFile.setEnabled(selected != null && "Finished".equals(selected.status));
            openFolder.setEnabled(selected != null && !selected.path.isBlank());
            cancel.setEnabled(selected != null && selected.isActive()
                    && selected.owner != null && !selected.id.isBlank());
            synchronized (downloads) {
                clear.setEnabled(downloads.stream().anyMatch(record -> !record.isActive()));
            }
        };

        Runnable refresh = () -> {
            if (table.isDisposed()) return;
            DownloadRecord selected = selectedRecord.get();
            java.util.List<DownloadRecord> snapshot;
            synchronized (downloads) {
                snapshot = new java.util.ArrayList<>(downloads);
            }
            table.setRedraw(false);
            try {
                table.removeAll();
                int selectedIndex = -1;
                for (int i = 0; i < snapshot.size(); i++) {
                    DownloadRecord record = snapshot.get(i);
                    TableItem item = new TableItem(table, SWT.NONE);
                    item.setText(new String[]{
                            dateFormat.format(new java.util.Date(record.time)),
                            record.status,
                            record.name,
                            record.path
                    });
                    item.setData(record);
                    if (record == selected) selectedIndex = i;
                }
                if (selectedIndex >= 0) table.select(selectedIndex);
            } finally {
                table.setRedraw(true);
            }
            empty.setText(table.getItemCount() == 0
                    ? "Downloads started in Rigel will appear here."
                    : "");
            empty.getParent().layout(true, true);
            updateButtons.run();
        };

        table.addListener(SWT.Selection, e -> updateButtons.run());
        copyPath.addListener(SWT.Selection, e -> {
            DownloadRecord record = selectedRecord.get();
            if (record != null) copyToClipboard(record.path);
        });
        openFile.addListener(SWT.Selection, e -> {
            DownloadRecord record = selectedRecord.get();
            if (record == null || record.path.isBlank()) return;
            try {
                java.awt.Desktop.getDesktop().open(java.nio.file.Paths.get(record.path).toFile());
            } catch (Exception ex) {
                showError(d, "Could not open file: " + ex.getMessage());
            }
        });
        openFolder.addListener(SWT.Selection, e -> {
            DownloadRecord record = selectedRecord.get();
            if (record == null) return;
            try {
                java.nio.file.Path path = java.nio.file.Paths.get(record.path);
                java.nio.file.Path parent = path.getParent();
                if (parent != null) java.awt.Desktop.getDesktop().open(parent.toFile());
            } catch (Exception ex) {
                showError(d, "Could not open folder: " + ex.getMessage());
            }
        });
        cancel.addListener(SWT.Selection, e -> {
            DownloadRecord record = selectedRecord.get();
            if (record == null || record.owner == null || !record.owner.cancelDownload(record.id)) return;
            record.status = "Cancelling";
            refresh.run();
        });
        clear.addListener(SWT.Selection, e -> {
            synchronized (downloads) { downloads.removeIf(record -> !record.isActive()); }
            refresh.run();
        });
        close.addListener(SWT.Selection, e -> d.close());

        Runnable poll = new Runnable() {
            @Override public void run() {
                if (d.isDisposed()) return;
                refresh.run();
                d.getDisplay().timerExec(500, this);
            }
        };

        refresh.run();
        openDialogCentered(d);
        d.getDisplay().timerExec(500, poll);
    }

    private Table createDataTable(Composite parent, String[] headings, int[] widths) {
        Table table = new Table(parent,
                SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setBackground(colAddrBg);
        table.setForeground(colAddrFg);
        table.setFont(fontTab);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        for (int i = 0; i < headings.length; i++) {
            TableColumn column = new TableColumn(table, SWT.LEFT);
            column.setText(headings[i]);
            column.setWidth(i < widths.length ? widths[i] : 160);
        }
        table.addControlListener(new ControlAdapter() {
            @Override public void controlResized(ControlEvent e) {
                TableColumn[] columns = table.getColumns();
                if (columns.length == 0) return;
                int used = 0;
                for (int i = 0; i < columns.length - 1; i++) used += columns[i].getWidth();
                int target = Math.max(180, table.getClientArea().width - used - 4);
                if (Math.abs(columns[columns.length - 1].getWidth() - target) > 2)
                    columns[columns.length - 1].setWidth(target);
            }
        });
        return table;
    }

    private Label dataTableEmptyLabel(Composite parent) {
        Label label = new Label(parent, SWT.NONE);
        label.setBackground(colNavBg);
        label.setForeground(colTextMuted);
        label.setFont(fontTab);
        label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return label;
    }

    private Composite dialogButtonRow(Composite parent) {
        Composite row = new Composite(parent, SWT.NONE);
        row.setBackground(colNavBg);
        row.setLayoutData(new GridData(SWT.RIGHT, SWT.BOTTOM, true, false));
        RowLayout rl = new RowLayout(SWT.HORIZONTAL);
        rl.spacing = 8;
        row.setLayout(rl);
        return row;
    }

    private static String trimMiddle(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int left = Math.max(1, (max - 1) / 2);
        int right = Math.max(1, max - left - 1);
        return s.substring(0, left) + "…" + s.substring(s.length() - right);
    }

    // ── Fullscreen ───────────────────────────────────────────────────────────

    private void enforceFullscreenChromeState() {
        if (!isFullscreen) return;
        if (chromeComposite != null && !chromeComposite.isDisposed()) {
            GridData gd = (GridData) chromeComposite.getLayoutData();
            gd.exclude = true;
            chromeComposite.setVisible(false);
        }
        if (navBarComposite != null && !navBarComposite.isDisposed()) {
            GridData gd = (GridData) navBarComposite.getLayoutData();
            gd.exclude = true;
            navBarComposite.setVisible(false);
        }
        if (sashForm != null && !sashForm.isDisposed()) {
            rememberSidebarWeights();
            sashForm.setWeights(new int[]{0, 100});
        }
    }

    void prepareNativeFullscreenCapture() {
        if (shell != null && !shell.isDisposed()) {
            MacWindowCaptureProtection.prepareNativeFullscreen(shell);
        }
    }

    public void enterFullscreen() {
        enterFullscreen(tabManager == null ? null : tabManager.getActiveBrowser());
    }

    private void toggleFullscreen() {
        if (isFullscreen) {
            exitFullscreen();
            return;
        }
        enterFullscreen();
    }

    boolean enterFullscreen(RigelBrowser owner) {
        if (shell == null || shell.isDisposed() || owner == null || owner.isDisposed()) return false;
        if (tabManager == null || tabManager.getActiveBrowser() != owner) return false;
        if (isFullscreen && fullscreenOwner == owner) return true;
        if (isFullscreen) exitFullscreenInternal(true, true);
        if (isFullscreen) return false;
        hideFindBar();
        shellWasAlreadyFullscreen = shell.getFullScreen();
        fullscreenOwner = owner;
        isFullscreen = true;
        enforceFullscreenChromeState();

        if (!shellWasAlreadyFullscreen) shell.setFullScreen(true);
        shell.layout(true, true);
        MacWindowCaptureProtection.protectSoon(shell);

        owner.setFocus();
        safeExecute(owner, "if(document.body)document.body.classList.add('rigel-fullscreen');");
        return true;
    }

    public void exitFullscreen() {
        exitFullscreenInternal(true, true);
    }

    void exitFullscreenFrom(RigelBrowser requester) {
        if (fullscreenOwner != null && requester != fullscreenOwner) return;
        exitFullscreenInternal(false, true);
    }

    void onTabWillActivate(RigelBrowser next) {
        if (isFullscreen && fullscreenOwner != null && fullscreenOwner != next)
            exitFullscreenInternal(true, true);
    }

    void onBrowserClosing(RigelBrowser browser) {
        if (isFullscreen && browser != null && browser == fullscreenOwner)
            exitFullscreenInternal(true, true);
    }

    void onBrowserNavigating(RigelBrowser browser) {
        if (isFullscreen && browser != null && browser == fullscreenOwner)
            exitFullscreenInternal(true, true);
    }

    private void exitFullscreenInternal(boolean notifyPage, boolean updateShell) {
        if (!isFullscreen) return;
        RigelBrowser owner = fullscreenOwner;
        if (notifyPage && owner != null && !owner.isDisposed()) {
            safeExecute(owner,
                    "if(typeof window.__rigelExitFSSilent==='function')window.__rigelExitFSSilent();"
                    + "if(document.body)document.body.classList.remove('rigel-fullscreen');");
        }
        isFullscreen = false;
        if (shell == null || shell.isDisposed()) {
            shellWasAlreadyFullscreen = false;
            fullscreenOwner = null;
            return;
        }
        if (updateShell && !shellWasAlreadyFullscreen && shell != null && !shell.isDisposed())
            shell.setFullScreen(false);
        shellWasAlreadyFullscreen = false;
        fullscreenOwner = null;

        restoreChrome();
        MacWindowCaptureProtection.protectSoon(shell);
    }

    private void restoreChrome() {
        if (shell == null || shell.isDisposed()) return;
        if (chromeComposite != null && !chromeComposite.isDisposed()) {
            GridData gd = (GridData) chromeComposite.getLayoutData();
            gd.exclude = false; chromeComposite.setVisible(true);
            chromeComposite.layout(true, true);
        }
        if (navBarComposite != null && !navBarComposite.isDisposed()) {
            GridData gd = (GridData) navBarComposite.getLayoutData();
            gd.exclude = false; navBarComposite.setVisible(true);
            navBarComposite.layout(true, true);
        }
        if (sashForm != null && !sashForm.isDisposed())
            sashForm.setWeights(sidebarWeights);
        shell.layout(true, true);
        if (tabStripRow != null && !tabStripRow.isDisposed()) { tabStripRow.redraw(); tabStripRow.update(); }
        if (chromeComposite != null && !chromeComposite.isDisposed()) { chromeComposite.redraw(); chromeComposite.update(); }
    }

    // ── Address bar ──────────────────────────────────────────────────────────

    private void commitAddress() {
        String url = resolveAddressInput(addressBar.getText());
        if (url != null) {
            tabManager.navigate(url);
            tabManager.focusActiveBrowser();
        }
    }

    /** Shared by the omnibox and the built-in new-tab page. */
    String resolveAddressInput(String input) {
        String raw = input == null ? "" : input.trim();
        if (raw.isEmpty()) return null;
        if (raw.equalsIgnoreCase("rigel://home")) return "rigel://home";

        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return raw;
        if (looksLikeWebHost(raw)) return preferredSchemeForHost(raw) + raw;

        String query = java.net.URLEncoder.encode(
                raw, java.nio.charset.StandardCharsets.UTF_8);
        String template = validatedSearchTemplate();
        return template.replace("%s", query);
    }

    private boolean looksLikeWebHost(String raw) {
        if (raw.isEmpty() || raw.chars().anyMatch(Character::isWhitespace)
                || raw.indexOf('@') >= 0) return false;
        try {
            URI uri = URI.create("https://" + raw);
            String host = uri.getHost();
            if (host == null || host.isBlank()) return false;
            String lowerHost = host.toLowerCase(java.util.Locale.ROOT);
            return lowerHost.equals("localhost") || lowerHost.indexOf('.') >= 0
                    || lowerHost.indexOf(':') >= 0;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private String preferredSchemeForHost(String raw) {
        try {
            String host = URI.create("http://" + raw).getHost();
            return isLocalNetworkHost(host) ? "http://" : "https://";
        } catch (IllegalArgumentException ex) {
            return "https://";
        }
    }

    private static boolean isLocalNetworkHost(String host) {
        if (host == null || host.isBlank()) return false;
        String value = host.toLowerCase(java.util.Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]"))
            value = value.substring(1, value.length() - 1);
        if (value.equals("localhost") || value.endsWith(".local")
                || value.endsWith(".lan") || value.endsWith(".home")
                || value.endsWith(".internal") || value.endsWith(".localdomain")) return true;
        if (value.indexOf(':') >= 0 && (value.equals("::1") || value.startsWith("fc")
                || value.startsWith("fd") || value.startsWith("fe8")
                || value.startsWith("fe9") || value.startsWith("fea")
                || value.startsWith("feb"))) return true;

        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) return false;
        int[] address = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                address[i] = Integer.parseInt(octets[i]);
                if (address[i] < 0 || address[i] > 255) return false;
            }
        } catch (NumberFormatException ex) {
            return false;
        }
        return address[0] == 10 || address[0] == 127
                || (address[0] == 169 && address[1] == 254)
                || (address[0] == 172 && address[1] >= 16 && address[1] <= 31)
                || (address[0] == 192 && address[1] == 168)
                || (address[0] == 100 && address[1] >= 64 && address[1] <= 127);
    }

    private String validatedSearchTemplate() {
        final String fallback = "https://www.google.com/search?q=%s";
        String template = settings == null ? fallback : settings.getSearchTemplate();
        return isValidSearchTemplate(template) ? template.trim() : fallback;
    }

    private static boolean isValidSearchTemplate(String template) {
        if (template == null) return false;
        template = template.trim();
        if (!template.contains("%s")) return false;
        try {
            URI probe = URI.create(template.replace("%s", "rigel"));
            String scheme = probe.getScheme();
            return probe.getHost() != null && scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    // ── Key bindings ─────────────────────────────────────────────────────────

    private void bindKeys(Display display) {
        keyFilter = this::handleGlobalKey;
        display.addFilter(SWT.KeyDown, keyFilter);
    }

    private void handleGlobalKey(Event e) {
        if (shell == null || shell.isDisposed() || tabManager == null) return;
        Shell activeShell = shell.getDisplay().getActiveShell();
        if (activeShell != shell) return;

        boolean cmd   = (e.stateMask & SWT.MOD1) != 0;
        boolean shift = (e.stateMask & SWT.SHIFT) != 0;
        boolean ctrl  = (e.stateMask & SWT.CTRL) != 0;
        boolean alt   = (e.stateMask & SWT.ALT) != 0;

        if (e.keyCode == SWT.ESC) {
            if (findBar != null && !findBar.isDisposed()) { hideFindBar(); e.doit = false; return; }
            if (isFullscreen) { exitFullscreen(); e.doit = false; return; }
        }

        if (e.keyCode == SWT.F11) {
            if (isFullscreen) exitFullscreen(); else enterFullscreen();
            e.doit = false;
            return;
        }

        if (ctrl && !cmd && !alt && e.keyCode == SWT.TAB) {
            tabManager.cycleTabs(shift ? -1 : 1);
            e.doit = false;
            return;
        }

        if (cmd && ((shift && key(e, 'f')) || (ctrl && key(e, 'f')))) {
            toggleFullscreen();
            e.doit = false;
            return;
        }

        if (!cmd) return;
        boolean handled = true;

        if (key(e, 'n') && !shift) {
            newWindowAction.openWindow(null, -1, -1);
        } else if (key(e, 't')) {
            if (shift) tabManager.reopenClosedTab();
            else       tabManager.createTab("New Tab", "rigel://home");
        } else if (key(e, 'o')) {
            openFile();
        } else if (key(e, 'w')) {
            if (shift) shell.close();
            else       tabManager.closeTab(tabManager.getActiveIndex());
        } else if (key(e, 'q')) {
            quitAction.run();
        } else if (key(e, 'l') || key(e, 'k')) {
            if (isFullscreen) exitFullscreen();
            addressBar.setFocus();
            addressBar.selectAll();
        } else if (key(e, 'r')) {
            if (shift) tabManager.reloadIgnoringCache();
            else       tabManager.reload();
        } else if (key(e, '.')) {
            tabManager.stopLoading();
        } else if (key(e, 'h') && shift) {
            tabManager.navigate("rigel://home");
        } else if (key(e, 's')) {
            if (shift) toggleLayout();
            else       savePageAs();
        } else if (key(e, 'f') && !shift && !ctrl) {
            if (isFullscreen) exitFullscreen();
            showFindBar();
        } else if (key(e, 'g')) {
            if (findBar != null && !findBar.isDisposed() && findField != null)
                tabManager.findInPage(findField.getText(), !shift);
        } else if (key(e, 'd')) {
            if (shift) tabManager.duplicateActiveTab();
            else       toggleBookmark();
        } else if (key(e, 'b')) {
            showBookmarksDialog();
        } else if (key(e, 'y')) {
            showHistoryDialog();
        } else if (key(e, ',')) {
            showSettingsDialog();
        } else if (key(e, 'p')) {
            tabManager.printActive();
        } else if (alt && key(e, 'i')) {
            showWebInspectorDialog();
        } else if (alt && key(e, 'j')) {
            showJavaScriptConsole();
        } else if (key(e, 'i')) {
            showPageInfoDialog();
        } else if (shift && key(e, 'j')) {
            showDownloadsDialog();
        } else if (shift && key(e, '[')) {
            tabManager.cycleTabs(-1);
        } else if (shift && key(e, ']')) {
            tabManager.cycleTabs(1);
        } else if (e.character == '[') {
            tabManager.back();
        } else if (e.character == ']') {
            tabManager.forward();
        } else if (e.character == '+' || e.character == '=') {
            tabManager.zoomIn();
        } else if (e.character == '-') {
            tabManager.zoomOut();
        } else if (e.character == '0') {
            tabManager.zoomReset();
        } else if (e.character >= '1' && e.character <= '9') {
            int count = tabManager.getTabs().size();
            int idx = e.character == '9' ? count - 1 : e.character - '1';
            if (idx >= 0 && idx < count) tabManager.switchTo(idx);
        } else if (shift && key(e, 'c')) {
            copyToClipboard(currentUrl());
        } else {
            handled = false;
        }

        if (handled) e.doit = false;
    }

    private String windowTitle() {
        String title = currentTitle();
        if (title == null || title.isBlank() || "New Tab".equals(title)) return "Rigel";
        return trimMiddle(title, 72) + " — Rigel";
    }

    private void presentStatus(String message) {
        if (shell == null || shell.isDisposed()) return;
        int generation = ++statusGeneration;
        statusVisible = true;
        shell.setText("Rigel — " + (message == null ? "" : message));
        shell.getDisplay().timerExec(1600, () -> {
            if (shell == null || shell.isDisposed() || generation != statusGeneration) return;
            statusVisible = false;
            shell.setText(windowTitle());
        });
    }

    void onTabLimitReached(int limit) {
        presentStatus("Live tab limit reached (" + limit
                + "). Close an older tab before opening another.");
        if (shell != null && !shell.isDisposed()) shell.getDisplay().beep();
    }

    private boolean key(Event e, char c) {
        char want = Character.toLowerCase(c);
        char fromKey = e.keyCode > 0 && e.keyCode <= Character.MAX_VALUE
                ? Character.toLowerCase((char)e.keyCode) : 0;
        char fromChar = e.character > 0 ? Character.toLowerCase(e.character) : 0;
        return fromKey == want || fromChar == want;
    }

    // ── Find bar ─────────────────────────────────────────────────────────────

    private String suspendFindBar() {
        if (findBar == null || findBar.isDisposed()) return null;
        String query = findField == null || findField.isDisposed() ? "" : findField.getText();
        findBar.dispose();
        findBar = null;
        findField = null;
        findResult = null;
        return query;
    }

    private void restoreFindBar(String query) {
        if (query == null) return;
        if (isFullscreen) {
            if (tabManager != null) tabManager.clearFind();
            return;
        }
        showFindBar();
        if (findField == null || findField.isDisposed()) return;
        findField.setText(query);
    }

    private void showFindBar() {
        if (isFullscreen) exitFullscreen();
        if (findBar != null && !findBar.isDisposed()) {
            findField.setFocus(); findField.selectAll(); return;
        }
        findBar = new Composite(shell, SWT.NONE);
        findBar.setBackground(colNavBg);
        findBar.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        GridLayout fl = new GridLayout(5, false);
        fl.marginWidth = 10; fl.marginHeight = 6; fl.horizontalSpacing = 6;
        findBar.setLayout(fl);

        findField = new Text(findBar, SWT.SINGLE | SWT.BORDER);
        findField.setMessage("Find in page");
        findField.setBackground(colAddrBg);
        findField.setForeground(colAddrFg);
        findField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        findResult = new Label(findBar, SWT.NONE);
        findResult.setBackground(colNavBg);
        findResult.setForeground(colTextMuted);
        findResult.setFont(fontTab);
        GridData resultData = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
        resultData.widthHint = 76;
        findResult.setLayoutData(resultData);

        findField.addListener(SWT.DefaultSelection,
                e -> runFind(findField.getText(), true, findResult, ++findSearchGeneration));
        findField.addModifyListener(e -> {
            int generation = ++findSearchGeneration;
            String query = findField.getText();
            if (query.isBlank()) {
                findResult.setText("");
                tabManager.clearFind();
                return;
            }
            findResult.setText("Searching…");
            findField.getDisplay().timerExec(140, () -> {
                if (generation == findSearchGeneration && findField != null
                        && !findField.isDisposed())
                    runFind(query, true, findResult, generation);
            });
        });

        RoundedIconLabel prev = makeIconLabel(findBar, "up", "Previous", colNavBg);
        prev.addMouseListener(clickOn(() ->
                runFind(findField.getText(), false, findResult, ++findSearchGeneration)));
        RoundedIconLabel next = makeIconLabel(findBar, "down", "Next", colNavBg);
        next.addMouseListener(clickOn(() ->
                runFind(findField.getText(), true, findResult, ++findSearchGeneration)));
        RoundedIconLabel done = makeIconLabel(findBar, "close", "Close (Esc)", colNavBg);
        done.addMouseListener(clickOn(this::hideFindBar));

        if (sidebarMode && sashForm != null && !sashForm.isDisposed()) findBar.moveAbove(sashForm);
        else if (contentArea != null && !contentArea.isDisposed()
                && contentArea.getParent() == shell) findBar.moveAbove(contentArea);
        shell.layout(true, true);
        findField.setFocus();
    }

    private void runFind(String query, boolean forward, Label result, int generation) {
        if (query == null || query.isBlank() || result == null || result.isDisposed()) return;
        tabManager.findInPage(query, forward, found -> {
            if (result.isDisposed()) return;
            Display display = result.getDisplay();
            if (display.isDisposed()) return;
            display.asyncExec(() -> {
                if (generation != findSearchGeneration || result.isDisposed()) return;
                result.setText(found ? "Found" : "No matches");
                result.setForeground(found ? colTextMuted : colTextAccent);
                result.getParent().layout(true, true);
            });
        });
    }

    private void hideFindBar() {
        if (findBar != null && !findBar.isDisposed()) {
            tabManager.clearFind();
            findSearchGeneration++;
            findBar.dispose(); findBar = null; findField = null; findResult = null;
            shell.layout(true, true);
        }
    }

    // ── Settings dialog ───────────────────────────────────────────────────────

    private void showSettingsDialog() {
        Shell d = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        trackUtilityShell(d);
        d.setText("Settings");
        d.setBackground(colNavBg);
        d.setSize(820, 590);
        d.setMinimumSize(680, 480);
        GridLayout dl = new GridLayout(1, false);
        dl.marginWidth = 18; dl.marginHeight = 16; dl.verticalSpacing = 14;
        d.setLayout(dl);

        TabFolder tabs = new TabFolder(d, SWT.NONE);
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        // Keep the dialog ordered by what people reach for most often even
        // though the individual pages are populated in dependency-friendly
        // source order below.
        for (String title : new String[]{
                "General", "Appearance", "Layout", "Privacy",
                "Downloads", "Shortcuts", "Gamepads", "Network"
        }) {
            TabItem item = new TabItem(tabs, SWT.NONE);
            item.setText(title);
        }

        // ── Gamepad section ────────────────────────────────────────────
        Composite controllerPage = settingsPage(tabs, "Gamepads", 1);
        sectionHeader(controllerPage, "Gamepads");
        Button controllerChk = new Button(controllerPage, SWT.CHECK);
        controllerChk.setText("Enable controller support for web games");
        controllerChk.setBackground(colNavBg); controllerChk.setForeground(colTextPrimary);
        controllerChk.setSelection(settings.isControllerBridgeEnabled());
        controllerChk.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Label controllerNote = new Label(controllerPage, SWT.WRAP);
        controllerNote.setText("Uses macOS GameController support for PS5 DualSense, PS4 DualShock, Xbox, Switch Pro / Gulikit-style controllers, and other pads macOS exposes.");
        controllerNote.setForeground(colTextMuted);
        controllerNote.setBackground(colNavBg);
        controllerNote.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Composite controllerForm = new Composite(controllerPage, SWT.NONE);
        controllerForm.setBackground(colNavBg);
        controllerForm.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout cfl = new GridLayout(2, false);
        cfl.marginWidth = 0; cfl.horizontalSpacing = 10; cfl.verticalSpacing = 8;
        controllerForm.setLayout(cfl);

        settingsLabel(controllerForm, "Face buttons");
        Combo faceLayoutCombo = new Combo(controllerForm, SWT.READ_ONLY);
        faceLayoutCombo.setItems(new String[]{"Standard / Xbox / PlayStation", "Nintendo swapped"});
        faceLayoutCombo.setBackground(colAddrBg);
        faceLayoutCombo.setForeground(colAddrFg);
        setAccessibleName(faceLayoutCombo, "Face buttons");
        faceLayoutCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        faceLayoutCombo.select("nintendo".equals(settings.getControllerFaceLayout()) ? 1 : 0);

        // ── Theme section ──────────────────────────────────────────────
        Composite themePage = settingsPage(tabs, "Appearance", 1);
        sectionHeader(themePage, "Theme");

        // Grid of theme swatches
        Composite swatchGrid = new Composite(themePage, SWT.NONE);
        swatchGrid.setBackground(colNavBg);
        swatchGrid.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout sg = new GridLayout(3, true);
        sg.marginWidth = 0; sg.horizontalSpacing = 10; sg.verticalSpacing = 10;
        swatchGrid.setLayout(sg);

        RigelTheme[] themes = RigelTheme.values();
        final RigelTheme[] selectedTheme = { currentTheme };

        for (RigelTheme theme : themes) {
            Composite swatch = new Composite(swatchGrid, SWT.NONE) {
                @Override protected void checkSubclass() {}
            };
            swatch.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
            GridLayout sl2 = new GridLayout(1, false);
            sl2.marginWidth = 0; sl2.marginHeight = 0;
            swatch.setLayout(sl2);

            // The colored preview block
            Canvas preview = new Canvas(swatch, SWT.NONE);
            GridData pd = new GridData(SWT.FILL, SWT.TOP, true, false);
            pd.heightHint = 56;
            preview.setLayoutData(pd);

            Color previewDeep   = rgb(d.getDisplay(), theme.chromeDeep);
            Color previewAccent = rgb(d.getDisplay(), theme.tabAccent);
            Color previewNav    = rgb(d.getDisplay(), theme.navBg);
            Color previewBorder = rgb(d.getDisplay(), theme.separator);

            preview.addPaintListener(e -> {
                GC gc = e.gc;
                gc.setAntialias(SWT.ON);
                Point sz = preview.getSize();
                // A tiny browser-chrome study instead of a generic color bar.
                gc.setForeground(previewNav);
                gc.setBackground(previewDeep);
                gc.fillGradientRectangle(0, 0, sz.x, sz.y, true);
                gc.setBackground(previewNav);
                gc.fillRoundRectangle(8, 9, Math.max(20, sz.x - 16), 29, 9, 9);
                gc.setBackground(previewDeep);
                gc.fillRoundRectangle(28, 15, Math.max(16, sz.x - 44), 17, 8, 8);
                gc.setBackground(previewAccent);
                gc.fillOval(14, 20, 7, 7);
                // Selection ring
                if (theme == selectedTheme[0]) {
                    gc.setForeground(previewAccent);
                    gc.setLineWidth(2);
                    gc.drawRoundRectangle(1, 1, sz.x - 3, sz.y - 3, 8, 8);
                } else {
                    gc.setForeground(previewBorder);
                    gc.setLineWidth(1);
                    gc.drawRoundRectangle(0, 0, sz.x - 1, sz.y - 1, 8, 8);
                }
                if (preview.isFocusControl()) {
                    gc.setForeground(previewAccent);
                    gc.setLineWidth(1);
                    gc.drawRoundRectangle(3, 3, sz.x - 7, sz.y - 7, 7, 7);
                }
            });
            preview.setToolTipText(theme.name + " theme");
            setAccessibleName(preview, theme.name + " theme");
            preview.getAccessible().addAccessibleControlListener(new AccessibleControlAdapter() {
                @Override public void getRole(AccessibleControlEvent e) {
                    e.detail = ACC.ROLE_RADIOBUTTON;
                }
                @Override public void getState(AccessibleControlEvent e) {
                    e.detail = ACC.STATE_FOCUSABLE | ACC.STATE_SELECTABLE;
                    if (theme == selectedTheme[0]) e.detail |= ACC.STATE_SELECTED | ACC.STATE_CHECKED;
                    if (preview.isFocusControl()) e.detail |= ACC.STATE_FOCUSED;
                }
            });
            preview.addListener(SWT.FocusIn, e -> preview.redraw());
            preview.addListener(SWT.FocusOut, e -> preview.redraw());

            preview.addDisposeListener(e -> {
                previewDeep.dispose(); previewAccent.dispose(); previewNav.dispose(); previewBorder.dispose();
            });

            // Theme name label below
            Label nameLabel = new Label(swatch, SWT.CENTER);
            nameLabel.setText(theme.name);
            nameLabel.setForeground(theme == selectedTheme[0] ? colTextPrimary : colTextMuted);
            nameLabel.setBackground(colNavBg);
            nameLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

            // Click selects this theme
            Listener select = e -> {
                selectedTheme[0] = theme;
                // Repaint all swatches
                for (Control child : swatchGrid.getChildren()) {
                    child.redraw();
                    if (child instanceof Composite) {
                        for (Control inner : ((Composite)child).getChildren()) inner.redraw();
                    }
                }
                // Update name label colors
                for (Control child : swatchGrid.getChildren()) {
                    if (child instanceof Composite comp) {
                        Control[] kids = comp.getChildren();
                        if (kids.length >= 2 && kids[1] instanceof Label lbl) {
                            // Find which theme this swatch belongs to
                            int idx2 = 0;
                            for (Control c2 : swatchGrid.getChildren()) {
                                if (c2 == child) break;
                                idx2++;
                            }
                            if (idx2 < themes.length) {
                                lbl.setForeground(themes[idx2] == selectedTheme[0]
                                        ? colTextPrimary : colTextMuted);
                            }
                        }
                    }
                }
            };
            preview.addListener(SWT.MouseDown, e -> preview.setFocus());
            preview.addListener(SWT.MouseUp, select);
            nameLabel.addListener(SWT.MouseUp, select);
            preview.addListener(SWT.KeyDown, e -> {
                if (e.keyCode == SWT.TAB) {
                    preview.traverse((e.stateMask & SWT.SHIFT) != 0
                            ? SWT.TRAVERSE_TAB_PREVIOUS : SWT.TRAVERSE_TAB_NEXT);
                    e.doit = false;
                } else if (e.keyCode == SWT.CR || e.character == ' ') {
                    select.handleEvent(e);
                    e.doit = false;
                }
            });
            preview.getAccessible().addAccessibleActionListener(new AccessibleActionAdapter() {
                @Override public void getActionCount(AccessibleActionEvent e) { e.count = 1; }
                @Override public void getName(AccessibleActionEvent e) {
                    if (e.index == 0) e.result = "select";
                }
                @Override public void doAction(AccessibleActionEvent e) {
                    if (e.index != 0 || preview.isDisposed()) return;
                    select.handleEvent(new Event());
                    e.result = ACC.OK;
                }
            });
        }

        Button glassChk = new Button(themePage, SWT.CHECK);
        glassChk.setText("Liquid glass window — transparent chrome, sidebar & home page");
        glassChk.setSelection(settings.isGlassChromeEnabled());
        glassChk.setBackground(colNavBg);
        glassChk.setForeground(colTextPrimary);
        glassChk.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        // ── Layout section ──────────────────────────────────────────────
        Composite layoutPage = settingsPage(tabs, "Layout", 1);
        sectionHeader(layoutPage, "Layout");
        Composite layoutRow = new Composite(layoutPage, SWT.NONE);
        layoutRow.setBackground(colNavBg);
        layoutRow.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        RowLayout lrl = new RowLayout(SWT.HORIZONTAL);
        lrl.spacing = 12; lrl.center = true;
        layoutRow.setLayout(lrl);

        Button topBtn  = new Button(layoutRow, SWT.RADIO);
        topBtn.setText("Top bar"); topBtn.setSelection(!sidebarMode);
        topBtn.setBackground(colNavBg); topBtn.setForeground(colTextPrimary);

        Button sideBtn = new Button(layoutRow, SWT.RADIO);
        sideBtn.setText("Sidebar"); sideBtn.setSelection(sidebarMode);
        sideBtn.setBackground(colNavBg); sideBtn.setForeground(colTextPrimary);

        // ── Proxy section ───────────────────────────────────────────────
        Composite proxyPage = settingsPage(tabs, "Network", 1);
        sectionHeader(proxyPage, "Rigel network");
        Label localProxyStatus = new Label(proxyPage, SWT.WRAP);
        String localRoute = "WKWebView -> SOCKS5 127.0.0.1:"
                + proxyServer.getBrowserProxyPort() + " -> HTTP CONNECT 127.0.0.1:"
                + proxyServer.getLocalPort();
        if (proxyServer.isUsingUpstream()) {
            ProxyServer.Upstream current = proxyServer.getUpstream();
            String udpRoute = current.type() == ProxyServer.UpstreamType.SOCKS5
                    ? "UDP is relayed through the same SOCKS5 upstream."
                    : "UDP is refused for this upstream type so it cannot bypass the selected route; WebKit can fall back to TCP.";
            localProxyStatus.setText(localRoute + " -> " + current.type() + " "
                    + current.host() + ":" + current.port() + ". " + udpRoute);
        } else {
            localProxyStatus.setText(localRoute
                    + " -> DIRECT for TCP; UDP uses Rigel's local direct relay. "
                    + "Both loopback stages are inside Rigel; DIRECT means there is no external upstream or VPN.");
        }
        localProxyStatus.setBackground(colNavBg);
        localProxyStatus.setForeground(colTextMuted);
        localProxyStatus.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        settingsLabel(proxyPage, "DNS resolver");
        Combo dnsCombo = new Combo(proxyPage, SWT.DROP_DOWN | SWT.READ_ONLY);
        dnsCombo.setItems(new String[] {
                "Cloudflare (encrypted, 1.1.1.1)",
                "Google (encrypted, 8.8.8.8)",
                "System (plain text)"});
        dnsCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        dnsCombo.setBackground(colAddrBg);
        dnsCombo.setForeground(colAddrFg);
        int dnsIndex = switch (proxy.SecureDns.Mode.fromSetting(settings.getDnsMode())) {
            case CLOUDFLARE -> 0;
            case GOOGLE -> 1;
            case SYSTEM -> 2;
        };
        dnsCombo.select(dnsIndex);
        Label dnsNote = new Label(proxyPage, SWT.WRAP);
        dnsNote.setText(
                "Encrypted DNS (DNS-over-HTTPS) keeps the names of the sites you visit "
                + "out of plain-text network traffic. If the encrypted resolver is "
                + "unreachable Rigel falls back to system DNS and logs it.");
        dnsNote.setBackground(colNavBg);
        dnsNote.setForeground(colTextMuted);
        dnsNote.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        sectionHeader(proxyPage, "Upstream proxy");
        Composite proxyForm = new Composite(proxyPage, SWT.NONE);
        proxyForm.setBackground(colNavBg);
        proxyForm.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout pfl = new GridLayout(2, false);
        pfl.marginWidth = 0; pfl.horizontalSpacing = 10; pfl.verticalSpacing = 8;
        proxyForm.setLayout(pfl);

        settingsLabel(proxyForm, "Mode");
        Combo proxyTypeCombo = new Combo(proxyForm, SWT.DROP_DOWN | SWT.READ_ONLY);
        proxyTypeCombo.setItems(new String[] {"Direct (no upstream)", "HTTP",
                "HTTPS (encrypted to upstream)", "SOCKS4", "SOCKS5"});
        proxyTypeCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        proxyTypeCombo.setBackground(colAddrBg);
        proxyTypeCombo.setForeground(colAddrFg);
        settingsLabel(proxyForm, "Host");
        Text hostText = settingsField(proxyForm, "e.g. 127.0.0.1");
        settingsLabel(proxyForm, "Port");
        Text portText = settingsField(proxyForm, "8080");
        if (proxyServer.isUsingUpstream()) {
            ProxyServer.UpstreamType currentType = proxyServer.getUpstreamType();
            proxyTypeCombo.select(switch (currentType) {
                case HTTP -> 1;
                case HTTPS -> 2;
                case SOCKS4 -> 3;
                case SOCKS5 -> 4;
            });
            hostText.setText(proxyServer.getUpstreamHost());
            portText.setText(String.valueOf(proxyServer.getUpstreamPort()));
        } else {
            proxyTypeCombo.select(0);
        }
        Runnable updateProxyFields = () -> {
            boolean enabled = proxyTypeCombo.getSelectionIndex() > 0;
            hostText.setEnabled(enabled);
            portText.setEnabled(enabled);
        };
        proxyTypeCombo.addListener(SWT.Selection, event -> updateProxyFields.run());
        updateProxyFields.run();

        Label proxyPrivacyNote = new Label(proxyPage, SWT.WRAP);
        proxyPrivacyNote.setText(
                "An upstream changes Rigel's web route. Plain HTTP/SOCKS4 hide nothing from "
                + "your local network; 'HTTPS (encrypted to upstream)' wraps the whole hop "
                + "to the upstream in TLS, so observers nearby see only a single encrypted "
                + "connection to that proxy (the proxy operator itself can still see "
                + "destinations). Traffic from other apps is unaffected.");
        proxyPrivacyNote.setBackground(colNavBg);
        proxyPrivacyNote.setForeground(colTextMuted);
        proxyPrivacyNote.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        // ── General section ─────────────────────────────────────────────
        Composite genPage = settingsPage(tabs, "General", 1);
        sectionHeader(genPage, "General");
        Composite genForm = new Composite(genPage, SWT.NONE);
        genForm.setBackground(colNavBg);
        genForm.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout gfl = new GridLayout(2, false);
        gfl.marginWidth = 0; gfl.horizontalSpacing = 10; gfl.verticalSpacing = 8;
        genForm.setLayout(gfl);

        settingsLabel(genForm, "Homepage");
        Text homeText = settingsField(genForm, "rigel://home");
        homeText.setText(settings.getHomepage());
        settingsLabel(genForm, "Search (%s = query)");
        Text searchText = settingsField(genForm, "https://www.google.com/search?q=%s");
        searchText.setText(settings.getSearchTemplate());
        Button restoreTabs = new Button(genForm, SWT.CHECK);
        restoreTabs.setText("Restore tabs from last session");
        restoreTabs.setBackground(colNavBg); restoreTabs.setForeground(colTextPrimary);
        restoreTabs.setSelection(settings.isRestoreSessionEnabled());
        restoreTabs.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        Button av1AllSitesChk = new Button(genForm, SWT.CHECK);
        av1AllSitesChk.setText("Normalize unsupported AV1 levels on all websites");
        av1AllSitesChk.setToolTipText(
                "Extends Rigel's Anime Nexus AV1 compatibility behavior to other websites.");
        av1AllSitesChk.setBackground(colNavBg); av1AllSitesChk.setForeground(colTextPrimary);
        av1AllSitesChk.setSelection(settings.isAv1NormalizationAllSitesEnabled());
        av1AllSitesChk.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        // ── Shortcuts section ───────────────────────────────────────────
        Composite shortcutsPage = settingsPage(tabs, "Shortcuts", 1);
        sectionHeader(shortcutsPage, "Shortcuts");
        Button shortcutsChk = new Button(shortcutsPage, SWT.CHECK);
        shortcutsChk.setText("Show shortcuts on new tab");
        shortcutsChk.setBackground(colNavBg); shortcutsChk.setForeground(colTextPrimary);
        shortcutsChk.setSelection(settings.isHomeShortcutsEnabled());
        shortcutsChk.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Label shortcutEntriesLabel = new Label(shortcutsPage, SWT.NONE);
        shortcutEntriesLabel.setText("Shortcut entries (Name | URL | Icon)");
        shortcutEntriesLabel.setBackground(colNavBg);
        shortcutEntriesLabel.setForeground(colTextMuted);
        shortcutEntriesLabel.setFont(fontTab);
        Text shortcutsText = new Text(shortcutsPage, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        shortcutsText.setText(settings.getHomeShortcutsText());
        shortcutsText.setToolTipText("One per line: Name | URL | Icon");
        shortcutsText.setBackground(colAddrBg);
        shortcutsText.setForeground(colAddrFg);
        shortcutsText.setFont(fontNav);
        setAccessibleName(shortcutsText, "Shortcut entries");
        shortcutEntriesLabel.getAccessible().addRelation(
                ACC.RELATION_LABEL_FOR, shortcutsText.getAccessible());
        shortcutsText.getAccessible().addRelation(
                ACC.RELATION_LABELLED_BY, shortcutEntriesLabel.getAccessible());
        GridData shortcutsData = new GridData(SWT.FILL, SWT.FILL, true, true);
        shortcutsData.heightHint = 150;
        shortcutsText.setLayoutData(shortcutsData);

        Button resetShortcuts = new Button(shortcutsPage, SWT.PUSH);
        resetShortcuts.setText("Restore Defaults");
        resetShortcuts.addListener(SWT.Selection, e -> shortcutsText.setText(config.Settings.defaultHomeShortcutsText()));

        // ── Downloads section ────────────────────────────────────────────
        Composite downloadsPage = settingsPage(tabs, "Downloads", 1);
        sectionHeader(downloadsPage, "Downloads");
        Composite dlForm = new Composite(downloadsPage, SWT.NONE);
        dlForm.setBackground(colNavBg);
        dlForm.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout dfl = new GridLayout(2, false);
        dfl.marginWidth = 0; dfl.horizontalSpacing = 10; dfl.verticalSpacing = 8;
        dlForm.setLayout(dfl);

        settingsLabel(dlForm, "Folder");
        Text dlDirText = settingsField(dlForm, "~/Downloads");
        dlDirText.setText(settings.getDownloadDir());
        Button askDl = new Button(dlForm, SWT.CHECK);
        askDl.setText("Ask where to save each file");
        askDl.setBackground(colNavBg); askDl.setForeground(colTextPrimary);
        askDl.setSelection(settings.isAskBeforeDownload());
        askDl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        // ── Privacy section ──────────────────────────────────────────────
        Composite privacyPage = settingsPage(tabs, "Privacy", 1);
        sectionHeader(privacyPage, "Privacy");
        Button blockChk = new Button(privacyPage, SWT.CHECK);
        blockChk.setText("Block ads and trackers (built in)");
        blockChk.setToolTipText(
                "Uses WebKit content rules plus Rigel's local-proxy hostname list. "
                + "WKWebView cannot load Chrome/Firefox extensions such as uBlock Origin.");
        blockChk.setBackground(colNavBg); blockChk.setForeground(colTextPrimary);
        blockChk.setSelection(settings.isBlocklistEnabled());
        blockChk.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Label blockerNote = new Label(privacyPage, SWT.WRAP);
        blockerNote.setText(
                "Rigel cannot run uBlock Origin itself, but this supported WebKit blocker drops "
                + "known ad/tracker requests and hides common ad containers without decrypting HTTPS.");
        blockerNote.setBackground(colNavBg);
        blockerNote.setForeground(colTextMuted);
        blockerNote.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Button historyChk = new Button(privacyPage, SWT.CHECK);
        historyChk.setText("Record browsing history");
        historyChk.setBackground(colNavBg); historyChk.setForeground(colTextPrimary);
        historyChk.setSelection(settings.isHistoryEnabled());
        historyChk.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Button clipChk = new Button(privacyPage, SWT.CHECK);
        clipChk.setText("Use native clipboard fallback for web copy buttons");
        clipChk.setBackground(colNavBg); clipChk.setForeground(colTextPrimary);
        clipChk.setSelection(settings.isNativeClipboardEnabled());
        clipChk.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Button clearHistory = new Button(privacyPage, SWT.PUSH);
        clearHistory.setText("Clear History");
        clearHistory.addListener(SWT.Selection, e -> {
            MessageBox confirm = new MessageBox(d,
                    SWT.ICON_WARNING | SWT.YES | SWT.NO | SWT.APPLICATION_MODAL);
            confirm.setText("Clear History");
            confirm.setMessage("Remove all browsing history? This cannot be undone.");
            if (confirm.open() != SWT.YES) return;
            history.clear();
            presentStatus("History cleared");
        });

        // ── Buttons ─────────────────────────────────────────────────────
        Composite btnRow = new Composite(d, SWT.NONE);
        btnRow.setBackground(colNavBg);
        btnRow.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        RowLayout brl = new RowLayout(SWT.HORIZONTAL);
        brl.spacing = 8;
        btnRow.setLayout(brl);

        if (proxyServer.isUsingUpstream()) {
            Button goDirect = new Button(btnRow, SWT.PUSH);
            goDirect.setText("Use Local Proxy Direct");
            goDirect.addListener(SWT.Selection, e -> {
                proxyServer.clearUpstream(); settings.clearUpstream();
                ensureWebProxyRouting();
                d.close();
                shell.getDisplay().asyncExec(() -> {
                    if (tabManager != null) tabManager.reloadAll();
                });
            });
        }

        Button cancel = new Button(btnRow, SWT.PUSH);
        cancel.setText("Cancel");
        cancel.addListener(SWT.Selection, e -> d.close());

        Button apply = new Button(btnRow, SWT.PUSH);
        apply.setText("Apply");
        d.setDefaultButton(apply);
        apply.addListener(SWT.Selection, e -> {
            // Capture appearance choices now, but do not rebuild the main
            // native view hierarchy while this modal Settings shell is active.
            RigelTheme wantedTheme = selectedTheme[0];
            boolean wantSidebar = sideBtn.getSelection();
            String hp = homeText.getText().trim();
            String st = searchText.getText().trim();
            String dd = dlDirText.getText().trim();
            boolean block = blockChk.getSelection();
            boolean contentBlockingChanged = settings.isBlocklistEnabled() != block;
            String faceLayout = faceLayoutCombo.getSelectionIndex() == 1 ? "nintendo" : "standard";
            int proxyTypeIndex = proxyTypeCombo.getSelectionIndex();
            ProxyServer.Upstream previousUpstream = proxyServer.getUpstream();
            String h = hostText.getText().trim();
            String p = portText.getText().trim();

            if (hp.isEmpty()) {
                showError(d, "Homepage cannot be empty.");
                return;
            }
            if (!isValidSearchTemplate(st)) {
                showError(d, "Search template must be a valid HTTP(S) URL containing %s.");
                return;
            }
            if (proxyTypeIndex > 0 && (h.isEmpty() || p.isEmpty())) {
                showError(d, "Enter both an upstream proxy host and port.");
                return;
            }

            int parsedProxyPort = -1;
            if (proxyTypeIndex > 0) {
                try {
                    parsedProxyPort = Integer.parseInt(p);
                    if (parsedProxyPort < 1 || parsedProxyPort > 65535)
                        throw new NumberFormatException();
                } catch (NumberFormatException ex) {
                    showError(d, "Port must be between 1 and 65535.");
                    return;
                }
            }

            int proxyPort = parsedProxyPort;
            String proxyTypeSetting = switch (proxyTypeIndex) {
                case 2 -> "https";
                case 3 -> "socks4";
                case 4 -> "socks5";
                default -> "http";
            };
            ProxyServer.UpstreamType proxyType = switch (proxyTypeIndex) {
                case 2 -> ProxyServer.UpstreamType.HTTPS;
                case 3 -> ProxyServer.UpstreamType.SOCKS4;
                case 4 -> ProxyServer.UpstreamType.SOCKS5;
                default -> ProxyServer.UpstreamType.HTTP;
            };
            String dnsModeSetting = switch (dnsCombo.getSelectionIndex()) {
                case 1 -> "google";
                case 2 -> "system";
                default -> "cloudflare";
            };
            boolean av1CompatibilityChanged = settings.isAv1NormalizationAllSitesEnabled()
                    != av1AllSitesChk.getSelection();
            settings.runBatch(() -> {
                settings.setSidebar(wantSidebar);
                settings.setTheme(wantedTheme.name);
                settings.setGlassChromeEnabled(glassChk.getSelection());
                settings.setHomepage(hp);
                settings.setSearchTemplate(st);
                settings.setRestoreSessionEnabled(restoreTabs.getSelection());
                settings.setAv1NormalizationAllSitesEnabled(av1AllSitesChk.getSelection());
                settings.setHomeShortcutsEnabled(shortcutsChk.getSelection());
                settings.setHomeShortcutsText(shortcutsText.getText());
                if (!dd.isEmpty()) settings.setDownloadDir(dd);
                settings.setAskBeforeDownload(askDl.getSelection());
                settings.setBlocklistEnabled(block);
                settings.setHistoryEnabled(historyChk.getSelection());
                settings.setNativeClipboardEnabled(clipChk.getSelection());
                settings.setControllerBridgeEnabled(controllerChk.getSelection());
                settings.setControllerFaceLayout(faceLayout);
                if (proxyTypeIndex == 0) settings.clearUpstream();
                else settings.setUpstream(proxyTypeSetting, h, proxyPort);
                settings.setDnsMode(dnsModeSetting);
            });
            proxy.SecureDns.setMode(proxy.SecureDns.Mode.fromSetting(dnsModeSetting));

            proxyServer.getBlocklist().setEnabled(block);
            if (contentBlockingChanged) RigelBrowser.configureContentBlocking(block);
            RigelBrowser.configureAv1NormalizationAllSites(av1AllSitesChk.getSelection());
            if (tabManager != null) {
                String downloadDirectory = expandUserPath(settings.getDownloadDir());
                boolean askBeforeDownload = settings.isAskBeforeDownload();
                for (TabManager.TabData tab : tabManager.getTabs()) {
                    if (tab.browser != null && !tab.browser.isDisposed())
                        tab.browser.configureDownloads(downloadDirectory, askBeforeDownload);
                }
            }
            if (proxyTypeIndex == 0) proxyServer.clearUpstream();
            else proxyServer.setUpstream(proxyType, h, proxyPort);
            boolean proxyRoutingChanged = !Objects.equals(
                    previousUpstream, proxyServer.getUpstream());
            ensureWebProxyRouting();
            d.close();

            // Run after the modal dialog has been disposed and the current SWT
            // callback has returned. This avoids reparenting live WKWebViews
            // during NSWindow ordering/disposal.
            shell.getDisplay().asyncExec(() -> {
                if (shell == null || shell.isDisposed()) return;
                settingsChangedAction.run();
                if (tabManager != null) {
                    if (proxyRoutingChanged) tabManager.reloadAll();
                    else if (av1CompatibilityChanged) tabManager.reload();
                }
                MacWindowCaptureProtection.protectSoon(shell);
            });
        });

        tabs.setSelection(0);
        openDialogCentered(d);
    }

    private void ensureWebProxyRouting() {
        RigelBrowser.configureProxy(
                proxyServer.getBrowserProxyPort(), RigelBrowser.PROXY_SOCKS5);
    }

    // ── Settings helpers ──────────────────────────────────────────────────────

    private Composite settingsPage(TabFolder folder, String title, int columns) {
        TabItem item = null;
        for (TabItem candidate : folder.getItems()) {
            if (title.equals(candidate.getText()) && candidate.getControl() == null) {
                item = candidate;
                break;
            }
        }
        if (item == null) {
            item = new TabItem(folder, SWT.NONE);
            item.setText(title);
        }
        Composite page = new Composite(folder, SWT.NONE);
        page.setBackground(colNavBg);
        GridLayout layout = new GridLayout(columns, false);
        layout.marginWidth = 22;
        layout.marginHeight = 20;
        layout.verticalSpacing = 14;
        layout.horizontalSpacing = 12;
        page.setLayout(layout);
        item.setControl(page);
        return page;
    }

    private void openDialogCentered(Shell dialog) {
        if (dialog == null || dialog.isDisposed()) return;
        dialog.layout(true, true);
        Rectangle area = shell != null && !shell.isDisposed()
                ? shell.getMonitor().getClientArea()
                : dialog.getMonitor().getClientArea();
        Rectangle owner = shell != null && !shell.isDisposed()
                ? shell.getBounds()
                : area;
        Point size = dialog.getSize();
        int x = owner.x + Math.max(0, (owner.width - size.x) / 2);
        int y = owner.y + Math.max(0, (owner.height - size.y) / 2);
        x = Math.max(area.x, Math.min(x, area.x + Math.max(0, area.width - size.x)));
        y = Math.max(area.y, Math.min(y, area.y + Math.max(0, area.height - size.y)));
        dialog.setLocation(x, y);
        dialog.open();
    }

    private void trackUtilityShell(Shell dialog) {
        if (dialog == null || dialog.isDisposed()) return;
        utilityShells.add(dialog);
        dialog.addDisposeListener(e -> utilityShells.remove(dialog));
    }

    private void closeUtilityShells() {
        for (Shell dialog : List.copyOf(utilityShells)) {
            if (dialog != null && !dialog.isDisposed()) dialog.close();
        }
        utilityShells.clear();
    }

    private void sectionHeader(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setForeground(colTextPrimary);
        l.setBackground(colNavBg);
        l.setFont(fontSectionLabel);
        l.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private void settingsLabel(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setForeground(colTextMuted);
        l.setBackground(colNavBg);
        l.setFont(fontTab);
        parent.setData(DATA_PENDING_SETTINGS_LABEL, l);
    }

    private Text settingsField(Composite parent, String hint) {
        Text t = new Text(parent, SWT.SINGLE | SWT.BORDER);
        t.setMessage(hint);
        t.setBackground(colAddrBg);
        t.setForeground(colAddrFg);
        t.setFont(fontNav);
        t.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Object pending = parent.getData(DATA_PENDING_SETTINGS_LABEL);
        if (pending instanceof Label label && !label.isDisposed()) {
            setAccessibleName(t, label.getText());
            label.getAccessible().addRelation(ACC.RELATION_LABEL_FOR, t.getAccessible());
            t.getAccessible().addRelation(ACC.RELATION_LABELLED_BY, label.getAccessible());
            parent.setData(DATA_PENDING_SETTINGS_LABEL, null);
        }
        return t;
    }

    // ── Widget helpers ────────────────────────────────────────────────────────

    private RoundedIconLabel makeIconLabel(Composite parent, String text, String tip, Color bg) {
        RoundedIconLabel l = new RoundedIconLabel(parent, bg);
        l.setText(text);
        l.setForeground(colTextMuted);
        l.setFont(fontNav);
        l.setToolTipText(tip);
        setAccessibleName(l, tip);
        GridData gd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        gd.widthHint = NAV_BUTTON_SIZE; gd.heightHint = NAV_BUTTON_SIZE;
        l.setLayoutData(gd);
        return l;
    }

    private static void setAccessibleName(Control control, String name) {
        if (control == null || name == null || name.isBlank()) return;
        Object previous = control.getData(DATA_ACCESSIBLE_NAME);
        boolean changed = previous == null || !name.equals(previous.toString());
        if (!changed && Boolean.TRUE.equals(control.getData(DATA_ACCESSIBLE_NAME_WIRED))) return;
        control.setData(DATA_ACCESSIBLE_NAME, name);
        if (Boolean.TRUE.equals(control.getData(DATA_ACCESSIBLE_NAME_WIRED))) {
            control.getAccessible().sendEvent(ACC.EVENT_NAME_CHANGED, null);
            return;
        }
        control.setData(DATA_ACCESSIBLE_NAME_WIRED, Boolean.TRUE);
        control.getAccessible().addAccessibleListener(new AccessibleAdapter() {
            private String currentName() {
                if (control.isDisposed()) return "";
                Object value = control.getData(DATA_ACCESSIBLE_NAME);
                return value == null ? "" : value.toString();
            }
            @Override public void getName(AccessibleEvent e) { e.result = currentName(); }
            @Override public void getHelp(AccessibleEvent e) { e.result = currentName(); }
        });
    }

    private class RoundedIconLabel extends Canvas {
        private final Color baseBg;
        private String iconText = "";
        private boolean hover = false;
        private boolean pressed = false;

        RoundedIconLabel(Composite parent, Color baseBg) {
            super(parent, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
            this.baseBg = baseBg;
            setBackground(baseBg);
            addListener(SWT.MouseEnter, e -> { hover = true; redraw(); });
            addListener(SWT.MouseExit,  e -> { hover = false; pressed = false; redraw(); });
            addListener(SWT.MouseDown, e -> {
                if (e.button != 1 || !isEnabled()) return;
                setFocus();
                pressed = true;
                redraw();
            });
            addListener(SWT.MouseUp, e -> { pressed = false; redraw(); });
            addListener(SWT.FocusIn, e -> redraw());
            addListener(SWT.FocusOut, e -> { pressed = false; redraw(); });
            addListener(SWT.KeyDown, e -> {
                if (e.keyCode == SWT.TAB) {
                    traverse((e.stateMask & SWT.SHIFT) != 0
                            ? SWT.TRAVERSE_TAB_PREVIOUS : SWT.TRAVERSE_TAB_NEXT);
                    e.doit = false;
                    return;
                }
                if (!isEnabled() || (e.keyCode != SWT.CR && e.character != ' ')) return;
                Event activation = new Event();
                activation.button = 1;
                notifyListeners(SWT.MouseUp, activation);
                e.doit = false;
            });
            getAccessible().addAccessibleControlListener(new AccessibleControlAdapter() {
                @Override public void getRole(AccessibleControlEvent e) {
                    e.detail = ACC.ROLE_PUSHBUTTON;
                }

                @Override public void getState(AccessibleControlEvent e) {
                    e.detail = ACC.STATE_FOCUSABLE;
                    if (isFocusControl()) e.detail |= ACC.STATE_FOCUSED;
                    if (!isEnabled()) e.detail |= ACC.STATE_DISABLED;
                    if (pressed) e.detail |= ACC.STATE_PRESSED;
                }
            });
            getAccessible().addAccessibleActionListener(new AccessibleActionAdapter() {
                @Override public void getActionCount(AccessibleActionEvent e) { e.count = 1; }
                @Override public void getName(AccessibleActionEvent e) {
                    if (e.index == 0) e.result = "press";
                }
                @Override public void doAction(AccessibleActionEvent e) {
                    if (e.index != 0 || isDisposed() || !isEnabled()) return;
                    Event activation = new Event();
                    activation.button = 1;
                    notifyListeners(SWT.MouseUp, activation);
                    e.result = ACC.OK;
                }
            });
            addPaintListener(e -> {
                GC gc = e.gc;
                Point sz = getSize();
                gc.setAntialias(SWT.ON);
                if (this.baseBg != null) {
                    gc.setBackground(this.baseBg);
                    gc.fillRectangle(0, 0, sz.x, sz.y);
                }
                if (isEnabled() && (hover || pressed)) {
                    gc.setBackground(pressed ? colTabActive : colBtnHover);
                    gc.fillRoundRectangle(2, 2, Math.max(0, sz.x - 4),
                            Math.max(0, sz.y - 4), 10, 10);
                }
                if (isFocusControl()) {
                    gc.setForeground(colTabAccent);
                    gc.setLineWidth(1);
                    gc.drawRoundRectangle(1, 1, Math.max(0, sz.x - 3),
                            Math.max(0, sz.y - 3), 11, 11);
                }
                gc.setForeground(!isEnabled() ? colSeparator
                        : hover ? colTextPrimary : getForeground());
                drawChromeIcon(gc, iconText, sz.x / 2, sz.y / 2);
            });
        }

        private void drawChromeIcon(GC gc, String icon, int cx, int cy) {
            gc.setLineWidth(2);
            gc.setLineCap(SWT.CAP_ROUND);
            gc.setLineJoin(SWT.JOIN_ROUND);
            switch (icon) {
                case "back" -> {
                    gc.drawLine(cx - 7, cy, cx + 7, cy);
                    gc.drawLine(cx - 7, cy, cx - 2, cy - 5);
                    gc.drawLine(cx - 7, cy, cx - 2, cy + 5);
                }
                case "forward" -> {
                    gc.drawLine(cx - 7, cy, cx + 7, cy);
                    gc.drawLine(cx + 7, cy, cx + 2, cy - 5);
                    gc.drawLine(cx + 7, cy, cx + 2, cy + 5);
                }
                case "reload" -> {
                    gc.drawArc(cx - 7, cy - 7, 14, 14, 38, 286);
                    gc.drawLine(cx + 6, cy - 6, cx + 7, cy - 1);
                    gc.drawLine(cx + 6, cy - 6, cx + 1, cy - 5);
                }
                case "home" -> {
                    gc.drawPolyline(new int[]{cx - 7, cy, cx, cy - 6, cx + 7, cy});
                    gc.drawLine(cx - 5, cy - 1, cx - 5, cy + 6);
                    gc.drawLine(cx + 5, cy - 1, cx + 5, cy + 6);
                    gc.drawLine(cx - 5, cy + 6, cx + 5, cy + 6);
                }
                case "sidebar" -> {
                    gc.drawRoundRectangle(cx - 8, cy - 7, 16, 14, 4, 4);
                    gc.drawLine(cx - 3, cy - 6, cx - 3, cy + 6);
                }
                case "topbar" -> {
                    gc.drawRoundRectangle(cx - 8, cy - 7, 16, 14, 4, 4);
                    gc.drawLine(cx - 7, cy - 2, cx + 7, cy - 2);
                }
                case "settings" -> {
                    gc.drawLine(cx - 7, cy - 5, cx + 7, cy - 5);
                    gc.drawLine(cx - 7, cy, cx + 7, cy);
                    gc.drawLine(cx - 7, cy + 5, cx + 7, cy + 5);
                    gc.setBackground(gc.getForeground());
                    gc.fillOval(cx - 4, cy - 7, 4, 4);
                    gc.fillOval(cx + 2, cy - 2, 4, 4);
                    gc.fillOval(cx - 2, cy + 3, 4, 4);
                }
                case "plus" -> {
                    gc.drawLine(cx - 6, cy, cx + 6, cy);
                    gc.drawLine(cx, cy - 6, cx, cy + 6);
                }
                case "close" -> {
                    gc.drawLine(cx - 5, cy - 5, cx + 5, cy + 5);
                    gc.drawLine(cx + 5, cy - 5, cx - 5, cy + 5);
                }
                case "up" -> {
                    gc.drawLine(cx - 5, cy + 3, cx, cy - 3);
                    gc.drawLine(cx, cy - 3, cx + 5, cy + 3);
                }
                case "down" -> {
                    gc.drawLine(cx - 5, cy - 3, cx, cy + 3);
                    gc.drawLine(cx, cy + 3, cx + 5, cy - 3);
                }
                default -> {
                    Point textSize = gc.textExtent(icon);
                    int x = Math.max(0, cx - textSize.x / 2);
                    int y = Math.max(0, cy - textSize.y / 2 - 1);
                    gc.drawText(icon, x, y, true);
                }
            }
        }

        @Override protected void checkSubclass() {}

        public void setText(String string) {
            iconText = string == null ? "" : string;
            redraw();
        }

        public String getText() {
            return iconText;
        }

        @Override public Point computeSize(int wHint, int hHint, boolean changed) {
            int w = wHint == SWT.DEFAULT ? NAV_BUTTON_SIZE : wHint;
            int h = hHint == SWT.DEFAULT ? NAV_BUTTON_SIZE : hHint;
            return new Point(w, h);
        }
    }

    private Label spacer(Composite parent, Color bg, int w) {
        Label l = new Label(parent, SWT.NONE);
        l.setBackground(bg);
        l.setLayoutData(new GridData(w, SWT.DEFAULT));
        return l;
    }

    private MouseAdapter clickOn(Runnable action) {
        return new MouseAdapter() {
            @Override public void mouseUp(MouseEvent e) {
                if (e.button == 1) action.run();
            }
        };
    }

    public static void showError(Shell parent, String msg) {
        MessageBox mb = new MessageBox(parent, SWT.ICON_ERROR | SWT.OK);
        mb.setText("Error"); mb.setMessage(msg); mb.open();
    }

    private void runOnUi(Runnable action) {
        if (action == null || shell == null || shell.isDisposed()) return;
        Display display = shell.getDisplay();
        if (display == null || display.isDisposed()) return;
        display.asyncExec(() -> {
            if (shell == null || shell.isDisposed()) return;
            action.run();
        });
    }

    private void safeExecute(RigelBrowser browser, String script) {
        if (browser == null || browser.isDisposed()) return;
        try {
            browser.execute(script);
        } catch (org.eclipse.swt.SWTException ignored) {
        }
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Shell        getShell()        { return shell; }
    public Composite    getContentArea()  { return contentArea; }
    public TabDeckLayout getStackLayout() { return stackLayout; }
    public RigelTheme   getCurrentTheme() { return currentTheme.effective(); }



    /**
     * Chrome-style new-window opener. RigelBrowser hands over an optional
     * initial URL plus the screen point where the new window should appear
     * (tab tear-off), or null/-1,-1 for a plain new window.
     */
    @FunctionalInterface
    public interface NewWindowOpener {
        void openWindow(String initialUrl, int x, int y);
    }

    /**
     * Deck layout for tab containers. Unlike SWT's StackLayout — which
     * zero-sizes every background tab — this keeps ALL tab containers sized
     * to the full content area and relies on z-ordering (moveAbove on
     * switchTo) to show the active one. Zero-sized background WKWebViews
     * are "not visible" to WebKit, which suspends their pages and terminates
     * their content processes — that is why playback stopped whenever tabs
     * were switched. Full-size mounted views stay alive and keep playing
     * (Safari/Chrome behaviour); the cost is bounded by TabManager's
     * Chrome-style background throttling, not by tearing pages down.
     */
    static final class TabDeckLayout extends org.eclipse.swt.widgets.Layout {
        /** Compatibility field: the layout no longer needs it, but callers
         *  (TabManager.reattach/switchTo) still track the intended top view. */
        public Control topControl;

        @Override
        protected org.eclipse.swt.graphics.Point computeSize(
                Composite composite, int wHint, int hHint, boolean flushCache) {
            return new org.eclipse.swt.graphics.Point(
                    wHint == SWT.DEFAULT ? 0 : wHint,
                    hHint == SWT.DEFAULT ? 0 : hHint);
        }

        @Override
        protected void layout(Composite composite, boolean flushCache) {
            org.eclipse.swt.graphics.Rectangle area = composite.getClientArea();
            int w = Math.max(0, area.width);
            int h = Math.max(0, area.height);
            for (Control child : composite.getChildren()) {
                org.eclipse.swt.graphics.Rectangle b = child.getBounds();
                if (b.width != w || b.height != h) child.setBounds(0, 0, w, h);
            }
        }
    }
    public boolean      areHomeShortcutsEnabled() {
        return settings == null || settings.isHomeShortcutsEnabled();
    }
    public List<config.Settings.Shortcut> getHomeShortcuts() {
        return settings == null ? config.Settings.defaultHomeShortcuts() : settings.getHomeShortcuts();
    }
}
