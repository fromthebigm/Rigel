package ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
/**
 * TabManager — owns the list of open tabs and the browsers inside them.
 *
 * ── Fullscreen approach ──────────────────────────────────────────────────────
 *
 * Rigel enables WebKit's native element-fullscreen support and leaves that
 * standards path untouched whenever it is available. Older/limited embedded
 * WebKit builds receive a compatibility implementation of the API contract:
 *
 *   1. Patch document.fullscreenEnabled / webkitFullscreenEnabled → true,
 *      so capability checks pass and players show their fullscreen buttons.
 *
 *   2. Patch document.fullscreenElement / webkitFullscreenElement getters
 *      to return our tracked JS variable, so player code that reads the
 *      current fullscreen element sees the correct value.
 *
 *   3. Override Element.prototype.requestFullscreen (+ webkit/moz/ms variants)
 *      to store the element, fire a synthetic fullscreenchange event, call
 *      javaEnterFullscreen(), and return Promise.resolve().
 *      → The player's Promise.then() and fullscreenchange handlers run normally.
 *
 *   4. Override Document.prototype.exitFullscreen (+ webkit/moz/ms variants)
 *      symmetrically.
 *
 *   5. Re-implement the Escape key exit that browsers provide automatically
 *      for real fullscreen (since our mock bypasses the browser machinery).
 *
 * Java side (BrowserUI.enterFullscreen / exitFullscreen):
 *   Hides the chrome composite and calls shell.setFullScreen(true).
 *   The WebKit view then fills the entire display while the fallback isolates
 *   the requested player and preserves its controls.
 *
 * ── Home page ────────────────────────────────────────────────────────────────
 * Navigating to "rigel://home" renders a built-in HTML page. The LocationListener
 * intercepts the URL before WebKit tries to fetch it.
 *
 * ── reattach() ───────────────────────────────────────────────────────────────
 * Called by BrowserUI when the layout mode is toggled (top-bar ↔ sidebar).
 * Reparents all tab containers to the new contentArea and re-points the
 * stackLayout reference so everything works seamlessly.
 */
public class TabManager {

    private static final int MAX_LIVE_TABS = 64;

    // ── Home page HTML ───────────────────────────────────────────────────────

