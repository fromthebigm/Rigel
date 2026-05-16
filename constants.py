"""
constants.py — Rigel Browser shared constants
"""

from pathlib import Path

# ── Data directory ─────────────────────────────────────────────────────────────
DATA_DIR = Path.home() / ".rigel"
DATA_DIR.mkdir(exist_ok=True)
DB_PATH  = DATA_DIR / "data.db"
SETTINGS_PATH = DATA_DIR / "settings.json"

# ── Layout constants ───────────────────────────────────────────────────────────
CHROME_H          = 54
CHROME_PANEL_H    = 520   # chrome height when a panel is open (covers panel dropdown)
INITIAL_W         = 1400
INITIAL_H         = 900

KEEPALIVE_JS = """
(function(){
  if(window.__rigel_alive) return; window.__rigel_alive=true;
  // Tiny no-op interval — keeps the WebKit runloop active so hidden windows
  // never get suspended and lose their page state.
  setInterval(function(){ void(0); }, 5000);
})();
"""

# ── Pleiades window registry ───────────────────────────────────────────────────
STAR_NAMES  = ["Rigel", "Alc", "Maia", "Elect", "Tayg", "Ast", "Cel", "Mer"]
STAR_GLYPHS = ["✦",    "★",   "✧",    "⋆",     "✩",   "✦",   "⊹",  "⁕"]
