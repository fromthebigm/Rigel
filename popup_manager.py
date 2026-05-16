"""
popup_manager.py — Rigel Browser popup/OAuth window manager
"""

import threading
import webview


class PopupManager:
    """
    Manages popup windows created by window.open() / target="_blank" auth flows.
    Each popup is a standalone webview window that closes itself when done.
    """

    AUTH_DOMAINS = [
        'accounts.google', 'appleid.apple', 'apple.com/auth',
        'facebook.com/dialog', 'connect.facebook',
        'oauth', 'authorize', 'callback', 'redirect',
    ]

    def __init__(self, api):
        self._api  = api
        self._wins = {}  # url -> webview window

    def open(self, url: str, title: str = ""):
        """Open a popup window for auth flows (Google, Apple, TikTok, etc.)"""
        w = webview.create_window(
            title=title or "Sign in",
            url=url,
            width=520, height=680,
            resizable=True,
        )
        self._wins[url] = w

        def _on_loaded(_win=None):
            def _check():
                try:
                    cur = w.get_current_url() or ""
                    if cur and not any(d in cur.lower() for d in self.AUTH_DOMAINS):
                        try: self._api._content.evaluate_js("window.location.reload();")
                        except: pass
                        threading.Timer(0.5, lambda: self._close(w, url)).start()
                except: pass
            threading.Timer(0.8, _check).start()

        try: w.events.loaded += _on_loaded
        except: pass

        def _on_close():
            self._wins.pop(url, None)
        try: w.events.closed += _on_close
        except: pass

        return w

    def _close(self, w, url):
        try: w.destroy()
        except: pass
        self._wins.pop(url, None)