    /**
     * Generates a theme-aware home page. Called each time rigel://home is
     * loaded. The page keeps its designed opaque background; the window's
     * liquid glass only replaces the surrounding chrome that used to be
     * flat grey.
     */
    private String buildHomeHtml() {
        BrowserUI.RigelTheme t = ui.getCurrentTheme();
        // Convert packed ints to CSS hex strings (no leading #)
        String bg      = hex(t.chromeDeep);
        String accent  = hex(t.tabAccent);
        String textFg  = hex(t.addrFg);
        String mutedFg = hex(t.textMuted);
        String rAccent = hexRgb(t.tabAccent);
        String rAddrBg = hexRgb(t.addrBg);
        String rNavBg  = hexRgb(t.navBg);
        String rActive = hexRgb(t.tabActive);

        StringBuilder sb = new StringBuilder(12288);
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        sb.append("<meta charset=\"UTF-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        sb.append("<title>New Tab</title>\n<style>\n");
        sb.append("  *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }\n");
        sb.append("  body {\n");
        sb.append("    min-height: 100vh; padding: clamp(36px, 8vh, 64px) 0 clamp(28px, 6vh, 42px);\n");
        sb.append("    background:\n");
        sb.append("      radial-gradient(900px 520px at 50% -8%, rgba(").append(rAccent).append(", 0.14), transparent 68%),\n");
        sb.append("      radial-gradient(640px 440px at 12% 92%, rgba(").append(rActive).append(", 0.42), transparent 72%),\n");
        sb.append("      #").append(bg).append(";\n");
        sb.append("    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;\n");
        sb.append("    color: #").append(textFg).append(";\n");
        sb.append("    display: flex; flex-direction: column;\n");
        sb.append("    align-items: center; justify-content: center;\n");
        sb.append("    overflow-x: hidden; overflow-y: auto; isolation: isolate;\n");
        sb.append("  }\n");
        sb.append("  body::before { content: ''; position: fixed; inset: 0; pointer-events: none; z-index: -1; opacity: .42; background: linear-gradient(115deg, rgba(255,255,255,.025), transparent 32%, rgba(255,255,255,.012) 68%, transparent); }\n");
        sb.append("  .content { display: flex; flex-direction: column; align-items: stretch; gap: clamp(22px, 4vh, 30px); width: 100%; max-width: 760px; padding: 0 clamp(18px, 5vw, 34px); }\n");
        sb.append("  .visually-hidden { position: absolute !important; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0; }\n");
        sb.append("  .logo { display: flex; align-items: center; justify-content: center; gap: 13px; }\n");
        sb.append("  .star-mark { width: 42px; height: 42px; display: grid; place-items: center; border-radius: 13px; font-size: 19px; color: #").append(accent).append("; line-height: 1; background: rgba(").append(rAddrBg).append(", .72); border: 1px solid rgba(").append(rAccent).append(", .22); box-shadow: 0 12px 34px rgba(0,0,0,.18); backdrop-filter: blur(18px); -webkit-backdrop-filter: blur(18px); }\n");
        sb.append("  .brand-copy { display: flex; flex-direction: column; gap: 3px; }\n");
        sb.append("  .wordmark { font-size: 19px; line-height: 1.1; font-weight: 650; letter-spacing: 0.12em; text-transform: uppercase; color: #").append(textFg).append("; }\n");
        sb.append("  .search-wrap { width: 100%; position: relative; filter: drop-shadow(0 20px 46px rgba(0,0,0,.20)); }\n");
        sb.append("  .search-wrap input {\n");
        sb.append("    width: 100%; height: 58px; padding: 0 62px 0 22px;\n");
        sb.append("    background: rgba(").append(rAddrBg).append(", 0.82);\n");
        sb.append("    border: 1px solid rgba(").append(rAccent).append(", 0.20);\n");
        sb.append("    border-radius: 18px; color: #").append(textFg).append(";\n");
        sb.append("    -webkit-text-fill-color: #").append(textFg).append(" !important; caret-color: #").append(accent).append(";\n");
        sb.append("    -webkit-user-select: text; user-select: text;\n");
        sb.append("    font: inherit; font-size: 16px; outline: none;\n");
        sb.append("    backdrop-filter: blur(24px) saturate(125%); -webkit-backdrop-filter: blur(24px) saturate(125%);\n");
        sb.append("    transition: border-color .18s ease, background .18s ease, box-shadow .18s ease; letter-spacing: -0.005em;\n");
        sb.append("  }\n");
        sb.append("  .search-wrap input::placeholder { color: #").append(mutedFg).append("; -webkit-text-fill-color: #").append(mutedFg).append("; opacity: .92; }\n");
        sb.append("  .search-wrap input:focus {\n");
        sb.append("    background: rgba(").append(rAddrBg).append(", 0.94); border-color: rgba(").append(rAccent).append(", 0.56);\n");
        sb.append("    box-shadow: 0 0 0 4px rgba(").append(rAccent).append(", 0.09), inset 0 0 0 1px rgba(255,255,255,.025);\n");
        sb.append("  }\n");
        sb.append("  .search-btn { position: absolute; right: 10px; top: 50%; transform: translateY(-50%); width: 38px; height: 38px; background: rgba(").append(rAccent).append(", .14); border: 1px solid rgba(").append(rAccent).append(", .14); border-radius: 12px; color: #").append(accent).append("; font-size: 18px; cursor: pointer; display: grid; place-items: center; transition: background .16s ease, transform .16s ease; }\n");
        sb.append("  .search-btn:hover { background: rgba(").append(rAccent).append(", .24); transform: translateY(-50%) translateX(1px); }\n");
        sb.append("  .quick-section { display: flex; flex-direction: column; gap: 11px; }\n");
        sb.append("  .section-label { padding-left: 3px; color: #").append(mutedFg).append("; -webkit-text-fill-color: #").append(mutedFg).append("; font-size: 10px; font-weight: 650; letter-spacing: .12em; text-transform: uppercase; }\n");
        sb.append("  .quick-links { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 11px; list-style: none; }\n");
        sb.append("  .quick-link { display: flex; align-items: center; gap: 11px; min-height: 64px; padding: 12px 14px; background: rgba(").append(rNavBg).append(", .70); border: 1px solid rgba(").append(rAccent).append(", .11); border-radius: 14px; cursor: pointer; text-decoration: none; color: #").append(textFg).append("; backdrop-filter: blur(16px); -webkit-backdrop-filter: blur(16px); transition: background .16s ease, border-color .16s ease, transform .16s ease, box-shadow .16s ease; }\n");
        sb.append("  .quick-link:hover { background: rgba(").append(rActive).append(", .88); border-color: rgba(").append(rAccent).append(", .30); transform: translateY(-2px); box-shadow: 0 12px 26px rgba(0,0,0,.13); }\n");
        sb.append("  .quick-link:focus-visible, .search-btn:focus-visible, input:focus-visible { outline: 2px solid #").append(accent).append("; outline-offset: 3px; }\n");
        sb.append("  .ql-icon { flex: 0 0 auto; width: 34px; height: 34px; overflow: hidden; border-radius: 10px; display: grid; place-items: center; font-size: 11px; font-weight: 700; letter-spacing: -.01em; color: #").append(accent).append("; background: rgba(").append(rAccent).append(", .12); border: 1px solid rgba(").append(rAccent).append(", .10); }\n");
        sb.append("  #q { color: #").append(textFg).append(" !important; -webkit-text-fill-color: #").append(textFg).append(" !important; }\n");
        sb.append("  .ql-copy { display: flex; flex-direction: column; min-width: 0; gap: 2px; }\n");
        sb.append("  .ql-label { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; font-weight: 520; color: #").append(textFg).append("; -webkit-text-fill-color: #").append(textFg).append("; letter-spacing: -.005em; }\n");
        sb.append("  .ql-host { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 10px; color: #").append(mutedFg).append("; -webkit-text-fill-color: #").append(mutedFg).append("; }\n");
        sb.append("  .key-hints { display: flex; justify-content: center; gap: 18px; color: #").append(mutedFg).append("; -webkit-text-fill-color: #").append(mutedFg).append("; font-size: 11px; }\n");
        sb.append("  .key-hints kbd { margin-right: 5px; padding: 2px 5px; color: #").append(textFg).append("; -webkit-text-fill-color: #").append(textFg).append("; background: rgba(").append(rNavBg).append(", .72); border: 1px solid rgba(").append(rAccent).append(", .12); border-radius: 5px; font: inherit; }\n");
        sb.append("  ::selection { background: rgba(").append(rAccent).append(", .34); color: #").append(textFg).append("; }\n");
        sb.append("  @media (max-width: 620px) { body { padding-top: 40px; } .quick-links { grid-template-columns: repeat(2, minmax(0, 1fr)); } .key-hints { display: none; } }\n");
        sb.append("  @media (max-width: 380px) { .quick-links { grid-template-columns: 1fr; } }\n");
        sb.append("  @media (max-height: 650px) { body { justify-content: flex-start; } .content { gap: 18px; } }\n");
        sb.append("  @media (prefers-reduced-motion: reduce) { *, *::before, *::after { transition: none !important; } .quick-link:hover, .search-btn:hover { transform: none; } }\n");
        sb.append("  @media (prefers-reduced-transparency: reduce), (prefers-contrast: more) { .star-mark, .search-wrap input, .quick-link { backdrop-filter: none; -webkit-backdrop-filter: none; } .search-wrap input { background: rgb(").append(rAddrBg).append("); } .quick-link { background: rgb(").append(rNavBg).append("); } }\n");
        sb.append("</style>\n</head>\n<body>\n");
        sb.append("<main class=\"content\">\n");
        sb.append("  <header class=\"logo\">\n");
        sb.append("    <span class=\"star-mark\" aria-hidden=\"true\">").append(html(t.icon)).append("</span>\n");
        sb.append("    <span class=\"brand-copy\"><h1 class=\"wordmark\">Rigel</h1></span>\n");
        sb.append("  </header>\n");
        sb.append("  <form class=\"search-wrap\" id=\"search-form\" role=\"search\">\n");
        sb.append("    <label class=\"visually-hidden\" for=\"q\">Search the web or enter an address</label>\n");
        sb.append("    <input type=\"text\" id=\"q\" placeholder=\"Search the web or enter a URL\" autocomplete=\"off\" spellcheck=\"false\" autocapitalize=\"none\" enterkeyhint=\"go\" dir=\"auto\">\n");
        sb.append("    <button class=\"search-btn\" type=\"submit\" aria-label=\"Search or navigate\"><svg aria-hidden=\"true\" width=\"17\" height=\"17\" viewBox=\"0 0 17 17\" fill=\"none\"><path d=\"M3.5 8.5h9M9 4l4.5 4.5L9 13\" stroke=\"currentColor\" stroke-width=\"1.7\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg></button>\n");
        sb.append("  </form>\n");
        if (ui.areHomeShortcutsEnabled()) {
            List<config.Settings.Shortcut> shortcuts = ui.getHomeShortcuts();
            if (!shortcuts.isEmpty()) {
                sb.append("  <section class=\"quick-section\" aria-labelledby=\"favorites-title\">\n");
                sb.append("    <h2 class=\"section-label\" id=\"favorites-title\">Favorites</h2>\n");
                sb.append("    <ul class=\"quick-links\">\n");
                for (config.Settings.Shortcut shortcut : shortcuts) {
                    sb.append("      <li><a class=\"quick-link\" href=\"")
                      .append(html(shortcut.url))
                      .append("\" title=\"").append(html(shortcut.title)).append(" — ")
                      .append(html(shortcut.url)).append("\"><span class=\"ql-icon\" aria-hidden=\"true\">")
                      .append(html(shortcut.icon))
                      .append("</span><span class=\"ql-copy\"><span class=\"ql-label\">")
                      .append(html(shortcut.title))
                      .append("</span><span class=\"ql-host\">").append(html(displayHost(shortcut.url)))
                      .append("</span></span></a></li>\n");
                }
                sb.append("    </ul>\n");
                sb.append("  </section>\n");
            }
        }
        {
            config.SearchEngines.Engine engine =
                    config.SearchEngines.currentEngineOrGoogle(ui.settings());
            if (engine != null && !engine.homeShortcut().isBlank()
                    && !engine.baseUrl().isBlank()) {
                sb.append("  <section class=\"quick-section\" aria-labelledby=\"engine-title\">\n");
                sb.append("    <h2 class=\"section-label\" id=\"engine-title\">Search engines</h2>\n");
                sb.append("    <ul class=\"quick-links\">\n");
                sb.append("      <li><a class=\"quick-link\" href=\"").append(html(engine.baseUrl()))
                      .append("\" title=\"Search the web with ").append(html(engine.name)).append("\"><span class=\"ql-icon\" aria-hidden=\"true\">")
                      .append(html(engine.homeShortcut())).append("</span><span class=\"ql-copy\"><span class=\"ql-label\">")
                      .append(html(engine.name)).append("</span><span class=\"ql-host\">").append(html(engine.baseUrl()))
                      .append("</span></span></a></li>\n");
                sb.append("    </ul>\n");
                sb.append("  </section>\n");
            }
        }
        sb.append("  <footer class=\"key-hints\" aria-hidden=\"true\"><span><kbd>⌘ L</kbd>Address</span><span><kbd>⌘ T</kbd>New tab</span><span><kbd>⌘ K</kbd>Search</span></footer>\n");
        sb.append("</main>\n");
        sb.append("<script>\n");
        sb.append("  var composing = false, userInteracted = false;\n");
        sb.append("  var q = document.getElementById('q');\n");
        sb.append("  q.addEventListener('compositionstart', function(){ composing = true; });\n");
        sb.append("  q.addEventListener('compositionend', function(){ composing = false; });\n");
        sb.append("  document.getElementById('search-form').addEventListener('submit', function(e) {\n");
        sb.append("    e.preventDefault(); if (composing) return; var value = q.value.trim(); if (!value) return;\n");
        sb.append("    if (typeof window.rigelHomeNavigate === 'function') window.rigelHomeNavigate(value);\n");
        sb.append("  });\n");
        sb.append("  document.addEventListener('click', function(e) {\n");
        sb.append("    var a = e.target && e.target.closest ? e.target.closest('a.quick-link') : null; if (!a) return;\n");
        sb.append("    if (typeof window.rigelHomeNavigate !== 'function') return; e.preventDefault(); window.rigelHomeNavigate(a.href);\n");
        sb.append("  });\n");
        sb.append("  document.getElementById('q').addEventListener('input', function(){ this.style.color='#").append(textFg).append("'; this.style.webkitTextFillColor='#").append(textFg).append("'; });\n");
        // Some hosting/focus states let macOS insert private-use-area characters
        // (U+E000-U+F8FF, e.g. arrow key glyphs) into really focused web inputs.
        // Never let them into the search text: strip them and restore the caret.
        sb.append("  q.addEventListener('input', function(e){ var v=q.value, out='', dirty=false, idx=0, removed=0;\n");
        sb.append("    for (const ch of v) { var c = ch.codePointAt(0);\n");
        sb.append("      if (c >= 0xE000 && c <= 0xF8FF) { dirty = true; if (idx < q.selectionStart) removed += ch.length; }\n");
        sb.append("      else out += ch;\n");
        sb.append("      idx += ch.length; }\n");
        sb.append("    if (dirty) { var s = Math.max(0, q.selectionStart - removed);\n");
        sb.append("      q.value = out; try { q.setSelectionRange(s, s); } catch (err) {} }\n");
        sb.append("  }, true);\n");
        sb.append("  window.addEventListener('pointerdown', function(){ userInteracted = true; }, {once:true, capture:true});\n");
        sb.append("  window.addEventListener('keydown', function(){ userInteracted = true; }, {once:true, capture:true});\n");
        sb.append("  function focusSearchOnce(){ if (userInteracted || !document.hasFocus()) return; var a=document.activeElement; if(a && a!==document.body && a!==document.documentElement && a!==q) return; try{q.focus({preventScroll:true});}catch(e){q.focus();} }\n");
        sb.append("  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', focusSearchOnce, {once:true}); else requestAnimationFrame(focusSearchOnce);\n");
        if (System.getenv("RIGEL_KEY_DIAG") != null) {
            sb.append("  (function(){\n");
            sb.append("    function det(e){ var v=q.value; var d='[KEYDIAG] '+e.type+' key='+e.key+' code='+e.keyCode+' inputType='+(e.inputType||'')+' value='+JSON.stringify(v)+' sel='+q.selectionStart; try{window.webkit.messageHandlers.rigelDiagnostics.postMessage(d);}catch(x){} }\n");
            sb.append("    q.addEventListener('keydown', det, true);\n");
            sb.append("    q.addEventListener('beforeinput', det, true);\n");
            sb.append("    q.addEventListener('input', det, true);\n");
            sb.append("  })();\n");
        }
        sb.append("</script>\n</body>\n</html>");
        return sb.toString();
    }

    /** Hex string without #, for CSS use: e.g. "6c8ef5" */
    private static String hex(int packed) {
        return String.format("%06x", packed & 0xFFFFFF);
    }

    private static String html(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String displayHost(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            URI uri = URI.create(value.trim());
            String host = uri.getHost();
            if (host == null || host.isBlank()) return value.trim();
            return host.replaceFirst("(?i)^www\\.", "");
        } catch (IllegalArgumentException ex) {
            return value.trim();
        }
    }

    /** CSS rgb() components "r, g, b" for use in rgba(). */
    private static String hexRgb(int packed) {
        return ((packed>>16)&0xFF) + ", " + ((packed>>8)&0xFF) + ", " + (packed&0xFF);
    }

    // ── Fullscreen JS ─────────────────────────────────────────────────────────

    /**
     * Complete Fullscreen API mock injected into every page.
     *
     * Strategy: Mock the entire Fullscreen API contract at the JS level:
     *   1. Report fullscreen support (fullscreenEnabled / webkitFullscreenEnabled)
     *   2. Mock Element.requestFullscreen() to call Java and fire synthetic events
     *   3. Mock document.exitFullscreen() similarly
     *   4. Track the fullscreen element and handle Promise resolution
     *   5. Escape key and external exit detection
     *
     * The player's own CSS (YouTube's .ytp-fullscreen class, etc.) handles
     * the actual layout. We just need the API contract to work.
     */
    private static final String FULLSCREEN_JS = """
(function rigelFullscreenMock() {
    if (window.__rigelFakeFsInit) return;
    window.__rigelFakeFsInit = true;

    /* This function's own source, captured so it can be re-injected
     * verbatim into a same-origin nested frame's window — giving that
     * frame's own document/Element.prototype the identical mock, since
     * monkeypatches applied here don't reach into separate frame realms. */
    var RIGEL_FULLSCREEN_MOCK_SRC = '(' + rigelFullscreenMock.toString() + ')();';

    function injectMockInto(win) {
        if (!win || win.__rigelFakeFsInit) return;
        try {
            win.eval(RIGEL_FULLSCREEN_MOCK_SRC);
        } catch (e) { /* sandboxed frame, nothing we can do */ }
    }

    /* ═══════════════════════════════════════════════════════════════════════
     *  Rigel Fake Fullscreen — complete Fullscreen API mock.
     *
     *  Strategy:
     *    1. Mock every property/method in the Fullscreen API so JS thinks
     *       fullscreen is supported and active.
     *    2. On enter: directly style the fullscreenElement to cover the
     *       entire viewport (position:fixed, edge-to-edge, z-index max).
     *       Hide all page siblings via visibility and set body bg to black.
     *    3. On exit: restore all original inline styles.
     *    4. Fire proper fullscreenchange events that bubble (spec-correct).
     *    5. Handle YouTube, Netflix, Crunchyroll, Twitch, Vimeo, and
     *       generic HTML5 video players.
     * ═══════════════════════════════════════════════════════════════════════ */

    var fullscreenElement = null;
    var logicalFullscreenElement = null;
    var isRequestingFullscreen = false;
    var preferNativeFullscreen = window.__rigelPreferNativeFullscreen !== false;
    var relaxedFullscreenActive = false;
    var youtubeFullscreenPlayer = null;
    var fullscreenVideo = null;

    /* saved state for restoration on exit */
    var savedStyles = null;       /* Map<Element, {css}> */
    var hiddenSiblings = null;    /* Map<Element, original cssText> */
    var savedBodyStyle = '';
    var savedHtmlStyle = '';
    var injectedBodyTabIndex = false;
    var savedScrollX = 0;
    var savedScrollY = 0;

    /* ── helper: define property safely ────────────────────────────── */
    function defProp(obj, name, getter) {
        try {
            Object.defineProperty(obj, name, {
                get: getter, configurable: true, enumerable: true
            });
        } catch(e) {}
    }

    function nativeFullscreenElement() {
        return document.fullscreenElement ||
               document.webkitFullscreenElement ||
               document.webkitCurrentFullScreenElement ||
               document.mozFullScreenElement ||
               document.msFullscreenElement ||
               null;
    }

    function hasNativeFullscreen() {
        var proto = (typeof Element !== 'undefined') ? Element.prototype : null;
        return !!(preferNativeFullscreen && proto &&
                  (proto.requestFullscreen ||
                   proto.webkitRequestFullscreen ||
                   proto.webkitRequestFullScreen ||
                   proto.mozRequestFullScreen ||
                   proto.msRequestFullscreen) &&
                  (document.fullscreenEnabled ||
                   document.webkitFullscreenEnabled ||
                   document.mozFullScreenEnabled ||
                   document.msFullscreenEnabled));
    }

    function requestNativeFullscreen(el) {
        if (!el) return false;
        var fn = el.requestFullscreen ||
                 el.webkitRequestFullscreen ||
                 el.webkitRequestFullScreen ||
                 el.mozRequestFullScreen ||
                 el.msRequestFullscreen;
        if (fn) {
            try {
                var result = fn.call(el);
                if (result && typeof result.catch === 'function') {
                    result.catch(function(){});
                }
                return true;
            } catch (e) {}
        }
        if (el.tagName === 'VIDEO') {
            var videoFn = el.webkitEnterFullscreen || el.webkitEnterFullScreen;
            if (videoFn) {
                try { videoFn.call(el); return true; } catch (e) {}
            }
        }
        return false;
    }

    function exitNativeFullscreen() {
        var fn = document.exitFullscreen ||
                 document.webkitExitFullscreen ||
                 document.webkitCancelFullScreen ||
                 document.mozCancelFullScreen ||
                 document.msExitFullscreen;
        if (!fn) return false;
        try {
            var result = fn.call(document);
            if (result && typeof result.catch === 'function') {
                result.catch(function(){});
            }
            return true;
        } catch (e) {
            return false;
        }
    }

    var useNativeFullscreen = hasNativeFullscreen();

    function prepareNativeFullscreenCapture() {
        try {
            if (typeof window.javaExitFullscreen === 'function') {
                window.javaExitFullscreen('__rigelProtectNativeFullscreen__');
            }
        } catch (e) {}
    }

    /* ── 1. Report fullscreen support ─────────────────────────────── */
    if (!useNativeFullscreen) {
        defProp(document, 'fullscreenEnabled',        function(){ return true; });
        defProp(document, 'webkitFullscreenEnabled',   function(){ return true; });
        defProp(document, 'mozFullScreenEnabled',      function(){ return true; });
        defProp(document, 'msFullscreenEnabled',       function(){ return true; });

        /* ── 2. Track fullscreenElement ───────────────────────────────── */
        defProp(document, 'fullscreenElement',        function(){ return logicalFullscreenElement; });
        defProp(document, 'webkitFullscreenElement',   function(){ return logicalFullscreenElement; });
        defProp(document, 'mozFullScreenElement',      function(){ return logicalFullscreenElement; });
        defProp(document, 'msFullscreenElement',       function(){ return logicalFullscreenElement; });
        defProp(document, 'webkitCurrentFullScreenElement', function(){ return logicalFullscreenElement; });

        /* Also report fullscreen state */
        defProp(document, 'fullscreen',               function(){ return !!logicalFullscreenElement; });
        defProp(document, 'webkitIsFullScreen',        function(){ return !!logicalFullscreenElement; });
        defProp(document, 'mozFullScreen',             function(){ return !!logicalFullscreenElement; });
    }

    /* ── 3. Helpers to manipulate DOM for fake fullscreen ──────────── */

    function hideAllSiblings(el) {
        hiddenSiblings = new Map();
        var node = el;
        while (node && node !== document.documentElement) {
            var parent = node.parentElement;
            if (parent) {
                var children = parent.children;
                for (var i = 0; i < children.length; i++) {
                    var sib = children[i];
                    if (sib === node) continue;
                    if (sib.tagName === 'SCRIPT' || sib.tagName === 'STYLE' ||
                        sib.tagName === 'LINK' || sib.tagName === 'META') continue;
                    hiddenSiblings.set(sib, sib.style.cssText);
                    sib.style.setProperty('visibility', 'hidden', 'important');
                    sib.style.setProperty('position', 'fixed', 'important');
                    sib.style.setProperty('pointer-events', 'none', 'important');
                    sib.style.setProperty('width', '0', 'important');
                    sib.style.setProperty('height', '0', 'important');
                    sib.style.setProperty('overflow', 'hidden', 'important');
                }
            }
            node = parent;
        }
    }

    function restoreAllSiblings() {
        if (!hiddenSiblings) return;
        hiddenSiblings.forEach(function(oldStyle, sib) {
            if (sib && sib.style) {
                sib.style.cssText = oldStyle;
            }
        });
        hiddenSiblings = null;
    }

    function restoreSavedStyle(node) {
        if (!savedStyles || !node || !node.style) return;
        var saved = savedStyles.get(node);
        if (!saved) return;
        restoreStyleRecord(node, saved);
        savedStyles.delete(node);
    }

    function restoreStyleRecord(node, saved) {
        if (!node || !node.style || !saved) return;
        if (saved.props) {
            Object.keys(saved.props).forEach(function(name) {
                var old = saved.props[name];
                if (old.value || old.priority) node.style.setProperty(name, old.value, old.priority);
                else node.style.removeProperty(name);
            });
        } else {
            node.style.cssText = saved.css;
        }
    }

    function selectFullscreenVideo(el) {
        if (!el) return null;
        if (logicalFullscreenElement && logicalFullscreenElement.tagName === 'VIDEO' &&
            visibleArea(logicalFullscreenElement) > 0 &&
            (logicalFullscreenElement === el || (el.contains && el.contains(logicalFullscreenElement)))) {
            return logicalFullscreenElement;
        }
        if (el.tagName === 'VIDEO') return el;

        var candidates = el.querySelectorAll ? el.querySelectorAll('video') : [];
        var visibleCount = 0, best = null, bestArea = 0;
        for (var i = 0; i < candidates.length; i++) {
            var area = visibleArea(candidates[i]);
            if (area > 0) {
                visibleCount++;
                if (area > bestArea) { bestArea = area; best = candidates[i]; }
            }
        }
        return visibleCount === 1 ? best : null;
    }

    function applyFullscreenStyles(el) {
        if (!savedStyles) savedStyles = new Map();

        function saveStyle(node) {
            if (node && !savedStyles.has(node)) {
                savedStyles.set(node, { css: node.style.cssText });
            }
        }

        function saveVideoStyle(node) {
            if (!node || savedStyles.has(node)) return;
            var props = {};
            ['width', 'height', 'max-width', 'max-height', 'box-sizing', 'object-fit'].forEach(function(name) {
                props[name] = {
                    value: node.style.getPropertyValue(name),
                    priority: node.style.getPropertyPriority(name)
                };
            });
            savedStyles.set(node, { props: props });
        }

        /* The target element itself */
        saveStyle(el);
        el.style.setProperty('position', 'fixed', 'important');
        el.style.setProperty('top', '0', 'important');
        el.style.setProperty('left', '0', 'important');
        el.style.setProperty('right', '0', 'important');
        el.style.setProperty('bottom', '0', 'important');
        el.style.setProperty('width', '100%', 'important');
        el.style.setProperty('height', '100%', 'important');
        el.style.setProperty('max-width', 'none', 'important');
        el.style.setProperty('max-height', 'none', 'important');
        el.style.setProperty('min-width', '0', 'important');
        el.style.setProperty('min-height', '0', 'important');
        el.style.setProperty('margin', '0', 'important');
        el.style.setProperty('padding', '0', 'important');
        el.style.setProperty('border', 'none', 'important');
        el.style.setProperty('border-radius', '0', 'important');
        el.style.setProperty('box-sizing', 'border-box', 'important');
        el.style.setProperty('overflow', 'hidden', 'important');
        el.style.setProperty('overscroll-behavior', 'none', 'important');
        el.style.setProperty('z-index', '2147483647', 'important');
        el.style.setProperty('transform', 'none', 'important');
        el.style.setProperty('contain', 'none', 'important');
        el.style.setProperty('object-fit', 'contain', 'important');
        el.style.setProperty('background', '#000', 'important');

        /* Size one active video, never every ad/preload/preview stream in a
         * custom player. Explicit video requests remain the preferred target. */
        var v = selectFullscreenVideo(el);
        if (fullscreenVideo && fullscreenVideo !== v) restoreSavedStyle(fullscreenVideo);
        fullscreenVideo = v;
        if (v) {
            saveVideoStyle(v);
            v.style.setProperty('width', '100%', 'important');
            v.style.setProperty('height', '100%', 'important');
            v.style.setProperty('max-width', 'none', 'important');
            v.style.setProperty('max-height', 'none', 'important');
            v.style.setProperty('box-sizing', 'border-box', 'important');
            v.style.setProperty('object-fit', 'contain', 'important');
        }

        /* Unclip layout ancestors, but keep the page roots scroll-locked. */
        var node = el.parentElement;
        while (node && node !== document.body && node !== document.documentElement) {
            saveStyle(node);
            node.style.setProperty('overflow', 'visible', 'important');
            node.style.setProperty('contain', 'none', 'important');
            node.style.setProperty('clip', 'auto', 'important');
            node.style.setProperty('clip-path', 'none', 'important');
            node.style.setProperty('z-index', 'auto', 'important');
            node.style.setProperty('transform', 'none', 'important');
            node = node.parentElement;
        }
    }

    function restoreFullscreenStyles() {
        if (!savedStyles) return;
        savedStyles.forEach(function(saved, el) {
            if (el && el.style) {
                restoreStyleRecord(el, saved);
            }
        });
        savedStyles = null;
        fullscreenVideo = null;
    }

    /* ── 4. YouTube-specific helpers ─────────────────────────────── */

    function isYouTube() {
        var host = String(location.hostname || '').toLowerCase();
        return host === 'youtube.com' || host.endsWith('.youtube.com') ||
               host === 'youtube-nocookie.com' || host.endsWith('.youtube-nocookie.com') ||
               host === 'youtu.be' || host.endsWith('.youtu.be');
    }

    function isYouTubeShorts() {
        return isYouTube() && /^\\/shorts(\\/|$)/.test(location.pathname || '');
    }

    function isTikTok() {
        var host = String(location.hostname || '').toLowerCase();
        return host === 'tiktok.com' || host.endsWith('.tiktok.com');
    }

    function isVerticalFeedSurface() {
        /* TikTok intentionally keeps its active feed surface navigable. YouTube
         * Shorts uses the same isolated player path as regular YouTube. */
        return isTikTok();
    }

    function visibleArea(el) {
        if (!el || typeof el.getBoundingClientRect !== 'function') return 0;
        var rect = el.getBoundingClientRect();
        if (!rect || rect.width <= 0 || rect.height <= 0) return 0;
        var style = window.getComputedStyle ? window.getComputedStyle(el) : null;
        if (style && (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0)) return 0;
        return rect.width * rect.height;
    }

    function findLargestVisibleVideo(root) {
        root = root || document;
        var videos = root.querySelectorAll ? root.querySelectorAll('video') : [];
        var best = null, bestArea = 0;
        for (var i = 0; i < videos.length; i++) {
            var area = visibleArea(videos[i]);
            if (area > bestArea) {
                best = videos[i];
                bestArea = area;
            }
        }
        return best;
    }

    function fullscreenWrapperForVideo(video, fallback) {
        if (!video) return fallback || null;
        return video.closest(
            'ytd-reel-video-renderer[is-active], ytd-reel-video-renderer, ' +
            '#movie_player, .html5-video-player, [role="application"], ' +
            '[data-e2e="browse-video"], [data-e2e="video-player"], article, ' +
            '.video-player, .player, .watch-video, .NFPlayer'
        ) || video || fallback || null;
    }

    function siteFullscreenTarget(fallback) {
        var video = findLargestVisibleVideo(document);
        if (isYouTubeShorts() || isTikTok()) {
            return fullscreenWrapperForVideo(video, fallback) || fallback;
        }
        if (isYouTube()) {
            return fullscreenWrapperForVideo(video, document.querySelector('#movie_player') ||
                                            document.querySelector('.html5-video-player') ||
                                            fallback) || fallback;
        }
        return fallback;
    }

    var MEDIA_PLAYER_ROOT_SELECTOR =
        '.html5-video-player, .video-player, .watch-video, .NFPlayer, .video-js, .jwplayer, .plyr, ' +
        '.shaka-video-container, .clappr-container, .mejs__container';
    var PLAYER_ROOT_SELECTOR = MEDIA_PLAYER_ROOT_SELECTOR + ', [role="application"], .player';

    function playerWrapperForVideo(video) {
        if (!video) return null;
        return video.closest(MEDIA_PLAYER_ROOT_SELECTOR);
    }

    function enterBestVisibleVideoFullscreen() {
        var vid = findLargestVideoDeep(document, 0);
        if (!vid) return false;
        var player = playerWrapperForVideo(vid);
        var target = (isYouTube() || isTikTok()) ? (siteFullscreenTarget(player || vid) || player || vid) : (player || vid);
        if (!target) return false;
        if (useNativeFullscreen) {
            prepareNativeFullscreenCapture();
            if (requestNativeFullscreen(target)) return true;
        }
        enterFullscreen(target, vid);
        return true;
    }

    function hideYouTubeFullscreenArtifacts(el) {
        if (!hiddenSiblings) hiddenSiblings = new Map();
        var toHide = [
            '#masthead-container', '#masthead', 'ytd-masthead',
            '#secondary', '#related', '#comments', '#chat',
            'ytd-live-chat-frame', '#guide', '#guide-content',
            '#below', '#meta', '#info', '#owner',
            'ytd-watch-metadata', '#description',
            '#header', '#chips-wrapper',
            // YouTube inserts these inside #movie_player after fullscreen has
            // already started, so sibling-only cleanup cannot remove them.
            '.ytp-ce-element', '.ytp-endscreen-content',
            '.ytp-videowall-still', '.ytp-suggestion-set',
            '.ytp-autonav-endscreen-upnext-container',
            '.ytp-pause-overlay', '.ytp-pause-overlay-container',
            '.ytp-cards-button', '.ytp-cards-teaser'
        ];
        if (!isYouTubeShorts()) toHide.push('ytd-page-manager > ytd-browse');
        toHide.forEach(function(sel) {
            var els = document.querySelectorAll(sel);
            for (var i = 0; i < els.length; i++) {
                var candidate = els[i];
                if (candidate === el || candidate.contains(el)) continue;
                if (!hiddenSiblings.has(candidate)) {
                    hiddenSiblings.set(candidate, candidate.style.cssText);
                }
                candidate.style.setProperty('display', 'none', 'important');
            }
        });
    }

    function youtubeEnter(el) {
        var player = (el && el.matches && el.matches('#movie_player, .html5-video-player') ? el : null) ||
                     (el && el.querySelector ? el.querySelector('#movie_player, .html5-video-player') : null) ||
                     (el && el.closest ? el.closest('#movie_player, .html5-video-player') : null) ||
                     document.querySelector('#movie_player') ||
                     document.querySelector('.html5-video-player');
        youtubeFullscreenPlayer = player;
        if (player) player.classList.add('ytp-fullscreen');
        hideYouTubeFullscreenArtifacts(el);
    }

    function youtubeExit() {
        var player = youtubeFullscreenPlayer;
        youtubeFullscreenPlayer = null;
        if (player) player.classList.remove('ytp-fullscreen');
    }

    function fireFullscreenEvents(eventTarget) {
        var target = eventTarget || logicalFullscreenElement || document;
        if (target !== document && (!target.isConnected || target.ownerDocument !== document)) target = document;
        try { target.dispatchEvent(new Event('fullscreenchange', {bubbles: true, composed: true, cancelable: false})); } catch(e) {}
        try { target.dispatchEvent(new Event('webkitfullscreenchange', {bubbles: true, composed: true, cancelable: false})); } catch(e) {}
        try { window.dispatchEvent(new Event('resize')); } catch(e) {}
    }

    function enterFullscreen(elem, logicalElem) {
        logicalElem = logicalElem || elem;
        if (isRequestingFullscreen) return Promise.resolve();
        if (fullscreenElement === elem && logicalFullscreenElement === logicalElem) return Promise.resolve();
        isRequestingFullscreen = true;

        if (fullscreenElement) {
            doExit(true);
        }

        fullscreenElement = elem;
        logicalFullscreenElement = logicalElem;
        relaxedFullscreenActive = isVerticalFeedSurface();
        if (!relaxedFullscreenActive) startFullscreenObserver();

        savedScrollX = window.scrollX;
        savedScrollY = window.scrollY;

        savedBodyStyle = document.body.style.cssText;
        savedHtmlStyle = document.documentElement.style.cssText;
        injectedBodyTabIndex = false;

        if (!relaxedFullscreenActive) {
            document.body.style.setProperty('overflow', 'hidden', 'important');
            document.documentElement.style.setProperty('overflow', 'hidden', 'important');
            document.body.style.setProperty('background', '#000', 'important');
            document.documentElement.style.setProperty('background', '#000', 'important');
            document.body.style.setProperty('margin', '0', 'important');
            document.body.style.setProperty('padding', '0', 'important');
            document.documentElement.style.setProperty('margin', '0', 'important');
            document.documentElement.style.setProperty('padding', '0', 'important');
            document.body.style.setProperty('transform', 'none', 'important');
            document.documentElement.style.setProperty('transform', 'none', 'important');
            document.body.style.setProperty('filter', 'none', 'important');
            document.documentElement.style.setProperty('filter', 'none', 'important');
            document.body.style.setProperty('perspective', 'none', 'important');
            document.documentElement.style.setProperty('perspective', 'none', 'important');
            document.body.style.setProperty('contain', 'none', 'important');
            document.documentElement.style.setProperty('contain', 'none', 'important');
            document.body.style.setProperty('will-change', 'auto', 'important');
            document.documentElement.style.setProperty('will-change', 'auto', 'important');

            hideAllSiblings(elem);
            applyFullscreenStyles(elem);
            if (isYouTube()) youtubeEnter(elem);
        } else {
            try { document.documentElement.classList.add('rigel-vertical-feed-fullscreen'); } catch(e) {}
            try {
                if (document.body && !document.activeElement.matches('input, textarea, select, [contenteditable="true"]')) {
                    if (!document.body.hasAttribute('tabindex')) {
                        document.body.setAttribute('tabindex', '-1');
                        injectedBodyTabIndex = true;
                    }
                    document.body.focus({preventScroll: true});
                }
            } catch(e) {}
        }

        document.body.classList.add('rigel-fullscreen');

        if (typeof window.javaEnterFullscreen === 'function') {
            var accepted = window.javaEnterFullscreen();
            if (accepted === false) {
                doExit(true);
                isRequestingFullscreen = false;
                return Promise.reject(typeof DOMException === 'function'
                    ? new DOMException('Fullscreen requires a user action', 'NotAllowedError')
                    : new Error('Fullscreen requires a user action'));
            }
        }
        fireFullscreenEvents();

        setTimeout(function() {
            if (fullscreenElement === elem && !relaxedFullscreenActive) {
                applyFullscreenStyles(elem);
                try { window.dispatchEvent(new Event('resize')); } catch(e) {}
            }
        }, 100);
        setTimeout(function() {
            if (fullscreenElement === elem && !relaxedFullscreenActive) {
                applyFullscreenStyles(elem);
            }
        }, 500);

        isRequestingFullscreen = false;
        return Promise.resolve();
    }

    function doExit(silent) {
        if (!fullscreenElement) return;

        var prevLogical = logicalFullscreenElement;
        fullscreenElement = null;
        logicalFullscreenElement = null;
        relaxedFullscreenActive = false;
        stopFullscreenObserver();

        restoreFullscreenStyles();
        restoreAllSiblings();

        document.body.style.cssText = savedBodyStyle;
        document.documentElement.style.cssText = savedHtmlStyle;

        if (injectedBodyTabIndex && document.body.getAttribute('tabindex') === '-1')
            document.body.removeAttribute('tabindex');
        injectedBodyTabIndex = false;

        try { window.scrollTo(savedScrollX, savedScrollY); } catch(e) {}

        if (isYouTube()) youtubeExit();

        document.body.classList.remove('rigel-fullscreen');
        try { document.documentElement.classList.remove('rigel-vertical-feed-fullscreen'); } catch(e) {}

        if (!silent) {
            fireFullscreenEvents(prevLogical);
            if (typeof window.javaExitFullscreen === 'function') {
                window.javaExitFullscreen();
            }
        }
    }

    function exitFullscreenImpl() {
        if (!fullscreenElement) return Promise.resolve();
        doExit(false);
        return Promise.resolve();
    }

    if (!useNativeFullscreen) {
        Element.prototype.requestFullscreen = function(opts) {
            var fsElem = this;
            if (isYouTube() || isTikTok()) {
                fsElem = siteFullscreenTarget(fsElem) || fsElem;
            } else if (this.tagName === 'VIDEO') {
                fsElem = playerWrapperForVideo(this) || this;
            }
            return enterFullscreen(fsElem, this);
        };
        Element.prototype.webkitRequestFullscreen = Element.prototype.requestFullscreen;
        Element.prototype.webkitRequestFullScreen = Element.prototype.requestFullscreen;
        Element.prototype.mozRequestFullScreen    = Element.prototype.requestFullscreen;
        Element.prototype.msRequestFullscreen     = Element.prototype.requestFullscreen;

        if (typeof HTMLVideoElement !== 'undefined') {
            HTMLVideoElement.prototype.webkitEnterFullscreen = function() {
                var target = (isYouTube() || isTikTok())
                    ? (siteFullscreenTarget(this) || this)
                    : (playerWrapperForVideo(this) || this);
                return enterFullscreen(target, this);
            };
            HTMLVideoElement.prototype.webkitExitFullscreen = function() {
                return exitFullscreenImpl();
            };
            HTMLVideoElement.prototype.webkitEnterFullScreen = HTMLVideoElement.prototype.webkitEnterFullscreen;
            HTMLVideoElement.prototype.webkitExitFullScreen = HTMLVideoElement.prototype.webkitExitFullscreen;

            defProp(HTMLVideoElement.prototype, 'webkitSupportsFullscreen', function(){ return true; });
            defProp(HTMLVideoElement.prototype, 'webkitDisplayingFullscreen', function(){ return logicalFullscreenElement === this; });
        }

        document.exitFullscreen = function() {
            return exitFullscreenImpl();
        };
        document.webkitExitFullscreen    = document.exitFullscreen;
        document.webkitCancelFullScreen  = document.exitFullscreen;
        document.mozCancelFullScreen     = document.exitFullscreen;
        document.msExitFullscreen        = document.exitFullscreen;
    }

    document.addEventListener('keydown', function(e) {
        if (useNativeFullscreen && (e.key === 'f' || e.key === 'F') &&
                !/^(INPUT|TEXTAREA|SELECT)$/.test((e.target && e.target.tagName) || '')) {
            prepareNativeFullscreenCapture();
        }
        if ((e.key === 'Escape' || e.keyCode === 27) && fullscreenElement) {
            e.preventDefault();
            e.stopImmediatePropagation();
            exitFullscreenImpl();
        }
    }, true);

    if (useNativeFullscreen) {
        document.addEventListener('dblclick', function(event) {
            var target = event.target;
            if (target && typeof target.closest === 'function' &&
                    target.closest('video, #movie_player, .html5-video-player')) {
                prepareNativeFullscreenCapture();
            }
        }, true);
        document.addEventListener('fullscreenchange', prepareNativeFullscreenCapture, true);
        document.addEventListener('webkitfullscreenchange', prepareNativeFullscreenCapture, true);
    }

    window.__rigelExitFS = function() {
        if (nativeFullscreenElement()) {
            return exitNativeFullscreen();
        }
        exitFullscreenImpl();
        return true;
    };

    /* Java-driven teardown must not echo javaExitFullscreen back into Java. */
    window.__rigelExitFSSilent = function() {
        var result = true;
        if (fullscreenElement) doExit(true);
        else if (nativeFullscreenElement()) result = exitNativeFullscreen();
        try { if (document.body) document.body.classList.remove('rigel-fullscreen'); } catch(e) {}
        try { document.documentElement.classList.remove('rigel-vertical-feed-fullscreen'); } catch(e) {}
        return result;
    };

    document.addEventListener('click', function(e) {
        var target = e.target;
        if (!target || typeof target.closest !== 'function') return;

        var btn = target.closest('button, [role="button"], [class*="fullscreen" i], [class*="full-screen" i], [aria-label*="fullscreen" i], [title*="fullscreen" i], [data-e2e*="fullscreen" i], .vjs-fullscreen-control, .ytp-fullscreen-button, .jw-icon-fullscreen, .plyr__control[data-plyr="fullscreen"]');
        if (!btn) return;

        // Ensure we don't hijack clicks on the main video element or large player containers
        if (btn.tagName === 'VIDEO') return;
        var rect = btn.getBoundingClientRect();
        if (rect.width > 300 || rect.height > 300) return;

        var className = (typeof btn.className === 'string') ? btn.className : '';
        var title = btn.getAttribute('title') || '';
        var ariaLabel = btn.getAttribute('aria-label') || '';

        var isFsBtn = /full.?screen/i.test(className) ||
                      /full.?screen/i.test(title) ||
                      /full.?screen/i.test(ariaLabel) ||
                      btn.classList.contains('vjs-fullscreen-control') ||
                      btn.classList.contains('ytp-fullscreen-button') ||
                      btn.classList.contains('jw-icon-fullscreen');

        if (isFsBtn) {
            var isExit = /exit|close|minimize|compress/i.test(className + title + ariaLabel);

            if (useNativeFullscreen) {
                if (!isExit) prepareNativeFullscreenCapture();
                return;
            }

            /*
             * The fallback below is used only when embedded WebKit does not
             * expose native element fullscreen. It must never run on modern
             * WKWebView because DOM-forced fullscreen breaks video geometry.
             * YouTube's own fullscreen button can ask WKWebView/native
             * fullscreen to reshuffle the compositor before our fake
             * fullscreen settles, producing the black/grey player. The
             * keyboard toggle path does not involve YouTube's handler and is
             * known-good, so route YouTube button clicks through that same
             * direct visible-video path and stop the site handler here.
             */
            if (isYouTube()) {
                e.preventDefault();
                e.stopImmediatePropagation();
                if (nativeFullscreenElement()) exitNativeFullscreen();
                else if (fullscreenElement) exitFullscreenImpl();
                else enterBestVisibleVideoFullscreen();
                return;
            }

            if (fullscreenElement) {
                e.preventDefault();
                e.stopImmediatePropagation();
                exitFullscreenImpl();
                return;
            }

            if (!isExit) {
                setTimeout(function() {
                    if (!fullscreenElement && !isRequestingFullscreen) {
                        var player = btn.closest(PLAYER_ROOT_SELECTOR);

                        var vid = btn.closest('video') || (player ? player.querySelector('video') : null) || document.querySelector('video');

                        var fsElem = player || vid;
                        if (isYouTube() || isTikTok()) {
                            fsElem = siteFullscreenTarget(fsElem) || fsElem;
                        }

                        if (fsElem) {
                            enterFullscreen(fsElem);
                        }
                    }
                }, 100);
            }
        }
    }, true);

    /* ── 6. Cross-origin iframe fallback ─────────────────────────────
     * Top-level click/dblclick listeners above never see events that land
     * inside a cross-origin <iframe> (megaplay/vidsrc-style embeds) — the
     * browser sandboxes those completely, so a button inside that iframe
     * clicking its own requestFullscreen() never reaches this script.
     *
     * Before resorting to coordinate guessing, we try the PRECISE route:
     * many of these embeds nest a same-origin wrapper iframe around a
     * deeper cross-origin player. accessing iframe.contentDocument throws
     * only when the frame is genuinely cross-origin — so we recurse
     * through every reachable same-origin frame first, searching for a
     * real fullscreen button with the exact same selector logic the
     * top-level listener already uses (and injecting this same mock into
     * each same-origin frame we pass through, so a button living THERE
     * has a patched requestFullscreen() of its own to call). Only when
     * that search bottoms out at a frame we truly cannot see into do we
     * fall back to fake-fullscreening the opaque iframe element itself,
     * driven by a click point Java hands us (since Java can't see inside
     * it either — all it has is screen coordinates). */

    window.__rigelFsIsActive = function() {
        return !!(nativeFullscreenElement() || fullscreenElement);
    };

    window.__rigelFsIframeActive = function() {
        var nativeElement = nativeFullscreenElement();
        return !!((nativeElement && nativeElement.tagName === 'IFRAME') ||
                  (fullscreenElement && fullscreenElement.tagName === 'IFRAME'));
    };

    /* Shared button-detection — same rules as the top-level click listener
     * above, factored out so the same-origin recursive search can reuse it
     * instead of guessing by coordinates. */
    function findFullscreenButton(doc) {
        var candidates = doc.querySelectorAll(
            'button, [role="button"], [class*="fullscreen" i], [class*="full-screen" i], ' +
            '[class*="expand" i], [class*="maximize" i], [aria-label*="fullscreen" i], ' +
            '[title*="fullscreen" i], [aria-label*="expand" i], [title*="expand" i], ' +
            '.vjs-fullscreen-control, .ytp-fullscreen-button, .jw-icon-fullscreen, ' +
            '.plyr__control[data-plyr="fullscreen"]'
        );
        for (var i = 0; i < candidates.length; i++) {
            var btn = candidates[i];
            if (btn.tagName === 'VIDEO') continue;
            var rect = btn.getBoundingClientRect();
            if (rect.width > 300 || rect.height > 300) continue;
            if (rect.width === 0 || rect.height === 0) continue; // hidden

            var className  = (typeof btn.className === 'string') ? btn.className : '';
            var title      = btn.getAttribute('title') || '';
            var ariaLabel  = btn.getAttribute('aria-label') || '';
            var haystack   = className + ' ' + title + ' ' + ariaLabel;

            var isFsBtn = /full.?screen|expand|maximize/i.test(haystack) ||
                          btn.classList.contains('vjs-fullscreen-control') ||
                          btn.classList.contains('ytp-fullscreen-button') ||
                          btn.classList.contains('jw-icon-fullscreen');
            if (!isFsBtn) continue;
            if (/exit|close|minimize|compress/i.test(haystack)) continue; // an exit button, not enter

            return btn;
        }
        return null;
    }

    /* Recursively search this document and any reachable same-origin
     * descendant frames for a real fullscreen button. Returns the button
     * element (which may live in a nested frame's own document) or null. */
    function findFullscreenButtonDeep(doc, depth) {
        if (!doc || depth > 4) return null; // guard against pathological nesting

        var found = findFullscreenButton(doc);
        if (found) return found;

        var frames = doc.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
            var inner;
            try {
                inner = frames[i].contentDocument; // throws if cross-origin
            } catch (e) {
                inner = null;
            }
            if (!inner) continue; // cross-origin — can't see further down this branch
            injectMockInto(frames[i].contentWindow);
            var deepFound = findFullscreenButtonDeep(inner, depth + 1);
            if (deepFound) return deepFound;
        }
        return null;
    }

    /* Walks same-origin frames looking for the iframe element that is
     * genuinely opaque to us (first cross-origin boundary hit while
     * descending toward the click point). That's the best fake-fullscreen
     * target when no real button could be found same-origin. */
    function findOpaqueFrameAt(x, y) {
        var el = document.elementFromPoint(x, y);
        var frame = el ? (el.tagName === 'IFRAME' ? el : el.closest('iframe')) : null;
        var doc = document;
        var depth = 0;
        while (frame && depth < 4) {
            var inner;
            try {
                inner = frame.contentDocument;
            } catch (e) {
                inner = null;
            }
            if (!inner) return frame; // this is the opaque boundary
            // Same-origin — recompute the click point relative to the
            // nested document and keep descending.
            var rect = frame.getBoundingClientRect();
            var nx = x - rect.left, ny = y - rect.top;
            var nestedEl = inner.elementFromPoint(nx, ny);
            var nestedFrame = nestedEl ? (nestedEl.tagName === 'IFRAME' ? nestedEl : nestedEl.closest('iframe')) : null;
            if (!nestedFrame) return frame; // same-origin but no deeper frame here
            frame = nestedFrame;
            doc = inner;
            x = nx; y = ny;
            depth++;
        }
        return frame;
    }

    window.__rigelFsIframe = function(x, y) {
        var nativeElement = nativeFullscreenElement();
        if (nativeElement) {
            exitNativeFullscreen();
            return true;
        }

        /* Already fullscreen on an iframe → this call is a toggle-off. */
        if (fullscreenElement && fullscreenElement.tagName === 'IFRAME') {
            exitFullscreenImpl();
            return true;
        }
        /* Some other (same-origin) element is fullscreen already — that
         * path owns this state, stay out of its way. */
        if (fullscreenElement) return false;

        /* Precise path: a real button exists somewhere reachable
         * same-origin — click it for real instead of faking anything.
         * This is strictly better than guessing, since it triggers the
         * site's own fullscreen logic exactly as if the user clicked it. */
        var realBtn = findFullscreenButtonDeep(document, 0);
        if (realBtn) {
            try {
                realBtn.click();
                return true;
            } catch (e) { /* fall through to the opaque-frame fallback */ }
        }

        /* Last resort: genuinely cross-origin, nothing reachable. Fake-
         * fullscreen whichever frame sits under the click point at the
         * deepest same-origin level we could reach. */
        var frame = findOpaqueFrameAt(x, y);
        if (!frame) return false;

        if (useNativeFullscreen && requestNativeFullscreen(frame)) return true;

        enterFullscreen(frame);
        return true;
    };

    /* Explicit exit, regardless of click point — used for the bottom-right
     * hit-zone fallback and for re-driving Escape when an iframe owns the
     * fullscreen state. */
    window.__rigelFsExitIframe = function() {
        var nativeElement = nativeFullscreenElement();
        if (nativeElement && nativeElement.tagName === 'IFRAME') {
            exitNativeFullscreen();
            return true;
        }
        if (fullscreenElement && fullscreenElement.tagName === 'IFRAME') {
            exitFullscreenImpl();
            return true;
        }
        return false;
    };

    /* Finds the largest visible <video> reachable same-origin, recursing
     * into nested same-origin frames the same way findFullscreenButtonDeep
     * does. Mirrors the heuristic requestFullscreen() already uses to pick
     * a sensible wrapper around a bare video element. */
    function findLargestVideoDeep(doc, depth) {
        if (!doc || depth > 4) return null;

        var best = null, bestArea = 0;
        var vids = doc.querySelectorAll('video');
        for (var i = 0; i < vids.length; i++) {
            var area = visibleArea(vids[i]);
            if (area > bestArea) {
                best = vids[i];
                bestArea = area;
            }
        }
        if (best) return best;

        var frames = doc.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
            var inner;
            try { inner = frames[i].contentDocument; } catch (e) { inner = null; }
            if (!inner) continue;
            var deepFound = findLargestVideoDeep(inner, depth + 1);
            if (deepFound) return deepFound;
        }
        return null;
    }

    /* Keyboard-driven, coordinate-free toggle (Cmd+Shift+F). No click
     * point exists here, so rather than guessing a screen position we:
     *   1. Exit immediately if ANYTHING is already fullscreen.
     *   2. Otherwise look for a real same-origin video — same wrapper
     *      heuristic requestFullscreen() uses — and enter on that.
     *   3. Otherwise try a real same-origin fullscreen button anywhere
     *      reachable (clicking it triggers the site's own logic).
     *   4. Last resort: the largest reachable <iframe> on the page,
     *      treated as an opaque cross-origin player. */
    window.__rigelFsToggle = function() {
        if (nativeFullscreenElement()) {
            exitNativeFullscreen();
            return true;
        }

        if (fullscreenElement) {
            exitFullscreenImpl();
            return true;
        }

        var vid = findLargestVideoDeep(document, 0);
        if (vid && enterBestVisibleVideoFullscreen()) {
            return true;
        }

        var realBtn = findFullscreenButtonDeep(document, 0);
        if (realBtn) {
            try {
                realBtn.click();
                return true;
            } catch (e) { /* fall through */ }
        }

        var frames = document.querySelectorAll('iframe');
        var bestFrame = null, bestArea = 0;
        for (var i = 0; i < frames.length; i++) {
            var rect = frames[i].getBoundingClientRect();
            var area = rect.width * rect.height;
            if (area > bestArea) { bestFrame = frames[i]; bestArea = area; }
        }
        if (bestFrame) {
            if (useNativeFullscreen && requestNativeFullscreen(bestFrame)) return true;
            enterFullscreen(bestFrame);
            return true;
        }

        return false;
    };

    var fsObserver = null;
    var fsConnectivityPoll = null;
    var fsObserverScheduled = false;

    function stopFullscreenObserver() {
        if (fsObserver) {
            try { fsObserver.disconnect(); } catch (e) {}
            fsObserver = null;
        }
        // The old connectivity watch was a MutationObserver over the whole
        // document subtree: on chatty fullscreen pages (TikTok lives) that is
        // a callback per mutation batch just to check one boolean. A poll is
        // bounded work with identical behaviour.
        if (fsConnectivityPoll) {
            try { clearInterval(fsConnectivityPoll); } catch (e) {}
            fsConnectivityPoll = null;
        }
        fsObserverScheduled = false;
    }

    function startFullscreenObserver() {
        if (fsObserver || !document.body || !fullscreenElement || relaxedFullscreenActive) return;
        fsObserver = new MutationObserver(function() {
            if (!fullscreenElement || relaxedFullscreenActive || fsObserverScheduled) return;
            if (!fullscreenElement.isConnected) { doExit(false); return; }
            fsObserverScheduled = true;
            var run = function() {
                fsObserverScheduled = false;
                if (!fullscreenElement || relaxedFullscreenActive) return;
                var needsVideo = fullscreenElement.tagName === 'VIDEO' ||
                                 (logicalFullscreenElement && logicalFullscreenElement.tagName === 'VIDEO');
                var desiredVideo = selectFullscreenVideo(fullscreenElement);
                var needsReapply = fullscreenElement.style.position !== 'fixed' ||
                                    fullscreenElement.style.width !== '100%' ||
                                    fullscreenElement.style.height !== '100%' ||
                                    fullscreenElement.style.overflow !== 'hidden' ||
                                    desiredVideo !== fullscreenVideo ||
                                    (needsVideo && (!fullscreenVideo || !fullscreenVideo.isConnected)) ||
                                    (fullscreenVideo && (fullscreenVideo.style.width !== '100%' ||
                                                         fullscreenVideo.style.height !== '100%'));
                if (needsReapply) applyFullscreenStyles(fullscreenElement);
                if (isYouTube()) hideYouTubeFullscreenArtifacts(fullscreenElement);
            };
            if (typeof requestAnimationFrame === 'function') requestAnimationFrame(run);
            else setTimeout(run, 16);
        });
        fsObserver.observe(fullscreenElement, {
            attributes: true, childList: true, subtree: true,
            attributeFilter: ['style', 'class']
        });
        // Detects the fullscreen element being torn out of the DOM (players
        // that swap containers mid-session). Fixed cost, no per-mutation work.
        fsConnectivityPoll = setInterval(function() {
            if (!fullscreenElement || relaxedFullscreenActive) return;
            if (!fullscreenElement.isConnected) doExit(false);
        }, 500);
    }
})();
""";

    // ── Public data class ────────────────────────────────────────────────────

    public static class TabData {
        public final Composite container;
        public final RigelBrowser browser;
        public       String    title;
        public       String    url;
        public       boolean   loading;
        public       int       progress;
        long navigationGeneration;
        long renderedHomeVersion;
        // ── Tab sleep (RAM discard) state ──
        /** True while the page content has been unloaded to reclaim memory. */
        public boolean sleeping;
        /** URL + scroll snapshot, taken just before the page was discarded. */
        String  sleepUrl;
        String  sleepTitle;
        int     sleepScrollX;
        int     sleepScrollY;
        /** Wall clock of the last time this tab stopped being active (0 = active now). */
        long    backgroundedAtMillis;
        /** One-shot: restore the saved scroll offset after the wake reload. */
        boolean pendingScrollRestore;

        TabData(Composite container, RigelBrowser browser, String title, String url) {
            this.container = container;
            this.browser   = browser;
            this.title     = title;
            this.url       = url;
            this.backgroundedAtMillis = System.currentTimeMillis();
        }
    }

    private static class ClosedTab {
        final String title;
        final String url;
        ClosedTab(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    // ── Fields ───────────────────────────────────────────────────────────────

    private Composite     contentArea;
    private BrowserUI.TabDeckLayout stackLayout;
    private final BrowserUI ui;
    private final List<TabData> tabs = new ArrayList<>();
    private final Deque<ClosedTab> closedTabs = new ArrayDeque<>();
    private static final GameControllerBridge SHARED_GAME_CONTROLLER = new GameControllerBridge();
    private final GameControllerBridge gameControllerBridge = SHARED_GAME_CONTROLLER;
    private int activeIndex = -1;
    private boolean gamepadPollingStarted = false;
    /** True while a runGamepadPoll tick is scheduled; prevents duplicate chains. */
    private boolean gamepadPollArmed = false;
    private int gamepadEmptyStreak = 0;
    private String gamepadLastJson = null;
    private RigelBrowser gamepadLastBrowser = null;
    private boolean gameControllerEnabled = true;
    private boolean nativeClipboardEnabled = true;
    private boolean appActive = true;
    private int focusGeneration = 0;
    private long homeRenderVersion = 1;
    /** Idle background tabs are discarded after this; clamps to >= 1 min.
     *  0 disables (system property rigel.tabSleepMinutes). */
    private static final long TAB_SLEEP_AFTER_MILLIS;
    static {
        int minutes = Integer.getInteger("rigel.tabSleepMinutes", 15);
        TAB_SLEEP_AFTER_MILLIS = minutes <= 0 ? 0 : Math.max(1, minutes) * 60_000L;
    }
    private final java.util.concurrent.ScheduledExecutorService tabSleepSweeper =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "rigel-tab-sleep");
                t.setDaemon(true);
                return t;
            });
    private volatile boolean shutdown;
    // ── Constructor ──────────────────────────────────────────────────────────

