"""
browser_api.py — Rigel Browser Python API exposed to the chrome webview via pywebview
"""

import json
import os
import re
import sys
import subprocess
import threading
import urllib.request
import urllib.error
from pathlib import Path

import webview

from constants import (
    CHROME_H, CHROME_PANEL_H, INITIAL_W, INITIAL_H, KEEPALIVE_JS,
)
from home_html import HOME_HTML
from js_snippets import (
    ADBLOCK_JS, POPUP_BLOCKER_JS, DARKMODE_JS, DOWNLOAD_JS,
    AUTOFILL_JS, CONTEXT_MENU_JS, CONTROLLER_JS, READER_JS,
    FIX_BLANK, pdf_viewer_html,
)
import database as db


class BrowserAPI:
    def __init__(self):
        self._content  = None
        self._chrome   = None
        self._chrome_h = CHROME_H
        self._settings = {
            "adblock": True, "darkmode": False, "forcehttps": True,
            "javascript": True, "fingerprint": False,
            "camera": False, "microphone": False, "passwords": True,
            "zoom": 100, "proxy": {"enabled": False}, "ua": "default"
        }
        self._tab_urls  = {}
        self._active_id = None
        self._controllers = {}
        self._ctrl_hud    = False

    def set_windows(self, chrome, content):
        self._chrome  = chrome
        self._content = content

        def on_loaded(*args, **kwargs):
            def _cb():
                try:
                    url   = self._content.get_current_url() or ""
                    title = self._content.evaluate_js("document.title") or url
                    self._content.evaluate_js(FIX_BLANK)
                    try: self._content.evaluate_js(KEEPALIVE_JS)
                    except: pass
                    if self._settings.get("adblock"):
                        try: self._content.evaluate_js(ADBLOCK_JS)
                        except: pass
                    try: self._content.evaluate_js(POPUP_BLOCKER_JS)
                    except: pass
                    if self._settings.get("darkmode"):
                        try: self._content.evaluate_js(DARKMODE_JS)
                        except: pass
                    try: self._content.evaluate_js(DOWNLOAD_JS)
                    except: pass
                    try: self._content.evaluate_js(CONTEXT_MENU_JS)
                    except: pass
                    try: self._content.evaluate_js(CONTROLLER_JS)
                    except: pass
                    if self._settings.get("passwords"):
                        try: self._content.evaluate_js(AUTOFILL_JS)
                        except: pass
                    z = self._settings.get("zoom", 100)
                    if z != 100:
                        try: self._content.evaluate_js(
                            f"document.documentElement.style.setProperty('zoom','{z}%');"
                        )
                        except: pass
                    tid = self._active_id or 1
                    if url and url not in ("about:blank", ""):
                        self._tab_urls[tid] = url
                    self._chrome.evaluate_js(
                        f"onContentNav({json.dumps(url)},{json.dumps(str(title)[:80])},{tid});"
                    )
                except Exception:
                    pass
            threading.Timer(0.7, _cb).start()

        self._content.events.loaded += on_loaded

        def _close_content():
            try: self._chrome.destroy()
            except: pass
        def _close_chrome():
            try: self._content.destroy()
            except: pass
        try: self._content.events.closing += _close_content
        except AttributeError: pass
        try: self._chrome.events.closing += _close_chrome
        except AttributeError: pass

        def _moved(x, y):
            try: self._content.move(x, y + self._chrome_h)
            except: pass
        try: self._chrome.events.moved += _moved
        except AttributeError: pass

        def _resized(w, h):
            try:
                cx = getattr(self._chrome, 'x', 0) or 0
                cy = getattr(self._chrome, 'y', 0) or 0
                self._content.resize(w, max(200, INITIAL_H - self._chrome_h))
                self._content.move(cx, cy + self._chrome_h)
            except: pass
        try: self._chrome.events.resized += _resized
        except AttributeError: pass

    # ── Navigation ──────────────────────────────────────────────────────────
    def open_url(self, url: str, tab_id: int = 0):
        if not self._content: return
        self._active_id = tab_id
        if not url or url == "__home__":
            self._content.load_html(HOME_HTML, base_uri="about:blank")
            self._tab_urls[tab_id] = "__home__"
        elif url.lower().split("?")[0].endswith(".pdf") and url.startswith("http"):
            self._content.load_html(pdf_viewer_html(url))
            self._tab_urls[tab_id] = url
        else:
            self._content.load_url(url)
            self._tab_urls[tab_id] = url

    def switch_to(self, tab_id: int, url: str = ""):
        self._active_id = tab_id
        stored = self._tab_urls.get(tab_id) or url
        if not stored or stored == "__home__":
            self._content.load_html(HOME_HTML, base_uri="about:blank"); return
        try:
            if (self._content.get_current_url() or "").rstrip("/") != stored.rstrip("/"):
                self._content.load_url(stored)
        except Exception:
            self._content.load_url(stored)

    def open_url_new_window(self, url: str):
        """Open a URL by switching to an idle star window. (replaced by window registry)"""
        pass

    def open_new_window(self, url: str = ""):
        """Switch to an idle star window. (replaced by window registry)"""
        pass

    def switch_star(self, name: str):
        """Switch active star window. (replaced by window registry)"""
        pass

    def go_back(self):
        try: self._content.evaluate_js("window.history.back()")
        except: pass

    def go_forward(self):
        try: self._content.evaluate_js("window.history.forward()")
        except: pass

    def reload(self):
        try: self._content.evaluate_js("window.location.reload()")
        except: pass

    def expand_chrome(self):
        if not self._chrome: return
        try:
            self._chrome_h = CHROME_PANEL_H
            w  = getattr(self._chrome, 'width', INITIAL_W) or INITIAL_W
            cx = getattr(self._chrome, 'x', 0) or 0
            cy = getattr(self._chrome, 'y', 0) or 0
            self._chrome.resize(w, CHROME_PANEL_H)
            self._content.move(cx, cy + CHROME_PANEL_H)
            self._content.resize(w, max(200, INITIAL_H - CHROME_PANEL_H))
        except: pass

    def collapse_chrome(self):
        if not self._chrome: return
        try:
            self._chrome_h = CHROME_H
            w  = getattr(self._chrome, 'width', INITIAL_W) or INITIAL_W
            cx = getattr(self._chrome, 'x', 0) or 0
            cy = getattr(self._chrome, 'y', 0) or 0
            self._chrome.resize(w, CHROME_H)
            self._content.move(cx, cy + CHROME_H)
            self._content.resize(w, max(200, INITIAL_H - CHROME_H))
        except: pass

    def open_popup(self, url: str):
        """Open a native popup for OAuth/auth flows."""
        try:
            auth_domains = ['accounts.google','appleid.apple','apple.com/auth',
                            'facebook.com/dialog','facebook.com/login',
                            'tiktok.com/login','twitter.com/i/oauth',
                            'oauth','authorize','callback']
            popup = webview.create_window("Sign in", url=url, width=520, height=700, resizable=True)

            def _on_loaded(_win=None):
                def _check():
                    try:
                        cur = (popup.get_current_url() or '').lower()
                        if cur and not any(d in cur for d in auth_domains):
                            try: self._content.evaluate_js("window.location.reload();")
                            except: pass
                            threading.Timer(0.6, _close).start()
                    except: pass
                threading.Timer(0.9, _check).start()

            def _close():
                try: popup.destroy()
                except: pass

            try: popup.events.loaded += _on_loaded
            except: pass
        except Exception:
            self.open_url_new_window(url)

        try: self._content.evaluate_js("window.history.back()")
        except: pass

    def set_zoom(self, level: int):
        self._settings["zoom"] = level
        try: self._content.evaluate_js(
            f"document.documentElement.style.setProperty('zoom','{level}%');"
        )
        except: pass

    def reader_mode(self):
        try: self._content.evaluate_js(READER_JS)
        except: pass

    def print_page(self):
        try: self._content.evaluate_js("window.print();")
        except: pass

    def open_devtools(self):
        try:
            self._content.evaluate_js(
                "(function(){if(window.__eruda_init){eruda.show();return;}"
                "window.__eruda_init=true;"
                "var s=document.createElement('script');"
                "s.src='https://cdn.jsdelivr.net/npm/eruda';"
                "s.onload=function(){eruda.init();};"
                "document.head.appendChild(s);})()"
            )
        except: pass

    def view_source(self):
        try:
            src = self._content.evaluate_js("document.documentElement.outerHTML") or ""
            escaped = json.dumps(src)
            self._content.evaluate_js(
                f"(function(){{"
                f"var w=window.open('about:blank','_self');"
                f"var html={escaped};"
                f"var safe=html.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');"
                f"w.document.open();"
                f"w.document.write('<html><head><meta charset=UTF-8><style>"
                f"body{{background:#0a0f2a;color:#b8d4ff;font-family:monospace;font-size:13px;"
                f"padding:20px;white-space:pre-wrap;word-wrap:break-word;}}</style></head>"
                f"<body>'+safe+'</body></html>');"
                f"w.document.close();}})();"
            )
        except: pass

    # ── Downloads ────────────────────────────────────────────────────────────
    def handle_download(self, url: str, filename: str = ""):
        if not filename:
            filename = url.split("?")[0].split("/")[-1] or "download"
        filename = re.sub(r'[\\/:*?"<>|]', "_", filename).strip() or "download"

        def _do():
            try:
                save_path = self._chrome.create_file_dialog(
                    webview.SAVE_DIALOG,
                    directory=str(Path.home() / "Downloads"),
                    save_filename=filename,
                )
            except Exception:
                save_path = None
            if isinstance(save_path, (list, tuple)):
                save_path = save_path[0] if save_path else None
            if not save_path:
                return

            def _toast(msg, color="#6dffb8", border="rgba(109,255,184,0.3)", dur=3500):
                try:
                    self._chrome.evaluate_js(
                        f"(function(){{"
                        f"var b=document.getElementById('__rigel_dl_toast')||document.createElement('div');"
                        f"b.id='__rigel_dl_toast';"
                        f"b.style='position:fixed;bottom:52px;left:50%;transform:translateX(-50%);"
                        f"background:rgba(8,12,30,0.97);border:1px solid {border};"
                        f"color:{color};padding:9px 20px;border-radius:10px;font-size:12px;"
                        f"z-index:99999;font-family:Space Grotesk,sans-serif;"
                        f"box-shadow:0 8px 32px rgba(0,0,0,0.6);white-space:nowrap';"
                        f"b.textContent={json.dumps(msg)};"
                        f"document.body.appendChild(b);"
                        f"clearTimeout(b.__t);b.__t=setTimeout(()=>b.remove(),{dur});}})();"
                    )
                except: pass

            dl_id = db.create_download(url, os.path.basename(save_path), save_path)
            existing = os.path.getsize(save_path) if os.path.exists(save_path) else 0
            _toast(f"⬇ Starting {os.path.basename(save_path)}…", "#a8d8ff", "rgba(77,166,255,0.3)", 60000)

            try:
                headers = {"User-Agent": "Mozilla/5.0 (compatible; Rigel/2.0)"}
                if existing > 0:
                    headers["Range"] = f"bytes={existing}-"
                resp = urllib.request.urlopen(
                    urllib.request.Request(url, headers=headers), timeout=60
                )
                cr = resp.headers.get("Content-Range", "")
                total = (
                    int(cr.split("/")[-1]) if cr and "/" in cr
                    else int(resp.headers.get("Content-Length", 0)) + existing
                )
                db.update_download(dl_id, total, existing)

                mode = "ab" if existing > 0 else "wb"
                done = existing
                with open(save_path, mode) as f:
                    while True:
                        buf = resp.read(65536)
                        if not buf: break
                        f.write(buf); done += len(buf)
                        if total > 0:
                            pct = int(done / total * 100)
                            _toast(f"⬇ {os.path.basename(save_path)} — {pct}%",
                                   "#a8d8ff", "rgba(77,166,255,0.3)", 60000)
                        db.update_download_progress(dl_id, done)

                try:
                    if sys.platform == "win32": subprocess.Popen(["explorer", "/select,", save_path])
                    elif sys.platform == "darwin": subprocess.Popen(["open", "-R", save_path])
                    else: subprocess.Popen(["xdg-open", os.path.dirname(save_path)])
                except: pass

                _toast(f"✦ Saved: {os.path.basename(save_path)}", "#6dffb8", "rgba(109,255,184,0.3)", 4000)
            except Exception as ex:
                _toast(f"Download failed: {str(ex)[:60]}", "#ff6b8a", "rgba(255,107,138,0.3)", 5000)

        threading.Thread(target=_do, daemon=True).start()

    def resume_download(self, dl_id: int):
        row = db.get_download(dl_id)
        if row: self.handle_download(row[0], row[1])

    def list_downloads(self):
        return db.list_downloads()

    def open_download_folder(self):
        dl = str(Path.home() / "Downloads")
        os.makedirs(dl, exist_ok=True)
        try:
            if sys.platform == "win32": os.startfile(dl)
            elif sys.platform == "darwin": subprocess.Popen(["open", dl])
            else: subprocess.Popen(["xdg-open", dl])
        except: pass

    # ── Passwords ────────────────────────────────────────────────────────────
    def get_credentials(self, host: str):
        return db.get_credentials(host)

    def offer_save_password(self, host: str, username: str, password: str):
        if not self._settings.get("passwords"): return
        if db.has_credential(host, username): return
        try: self._chrome.evaluate_js(
            f"showPwPrompt({json.dumps(host)},{json.dumps(username)},{json.dumps(password)});"
        )
        except: pass

    def save_password(self, host: str, username: str, password: str):
        db.save_password(host, username, password)

    def list_passwords(self):
        return db.list_passwords()

    def delete_password(self, pw_id: int):
        db.delete_password(pw_id)

    # ── Bookmarks ────────────────────────────────────────────────────────────
    def save_bookmark(self, url: str, title: str):
        db.save_bookmark(url, title)

    def load_bookmarks(self):
        return db.load_bookmarks()

    def delete_bookmark(self, url: str):
        db.delete_bookmark(url)

    # ── History ──────────────────────────────────────────────────────────────
    def save_history(self, url: str, title: str):
        db.save_history(url, title)

    def load_history(self):
        return db.load_history()

    def clear_history(self):
        db.clear_history()

    # ── Cookies ──────────────────────────────────────────────────────────────
    def get_cookies(self, host: str):
        try:
            raw = self._content.evaluate_js("document.cookie") or ""
            cookies = []
            for pair in raw.split(";"):
                pair = pair.strip()
                if "=" in pair:
                    name, _, value = pair.partition("=")
                    cookies.append({"name": name.strip(), "value": value.strip()})
                elif pair:
                    cookies.append({"name": pair, "value": ""})
            return cookies
        except Exception:
            return []

    def clear_cookies(self):
        try:
            self._content.evaluate_js(
                "document.cookie.split(';').forEach(function(c){"
                "var k=c.trim().split('=')[0];"
                "document.cookie=k+'=;expires=Thu,01 Jan 1970 00:00:00 UTC;path=/;';"
                "});"
            )
        except: pass

    # ── Controllers ──────────────────────────────────────────────────────────
    def get_controllers(self):
        return list(self._controllers.values())

    def controller_connected(self, gp_id: str, ctrl_type: str):
        self._controllers[gp_id] = {"id": gp_id, "type": ctrl_type}
        try:
            self._chrome.evaluate_js(
                f"onCtrlConnected({json.dumps(gp_id)},{json.dumps(ctrl_type)});"
            )
        except: pass

    def controller_disconnected(self, gp_id: str):
        self._controllers.pop(gp_id, None)
        try:
            self._chrome.evaluate_js(
                f"onCtrlDisconnected({json.dumps(gp_id)});"
            )
        except: pass

    def set_ctrl_hud(self, enabled: bool):
        self._ctrl_hud = bool(enabled)
        try:
            js = (
                "var h=document.getElementById('__rigel_ctrl_hud');"
                f"if(h) h.style.display='{('flex' if enabled else 'none')}';"
            )
            self._content.evaluate_js(js)
        except: pass

    # ── Tailscale ─────────────────────────────────────────────────────────────
    def tailscale_status(self):
        try:
            req = urllib.request.Request(
                "http://127.0.0.1:41112/localapi/v0/status",
                headers={"Tailscale-Cap": "70"}
            )
            resp = urllib.request.urlopen(req, timeout=3)
            data = json.loads(resp.read().decode())
            peers = list((data.get("Peer") or {}).values())
            current_exit = ""
            for p in peers:
                if p.get("ExitNode"):
                    current_exit = p.get("HostName") or p.get("DNSName") or ""
                    break
            return {
                "backendState": data.get("BackendState", "Unknown"),
                "self": data.get("Self"),
                "peers": peers,
                "currentExitNode": current_exit,
            }
        except Exception:
            try:
                result = subprocess.run(
                    ["tailscale", "status", "--json"],
                    capture_output=True, text=True, timeout=5
                )
                if result.returncode == 0 and result.stdout.strip():
                    data = json.loads(result.stdout)
                    peers = list((data.get("Peer") or {}).values())
                    current_exit = ""
                    for p in peers:
                        if p.get("ExitNode"):
                            current_exit = p.get("HostName") or p.get("DNSName") or ""
                            break
                    return {
                        "backendState": data.get("BackendState", "Unknown"),
                        "self": data.get("Self"),
                        "peers": peers,
                        "currentExitNode": current_exit,
                    }
                else:
                    return {"error": result.stderr.strip() or "tailscale status failed"}
            except FileNotFoundError:
                return {"error": "Tailscale not installed. Download from tailscale.com/download"}
            except Exception as e2:
                return {"error": f"Tailscale unreachable: {str(e2)[:80]}"}

    def tailscale_set_exit_node(self, host: str):
        try:
            cmd = ["tailscale", "set", "--exit-node", host] if host else ["tailscale", "set", "--exit-node="]
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
            if result.returncode == 0:
                return {"ok": True}
            else:
                return {"ok": False, "error": result.stderr.strip() or "Unknown error"}
        except FileNotFoundError:
            return {"ok": False, "error": "Tailscale CLI not found"}
        except Exception as e:
            return {"ok": False, "error": str(e)[:80]}

    # ── Settings ─────────────────────────────────────────────────────────────
    def update_settings(self, settings_json: str):
        try:
            s = json.loads(settings_json)
            self._settings.update(s)

            if s.get("adblock"):
                try: self._content.evaluate_js(ADBLOCK_JS)
                except: pass

            dm = s.get("darkmode")
            if dm is True:
                try: self._content.evaluate_js(DARKMODE_JS)
                except: pass
            elif dm is False:
                try: self._content.evaluate_js(
                    "var s=document.getElementById('__rigel_dm');if(s)s.remove();"
                )
                except: pass

            if s.get("passwords"):
                try: self._content.evaluate_js(AUTOFILL_JS)
                except: pass

            cam = self._settings.get("camera", False)
            mic = self._settings.get("microphone", False)
            if not cam and not mic:
                bjs = ("navigator.mediaDevices&&(navigator.mediaDevices.getUserMedia="
                       "function(){return Promise.reject(new DOMException('Permission denied','NotAllowedError'));});")
            elif not cam:
                bjs = ("(function(){var o=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);"
                       "navigator.mediaDevices.getUserMedia=function(c){return c&&c.video?"
                       "Promise.reject(new DOMException('Permission denied','NotAllowedError')):o(c);}})();")
            elif not mic:
                bjs = ("(function(){var o=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);"
                       "navigator.mediaDevices.getUserMedia=function(c){return c&&c.audio?"
                       "Promise.reject(new DOMException('Permission denied','NotAllowedError')):o(c);}})();")
            else:
                bjs = ""
            if bjs:
                try: self._content.evaluate_js(bjs)
                except: pass
        except Exception:
            pass
