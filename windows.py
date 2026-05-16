import json
import os
import threading
import argparse
from pathlib import Path

import webview as wv

from constants import (DATA_DIR, CHROME_H, INITIAL_W, INITIAL_H,
                       STAR_NAMES, STAR_GLYPHS)
from browser_api import BrowserAPI
from popup_manager import PopupManager
from rigel_html import CHROME_HTML
from js import POPUP_BRIDGE_JS
from db import init_db

# ── Global registry ───────────────────────────────────────────────────────────
_WINDOWS: dict = {}
_ACTIVE_STAR: list = ["Rigel"]
_REGISTRY_LOCK = threading.Lock()


def _switch_to_star(name: str):
    with _REGISTRY_LOCK:
        prev = _ACTIVE_STAR[0]
        _ACTIVE_STAR[0] = name
        for star, rec in _WINDOWS.items():
            chrome  = rec["chrome"]
            content = rec["content"]
            api     = rec["api"]
            if star == name:
                try: api._chrome_h = CHROME_H; api.collapse_chrome()
                except: pass
                try: chrome.evaluate_js("closeAllPanels();")
                except: pass
                try: chrome.show()
                except: pass
                try: content.show()
                except: pass
            else:
                if star == prev:
                    try: api._chrome_h = CHROME_H; api.collapse_chrome()
                    except: pass
                    try: chrome.evaluate_js("closeAllPanels();")
                    except: pass
                try: chrome.hide()
                except: pass
                try: content.hide()
                except: pass


def _notify_all_switchers():
    state = []
    active = _ACTIVE_STAR[0]
    for star in STAR_NAMES:
        rec = _WINDOWS.get(star, {})
        state.append({
            "name":   star,
            "glyph":  STAR_GLYPHS[STAR_NAMES.index(star)],
            "url":    rec.get("url", "__home__"),
            "title":  rec.get("title", star),
            "active": star == active,
        })
    js = f"onStarState({json.dumps(state)});"
    for rec in _WINDOWS.values():
        try: rec["chrome"].evaluate_js(js)
        except: pass


def _build_star_window(star_name: str, star_index: int):
    api = BrowserAPI()
    api._star_name = star_name
    popup_mgr = PopupManager(api)

    chrome_win = wv.create_window(
        title=f"Rigel — {star_name}",
        html=CHROME_HTML, js_api=api,
        width=INITIAL_W, height=CHROME_H, x=0, y=0,
        frameless=True, resizable=True,
        hidden=(star_index != 0),
    )
    content_win = wv.create_window(
        title="", url="about:blank",
        width=INITIAL_W, height=INITIAL_H - CHROME_H, x=0, y=CHROME_H,
        hidden=(star_index != 0),
    )

    api.set_windows(chrome_win, content_win)

    with _REGISTRY_LOCK:
        _WINDOWS[star_name] = {
            "chrome":  chrome_win,
            "content": content_win,
            "api":     api,
            "url":     "__home__",
            "title":   star_name,
        }

    def _inject_popup_bridge(win=None):
        def _do():
            try: content_win.evaluate_js(POPUP_BRIDGE_JS)
            except: pass
        threading.Timer(0.3, _do).start()
    try: content_win.events.loaded += _inject_popup_bridge
    except AttributeError: pass

    def _on_new_window_request(url, *args):
        if not url or url == "about:blank": return
        threading.Thread(target=popup_mgr.open, args=(url,), daemon=True).start()
    try: content_win.events.new_window_request += _on_new_window_request
    except AttributeError: pass

    def _open_new_window(url: str = ""):
        with _REGISTRY_LOCK:
            current = _ACTIVE_STAR[0]
            idle = None
            for sname in STAR_NAMES:
                if sname == current: continue
                rec = _WINDOWS.get(sname, {})
                if rec.get("url", "__home__") in ("__home__", "", "about:blank"):
                    idle = sname
                    break
            if idle is None:
                idx = STAR_NAMES.index(current)
                idle = STAR_NAMES[(idx + 1) % len(STAR_NAMES)]
        _switch_to_star(idle)
        if url:
            def _nav():
                try: _WINDOWS.get(idle, {})["chrome"].evaluate_js(f"navigate({json.dumps(url)});")
                except: pass
            threading.Timer(0.5, _nav).start()
        _notify_all_switchers()

    api.open_new_window     = _open_new_window
    api.open_url_new_window = _open_new_window

    def _switch_star(name: str):
        threading.Thread(target=_switch_to_star, args=(name,), daemon=True).start()
        threading.Timer(0.15, _notify_all_switchers).start()
    api.switch_star = _switch_star

    def _on_content_loaded(win=None):
        def _cb():
            try:
                url   = content_win.get_current_url() or ""
                title = content_win.evaluate_js("document.title") or url
                with _REGISTRY_LOCK:
                    rec = _WINDOWS.get(star_name, {})
                    rec["url"]   = url or rec.get("url", "__home__")
                    rec["title"] = str(title)[:60] or star_name
                threading.Timer(0.1, _notify_all_switchers).start()
            except: pass
        threading.Timer(0.8, _cb).start()
    try: content_win.events.loaded += _on_content_loaded
    except AttributeError: pass

    return chrome_win, content_win, api


# ── Entry point ───────────────────────────────────────────────────────────────
def main():
    init_db()

    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--proxy-server", default="direct://",
                        help="Proxy — e.g. socks5://127.0.0.1:1080")
    args, _ = parser.parse_known_args()
    proxy = args.proxy_server.strip()

    for _v in ("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY",
               "ALL_PROXY", "all_proxy", "FTP_PROXY", "ftp_proxy"):
        os.environ.pop(_v, None)

    if not proxy or proxy == "direct://":
        os.environ["no_proxy"] = "*"
        os.environ["NO_PROXY"] = "*"
    else:
        if not proxy.startswith(("http://", "https://", "socks4://", "socks5://")):
            proxy = "http://" + proxy
        os.environ["http_proxy"]  = proxy
        os.environ["https_proxy"] = proxy
        os.environ["HTTP_PROXY"]  = proxy
        os.environ["HTTPS_PROXY"] = proxy
        os.environ.pop("no_proxy", None)
        os.environ.pop("NO_PROXY", None)
        _existing = os.environ.get("QTWEBENGINE_CHROMIUM_FLAGS", "")
        os.environ["QTWEBENGINE_CHROMIUM_FLAGS"] = _existing + f" --proxy-server={proxy}"

    STORAGE_PATH = str(DATA_DIR / "webdata")
    Path(STORAGE_PATH).mkdir(exist_ok=True)

    wv.settings['OPEN_EXTERNAL_LINKS_IN_BROWSER'] = False
    try: wv.settings['PRIVATE_MODE'] = False
    except: pass
    try: wv.settings['MEDIA_STREAM'] = True
    except: pass
    try: wv.settings['ALLOW_DOWNLOADS'] = True
    except: pass

    for i, star in enumerate(STAR_NAMES):
        _build_star_window(star, i)

    def _initial_nav():
        try: _WINDOWS.get("Rigel", {})["chrome"].evaluate_js("navigate('__home__');")
        except: pass
        _notify_all_switchers()
    threading.Timer(1.4, _initial_nav).start()
    os.environ['auto_proxy_url'] = 'file:///Users/daniyalkhan/Desktop/pyt/proxy.pac'
    os.environ['AUTO_PROXY_URL'] = 'file:///Users/daniyalkhan/Desktop/pyt/proxy.pac'
    wv.start(debug=False, storage_path=STORAGE_PATH)

if __name__ == "__main__":
    main()