    public TabManager(Composite contentArea, BrowserUI.TabDeckLayout stackLayout, BrowserUI ui) {
        this.contentArea = contentArea;
        this.stackLayout = stackLayout;
        this.ui          = ui;
        contentArea.addDisposeListener(e -> shutdown());
        startGamepadPolling();
        // Cheap 60s housekeeping tick; all real work happens off-tick in
        // evaluateAsync callbacks, only for tabs past the idle threshold.
        // The step reschedules itself: 60s while any tab still needs
        // auditing, backing off to 300s once nothing does, so a fully idle
        // browser stops waking its sweeper thread every minute.
        tabSleepSweeper.schedule(this::sweepTabSleepStep,
                60, java.util.concurrent.TimeUnit.SECONDS);
    }

    // ── Reattach after layout toggle ─────────────────────────────────────────

    /**
     * Called by BrowserUI when it rebuilds the layout (top-bar ↔ sidebar).
     * Reparents all tab containers to the new contentArea composite and
     * re-points our stackLayout reference so subsequent switchTo() calls
     * work correctly.
     */
    public void reattach(Composite newContentArea, BrowserUI.TabDeckLayout newStackLayout) {
        this.contentArea = newContentArea;
        this.stackLayout = newStackLayout;

        for (TabData tab : tabs) {
            tab.container.setParent(newContentArea);
        }

        // Re-apply the active tab on top of the z-ordered deck
        if (activeIndex >= 0 && activeIndex < tabs.size()) {
            stackLayout.topControl = tabs.get(activeIndex).container;
            tabs.get(activeIndex).container.moveAbove(null);
        }
        applyDeckVisibility();
        newContentArea.layout(true, true);
    }

    // ── Tab lifecycle ────────────────────────────────────────────────────────

    public void createTab(String title, String initialUrl) {
        if (tabs.size() >= MAX_LIVE_TABS) {
            ui.onTabLimitReached(MAX_LIVE_TABS);
            return;
        }
        // NO_BACKGROUND all the way down: when the window glass material is
        // active and a home tab makes its WKWebView transparent, nothing in
        // the container chain may paint an opaque fill over the glass.
        Composite container = new Composite(contentArea, SWT.NO_BACKGROUND);
        container.setBackgroundMode(SWT.INHERIT_FORCE);
        container.setLayout(new FillLayout());

        RigelBrowser browser;
        try {
            browser = new RigelBrowser(container, SWT.NO_BACKGROUND);
        } catch (Throwable constructionFailure) {
            container.dispose();
            throw constructionFailure;
        }
        installDocumentScripts(browser);

        TabData tab = new TabData(container, browser, title, initialUrl);
        tabs.add(tab);
        int idx = tabs.size() - 1;

        wireListeners(tab);
        wireHomeBridge(tab);
        wireFullscreenBridge(browser);
        wireClipboardBridge(browser);
        wireDownloads(browser);
        wireOpenWindow(browser);
        ui.attachPageContextMenu(browser);
        applyZoom(browser);

        switchTo(idx);
        navigate(initialUrl, tab);
        focusBrowserSoon(tab);
    }

    /**
     * Handles window.open() / target="_blank" / target="_self" popup
     * requests. Without this, SWT's Browser silently drops every such
     * request — the page's JS call "succeeds" but nothing visible happens,
     * which is exactly what plain HTML game-launcher buttons, link lists,
     * and most "open in new tab" UI rely on.
     *
     * SWT's contract: OpenWindowListener.open() must synchronously create
     * a Browser and assign it to event.browser before returning, or the
     * popup's navigation has nowhere to go. We create a full Rigel tab
     * immediately so the new content has a home regardless of whether the
     * page later calls show() — most game launchers and simple links never
     * bother with the full open/show/size choreography real browsers
     * support, they just open() and start navigating right away.
     */
    private void wireOpenWindow(RigelBrowser browser) {
        browser.setOpenHandler(initialUrl -> {
            RigelBrowser popupBrowser = createBlankTab("Popup",
                    initialUrl == null || initialUrl.isBlank() ? "about:blank" : initialUrl);
            int idx = indexOfBrowser(popupBrowser);
            if (idx >= 0) switchTo(idx);
            ui.getShell().forceActive();
            return popupBrowser;
        });
        browser.setCloseHandler(() -> {
            int idx = indexOfBrowser(browser);
            if (idx >= 0) closeTab(idx);
        });
    }

    private RigelBrowser createBlankTab(String title, String url) {
        if (tabs.size() >= MAX_LIVE_TABS) {
            ui.onTabLimitReached(MAX_LIVE_TABS);
            return null;
        }
        Composite container = new Composite(contentArea, SWT.NO_BACKGROUND);
        container.setBackgroundMode(SWT.INHERIT_FORCE);
        container.setLayout(new FillLayout());

        RigelBrowser browser;
        try {
            browser = new RigelBrowser(container, SWT.NO_BACKGROUND);
        } catch (Throwable constructionFailure) {
            container.dispose();
            throw constructionFailure;
        }
        installDocumentScripts(browser);
        TabData tab = new TabData(container, browser, title, url);
        tabs.add(tab);
        int idx = tabs.size() - 1;

        wireListeners(tab);
        wireHomeBridge(tab);
        wireFullscreenBridge(browser);
        wireClipboardBridge(browser);
        wireDownloads(browser);
        wireOpenWindow(browser);
        ui.attachPageContextMenu(browser);
        applyZoom(browser);

        switchTo(idx);
        focusBrowserSoon(tab);
        return browser;
    }

    /** Locates a tab's index by its browser instance — used when a browser
     *  itself (rather than user interaction) drives a close, e.g. via
     *  window.close() inside a popup tab. */
    private int indexOfBrowser(RigelBrowser browser) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).browser == browser) return i;
        }
        return -1;
    }

    // ── Zoom ─────────────────────────────────────────────────────────────

    private int zoomPercent = 100;

    public void setInitialZoom(int percent) {
        this.zoomPercent = clampZoom(percent);
    }

    private int clampZoom(int p) { return Math.max(50, Math.min(250, p)); }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private void applyZoom(RigelBrowser b) {
        if (b == null || b.isDisposed()) return;
        // Prefer WebKit's real page zoom (Safari-style). It applies to layout
        // like a browser zoom — unlike the CSS-zoom hack, it keeps fixed-
        // positioned UI, popover/anchor geometry, and getBoundingClientRect
        // math consistent — and persists across navigations on its own.
        if (b.setPageZoom(zoomPercent / 100.0)) return;
        // Legacy WebKit fallback: CSS zoom. At 100%, remove it entirely so
        // WebKit can keep video in its normal hardware-composited path.
        if (zoomPercent == 100) {
            safeExecute(b, "document.documentElement.style.removeProperty('zoom');");
        } else {
            safeExecute(b, "document.documentElement.style.zoom='" + (zoomPercent / 100.0) + "';");
        }
    }

    public void zoomIn()  { setZoomAll(zoomPercent + 10); }
    public void zoomOut() { setZoomAll(zoomPercent - 10); }
    public void zoomReset() { setZoomAll(100); }

    private void setZoomAll(int percent) {
        zoomPercent = clampZoom(percent);
        for (TabData t : tabs) applyZoom(t.browser);
        ui.onZoomChanged(zoomPercent);
    }

    public int getZoomPercent() { return zoomPercent; }

    public void setGameControllerEnabled(boolean enabled) {
        gameControllerEnabled = enabled;
        if (!enabled) {
            active().ifPresent(b -> {
                safeExecute(b, "if (typeof window.__rigelSetGamepads === 'function') window.__rigelSetGamepads('[]');");
            });
        } else {
            active().ifPresent(b -> {
                injectGamepadShim(b);
                pushGamepads(b);
            });
            // The poll chain parks itself when the bridge is off.
            postGamepadPoll(250);
        }
    }

    public void setNativeClipboardEnabled(boolean enabled) {
        nativeClipboardEnabled = enabled;
        for (TabData tab : tabs) {
            if (tab.browser == null || tab.browser.isDisposed()) continue;
            safeExecute(tab.browser, "window.__rigelNativeClipboardEnabled=" + enabled + ";");
            if (enabled) injectClipboardShim(tab.browser);
        }
    }

    public void setControllerFaceLayout(String layout) {
        gameControllerBridge.setFaceButtonLayout(layout);
        if (gameControllerEnabled) active().ifPresent(this::pushGamepads);
    }

    /** Stop all per-window executors/timer chains exactly once on disposal. */
    public void shutdown() {
        if (shutdown) return;
        shutdown = true;
        focusGeneration++;
        gamepadPollingStarted = false;
        gamepadPollArmed = false;
        tabSleepSweeper.shutdownNow();
    }

    public void setAppActive(boolean active) {
        appActive = active;
        if (!active) focusGeneration++;
        if (active && gameControllerEnabled) {
            // The poll chain parked itself when Rigel was backgrounded;
            // wake it back up with the first sample right away.
            postGamepadPoll(250);
            active().ifPresent(b -> {
                injectGamepadShim(b);
                pushGamepads(b);
            });
        }
    }

    // ── Find in page ───────────────────────────────────────────────────

    /** Highlights the next match using the legacy window.find(); returns nothing
     *  but is best-effort — sufficient for an embedded find bar. */
    public void findInPage(String query, boolean forward) {
        findInPage(query, forward, null);
    }

    public void findInPage(String query, boolean forward,
                           java.util.function.Consumer<Boolean> completion) {
        RigelBrowser b = getActiveBrowser();
        if (b == null || b.isDisposed() || query == null || query.isEmpty()) {
            if (completion != null) completion.accept(false);
            return;
        }
        String q = jsString(query);
        b.evaluateAsync("try{return !!window.find('" + q + "', false, " + (!forward)
                + ", true, false, true, false);}catch(e){return false;}", (value, failure) -> {
            if (completion != null && !b.isDisposed() && b == getActiveBrowser()) {
                boolean found = failure == null && value != null
                        && (Boolean.TRUE.equals(value)
                        || "true".equalsIgnoreCase(value.toString())
                        || "1".equals(value.toString()));
                completion.accept(found);
            }
        });
    }

    public void clearFind() {
        RigelBrowser b = getActiveBrowser();
        if (b == null || b.isDisposed()) return;
        safeExecute(b, "try{var s=window.getSelection();if(s)s.removeAllRanges();}catch(e){}");
    }

    // ── Downloads ────────────────────────────────────────────────────

    /** Delegates download handling to BrowserUI, which owns the download policy
     *  (target dir, prompt, progress) and the Settings instance. */
    private void wireDownloads(RigelBrowser browser) {
        ui.attachDownloadHandler(browser);
    }

    public void closeTab(int idx) {
        if (idx < 0 || idx >= tabs.size()) return;

        rememberClosed(tabs.get(idx));

        if (tabs.size() == 1) {
            // close() is vetoable and previously left the sole tab's WKWebView
            // alive. Dispose the shell; its dispose listener saves the session
            // before child tabs are torn down.
            org.eclipse.swt.widgets.Shell window = ui.getShell();
            if (window != null && !window.isDisposed()) window.dispose();
            tabs.clear();
            activeIndex = -1;
            return;
        }

        TabData dead = tabs.remove(idx);
        ui.onBrowserClosing(dead.browser);
        dead.container.dispose();

        if (activeIndex >= tabs.size()) activeIndex = tabs.size() - 1;
        else if (activeIndex > idx)     activeIndex--;

        switchTo(activeIndex);
    }

    public void closeTab(TabData tab) {
        int idx = tabs.indexOf(tab);
        if (idx >= 0) closeTab(idx);
    }

    private void rememberClosed(TabData tab) {
        if (tab == null || tab.url == null || tab.url.isBlank()) return;
        closedTabs.push(new ClosedTab(tab.title, tab.url));
        while (closedTabs.size() > 20) closedTabs.removeLast();
    }

    public void reopenClosedTab() {
        while (!closedTabs.isEmpty()) {
            ClosedTab closed = closedTabs.pop();
            if (closed.url != null && !closed.url.isBlank()) {
                createTab(closed.title == null || closed.title.isBlank() ? "Restored Tab" : closed.title, closed.url);
                return;
            }
        }
    }

    public void duplicateActiveTab() {
        TabData tab = activeTab();
        duplicateTab(tab);
    }

    public void duplicateTab(TabData tab) {
        if (tab == null) return;
        createTab(tab.title == null || tab.title.isBlank() ? "New Tab" : tab.title,
                tab.url == null || tab.url.isBlank() ? "rigel://home" : tab.url);
    }

    /**
     * Chrome-style tab reorder: moves {@code tab} so that it occupies the
     * slot at {@code targetIndex} in the strip (the insertion point computed
     * against the pre-move order, exactly like dropping onto that slot).
     * The active tab stays the same tab after the move.
     */
    public void moveTab(TabData tab, int targetIndex) {
        if (tab == null || contentArea == null || contentArea.isDisposed()) return;
        int from = tabs.indexOf(tab);
        if (from < 0 || tabs.size() < 2 || targetIndex < 0 || targetIndex > tabs.size()) return;
        int to = targetIndex > from ? targetIndex - 1 : targetIndex;
        if (to == from || to < 0 || to > tabs.size() - 1) return;
        TabData active = activeTab();
        tabs.remove(from);
        tabs.add(to, tab);
        if (active != null) activeIndex = tabs.indexOf(active);
        ui.onTabsChanged();
    }

    /** Moves the active tab to the given slot (0-based; like dropping it in
     *  that strip position). Used by the FFM/native closed-tab parity path
     *  and any programmatic reorder. */
    public void reorderActiveTab(int targetIndex) {
        TabData tab = activeTab();
        if (tab != null) moveTab(tab, targetIndex);
    }

    /** Address-bar completion: best match from browsing history (most
     *  recent first, prefix > substring). Returns the full URL to navigate
     *  to, or null when nothing is a strong match (caller then resolves the
     *  typed input as before). */
    public String completeAddress(String typed) {
        if (typed == null) return null;
        String t = typed.trim();
        if (t.isEmpty()) return null;
        String lowerT = t.toLowerCase(java.util.Locale.ROOT);
        if ("rigel://home".startsWith(lowerT)) return "rigel://home";
        String best = null;
        int bestScore = 0;
        for (config.History.Entry e : ui.historyEntries()) {
            String url = e.url == null ? "" : e.url.trim();
            if (url.isEmpty()) continue;
            String lowerU = url.toLowerCase(java.util.Locale.ROOT);
            int score;
            if (url.startsWith(t) || lowerU.startsWith(lowerT)) score = 100;
            else if (lowerU.contains(lowerT)) score = 50;
            else continue;
            if (score > bestScore) { bestScore = score; best = url; }
        }
        return bestScore > 0 ? best : null;
    }

    /**
     * Address-bar input → navigation URL, or null when nothing reasonable.
     * Blank input opens the home page; explicit schemes navigate as-is;
     * web-host lookalikes get an https scheme; anything else is searched via
     * the configured search engine (Settings → Search tab).
     */
    public String resolveAddressInput(String input) {
        String raw = input == null ? "" : input.trim();
        if (raw.isEmpty()) return "rigel://home";
        if ("rigel://home".equalsIgnoreCase(raw)) return "rigel://home";
        if (raw.matches("^[a-z][a-z0-9+.-]*://.+$")) return raw;
        if (looksLikeWebHost(raw)) return preferredSchemeForHost(raw) + raw;
        return config.SearchEngines.searchUrl(
                config.SearchEngines.currentEngineOrGoogle(ui.settings()), raw);
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

    /**
     * Removes a tab without recording it as "closed", for tear-off into a
     * new window (Chrome-style tab dragging). Disposes this window's copy;
     * the new window restores the URL. Returns the URL to hand over, or null
     * if the tab could not be detached.
     */
    public String detachTab(TabData tab) {
        if (tab == null || !tabs.contains(tab)) return null;
        String url = tab.url == null || tab.url.isBlank() ? "rigel://home" : tab.url;
        String title = tab.title;
        int idx = tabs.indexOf(tab);
        if (tabs.size() == 1) {
            // Only tab in the window: opening the new window closes this one.
            ui.getShell().close();
            return url;
        }
        tabs.remove(idx);
        ui.onBrowserClosing(tab.browser);
        tab.container.dispose();
        if (activeIndex >= tabs.size()) activeIndex = tabs.size() - 1;
        else if (activeIndex > idx) activeIndex--;
        switchTo(activeIndex);
        return url;
    }

    public void closeOtherTabs(TabData keep) {
        if (keep == null || !tabs.contains(keep) || tabs.size() < 2) return;
        for (int i = tabs.size() - 1; i >= 0; i--) {
            TabData tab = tabs.get(i);
            if (tab == keep) continue;
            rememberClosed(tab);
            ui.onBrowserClosing(tab.browser);
            tabs.remove(i);
            tab.container.dispose();
        }
        activeIndex = tabs.indexOf(keep);
        switchTo(activeIndex);
    }

    public void switchTo(int idx) {
        if (idx < 0 || idx >= tabs.size()) return;
        TabData tab = tabs.get(idx);
        TabData previous = activeIndex >= 0 && activeIndex < tabs.size()
                && tabs.get(activeIndex) != tab ? tabs.get(activeIndex) : null;
        ui.onTabWillActivate(tab.browser);
        activeIndex = idx;
        if ("rigel://home".equals(tab.url)
                && tab.renderedHomeVersion != homeRenderVersion
                && !tab.browser.isDisposed()) {
            beginLoading(tab);
            renderHome(tab);
        }
        // Background tabs stay alive at full size behind the active one, so
        // WebKit never suspends or reclaims them and their media keeps
        // playing (Safari/Chrome behaviour). Throttle their animation timers
        // so they don't burn battery while hidden behind the active tab.
        if (previous != null && previous.browser != null && !previous.browser.isDisposed()) {
            setBackgroundThrottle(previous.browser, true);
        }
        // Only the active tab's container is visible. A hidden container
        // sets NSView.hidden on the whole subtree, which makes WebKit
        // treat the page as hidden: compositing stops, rAF halts, timers
        // throttle, and WebKit can reclaim decoded frames/tiles. Audio
        // keeps playing (Safari/Chrome background-tab behavior), so the
        // old "keep everything visible so media keeps playing" rationale
        // is preserved for audible pages without burning GPU+RAM per tab.
        stackLayout.topControl = tab.container;
        tab.container.moveAbove(null);
        applyDeckVisibility();
        contentArea.layout();
        if (previous != null) previous.backgroundedAtMillis = System.currentTimeMillis();
        tab.backgroundedAtMillis = 0;
        // A sleeping tab's page was discarded to reclaim RAM; click = reload
        // and restore scroll. Must precede the throttle/JS-touching steps
        // below so they run against the woken document.
        wakeIfSleeping(tab);
        if (tab.browser != null && !tab.browser.isDisposed()) {
            setBackgroundThrottle(tab.browser, false);
        }
        tab.browser.resumeAfterContentProcessTermination();
        ui.onActiveTabChanged();
        ui.onTabsChanged();
        ui.onActiveTabLoadState(tab.loading, tab.progress);
        syncChrome(tab);
        focusBrowserSoon(tab);
    }

    // ── Tab sleep (discard idle background tabs to reclaim RAM) ─────────────

    /**
     * Audit script result: "scrollX,scrollY,playing,fullscreen". Runs in the
     * candidate tab just before discard so the decision re-validates the
     * page's live state (a video that started playing after the sweep's
     * coarse filter must veto the sleep).
     * NOTE: evaluateAsync wraps sources in an outer function, so this uses
     * top-level `return` — an explicit IIFE here would swallow the result.
     */
    private static final String TAB_SLEEP_AUDIT_JS =
              "try {"
            + "  var playing=false, m=document.querySelectorAll('video,audio');"
            + "  for (var i=0;i<m.length;i++){var e=m[i];"
            + "    if(!e.paused && !e.ended && e.readyState>1){playing=true;break}}"
            + "  return (window.scrollX|0)+','+(window.scrollY|0)+','"
            + "       + (playing?'1':'0')+','+(document.fullscreenElement?'1':'0');"
            + "} catch (e) { return '0,0,1,0'; }";

    private void sweepTabSleepStep() {
        if (shutdown || TAB_SLEEP_AFTER_MILLIS <= 0) return;
        Display display = (contentArea == null || contentArea.isDisposed())
                ? null : contentArea.getDisplay();
        if (display == null || display.isDisposed()) {
            // Window is gone; the daemon sweeper must not outlive it.
            shutdown = true;
            tabSleepSweeper.shutdownNow();
            return;
        }
        display.asyncExec(() -> {
            if (shutdown) return;
            long now = System.currentTimeMillis();
            boolean auditedAny = false;
            for (TabData tab : new ArrayList<>(tabs)) {
                if (isActive(tab) || tab.sleeping || tab.loading) continue;
                if (tab.backgroundedAtMillis <= 0) continue;
                if (now - tab.backgroundedAtMillis < TAB_SLEEP_AFTER_MILLIS) continue;
                if (tab.browser == null || tab.browser.isDisposed()) continue;
                if (tab.url == null || tab.url.isBlank()
                        || "rigel://home".equals(tab.url)
                        || tab.url.startsWith("about:")) continue;
                // Never swap a live download's page out from under it.
                if (ui.hasActiveDownload(tab.browser)) continue;
                auditAndSleep(tab);
                auditedAny = true;
            }
            if (shutdown || tabSleepSweeper.isShutdown()) return;
            tabSleepSweeper.schedule(this::sweepTabSleepStep,
                    auditedAny ? 60 : 300, java.util.concurrent.TimeUnit.SECONDS);
        });
    }

    private void auditAndSleep(TabData tab) {
        tab.browser.evaluateAsync(TAB_SLEEP_AUDIT_JS, (result, error) -> {
            if (error != null || result == null || tab.sleeping) return;
            if (!tabs.contains(tab) || isActive(tab) || tab.loading) return;
            if (tab.pendingScrollRestore) return;   // a wake is mid-flight
            // Re-check idleness: the sweep may have been queued before the
            // tab was briefly activated, and a just-backgrounded tab deserves
            // its full idle window again.
            if (tab.backgroundedAtMillis <= 0
                    || System.currentTimeMillis() - tab.backgroundedAtMillis
                            < TAB_SLEEP_AFTER_MILLIS) return;
            if (tab.browser.isDisposed()) return;
            String[] parts = result.toString().split(",");
            if (parts.length < 4) return;
            // Playing media / fullscreen wins over the idle heuristic.
            if ("1".equals(parts[2]) || "1".equals(parts[3])) return;
            int sx, sy;
            try {
                sx = Integer.parseInt(parts[0].trim());
                sy = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException bad) {
                return;
            }
            commitSleep(tab, sx, sy);
        });
    }

    /**
     * Unloads the page to about:blank. This is the real discard: the JS heap,
     * decoded media, canvases, and layer caches for the page are released by
     * WebKit (the WebContent process may stay resident but collapses to idle
     * size). tab.url is intentionally NOT rewritten — the location listener
     * already ignores about:blank, and the sleeping flags keep the strip
     * showing the cached title; the URL bar reflects the restored address on
     * the next activation.
     */
    private void commitSleep(TabData tab, int scrollX, int scrollY) {
        tab.sleepUrl = tab.url;
        tab.sleepTitle = tab.title;
        tab.sleepScrollX = scrollX;
        tab.sleepScrollY = scrollY;
        tab.sleeping = true;
        // Replace kills history entries cost-free here because the back/forward
        // list is a casualty of any discard (same tradeoff as Chrome).
        safeExecute(tab.browser, "try{location.replace('about:blank')}catch(e){}");
        ui.onTabsChanged();
    }

    /** Wake a sleeping tab: reload the saved URL and restore the scroll. */
    private void wakeIfSleeping(TabData tab) {
        if (!tab.sleeping) return;
        tab.sleeping = false;
        String target = tab.sleepUrl;
        if (target == null || target.isBlank()) return;
        tab.pendingScrollRestore = true;
        // Inject shims into the fresh document, then navigate.
        injectPageEnhancements(tab.browser);
        tab.browser.setUrl(target);
    }

    /** Called from the progress listener when a restored page completes. */
    private void maybeRestoreScroll(TabData tab) {
        if (!tab.pendingScrollRestore || tab.browser == null || tab.browser.isDisposed()) return;
        tab.pendingScrollRestore = false;
        if (tab.sleepScrollX <= 0 && tab.sleepScrollY <= 0) return;
        safeExecute(tab.browser, "try{window.scrollTo(" + tab.sleepScrollX + ","
                + tab.sleepScrollY + ")}catch(e){}");
    }

    private void setBackgroundThrottle(RigelBrowser browser, boolean throttled) {

        safeExecute(browser, "if(window.__rigelSetBackgroundTab)window.__rigelSetBackgroundTab("
                + (throttled ? "true" : "false") + ");");
    }

    /**
     * Hides every container except the active tab's. StackLayout itself
     * only z-orders; without this, every slot in the deck stayed visible,
     * so WebKit kept all N tabs painting, compositing, and animating at
     * full rate — the main source of battery/RAM pressure in the deck.
     */
    private void applyDeckVisibility() {
        for (int i = 0; i < tabs.size(); i++) {
            TabData t = tabs.get(i);
            if (t.container == null || t.container.isDisposed()) continue;
            boolean active = i == activeIndex;
            if (t.container.isVisible() != active) t.container.setVisible(active);
        }
    }

    public void switchTo(TabData tab) {
        int idx = tabs.indexOf(tab);
        if (idx >= 0) switchTo(idx);
    }

    public void cycleTabs(int delta) {
        if (tabs.size() < 2 || delta == 0) return;
        int next = Math.floorMod(activeIndex + delta, tabs.size());
        switchTo(next);
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    public void back()    { active().ifPresent(RigelBrowser::back); }
    public void forward() { active().ifPresent(RigelBrowser::forward); }
    public void stopLoading() {
        active().ifPresent(b -> {
            b.stop();
            TabData tab = activeTab();
            if (tab != null) {
                tab.loading = false;
                tab.progress = 0;
                ui.onActiveTabLoadState(false, 0);
            }
        });
    }
    public void reload()  {
        active().ifPresent(b -> {
            TabData t = activeTab();
            if (t != null) beginLoading(t);
            if (t != null && "rigel://home".equals(t.url)) {
                t.title = "New Tab";
                renderHome(t);
                syncChrome(t);
                focusBrowserSoon(t);
            }
            else {
                if (t != null) syncChrome(t);
                b.reload();
            }
        });
    }

    /** Reloads every live tab in a short stagger, avoiding a simultaneous network burst. */
    public void reloadAll() {
        if (contentArea == null || contentArea.isDisposed()) return;
        List<TabData> snapshot = List.copyOf(tabs);
        int delay = 0;
        for (TabData tab : snapshot) {
            int tabDelay = tab == activeTab() ? 0 : (delay += 75);
            contentArea.getDisplay().timerExec(tabDelay, () -> {
                if (!tabs.contains(tab) || tab.browser == null || tab.browser.isDisposed()) return;
                beginLoading(tab);
                if ("rigel://home".equals(tab.url)) {
                    tab.title = "New Tab";
                    renderHome(tab);
                    if (isActive(tab)) {
                        syncChrome(tab);
                        focusBrowserSoon(tab);
                    }
                } else {
                    if (isActive(tab)) syncChrome(tab);
                    tab.browser.reload();
                }
            });
        }
    }

    public void reloadIgnoringCache() {
        active().ifPresent(b -> {
            TabData t = activeTab();
            if (t != null) beginLoading(t);
            if (t != null && "rigel://home".equals(t.url)) {
                t.title = "New Tab";
                renderHome(t);
                syncChrome(t);
                focusBrowserSoon(t);
            } else {
                if (t != null) syncChrome(t);
                b.reloadIgnoringCache();
            }
        });
    }

    public void printActive() {
        active().ifPresent(b -> safeExecute(b, "try{window.print();}catch(e){}"));
    }

    public void focusActiveBrowser() {
        TabData tab = activeTab();
        if (tab == null || tab.browser == null || tab.browser.isDisposed()) return;
        int generation = ++focusGeneration;
        tab.browser.getDisplay().asyncExec(() -> {
            if (generation != focusGeneration || !isActive(tab)
                    || tab.browser.isDisposed() || !appActive) return;
            tab.browser.setFocus();
            if ("rigel://home".equals(tab.url)) safeExecute(tab.browser, HOME_FOCUS_JS);
        });
    }

    public void navigate(String url) {
        TabData tab = activeTab();
        if (tab == null || tab.browser == null || tab.browser.isDisposed()) return;
        tab.url = url;
        if ("rigel://home".equals(url)) tab.title = "New Tab";
        beginLoading(tab);
        syncChrome(tab);
        navigate(url, tab);
        if ("rigel://home".equals(url)) focusBrowserSoon(tab);
    }

    /** Central navigate — handles rigel://home specially. */
    private void navigate(String url, TabData tab) {
        if (tab == null || tab.browser == null || tab.browser.isDisposed()) return;
        if (!tab.loading) beginLoading(tab);
        tab.navigationGeneration++;
        if (!"rigel://home".equals(url)) {
            // Leaving the home page: restore the opaque page background real
            // sites expect. The window-level glass material stays installed;
            // an opaque WKWebView simply covers it.
            tab.browser.setTransparent(false);
        }
        boolean accepted = "rigel://home".equals(url)
                ? renderHome(tab)
                : tab.browser.setUrl(url);
        if (!accepted) {
            tab.loading = false;
            tab.progress = 0;
            if (isActive(tab)) ui.onActiveTabLoadState(false, 0);
        }
    }

    private void beginLoading(TabData tab) {
        if (tab == null) return;
        tab.loading = true;
        tab.progress = 4;
        if (isActive(tab)) ui.onActiveTabLoadState(true, tab.progress);
    }

    private boolean renderHome(TabData tab) {
        if (tab == null || tab.browser == null || tab.browser.isDisposed()) return false;
        // Home keeps its own designed (opaque) background. Glass lives in the
        // window chrome — the strips that used to be flat grey — not in page
        // space, so the webview is explicitly back to opaque here.
        tab.browser.setTransparent(false);
        boolean accepted = tab.browser.setText(buildHomeHtml(), true);
        if (accepted) tab.renderedHomeVersion = homeRenderVersion;
        else {
            tab.loading = false;
            tab.progress = 0;
            if (isActive(tab)) ui.onActiveTabLoadState(false, 0);
        }
        return accepted;
    }

    private void syncChrome(TabData tab) {
        if (tab == null) return;
        if (isActive(tab)) ui.onUrlChanged(tab.url);
        ui.onTitleChanged(tab.title == null || tab.title.isBlank() ? "New Tab" : tab.title);
    }

    // ── Listeners ────────────────────────────────────────────────────────────

    private void wireListeners(TabData tab) {
        RigelBrowser b = tab.browser;
        // webViewDidClose: (FFM/native parity) — close the owning tab even if
        // it wasn't closed through the UI (e.g. native callback for popups).
        b.addNativeClosedListener(() -> {
            int idx = indexOfBrowser(b);
            if (idx >= 0 && !b.isDisposed()) closeTab(idx);
        });

        b.addLocationListener(new RigelBrowser.LocationListener() {
            @Override
            public boolean changing(String location) {
                // Native WebKit is synchronously waiting for this policy
                // answer. Defer fullscreen/AppKit mutations until the callback
                // unwinds to avoid re-entering WebKit from its decision handler.
                b.getDisplay().asyncExec(() -> {
                    if (!b.isDisposed() && tabs.contains(tab)) ui.onBrowserNavigating(b);
                });
                long generation = ++tab.navigationGeneration;
                // Intercept rigel://home before WebKit tries to fetch it
                if ("rigel://home".equals(location)) {
                    b.getDisplay().asyncExec(() -> {
                        if (b.isDisposed() || !tabs.contains(tab)
                                || generation != tab.navigationGeneration) return;
                        tab.url   = "rigel://home";
                        tab.title = "New Tab";
                        beginLoading(tab);
                        renderHome(tab);
                        if (isActive(tab)) ui.onUrlChanged("rigel://home");
                        ui.onTitleChanged("New Tab");
                        focusBrowserSoon(tab);
                    });
                    return false;
                }
                return true;
            }

            @Override
            public void changed(String location) {
                if ("rigel://home".equals(location) ||
                    "about:blank".equals(location)) return;

                // In-page navigation onto a real site: make sure any
                // transparency left over from rigel://home is dropped so the
                // page renders its own opaque background.
                if (!b.isDisposed()) b.setTransparent(false);

                tab.url = location;
                try {
                    URI uri = new URI(location);
                    String h = uri.getHost();
                    if (h != null && !h.isEmpty()) {
                        tab.title = h.replaceFirst("^www\\.", "");
                    }
                } catch (Exception ignored) {}

                if (isActive(tab)) ui.onUrlChanged(location);
                ui.onTitleChanged(tab.title);
                // A wake reload would otherwise double-record the visit.
                if (!tab.pendingScrollRestore) ui.recordHistory(tab.title, location);

                injectPageEnhancements(b);
            }
        });

        b.addTitleListener(title -> {
            // While sleeping, the live document is about:blank — keep the
            // cached page title so the strip keeps identifying the tab.
            if (tab.sleeping) return;
            tab.title = title == null || title.isBlank()
                    ? fallbackTitle(tab.url) : title;
            ui.onTitleChanged(tab.title);
        });

        // Belt-and-suspenders: also inject on completed for players that
        // initialise lazily after the main document finishes loading.
        // The __rigelFS stamp makes any repeated execution a no-op.
        b.addProgressListener(new RigelBrowser.ProgressListener() {
            @Override
            public void changed(int current, int total) {
                if (tab.sleeping) return;   // discard navigation owns the strip
                boolean stateChanged = !tab.loading;
                tab.loading = true;
                tab.progress = total <= 0 ? 12
                        : Math.max(4, Math.min(96, current * 100 / total));
                ui.redrawTabControl(tab, stateChanged);
                if (isActive(tab)) ui.onLoadProgress(current, total, false);
            }

            @Override
            public void completed() {
                if (tab.sleeping) return;
                boolean stateChanged = tab.loading;
                tab.loading = false;
                tab.progress = 0;
                ui.redrawTabControl(tab, stateChanged);
                if (isActive(tab)) ui.onLoadProgress(100, 100, true);
                applyZoom(b);
                injectPageEnhancements(b);
                maybeRestoreScroll(tab);
            }
        });
    }

    // ── Fullscreen bridge ─────────────────────────────────────────────────────

    private void wireFullscreenBridge(RigelBrowser browser) {
        browser.addScriptFunction("javaEnterFullscreen", args -> {
            browser.getDisplay().asyncExec(() -> {
                if (browser.isDisposed()) return;
                if (!ui.enterFullscreen(browser)) {
                    safeExecute(browser,
                            "if(typeof window.__rigelExitFSSilent==='function')window.__rigelExitFSSilent();");
                }
            });
            return null;
        });
        browser.addScriptFunction("javaExitFullscreen", args -> {
            boolean protectOnly = args != null && args.length > 0
                    && "__rigelProtectNativeFullscreen__".equals(String.valueOf(args[0]));
            browser.getDisplay().asyncExec(() -> {
                if (browser.isDisposed()) return;
                if (protectOnly) ui.prepareNativeFullscreenCapture();
                else ui.exitFullscreenFrom(browser);
            });
            return null;
        });

    }

    private void wireHomeBridge(TabData tab) {
        RigelBrowser browser = tab.browser;
        browser.addScriptFunction("rigelHomeNavigate", args -> {
            if (!"rigel://home".equals(tab.url)) return Boolean.FALSE;
            if (args == null || args.length == 0 || args[0] == null) return Boolean.FALSE;
            String resolved = ui.resolveAddressInput(args[0].toString());
            if (resolved == null) return Boolean.FALSE;
            browser.getDisplay().asyncExec(() -> {
                if (browser.isDisposed() || !tabs.contains(tab)) return;
                tab.url = resolved;
                if ("rigel://home".equals(resolved)) tab.title = "New Tab";
                if (isActive(tab)) syncChrome(tab);
                beginLoading(tab);
                navigate(resolved, tab);
            });
            return Boolean.TRUE;
        });
    }

    private void wireClipboardBridge(RigelBrowser browser) {
        browser.addScriptFunction("rigelClipboardWrite", args -> {
            if (!nativeClipboardEnabled || args == null || args.length == 0 || args[0] == null) return Boolean.FALSE;
            String text = args[0].toString();
            Clipboard clipboard = new Clipboard(browser.getDisplay());
            try {
                clipboard.setContents(new Object[]{ text }, new Transfer[]{ TextTransfer.getInstance() });
                return Boolean.TRUE;
            } catch (Exception ex) {
                return Boolean.FALSE;
            } finally {
                clipboard.dispose();
            }
        });
    }


    private void injectFullscreenJs(RigelBrowser browser) {
        if (browser.isDisposed()) return;
        if ("wkwebview".equals(browser.getBackendName())) {
            safeExecute(browser, "window.__rigelPreferNativeFullscreen=true;");
        }
        safeExecute(browser, FULLSCREEN_JS);
    }

    private void injectPageEnhancements(RigelBrowser browser) {
        if (browser.isDisposed()) return;
        if ("1".equals(System.getenv("RIGEL_DISABLE_PAGE_SHIMS"))) return;
        injectCapabilityProbe(browser);
        injectFullscreenJs(browser);
        injectKeyboardFocusShim(browser);
        if (nativeClipboardEnabled) injectClipboardShim(browser);
        if (gameControllerEnabled) {
            injectGamepadShim(browser);
            pushGamepads(browser);
        }
    }

    private static final String HOME_FOCUS_JS = String.join("\n",
        "(function () {",
        "    var q = document.getElementById('q');",
        "    if (!q || !document.hasFocus()) return;",
        "    var a = document.activeElement;",
        "    if (a && a !== document.body && a !== document.documentElement && a !== q) return;",
        "    q.style.webkitTextFillColor = getComputedStyle(q).color;",
        "    try { q.focus({preventScroll: true}); } catch (e) { q.focus(); }",
        "})();"
    );

    private static final String KEY_FOCUS_JS = """
(function () {
    if (window.__rigelKeyFocusShim) return;
    window.__rigelKeyFocusShim = true;

    var INTERACTIVE_SELECTOR =
        'input, textarea, select, button, a[href], label, summary, details, '
        + '[contenteditable], [role="button"], [role="link"], [role="checkbox"], [role="radio"], '
        + '[role="switch"], [role="tab"], [role="menuitem"], [role="menu"], [role="dialog"], '
        + '[role="listbox"], [role="option"], [role="combobox"], [role="textbox"], [tabindex]';

    var EXPLICIT_CONTROL_SELECTOR =
        'input, textarea, select, button, a[href], label, summary, details, [contenteditable], '
        + '[role="button"], [role="link"], [role="checkbox"], [role="radio"], [role="switch"], '
        + '[role="tab"], [role="menuitem"], [role="menu"], [role="dialog"], [role="listbox"], '
        + '[role="option"], [role="combobox"], [role="textbox"]';

    var SAFE_SURFACE_SELECTOR = 'canvas, [data-rigel-keyboard-focus]';
    var COMPAT_SURFACE_SELECTOR = SAFE_SURFACE_SELECTOR
        + ', video, audio, [role="application"], [data-game], .html5-video-player, .video-player, .watch-video'
        + ', .NFPlayer, .video-js, .jwplayer, .plyr, .shaka-video-container'
        + ', .clappr-container, .mejs__container, .player';

    var MODE_MESSAGE = '__rigel_web_input_mode_v1__';

    function normalizeMode(value) {
        value = String(value || '').trim().toLowerCase();
        return value === 'compatibility' || value === 'controls' ? value : 'automatic';
    }

    function sendMode(target) {
        try {
            target.postMessage({ rigelType: MODE_MESSAGE, mode: window.__rigelWebInputMode }, '*');
        } catch (e) {}
    }

    function forwardMode() {
        for (var i = 0; i < window.frames.length; i++) sendMode(window.frames[i]);
    }

    window.__rigelSetWebInputMode = function (value) {
        window.__rigelWebInputMode = normalizeMode(value);
        forwardMode();
        return window.__rigelWebInputMode;
    };
    window.__rigelSetWebInputMode(window.__rigelWebInputMode || 'automatic');

    window.addEventListener('message', function (event) {
        var data = event && event.data;
        if (!data || data.rigelType !== MODE_MESSAGE) return;
        if (data.request === true) {
            for (var i = 0; i < window.frames.length; i++) {
                if (window.frames[i] === event.source) { sendMode(event.source); break; }
            }
            return;
        }
        if (window === window.top || event.source !== window.parent) return;
        window.__rigelWebInputMode = normalizeMode(data.mode);
        forwardMode();
    }, false);
    if (window !== window.top) {
        try { window.parent.postMessage({ rigelType: MODE_MESSAGE, request: true }, '*'); }
        catch (e) {}
    }

    function effectiveMode() {
        var mode = normalizeMode(window.__rigelWebInputMode);
        if (mode !== 'automatic') return mode;
        var host = String(location.hostname || '').toLowerCase();
        // Anime Nexus's custom player relies on page focus after background
        // clicks. Keep that compatibility behavior without applying it to
        // unrelated sites such as chess apps with custom dialog controls.
        if (host === 'anime.nexus' || host.endsWith('.anime.nexus')) return 'compatibility';
        return 'controls';
    }

    function eventPath(event) {
        try {
            var composed = event && typeof event.composedPath === 'function' ? event.composedPath() : null;
            if (composed && composed.length) return composed;
        } catch (e) {}

        var path = [];
        var node = event && event.target;
        while (node) {
            path.push(node);
            node = node.parentNode || (node.host || null);
        }
        path.push(window);
        return path;
    }

    function nodeMatches(node, selector) {
        try {
            return !!(node && node.nodeType === 1 && typeof node.matches === 'function'
                    && node.matches(selector));
        } catch (e) {
            return false;
        }
    }

    function interactiveNode(node, mode) {
        if (!node || node.nodeType !== 1) return false;
        if (node.isContentEditable) return true;
        try {
            if (nodeMatches(node, EXPLICIT_CONTROL_SELECTOR)) return true;
            // Player roots commonly carry tabindex, role=application, onclick,
            // or cursor:pointer. In Compatibility mode those attributes belong
            // to the keyboard surface, not to a child control. Explicit overlay
            // buttons are caught above before their player ancestor is reached.
            if (mode === 'compatibility' && nodeMatches(node, COMPAT_SURFACE_SELECTOR)) return false;
            if (typeof node.matches === 'function' && node.matches(INTERACTIVE_SELECTOR)) return true;
            if (typeof node.onclick === 'function' || node.hasAttribute('onclick')) return true;
            var role = String(node.getAttribute('role') || '').toLowerCase();
            if (/^(button|link|checkbox|radio|switch|tab|menuitem|option)$/.test(role)) return true;
            var style = window.getComputedStyle ? window.getComputedStyle(node) : null;
            return !!(style && style.cursor === 'pointer');
        } catch (e) {
            return false;
        }
    }

    function interactiveEvent(event) {
        var mode = effectiveMode();
        var path = eventPath(event);
        for (var i = 0; i < path.length; i++) {
            if (interactiveNode(path[i], mode)) return true;
        }
        return false;
    }

    function interactiveElement(el, mode) {
        if (!el) return false;
        if (interactiveNode(el, mode)) return true;
        if (mode === 'compatibility' && nodeMatches(el, COMPAT_SURFACE_SELECTOR)) return false;
        try {
            return typeof el.closest === 'function' && !!el.closest(INTERACTIVE_SELECTOR);
        } catch (e) {
            return false;
        }
    }

    function pathMatches(event, selector) {
        var path = eventPath(event);
        for (var i = 0; i < path.length; i++) {
            var node = path[i];
            if (!node || node.nodeType !== 1) continue;
            try {
                if (typeof node.matches === 'function' && node.matches(selector)) return true;
            } catch (e) {}
        }
        return false;
    }

    function keyboardSurface(event, mode) {
        return pathMatches(event, mode === 'compatibility'
                ? COMPAT_SURFACE_SELECTOR : SAFE_SURFACE_SELECTOR);
    }

    function focusDocumentBody() {
        if (!document.body) return;
        var mode = effectiveMode();
        var active = document.activeElement;
        if (interactiveElement(active, mode)) return;
        if (active && active !== document.body && active !== document.documentElement
                && !(mode === 'compatibility' && nodeMatches(active, COMPAT_SURFACE_SELECTOR))) return;
        try {
            if (!document.body.hasAttribute('tabindex')) document.body.setAttribute('tabindex', '-1');
            document.body.focus({ preventScroll: true });
        } catch (e) {}
    }

    var focusEvent = (typeof PointerEvent === 'function') ? 'pointerdown' : 'mousedown';
    document.addEventListener(focusEvent, function (event) {
        var mode = effectiveMode();
        if (event.defaultPrevented || (typeof event.button === 'number' && event.button !== 0) ||
                interactiveEvent(event) || !keyboardSurface(event, mode)) return;

        var activeBefore = document.activeElement;
        setTimeout(function () {
            // Capture listeners run before the page's handlers. Re-check after
            // dispatch so preventDefault(), focus changes, dialogs and portals win.
            if (event.defaultPrevented) return;
            var activeNow = document.activeElement;
            if (activeNow && activeNow !== activeBefore &&
                    activeNow !== document.body && activeNow !== document.documentElement) return;
            focusDocumentBody();
        }, 0);
    }, true);
})();
""";

    static boolean installKeyboardFocusDocumentScript(RigelBrowser browser) {
        return browser != null && !browser.isDisposed()
                && browser.addDocumentStartScript(KEY_FOCUS_JS);
    }

    /**
     * Document-start scripts installed for every tab: the keyboard focus
     * shim, the Chrome-style background-tab throttle, and the CSS anchor
     * positioning polyfill for older system WebKits (see ANCHOR_POLYFILL_JS).
     */
    private static void installDocumentScripts(RigelBrowser browser) {
        // Diagnostic kill switch for aitest.sh A/B bisection: skip every
        // page-level shim so failures can be attributed to one script.
        if ("1".equals(System.getenv("RIGEL_DISABLE_PAGE_SHIMS"))) return;
        installKeyboardFocusDocumentScript(browser);
        browser.addDocumentStartScript(BACKGROUND_THROTTLE_JS);
        browser.addDocumentStartScript(ANCHOR_POLYFILL_JS);
        // Also run the throttle shim on the document that is already loaded
        // (the home page renders immediately after creation).
        if (!browser.isDisposed()) {
            try {
                browser.execute(BACKGROUND_THROTTLE_JS);
            } catch (org.eclipse.swt.SWTException ignored) {
            }
        }
    }

    /**
     * Old system WebKit builds (e.g. M3 MacBook Air machines that have not
     * updated to the macOS release that shipped CSS Anchor Positioning, while
     * newer hardware like an M4 Mac mini already runs it) cannot anchor a
     * popover to its trigger button or use position-try fallbacks — the
     * anchor()/position-area/position-try rules are simply dropped, so player
     * settings menus land off-screen or never appear. Chromium has shipped
     * anchor positioning for years, which is why the same sites work in
     * Chrome. Polyfilling the feature brings those WebKits to parity; the
     * polyfill itself only activates when native support is missing.
     */
    private static final String ANCHOR_POLYFILL_JS = loadResourceScript("anchor-polyfill.js");

    private static String loadResourceScript(String name) {
        try (InputStream input = TabManager.class.getResourceAsStream("/" + name)) {
            if (input == null) {
                System.err.println("[Rigel] Missing bundled script " + name);
                return "";
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            System.err.println("[Rigel] Could not load bundled script " + name + ": " + failure);
            return "";
        }
    }

    /**
     * Chrome-style background tab throttling. Rigel keeps every tab's
     * WKWebView mounted and visible to WebKit (z-ordered behind the active
     * tab) so background media keeps playing and WebKit does not terminate
     * or suspend those content processes. The cost of that design is that
     * background pages keep running their animation timers at full speed.
     * This shim pauses requestAnimationFrame and clamps setTimeout/
     * setInterval to >= 1 second while the tab is backgrounded — the same
     * throttling Chrome applies to background tabs — without touching media
     * playback, WebSockets, or anything else. It is toggled per tab on
     * switchTo().
     */
    private static final String BACKGROUND_THROTTLE_JS = """
(function () {
    if (window.__rigelThrottleShim) return;
    window.__rigelThrottleShim = true;

    var backgrounded = false;
    var nativeRAF = window.requestAnimationFrame ? window.requestAnimationFrame.bind(window) : null;
    var nativeCancelRAF = window.cancelAnimationFrame ? window.cancelAnimationFrame.bind(window) : null;
    var nativeSetTimeout = window.setTimeout.bind(window);
    var nativeSetInterval = window.setInterval.bind(window);
    var held = new Map();
    var nextId = 1;

    function clampDelay(delay) {
        if (!backgrounded) return delay;
        delay = Number(delay) || 0;
        return delay < 1000 ? 1000 : delay;
    }

    window.requestAnimationFrame = function (callback) {
        if (!backgrounded && nativeRAF) return nativeRAF(callback);
        var id = nextId++;
        held.set(id, callback);
        return id;
    };
    window.cancelAnimationFrame = function (id) {
        if (held.has(id)) { held.delete(id); return; }
        if (nativeCancelRAF) nativeCancelRAF(id);
    };
    // HTML5 allows extra arguments (setTimeout(fn, ms, ...args)); dropping
    // them breaks schedulers that pass state through the timer (chess.com's
    // board engine, Cloudflare Turnstile, Sentry all do this and crashed).
    window.setTimeout = function (callback, delay) {
        var args = Array.prototype.slice.call(arguments, 2);
        return nativeSetTimeout.apply(null, [callback, clampDelay(delay)].concat(args));
    };
    window.setInterval = function (callback, delay) {
        var args = Array.prototype.slice.call(arguments, 2);
        return nativeSetInterval.apply(null, [callback, clampDelay(delay)].concat(args));
    };

    window.__rigelSetBackgroundTab = function (on) {
        on = !!on;
        if (on === backgrounded) return backgrounded;
        backgrounded = on;
        try { document[on ? 'setAttribute' : 'removeAttribute']('data-rigel-background-tab', ''); } catch (e) {}
        if (!on && nativeRAF) {
            var pending = Array.from(held.values());
            held.clear();
            for (var i = 0; i < pending.length; i++) {
                (function (cb) {
                    nativeRAF(function (ts) { try { cb(ts); } catch (e) {} });
                })(pending[i]);
            }
        }
        return backgrounded;
    };
})();
""";

    private static final String CLIPBOARD_SHIM_JS = """
(function () {
    window.__rigelNativeClipboardEnabled = true;
    if (window.__rigelClipboardShim) return;
    window.__rigelClipboardShim = true;

    var nativeClipboard = navigator.clipboard;
    function nativeWrite(text) {
        try {
            return window.__rigelNativeClipboardEnabled !== false &&
                   typeof window.rigelClipboardWrite === 'function' &&
                   !!window.rigelClipboardWrite(String(text == null ? '' : text));
        } catch (e) {
            return false;
        }
    }

    var shim = {};
    if (nativeClipboard) {
        try {
            Object.getOwnPropertyNames(Object.getPrototypeOf(nativeClipboard)).forEach(function (key) {
                if (key !== 'constructor' && key !== 'writeText' && typeof nativeClipboard[key] === 'function') {
                    shim[key] = nativeClipboard[key].bind(nativeClipboard);
                }
            });
        } catch (e) {}
    }
    shim.writeText = function (text) {
        return new Promise(function (resolve, reject) {
            if (nativeWrite(text)) { resolve(); return; }
            if (nativeClipboard && typeof nativeClipboard.writeText === 'function') {
                try { nativeClipboard.writeText.call(nativeClipboard, text).then(resolve, reject); }
                catch (e) { reject(e); }
                return;
            }
            reject(typeof DOMException === 'function'
                ? new DOMException('Clipboard unavailable', 'NotAllowedError')
                : new Error('Clipboard unavailable'));
        });
    };

    try {
        Object.defineProperty(navigator, 'clipboard', {
            configurable: true,
            get: function () { return shim; }
        });
    } catch (e) {}

    var nativeExec = document.execCommand ? document.execCommand.bind(document) : null;
    document.execCommand = function (cmd) {
        if (String(cmd || '').toLowerCase() === 'copy') {
            var selected = '';
            try { selected = String(window.getSelection ? window.getSelection() : ''); } catch (e) {}
            if (selected && nativeWrite(selected)) return true;
        }
        return nativeExec ? nativeExec.apply(document, arguments) : false;
    };
})();
""";

    private static final String GAMEPAD_SHIM_JS = """
(function () {
    if (window.__rigelGamepadShim) return;
    window.__rigelGamepadShim = true;
    var nativeGetGamepads = navigator.getGamepads ? navigator.getGamepads.bind(navigator) : null;
    var pads = [];

    function makeButton(src) {
        var value = Math.max(0, Math.min(1, Number(src && src.value) || 0));
        var pressed = !!(src && src.pressed) || value > 0.5;
        return { pressed: pressed, touched: !!(src && src.touched) || pressed || value > 0, value: value };
    }

    function makePad(src, index) {
        var axes = (src.axes || [0, 0, 0, 0]).map(function (v) { return Number(v) || 0; });
        while (axes.length < 4) axes.push(0);
        return {
            id: src.id || 'Rigel Controller',
            index: index,
            connected: src.connected !== false,
            mapping: src.mapping || 'standard',
            timestamp: performance.now(),
            axes: axes,
            buttons: (src.buttons || []).map(makeButton),
            vibrationActuator: null
        };
    }

    function fire(name, pad) {
        try { window.dispatchEvent(new GamepadEvent(name, { gamepad: pad })); }
        catch (e) {
            try {
                var ev = new Event(name);
                ev.gamepad = pad;
                window.dispatchEvent(ev);
            } catch (_) {}
        }
    }

    window.__rigelSetGamepads = function (payload) {
        var raw = [];
        try { raw = typeof payload === 'string' ? JSON.parse(payload) : (payload || []); }
        catch (e) { raw = []; }
        var next = raw.map(makePad);
        var previous = pads;
        pads = next;
        var count = Math.max(previous.length, pads.length);
        for (var i = 0; i < count; i++) {
            if (!previous[i] && pads[i]) fire('gamepadconnected', pads[i]);
            else if (previous[i] && !pads[i]) {
                var dead = previous[i];
                dead.connected = false;
                fire('gamepaddisconnected', dead);
            }
        }
    };

    try {
        Object.defineProperty(navigator, 'getGamepads', {
            configurable: true,
            value: function () {
                var nativePads = nativeGetGamepads ? Array.prototype.slice.call(nativeGetGamepads() || []) : [];
                if (nativePads.some(function (p) { return !!p; })) return nativePads;
                var result = new Array(Math.max(4, pads.length));
                for (var n = 0; n < result.length; n++) result[n] = null;
                for (var i = 0; i < pads.length && i < result.length; i++) result[i] = pads[i];
                return result;
            }
        });
        Object.defineProperty(navigator, 'webkitGetGamepads', {
            configurable: true,
            value: navigator.getGamepads
        });
    } catch (e) {}
})();
""";

    private static final String CAPABILITY_PROBE_JS = """
(function () {
    var passkeys = false;
    try {
        passkeys = !!(window.PublicKeyCredential &&
            navigator.credentials &&
            typeof navigator.credentials.create === 'function' &&
            typeof navigator.credentials.get === 'function');
    } catch (e) {}
    var mediaCapture = false;
    try {
        mediaCapture = !!(navigator.mediaDevices &&
            typeof navigator.mediaDevices.getUserMedia === 'function');
    } catch (e) {}
    window.__rigelCapabilities = {
        gamepads: true,
        mediaCapture: mediaCapture,
        passkeys: passkeys
    };
})();
""";


    private void focusBrowserSoon(TabData tab) {
        if (tab == null || tab.browser == null || tab.browser.isDisposed()) return;
        int generation = ++focusGeneration;
        tab.browser.getDisplay().asyncExec(() -> {
            if (generation != focusGeneration || !isActive(tab)
                    || tab.browser.isDisposed() || !appActive) return;
            Control focused = tab.browser.getDisplay().getFocusControl();
            if (focused != null && !focused.isDisposed()
                    && !isDescendantOf(focused, contentArea)) return;
            tab.browser.setFocus();
            if ("rigel://home".equals(tab.url)) safeExecute(tab.browser, HOME_FOCUS_JS);
        });
    }

    private static boolean isDescendantOf(Control control, Composite ancestor) {
        for (Control current = control; current != null; current = current.getParent()) {
            if (current == ancestor) return true;
        }
        return false;
    }

    private void injectGamepadShim(RigelBrowser browser) {
        if (browser == null || browser.isDisposed()) return;
        safeExecute(browser, GAMEPAD_SHIM_JS);
    }

    private void injectCapabilityProbe(RigelBrowser browser) {
        if (browser == null || browser.isDisposed()) return;
        safeExecute(browser, CAPABILITY_PROBE_JS);
    }


    private void injectKeyboardFocusShim(RigelBrowser browser) {
        if (browser == null || browser.isDisposed()) return;
        safeExecute(browser, KEY_FOCUS_JS);
        int index = indexOfBrowser(browser);
        String url = index >= 0 ? tabs.get(index).url : "";
        String mode = ui.webInputModeForUrl(url);
        safeExecute(browser, "if(typeof window.__rigelSetWebInputMode==='function')"
                + "window.__rigelSetWebInputMode('" + jsString(mode) + "');");
    }

    public void refreshWebInputModes() {
        for (TabData tab : tabs) {
            if (tab.browser == null || tab.browser.isDisposed()) continue;
            injectKeyboardFocusShim(tab.browser);
        }
    }

    private void injectClipboardShim(RigelBrowser browser) {
        if (browser == null || browser.isDisposed()) return;
        safeExecute(browser, CLIPBOARD_SHIM_JS);
    }

    private void pushGamepads(RigelBrowser browser) {
        if (!gameControllerEnabled) return;
        pushGamepads(browser, gameControllerBridge.snapshotJson());
    }

    private void pushGamepads(RigelBrowser browser, String json) {
        if (!gameControllerEnabled) return;
        if (browser == null || browser.isDisposed()) return;
        safeExecute(browser, "if (typeof window.__rigelSetGamepads === 'function') window.__rigelSetGamepads('" + jsString(json) + "');");
    }

    private void startGamepadPolling() {
        if (gamepadPollingStarted || contentArea == null || contentArea.isDisposed()) return;
        gamepadPollingStarted = true;
        postGamepadPoll(250);
    }

    /** Re-arms the poll chain. No-op if a tick is already scheduled, so the
     *  restart hooks (activation, settings toggle) can call it freely. */
    private void postGamepadPoll(int delayMs) {
        if (!gamepadPollingStarted || gamepadPollArmed) return;
        if (contentArea == null || contentArea.isDisposed()) return;
        Display display = contentArea.getDisplay();
        if (display == null || display.isDisposed()) return;
        gamepadPollArmed = true;
        display.timerExec(delayMs, this::runGamepadPoll);
    }

    /**
     * Battery: the original chain woke the runloop every 750 ms forever —
     * even backgrounded, even with no controller attached — which alone
     * blocked App Nap for the whole process. The chain now parks itself
     * completely whenever Rigel is backgrounded or the bridge is off (the
     * activation/settings hooks re-arm it), and while a foregrounded Rigel
     * has no controller attached the heartbeat escalates 1s -> 2s -> 5s
     * instead of polling at 500 ms forever. A new controller is picked up
     * within one heartbeat, and gameplay cadence is unchanged.
     */
    private void runGamepadPoll() {
        if (contentArea == null || contentArea.isDisposed()) return;
        gamepadPollArmed = false;
        if (gameControllerEnabled && appActive) {
            String json = gameControllerBridge.snapshotJson();
            boolean changed = !json.equals(gamepadLastJson);
            int nextDelay;
            if ("[]".equals(json)) {
                gamepadEmptyStreak++;
                nextDelay = gamepadEmptyStreak >= 3 ? 5000
                        : gamepadEmptyStreak == 2 ? 2000 : 1000;
            } else {
                // Change-driven cadence: hold 33Hz while inputs are
                // moving (gameplay), otherwise a cheap heartbeat —
                // a fresh input boosts us back within one heartbeat.
                gamepadEmptyStreak = 0;
                nextDelay = changed ? 33 : 250;
            }
            TabData tab = activeTab();
            if (tab != null && tab.browser != null && !tab.browser.isDisposed()) {
                if (tab.browser != gamepadLastBrowser || changed) {
                    pushGamepads(tab.browser, json);
                    gamepadLastBrowser = tab.browser;
                    gamepadLastJson = json;
                }
            }
            postGamepadPoll(nextDelay);
        } else {
            // Parked: zero wakeups while backgrounded or disabled.
            gamepadLastBrowser = null;
            gamepadLastJson = null;
            gamepadEmptyStreak = 0;
        }
    }

    private void safeExecute(RigelBrowser browser, String script) {
        if (browser == null || browser.isDisposed()) return;
        try {
            browser.execute(script);
        } catch (org.eclipse.swt.SWTException ignored) {
        }
    }

    private static String jsString(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\'' -> out.append("\\'");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private java.util.Optional<RigelBrowser> active() {
        if (activeIndex < 0 || activeIndex >= tabs.size())
            return java.util.Optional.empty();
        return java.util.Optional.of(tabs.get(activeIndex).browser);
    }

    private TabData activeTab() {
        if (activeIndex < 0 || activeIndex >= tabs.size()) return null;
        return tabs.get(activeIndex);
    }

    /** Visual smoke test: snapshot (with alpha) of the active page to a PNG. */
    void snapshotActive(String path) {
        TabData tab = activeTab();
        if (tab != null && tab.browser != null && !tab.browser.isDisposed())
            tab.browser.snapshot(path);
    }

    /** Diagnostic smoke test: evaluate script in the active page. */
    void evaluateActive(String javascript, java.util.function.BiConsumer<Object, Throwable> completion) {
        TabData tab = activeTab();
        if (tab == null || tab.browser == null || tab.browser.isDisposed()) {
            completion.accept(null, new IllegalStateException("no active browser"));
            return;
        }
        tab.browser.evaluateAsync(javascript, completion);
    }

    private boolean isActive(TabData tab) {
        return activeIndex >= 0
            && activeIndex < tabs.size()
            && tabs.get(activeIndex) == tab;
    }

    private static String fallbackTitle(String url) {
        if (url == null || url.isBlank() || "about:blank".equals(url)) return "New Tab";
        if ("rigel://home".equals(url)) return "New Tab";
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host != null && !host.isBlank()) return host.replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException ignored) {
        }
        return "Untitled";
    }

    // ── Theme refresh ─────────────────────────────────────────────────────────

    /**
     * If the currently active tab is showing rigel://home, re-renders it with
     * the current theme.  Called by BrowserUI.applyTheme() so the home page
     * updates instantly when the user picks a new theme — without touching any
     * background tab that might be mid-game or mid-session on a real website.
     */
    public void refreshActiveHomeTab() {
        homeRenderVersion++;
        TabData t = activeTab();
        if (t != null && "rigel://home".equals(t.url) && !t.browser.isDisposed()) {
            beginLoading(t);
            renderHome(t);
            focusBrowserSoon(t);
        }
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public List<TabData> getTabs()        { return tabs; }
    public int           getActiveIndex() { return activeIndex; }
    public boolean isActiveLoading() {
        TabData tab = activeTab();
        return tab != null && tab.loading;
    }
    public int getActiveProgress() {
        TabData tab = activeTab();
        return tab == null ? 0 : tab.progress;
    }
    public boolean hasClosedTabs() { return !closedTabs.isEmpty(); }

    public List<String> getOpenUrls() {
        List<String> urls = new ArrayList<>();
        for (TabData tab : tabs) {
            String url = tab.url;
            if (url == null || url.isBlank()) url = "rigel://home";
            urls.add(url);
        }
        return urls;
    }

    /** Returns the browser widget for the currently active tab, or null if none. */
    public RigelBrowser getActiveBrowser() {
        if (activeIndex < 0 || activeIndex >= tabs.size()) return null;
        return tabs.get(activeIndex).browser;
    }
}
