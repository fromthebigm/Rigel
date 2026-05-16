"""
chrome_html.py — Rigel Browser chrome/toolbar HTML (frameless nav bar + panels)
"""

CHROME_HTML = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"/>
<style>
@import url('https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@300;400;500;600&family=Space+Mono:wght@400&display=swap');
:root {
  --void:#03040d; --nebula:#0a0f2a; --cloud:#0f1535; --drift:#162048;
  --stellar:#b8d4ff; --starwhite:#e8f0ff; --alcyone:#4da6ff; --atlas:#7fc4ff;
  --maia:#a8d8ff; --merope:#2979e8; --electra:#6de0ff;
  --glow:rgba(77,166,255,0.18); --border:rgba(184,212,255,0.12); --border2:rgba(184,212,255,0.22);
  --danger:#ff6b8a; --success:#6dffb8; --tab-h:0px; --bar-h:50px;
}
*{box-sizing:border-box;margin:0;padding:0;}
html,body{height:100%;overflow:visible;background:var(--void);}
body{font-family:'Space Grotesk',sans-serif;color:var(--starwhite);user-select:none;-webkit-app-region:drag;}
#nav{display:flex;align-items:center;gap:6px;padding:5px 10px;height:var(--bar-h);
  background:rgba(6,10,26,0.98);border-bottom:1px solid var(--border);}
#logo{display:flex;align-items:center;gap:7px;flex-shrink:0;padding-right:6px;
  border-right:1px solid var(--border);margin-right:2px;-webkit-app-region:no-drag;}
#logo-mark{width:24px;height:24px;border-radius:7px;background:linear-gradient(135deg,var(--merope),var(--alcyone));
  display:flex;align-items:center;justify-content:center;font-size:13px;box-shadow:0 0 12px rgba(77,166,255,0.4);}
#logo-text{font-size:12px;font-weight:600;letter-spacing:.06em;
  background:linear-gradient(135deg,var(--maia),var(--electra));
  -webkit-background-clip:text;-webkit-text-fill-color:transparent;}
.btn{width:28px;height:28px;border-radius:7px;border:none;background:transparent;
  color:rgba(184,212,255,0.45);font-size:14px;cursor:pointer;
  display:flex;align-items:center;justify-content:center;
  transition:all .14s;flex-shrink:0;font-family:'Space Grotesk',sans-serif;-webkit-app-region:no-drag;}
.btn:hover:not(:disabled){color:var(--alcyone);background:var(--glow);}
.btn:disabled{opacity:.2;cursor:default;}
.btn.lit2{color:var(--electra);background:rgba(109,224,255,0.1);}
#url-wrap{flex:1;display:flex;align-items:center;gap:6px;background:var(--nebula);
  border:1px solid var(--border);border-radius:9px;height:31px;padding:0 6px 0 10px;
  transition:all .16s;-webkit-app-region:no-drag;}
#url-wrap:focus-within{border-color:var(--alcyone);box-shadow:0 0 0 3px rgba(77,166,255,0.1);}
#lock{font-size:9px;flex-shrink:0;}
#urlbar{flex:1;background:transparent;border:none;outline:none;color:var(--starwhite);
  font-size:12px;font-family:'Space Grotesk',sans-serif;}
#urlbar::placeholder{color:rgba(184,212,255,0.35);}
#gobtn{width:22px;height:22px;border-radius:5px;border:none;background:rgba(77,166,255,0.15);
  color:var(--alcyone);font-size:11px;cursor:pointer;display:flex;align-items:center;justify-content:center;transition:all .12s;}
#gobtn:hover{background:rgba(77,166,255,0.28);}
#pills{display:flex;gap:4px;flex-shrink:0;-webkit-app-region:no-drag;}
.pill{padding:3px 8px;border-radius:20px;font-size:10px;font-weight:500;cursor:pointer;
  transition:all .14s;display:flex;align-items:center;gap:3px;border:1px solid transparent;letter-spacing:.02em;}
.pill-shield{background:rgba(109,255,184,0.08);color:var(--success);border-color:rgba(109,255,184,0.2);}
.pill-shield.off{background:rgba(255,107,138,0.08);color:var(--danger);border-color:rgba(255,107,138,0.2);}
.pill-proxy{background:rgba(77,166,255,0.08);color:var(--atlas);border-color:var(--border);}
.pill-proxy.off{opacity:.35;}
#zdisplay{font-size:10px;color:rgba(184,212,255,0.45);font-family:'Space Mono',monospace;
  background:var(--nebula);border-radius:5px;padding:2px 7px;min-width:40px;text-align:center;
  flex-shrink:0;border:1px solid var(--border);-webkit-app-region:no-drag;}
.panel{position:fixed;top:calc(var(--bar-h) + 4px);right:8px;
  min-width:300px;max-width:390px;background:rgba(8,12,30,0.97);border:1px solid var(--border2);
  border-radius:14px;padding:16px;z-index:9999;display:none;
  box-shadow:0 24px 80px rgba(0,0,0,0.85);backdrop-filter:blur(24px);
  color:var(--starwhite);font-size:13px;max-height:450px;overflow-y:auto;-webkit-app-region:no-drag;}
.panel.open{display:block;animation:pIn .14s ease;}
@keyframes pIn{from{opacity:0;transform:translateY(-6px) scale(.98);}to{opacity:1;transform:none;}}
.panel::-webkit-scrollbar{width:4px;}
.panel::-webkit-scrollbar-thumb{background:var(--drift);border-radius:2px;}
.ph{display:flex;align-items:center;gap:8px;font-size:11px;font-weight:600;color:var(--atlas);
  letter-spacing:.06em;text-transform:uppercase;margin-bottom:12px;padding-bottom:10px;
  border-bottom:1px solid var(--border);}
.ph-close{margin-left:auto;width:18px;height:18px;border-radius:50%;
  display:flex;align-items:center;justify-content:center;cursor:pointer;font-size:9px;
  color:rgba(184,212,255,0.4);background:rgba(255,255,255,.05);transition:all .12s;}
.ph-close:hover{color:var(--danger);background:rgba(255,107,138,0.15);}
.row{display:flex;align-items:center;justify-content:space-between;
  padding:8px 0;border-bottom:1px solid rgba(184,212,255,0.05);gap:10px;}
.row:last-child{border-bottom:none;}
.rl{display:flex;flex-direction:column;gap:2px;}
.rl small{font-size:10px;color:rgba(184,212,255,0.38);}
.toggle{width:36px;height:20px;border-radius:10px;background:var(--cloud);position:relative;
  cursor:pointer;flex-shrink:0;transition:background .2s;border:1px solid var(--border);outline:none;}
.toggle::after{content:'';position:absolute;width:14px;height:14px;
  background:rgba(184,212,255,0.5);border-radius:50%;top:2px;left:2px;transition:all .2s;}
.toggle.on{background:linear-gradient(135deg,var(--merope),var(--alcyone));border-color:var(--alcyone);}
.toggle.on::after{background:#fff;transform:translateX(16px);box-shadow:0 0 6px rgba(77,166,255,0.6);}
.pinput{background:var(--nebula);border:1px solid var(--border);border-radius:7px;
  color:var(--starwhite);font-size:12px;padding:6px 10px;outline:none;
  font-family:'Space Grotesk',sans-serif;transition:border-color .14s;}
.pinput:focus{border-color:var(--alcyone);}
select.pinput{cursor:pointer;}
.paction{width:100%;margin-top:10px;padding:8px;border-radius:9px;border:none;
  background:linear-gradient(135deg,var(--merope),var(--alcyone));
  color:#fff;font-family:'Space Grotesk',sans-serif;font-weight:500;
  cursor:pointer;font-size:12px;box-shadow:0 4px 16px rgba(77,166,255,0.3);transition:opacity .12s;}
.paction:hover{opacity:.88;}
.sec-title{font-size:9px;font-weight:600;letter-spacing:.12em;text-transform:uppercase;
  color:rgba(184,212,255,0.35);padding:10px 0 5px;}
.li{display:flex;align-items:center;gap:8px;padding:7px 0;
  border-bottom:1px solid rgba(184,212,255,0.05);font-size:12px;cursor:pointer;transition:color .12s;}
.li:hover{color:var(--alcyone);}
.li:last-child{border-bottom:none;}
.li-main{flex:1;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;}
.li-sub{font-size:10px;color:rgba(184,212,255,0.35);flex-shrink:0;}
.li-del{font-size:10px;color:rgba(184,212,255,0.25);flex-shrink:0;padding:2px 4px;border-radius:4px;}
.li-del:hover{color:var(--danger);background:rgba(255,107,138,0.1);}
#pw-prompt{position:fixed;bottom:16px;left:50%;transform:translateX(-50%);
  background:rgba(8,12,30,0.97);border:1px solid var(--border2);border-radius:12px;
  padding:12px 16px;z-index:99998;display:none;
  box-shadow:0 8px 32px rgba(0,0,0,0.7);min-width:310px;
  font-size:12px;-webkit-app-region:no-drag;align-items:center;gap:10px;}
.pw-info{flex:1;}
.pw-info strong{display:block;font-size:13px;margin-bottom:2px;}
.pw-info span{color:rgba(184,212,255,0.45);font-size:11px;}
.pw-btn{padding:5px 12px;border-radius:7px;border:none;cursor:pointer;
  font-family:'Space Grotesk',sans-serif;font-size:11px;font-weight:500;}
.pw-save{background:linear-gradient(135deg,var(--merope),var(--alcyone));color:#fff;}
.pw-dismiss{background:rgba(255,255,255,.07);color:rgba(184,212,255,0.6);margin-left:4px;}
#suggestions{position:fixed;z-index:99999;background:rgba(8,12,30,0.99);
  border:1px solid var(--border2);border-radius:9px;box-shadow:0 16px 60px rgba(0,0,0,0.8);
  overflow:hidden;display:none;-webkit-app-region:no-drag;}
.sugg{padding:8px 12px;font-size:12px;cursor:pointer;display:flex;align-items:center;gap:10px;
  border-bottom:1px solid rgba(184,212,255,0.05);transition:background .1s;}
.sugg:last-child{border-bottom:none;}
.sugg:hover{background:rgba(77,166,255,0.1);}
.sugg-icon{font-size:12px;flex-shrink:0;}
.sugg-text{flex:1;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;}
.sugg-type{font-size:10px;color:rgba(184,212,255,0.35);flex-shrink:0;}
.inline-btn{padding:3px 10px;border-radius:6px;border:1px solid var(--border);
  background:transparent;color:var(--atlas);font-size:11px;cursor:pointer;
  font-family:'Space Grotesk',sans-serif;transition:all .12s;}
.inline-btn:hover{background:var(--glow);border-color:var(--alcyone);}
.danger-btn{padding:3px 10px;border-radius:6px;border:1px solid rgba(255,107,138,0.3);
  background:rgba(255,107,138,0.08);color:var(--danger);font-size:11px;cursor:pointer;
  font-family:'Space Grotesk',sans-serif;}
</style>
</head>
<body>
<div id="nav">
  <div id="logo">
    <div id="logo-mark">✦</div>
    <span id="logo-text">RIGEL</span>
  </div>
  <button class="btn" onclick="goBack()"   title="Back (Alt+←)">&#8592;</button>
  <button class="btn" onclick="goFwd()"    title="Forward (Alt+→)">&#8594;</button>
  <button class="btn" onclick="goReload()" title="Reload (Ctrl+R)">&#8635;</button>
  <button class="btn" onclick="goHome()"   title="Home">⌂</button>
  <button class="btn" id="btn-switcher" onclick="openPanel('switcher')" title="Window Switcher — Pleiades (Ctrl+N)" style="font-size:13px;letter-spacing:1px;">⠿</button>
  <div id="url-wrap">
    <span id="lock">🔒</span>
    <input id="urlbar" type="text" spellcheck="false" autocomplete="off"
           placeholder="Search or enter a URL…"
           onkeydown="urlKey(event)" oninput="suggest(this.value)"
           onfocus="this.select()" onblur="hideSuggest()"/>
    <button id="gobtn" onclick="navigate(document.getElementById('urlbar').value)">→</button>
  </div>
  <div id="pills">
    <div class="pill pill-shield" id="pill-shield" onclick="toggleSetting('adblock')" title="Ad blocking">✦ Shields</div>
    <div class="pill pill-proxy off" id="pill-proxy" onclick="openPanel('proxy')" title="Proxy">◈ Proxy</div>
  </div>
  <button class="btn" id="btn-dark"   onclick="toggleSetting('darkmode')"  title="Dark mode (Ctrl+D)">◑</button>
  <button class="btn"                 onclick="doZoom(-10)"                 title="Zoom out">−</button>
  <span   id="zdisplay">100%</span>
  <button class="btn"                 onclick="doZoom(10)"                  title="Zoom in">+</button>
  <button class="btn" id="btn-reader" onclick="doReader()"                  title="Reader mode">≡</button>
  <button class="btn"                 onclick="openPanel('bm')"             title="Bookmarks (Ctrl+B)">✦</button>
  <button class="btn"                 onclick="openPanel('hist')"           title="History (Ctrl+H)">◷</button>
  <button class="btn"                 onclick="openPanel('passwords')"      title="Passwords">🔑</button>
  <button class="btn"                 onclick="openPanel('downloads')"      title="Downloads">⬇</button>
  <button class="btn"                 onclick="openPanel('cookies')"        title="Cookies">🍪</button>
  <button class="btn" id="btn-ctrl"   onclick="openPanel('ctrl')"           title="Controllers">🎮</button>
  <button class="btn" id="btn-ts"     onclick="openPanel('tailscale')"      title="Tailscale VPN">🔵</button>
  <button class="btn"                 onclick="openPanel('settings')"       title="Settings">⊙</button>
</div>
<div id="suggestions"></div>

<!-- Password save prompt -->
<div id="pw-prompt">
  <div class="pw-info">
    <strong id="pw-host"></strong>
    <span id="pw-user"></span>
  </div>
  <button class="pw-btn pw-save"    onclick="savePwPrompt()">Save password</button>
  <button class="pw-btn pw-dismiss" onclick="dismissPwPrompt()">Not now</button>
</div>

<!-- Bookmarks panel -->
<div class="panel" id="panel-bm">
  <div class="ph">✦ Bookmarks <span class="ph-close" onclick="closePanel('bm')">✕</span></div>
  <button style="width:100%;padding:7px;border-radius:8px;border:1px dashed rgba(184,212,255,0.2);
    background:transparent;color:var(--atlas);cursor:pointer;font-family:'Space Grotesk',sans-serif;
    font-size:12px;margin-bottom:10px;transition:all .14s;"
    onmouseover="this.style.background='rgba(77,166,255,0.08)'"
    onmouseout="this.style.background='transparent'"
    onclick="addBookmark()">✦ Bookmark this page</button>
  <div id="bm-list"></div>
</div>

<!-- History panel -->
<div class="panel" id="panel-hist">
  <div class="ph">◷ History
    <button class="danger-btn" style="margin-left:8px;" onclick="clearHistory()">Clear all</button>
    <span class="ph-close" onclick="closePanel('hist')">✕</span>
  </div>
  <div id="hist-list"></div>
</div>

<!-- Passwords panel -->
<div class="panel" id="panel-passwords">
  <div class="ph">🔑 Saved Passwords <span class="ph-close" onclick="closePanel('passwords')">✕</span></div>
  <div id="pw-list"><div style="color:rgba(184,212,255,0.35);font-size:12px;padding:8px 0">Loading…</div></div>
</div>

<!-- Downloads panel -->
<div class="panel" id="panel-downloads">
  <div class="ph">⬇ Downloads
    <button class="inline-btn" style="margin-left:8px;" onclick="window.pywebview.api.open_download_folder()">Open folder</button>
    <span class="ph-close" onclick="closePanel('downloads')">✕</span>
  </div>
  <div id="dl-list"><div style="color:rgba(184,212,255,0.35);font-size:12px;padding:8px 0">No downloads yet.</div></div>
</div>

<!-- Cookies panel -->
<div class="panel" id="panel-cookies">
  <div class="ph">🍪 Cookie Manager <span class="ph-close" onclick="closePanel('cookies')">✕</span></div>
  <div class="row">
    <div class="rl">Current page<small id="ck-host">—</small></div>
    <button class="danger-btn" onclick="clearPageCookies()">Clear cookies</button>
  </div>
  <div id="ck-list" style="margin-top:8px;"></div>
</div>

<!-- Proxy panel -->
<div class="panel" id="panel-proxy">
  <div class="ph">◈ Proxy / Network <span class="ph-close" onclick="closePanel('proxy')">✕</span></div>
  <div class="row">
    <div class="rl">Enable Proxy<small>Route traffic through a proxy</small></div>
    <button class="toggle" id="tog-proxy" onclick="toggleProxy()"></button>
  </div>
  <div class="row">
    <div class="rl">Type</div>
    <select class="pinput" id="proxy-type" style="width:110px">
      <option value="http">HTTP</option><option value="socks5">SOCKS5</option><option value="socks4">SOCKS4</option>
    </select>
  </div>
  <div class="row">
    <div class="rl">Host</div>
    <input class="pinput" id="proxy-host" type="text" placeholder="127.0.0.1" style="width:140px"/>
  </div>
  <div class="row">
    <div class="rl">Port</div>
    <input class="pinput" id="proxy-port" type="text" placeholder="8080" style="width:80px"/>
  </div>
  <button class="paction" onclick="saveProxy()">Apply</button>
</div>

<!-- Controller panel -->
<div class="panel" id="panel-ctrl" style="min-width:340px;">
  <div class="ph">🎮 Controllers
    <button class="inline-btn" style="margin-left:8px;font-size:10px;" onclick="refreshCtrl()">Refresh</button>
    <span class="ph-close" onclick="closePanel('ctrl')">✕</span>
  </div>
  <div id="ctrl-list"><div style="color:rgba(184,212,255,0.35);font-size:12px;padding:10px 0">Checking for controllers…</div></div>
  <div class="sec-title" style="margin-top:8px;">Settings</div>
  <div class="row">
    <div class="rl">Controller HUD<small>Show live axes/buttons overlay on page</small></div>
    <button class="toggle" id="tog-ctrl-hud" onclick="toggleCtrlHUD()"></button>
  </div>
  <div class="row">
    <div class="rl">Gamepad Passthrough<small>Enable for PS Remote Play / Chiaki</small></div>
    <button class="toggle on" id="tog-ctrl-pass"></button>
  </div>
  <div class="sec-title">Supported Controllers</div>
  <div style="font-size:11px;color:rgba(184,212,255,0.4);line-height:1.8;padding:4px 0;">
    🎮 DualSense (PS5) &nbsp;·&nbsp; DualShock 4 (PS4)<br>
    🕹 Joy-Con L/R (Nintendo Switch)<br>
    🎮 Xbox One / Series X|S / 360<br>
    🎮 8BitDo &amp; generic HID gamepads<br>
    <span style="color:rgba(184,212,255,0.25);font-size:10px;">Connect via USB or Bluetooth. Some controllers need DS4Windows/BetterJoy on Windows.</span>
  </div>
</div>

<!-- Tailscale panel -->
<div class="panel" id="panel-tailscale" style="min-width:340px;">
  <div class="ph">🔵 Tailscale
    <button class="inline-btn" style="margin-left:8px;font-size:10px;" onclick="refreshTailscale()">Refresh</button>
    <span class="ph-close" onclick="closePanel('tailscale')">✕</span>
  </div>
  <div id="ts-status" style="font-size:12px;color:rgba(184,212,255,0.4);padding:8px 0 4px">Checking Tailscale status…</div>
  <div id="ts-self" style="display:none;padding:8px 0;border-bottom:1px solid rgba(184,212,255,0.08)">
    <div style="font-size:10px;color:rgba(184,212,255,0.35);text-transform:uppercase;letter-spacing:.08em;margin-bottom:4px">This Device</div>
    <div id="ts-self-name" style="font-size:13px;font-weight:500;color:var(--starwhite)"></div>
    <div id="ts-self-ip" style="font-size:11px;color:var(--atlas);font-family:'Space Mono',monospace;margin-top:2px"></div>
  </div>
  <div class="sec-title" style="margin-top:6px;">Exit Node</div>
  <div class="row">
    <div class="rl">Use Exit Node<small>Route all traffic through a Tailscale peer</small></div>
    <button class="toggle" id="tog-ts-exit" onclick="toggleTsExit()"></button>
  </div>
  <div class="row">
    <div class="rl">Exit Node Host</div>
    <input class="pinput" id="ts-exit-host" type="text" placeholder="hostname or IP" style="width:160px;font-size:11px"/>
  </div>
  <button class="paction" onclick="applyTsExit()">Apply Exit Node</button>
  <div class="sec-title">Tailnet Peers</div>
  <div id="ts-peers"><div style="color:rgba(184,212,255,0.35);font-size:11px;padding:6px 0">Loading peers…</div></div>
  <div class="sec-title">Quick Access</div>
  <div class="row">
    <div class="rl">Tailscale Admin Console</div>
    <button class="inline-btn" onclick="navigate('https://login.tailscale.com/admin/machines')">Open →</button>
  </div>
  <div class="row">
    <div class="rl">Tailscale Web UI<small>Local device manager</small></div>
    <button class="inline-btn" onclick="navigate('http://100.100.100.100')">Open →</button>
  </div>
  <div style="margin-top:10px;font-size:10px;color:rgba(184,212,255,0.2);line-height:1.6;">
    Tailscale must be installed &amp; running on this device.<br>
    <a href="#" onclick="navigate('https://tailscale.com/download');return false;"
      style="color:rgba(77,166,255,0.5);text-decoration:none;">Download Tailscale ↗</a>
  </div>
</div>

<!-- Pleiades Window Switcher panel -->
<div class="panel" id="panel-switcher" style="min-width:520px;max-width:560px;">
  <div class="ph">⠿ Pleiades — Windows
    <span class="ph-close" onclick="closePanel('switcher')">✕</span>
  </div>
  <div id="switcher-grid" style="display:grid;grid-template-columns:repeat(4,1fr);gap:10px;padding:4px 0;"></div>
</div>

<!-- Settings panel -->
<div class="panel" id="panel-settings" style="min-width:340px;">
  <div class="ph">⊙ Settings <span class="ph-close" onclick="closePanel('settings')">✕</span></div>
  <div class="sec-title">Privacy & Security</div>
  <div class="row">
    <div class="rl">Ad & Tracker Blocking<small>Remove ads, trackers, telemetry</small></div>
    <button class="toggle" id="tog-adblock" onclick="toggleSetting('adblock')"></button>
  </div>
  <div class="row">
    <div class="rl">Force HTTPS<small>Upgrade insecure connections</small></div>
    <button class="toggle" id="tog-forcehttps" onclick="toggleSetting('forcehttps')"></button>
  </div>
  <div class="row">
    <div class="rl">Block Fingerprinting<small>Spoof canvas/audio fingerprints</small></div>
    <button class="toggle" id="tog-fingerprint" onclick="toggleSetting('fingerprint')"></button>
  </div>
  <div class="row">
    <div class="rl">Save Passwords<small>Autofill login forms</small></div>
    <button class="toggle" id="tog-passwords" onclick="toggleSetting('passwords')"></button>
  </div>
  <div class="sec-title">Permissions</div>
  <div class="row">
    <div class="rl">Camera Access<small>Allow sites to use your camera</small></div>
    <button class="toggle" id="tog-camera" onclick="toggleSetting('camera')"></button>
  </div>
  <div class="row">
    <div class="rl">Microphone Access<small>Allow sites to use your mic</small></div>
    <button class="toggle" id="tog-microphone" onclick="toggleSetting('microphone')"></button>
  </div>
  <div class="sec-title">Developer</div>
  <div class="row">
    <div class="rl">DevTools (F12)<small>In-page Eruda inspector</small></div>
    <button class="inline-btn" onclick="window.pywebview.api.open_devtools()">Open →</button>
  </div>
  <div class="row">
    <div class="rl">View Page Source<small>Raw HTML of current page</small></div>
    <button class="inline-btn" onclick="window.pywebview.api.view_source()">View →</button>
  </div>
  <div class="sec-title">Appearance</div>
  <div class="row">
    <div class="rl">Dark Mode Injection<small>Force dark theme on any site</small></div>
    <button class="toggle" id="tog-darkmode" onclick="toggleSetting('darkmode')"></button>
  </div>
  <div class="row">
    <div class="rl">Page Zoom</div>
    <div style="display:flex;align-items:center;gap:6px;">
      <button class="btn" onclick="doZoom(-10)">−</button>
      <span id="zdisplay2" style="font-size:11px;color:var(--atlas);min-width:36px;text-align:center;font-family:'Space Mono',monospace">100%</span>
      <button class="btn" onclick="doZoom(10)">+</button>
      <button class="btn" onclick="doZoom(0)" style="font-size:10px">↺</button>
    </div>
  </div>
  <div class="sec-title">Network</div>
  <div class="row">
    <div class="rl">Proxy<small>HTTP, SOCKS4, SOCKS5</small></div>
    <button class="inline-btn" onclick="closePanel('settings');openPanel('proxy')">Configure →</button>
  </div>
  <div class="row">
    <div class="rl">Tailscale VPN<small>Peer-to-peer mesh network</small></div>
    <button class="inline-btn" onclick="closePanel('settings');openPanel('tailscale')">Configure →</button>
  </div>
  <div class="row">
    <div class="rl">Controllers<small>Gamepad / Remote Play support</small></div>
    <button class="inline-btn" onclick="closePanel('settings');openPanel('ctrl')">Configure →</button>
  </div>
  <div class="row">
    <div class="rl">User Agent<small>Override browser identity</small></div>
    <select class="pinput" id="ua-select" onchange="setUA(this.value)" style="width:148px;font-size:11px">
      <option value="default">Default</option>
      <option value="chrome-win">Chrome / Windows</option>
      <option value="firefox-win">Firefox / Windows</option>
      <option value="safari-mac">Safari / macOS</option>
      <option value="mobile-android">Mobile / Android</option>
    </select>
  </div>
  <div class="sec-title">Page Actions</div>
  <div class="row">
    <div class="rl">Print Page<small>Ctrl+P</small></div>
    <button class="inline-btn" onclick="window.pywebview.api.print_page()">Print →</button>
  </div>
  <div class="row">
    <div class="rl">Downloads Folder</div>
    <button class="inline-btn" onclick="window.pywebview.api.open_download_folder()">Open →</button>
  </div>
  <div class="sec-title">About</div>
  <div class="row" style="color:rgba(184,212,255,0.4);font-size:11px;">
    <span>Rigel Browser</span><span style="font-family:'Space Mono',monospace">v3.0.0</span>
  </div>
</div>

<script>
let currentUrl='__home__', currentTitle='Rigel', zoom=100, openPanelId=null;
let settings={adblock:true,darkmode:false,forcehttps:true,javascript:true,
  fingerprint:false,camera:false,microphone:false,passwords:true,
  proxy:{enabled:false,type:'http',host:'',port:''},ua:'default'};
let bookmarks=[], history_=[];
let _pwPending=null;

function ensureHttp(raw){
  raw=(raw||'').trim();
  if(!raw||raw==='__home__') return '__home__';
  if(raw.startsWith('about:')) return raw;
  if(settings.forcehttps&&raw.startsWith('http://')) raw='https://'+raw.slice(7);
  if(/^https?:\/\//.test(raw)) return raw;
  if(/^[a-zA-Z0-9]([a-zA-Z0-9\-]*\.)+[a-zA-Z]{2,}/.test(raw)) return 'https://'+raw;
  return 'https://search.brave.com/search?q='+encodeURIComponent(raw);
}
function favicon(url){try{return 'https://icons.duckduckgo.com/ip3/'+new URL(url).hostname+'.ico';}catch{return '';}}
function trunc(s,n=26){return s&&s.length>n?s.slice(0,n)+'…':s||'Rigel';}
function isHttps(u){return (u||'').startsWith('https://');}
function esc(s){return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');}

function render(){
  document.getElementById('urlbar').value=currentUrl==='__home__'?'':currentUrl;
  document.getElementById('lock').textContent=isHttps(currentUrl)?'🔒':'🔓';
  syncUI();
}

function onContentNav(url,title,tabId){
  currentUrl=url; currentTitle=title||url;
  if(url&&url!=='__home__'&&url!=='about:blank'){
    history_.unshift({url,title:title||url,ts:Date.now()});
    if(history_.length>200) history_.pop();
    window.pywebview.api.save_history(url,title||url);
  }
  render();
}

function navigate(raw){
  const url=ensureHttp(raw);
  currentUrl=url;
  window.pywebview.api.open_url(url,1);
  hideSuggest();
  render();
}

function openNewWindow(url){
  const u=url?ensureHttp(url):undefined;
  window.pywebview.api.open_new_window(u||'');
}
function urlKey(e){
  if(e.key==='Enter'){navigate(e.target.value);hideSuggest();}
  if(e.key==='Escape'){e.target.blur();hideSuggest();}
}
function goBack()  {window.pywebview.api.go_back();}
function goFwd()   {window.pywebview.api.go_forward();}
function goReload(){window.pywebview.api.reload();}
function goHome()  {navigate('__home__');}

function suggest(val){
  val=val.trim();
  const box=document.getElementById('suggestions');
  if(!val||val.length<2){box.style.display='none';return;}
  const ub=document.getElementById('urlbar'),r=ub.getBoundingClientRect();
  box.style.cssText=`display:block;top:${r.bottom+4}px;left:${r.left}px;width:${r.width}px;`;
  const items=[];
  history_.filter(h=>h.url.includes(val)||h.title.toLowerCase().includes(val.toLowerCase())).slice(0,3)
    .forEach(h=>items.push({icon:'◷',text:h.title||h.url,url:h.url,type:'history'}));
  bookmarks.filter(b=>b.url.includes(val)||b.title.toLowerCase().includes(val.toLowerCase())).slice(0,2)
    .forEach(b=>items.push({icon:'✦',text:b.title,url:b.url,type:'bookmark'}));
  items.push({icon:'🔍',text:`Search: ${val}`,url:'https://search.brave.com/search?q='+encodeURIComponent(val),type:'search'});
  box.innerHTML=items.map(it=>`<div class="sugg" onmousedown="navigate(${JSON.stringify(it.url)})">
    <span class="sugg-icon">${it.icon}</span>
    <span class="sugg-text">${esc(it.text)}</span>
    <span class="sugg-type">${it.type}</span></div>`).join('');
}
function hideSuggest(){setTimeout(()=>document.getElementById('suggestions').style.display='none',120);}

function syncUI(){
  ['adblock','darkmode','forcehttps','fingerprint','camera','microphone','passwords'].forEach(k=>{
    const t=document.getElementById('tog-'+k);
    if(t) t.className='toggle'+(settings[k]?' on':'');
  });
  const ps=document.getElementById('pill-shield');
  if(ps) ps.className='pill pill-shield'+(settings.adblock?'':' off');
  const pp=document.getElementById('pill-proxy');
  if(pp) pp.className='pill pill-proxy'+(settings.proxy&&settings.proxy.enabled?'':' off');
  const bd=document.getElementById('btn-dark');
  if(bd) bd.className='btn'+(settings.darkmode?' lit2':'');
  ['zdisplay','zdisplay2'].forEach(id=>{const el=document.getElementById(id);if(el)el.textContent=zoom+'%';});
}
function toggleSetting(k){settings[k]=!settings[k];syncUI();window.pywebview.api.update_settings(JSON.stringify(settings));}
function doZoom(d){zoom=d===0?100:Math.max(40,Math.min(300,zoom+d));syncUI();window.pywebview.api.set_zoom(zoom);}
function setUA(val){settings.ua=val;window.pywebview.api.update_settings(JSON.stringify(settings));}
function doReader(){window.pywebview.api.reader_mode();}

// Bookmarks
function addBookmark(){
  if(!currentUrl||currentUrl==='__home__') return;
  if(!bookmarks.find(b=>b.url===currentUrl)){
    bookmarks.unshift({url:currentUrl,title:currentTitle||currentUrl,ts:Date.now()});
    window.pywebview.api.save_bookmark(currentUrl,currentTitle||currentUrl);
  }
  renderBM();
}
function renderBM(){
  const el=document.getElementById('bm-list');
  el.innerHTML=bookmarks.length?bookmarks.map((b,i)=>`<div class="li">
    <span class="li-main" onclick="navigate(${JSON.stringify(b.url)});closePanel('bm')">${esc(b.title)}</span>
    <span class="li-del" onclick="delBM(${i})">✕</span></div>`).join('')
    :'<div style="color:rgba(184,212,255,0.35);font-size:12px;padding:10px 0">No bookmarks yet.</div>';
}
function delBM(i){if(bookmarks[i])window.pywebview.api.delete_bookmark(bookmarks[i].url);bookmarks.splice(i,1);renderBM();}

// History
function renderHist(){
  const el=document.getElementById('hist-list');
  el.innerHTML=history_.length?history_.slice(0,80).map(h=>`<div class="li">
    <span class="li-main" onclick="navigate(${JSON.stringify(h.url)});closePanel('hist')">${esc(trunc(h.title,44))}</span>
    <span class="li-sub">${new Date(h.ts).toLocaleTimeString()}</span></div>`).join('')
    :'<div style="color:rgba(184,212,255,0.35);font-size:12px;padding:10px 0">No history yet.</div>';
}
function clearHistory(){history_=[];window.pywebview.api.clear_history();renderHist();}

// Passwords
function renderPasswords(){
  window.pywebview.api.list_passwords().then(function(list){
    const el=document.getElementById('pw-list');
    if(!list||!list.length){el.innerHTML='<div style="color:rgba(184,212,255,0.35);font-size:12px;padding:8px 0">No saved passwords.</div>';return;}
    el.innerHTML=list.map(p=>`<div class="li" style="flex-direction:column;align-items:flex-start;gap:3px;cursor:default;">
      <div style="display:flex;width:100%;align-items:center;">
        <span style="flex:1;font-size:12px;font-weight:500">${esc(p.host)}</span>
        <span class="li-del" onclick="delPw(${p.id})">✕</span>
      </div>
      <span style="font-size:11px;color:rgba(184,212,255,0.45)">${esc(p.username)}</span>
    </div>`).join('');
  }).catch(function(){});
}
function delPw(id){window.pywebview.api.delete_password(id).then(function(){renderPasswords();});}

function showPwPrompt(host,username,password){
  _pwPending={host,username,password};
  document.getElementById('pw-host').textContent=host;
  document.getElementById('pw-user').textContent='Username: '+username;
  document.getElementById('pw-prompt').style.display='flex';
}
function savePwPrompt(){
  if(_pwPending) window.pywebview.api.save_password(_pwPending.host,_pwPending.username,_pwPending.password);
  dismissPwPrompt();
}
function dismissPwPrompt(){_pwPending=null;document.getElementById('pw-prompt').style.display='none';}

// Downloads
function renderDownloads(){
  window.pywebview.api.list_downloads().then(function(list){
    const el=document.getElementById('dl-list');
    if(!list||!list.length){el.innerHTML='<div style="color:rgba(184,212,255,0.35);font-size:12px;padding:8px 0">No downloads yet.</div>';return;}
    el.innerHTML=list.map(d=>{
      const done=d.size>0&&d.done>=d.size;
      const pct=d.size>0?Math.round(d.done/d.size*100):d.done>0?100:0;
      return `<div class="li" style="flex-direction:column;align-items:flex-start;gap:4px;cursor:default;">
        <div style="display:flex;width:100%;align-items:center;gap:6px;">
          <span style="flex:1;font-size:11.5px;font-weight:500;overflow:hidden;white-space:nowrap;text-overflow:ellipsis">${esc(d.filename)}</span>
          ${done?`<span style="color:var(--success);font-size:11px">✓ Done</span>`:`<span style="color:var(--atlas);font-size:10px">${pct}%</span>`}
          ${!done?`<span class="li-del" style="color:var(--alcyone)" onclick="window.pywebview.api.resume_download(${d.id})" title="Resume">↺</span>`:''}
        </div>
        ${d.size>0?`<div style="width:100%;height:2px;background:rgba(184,212,255,0.1);border-radius:2px;">
          <div style="width:${pct}%;height:100%;background:linear-gradient(90deg,var(--merope),var(--alcyone));border-radius:2px"></div></div>`:''}
        <span style="font-size:10px;color:rgba(184,212,255,0.3);overflow:hidden;white-space:nowrap;text-overflow:ellipsis;width:100%">${esc(d.path)}</span>
      </div>`;
    }).join('');
  }).catch(function(){});
}

// Cookies
function openCookies(){
  let host='';
  try{host=currentUrl&&currentUrl!=='__home__'?new URL(currentUrl).hostname:'';}catch{}
  document.getElementById('ck-host').textContent=host||'No page loaded';
  window.pywebview.api.get_cookies(host).then(function(cookies){
    const el=document.getElementById('ck-list');
    if(!cookies||!cookies.length){el.innerHTML='<div style="color:rgba(184,212,255,0.35);font-size:11px;padding:6px 0">No readable cookies.</div>';return;}
    el.innerHTML=cookies.map(c=>`<div style="padding:5px 0;border-bottom:1px solid rgba(184,212,255,0.05);">
      <div style="font-size:11.5px;font-weight:500;color:var(--stellar)">${esc(c.name)}</div>
      <div style="font-size:10px;color:rgba(184,212,255,0.35);overflow:hidden;white-space:nowrap;text-overflow:ellipsis">${esc(c.value.slice(0,80))}</div>
    </div>`).join('');
  }).catch(function(){document.getElementById('ck-list').innerHTML='<div style="color:rgba(184,212,255,0.35);font-size:11px;padding:6px 0">Cannot read cookies.</div>';});
}
function clearPageCookies(){window.pywebview.api.clear_cookies();}

// Proxy
function toggleProxy(){
  settings.proxy.enabled=!settings.proxy.enabled;
  const t=document.getElementById('tog-proxy');
  if(t) t.className='toggle'+(settings.proxy.enabled?' on':'');
  syncUI();
}
function saveProxy(){
  settings.proxy={
    enabled:document.getElementById('tog-proxy').classList.contains('on'),
    type:document.getElementById('proxy-type').value,
    host:document.getElementById('proxy-host').value,
    port:document.getElementById('proxy-port').value,
  };
  window.pywebview.api.update_settings(JSON.stringify(settings));
  syncUI();closePanel('proxy');
}

// Panels
function openPanel(id){
  if(openPanelId===id){closeAllPanels();return;}
  closeAllPanels();openPanelId=id;
  const el=document.getElementById('panel-'+id);
  if(el){
    el.classList.add('open');
    if(id==='bm')        renderBM();
    if(id==='hist')      renderHist();
    if(id==='passwords') renderPasswords();
    if(id==='downloads') renderDownloads();
    if(id==='cookies')   openCookies();
    if(id==='ctrl')      refreshCtrl();
    if(id==='tailscale') refreshTailscale();
    if(id==='switcher')  renderSwitcher();
  }
  try{ window.pywebview.api.expand_chrome(); }catch(_){}
}
function closePanel(id){
  const el=document.getElementById('panel-'+id);
  if(el) el.classList.remove('open');
  if(openPanelId===id){ openPanelId=null; try{ window.pywebview.api.collapse_chrome(); }catch(_){} }
}
function closeAllPanels(){
  document.querySelectorAll('.panel').forEach(p=>p.classList.remove('open'));
  openPanelId=null;
  try{ window.pywebview.api.collapse_chrome(); }catch(_){}
}
document.addEventListener('click',e=>{
  if(openPanelId&&!e.target.closest('.panel')&&!e.target.closest('.btn')&&!e.target.closest('.pill'))
    closeAllPanels();
});

document.addEventListener('keydown',e=>{
  const m=e.ctrlKey||e.metaKey;
  if(m&&e.key==='n'){e.preventDefault();openPanel('switcher');}
  if(m&&e.key==='l'){e.preventDefault();const u=document.getElementById('urlbar');u.focus();u.select();}
  if(m&&e.key==='r'){e.preventDefault();goReload();}
  if(m&&e.key==='d'){e.preventDefault();toggleSetting('darkmode');}
  if(m&&e.key==='b'){e.preventDefault();openPanel('bm');}
  if(m&&e.key==='h'){e.preventDefault();openPanel('hist');}
  if(m&&e.key==='p'){e.preventDefault();window.pywebview.api.print_page();}
  if(m&&e.key==='='){e.preventDefault();doZoom(10);}
  if(m&&e.key==='-'){e.preventDefault();doZoom(-10);}
  if(m&&e.key==='0'){e.preventDefault();doZoom(0);}
  if(e.key==='F5'){e.preventDefault();goReload();}
  if(e.key==='F12'){e.preventDefault();window.pywebview.api.open_devtools();}
  if(e.altKey&&e.key==='ArrowLeft'){e.preventDefault();goBack();}
  if(e.altKey&&e.key==='ArrowRight'){e.preventDefault();goFwd();}
});

// ── Controller support ────────────────────────────────────────────────────────
var ctrlHUDEnabled = false;
var ctrlState = {connected:[]};

function refreshCtrl(){
  window.pywebview.api.get_controllers().then(function(list){
    ctrlState.connected = list||[];
    updateCtrlBtn();
    renderCtrlPanel();
  }).catch(function(){ renderCtrlPanel(); });
}
function toggleCtrlHUD(){
  ctrlHUDEnabled = !ctrlHUDEnabled;
  var t = document.getElementById('tog-ctrl-hud');
  if(t) t.className='toggle'+(ctrlHUDEnabled?' on':'');
  window.pywebview.api.set_ctrl_hud(ctrlHUDEnabled);
}
function onCtrlConnected(id, type){
  if(!ctrlState.connected.find(function(c){return c.id===id;}))
    ctrlState.connected.push({id:id,type:type});
  updateCtrlBtn(); renderCtrlPanel();
}
function onCtrlDisconnected(id){
  ctrlState.connected = ctrlState.connected.filter(function(c){return c.id!==id;});
  updateCtrlBtn(); renderCtrlPanel();
}
function updateCtrlBtn(){
  var btn = document.getElementById('btn-ctrl');
  if(!btn) return;
  btn.style.color = ctrlState.connected.length ? '#6dffb8' : '';
  btn.title = ctrlState.connected.length
    ? ctrlState.connected.length+' controller(s) connected' : 'Controllers';
}
function renderCtrlPanel(){
  var el = document.getElementById('ctrl-list');
  if(!el) return;
  if(!ctrlState.connected.length){
    el.innerHTML='<div style="color:rgba(184,212,255,0.35);font-size:12px;padding:10px 0">'
      +'No controllers detected.<br><small style="font-size:10px;color:rgba(184,212,255,0.25)">'
      +'Connect a DualSense, DualShock, Joy-Con, or Xbox controller via USB or Bluetooth.<br>'
      +'On Windows you may need DS4Windows or BetterJoy for full support.</small></div>';
    return;
  }
  var icons={dualsense:'🎮',dualshock:'🎮','joycon-l':'🕹','joycon-r':'🕹',joycon:'🕹',xbox:'🎮','8bitdo':'🎮',generic:'🕹'};
  el.innerHTML = ctrlState.connected.map(function(c){
    var icon=icons[c.type]||'🕹';
    return '<div class="li" style="cursor:default;gap:10px">'
      +'<span style="font-size:18px">'+icon+'</span>'
      +'<div class="rl"><span style="font-size:11.5px;font-weight:500">'+c.type.toUpperCase()+'</span>'
      +'<small style="font-size:10px;color:rgba(184,212,255,0.3)">'+(c.id||'').split('(')[0].trim().slice(0,40)+'</small></div>'
      +'<span style="margin-left:auto;font-size:10px;color:#6dffb8">● Live</span>'
      +'</div>';
  }).join('');
}

// ── Tailscale ────────────────────────────────────────────────────────────────
var tsExitEnabled = false;

function refreshTailscale(){
  var statusEl = document.getElementById('ts-status');
  var selfEl   = document.getElementById('ts-self');
  var peersEl  = document.getElementById('ts-peers');
  if(statusEl) statusEl.textContent = 'Checking Tailscale status…';
  if(peersEl)  peersEl.innerHTML = '<div style="color:rgba(184,212,255,0.35);font-size:11px;padding:6px 0">Loading…</div>';
  window.pywebview.api.tailscale_status().then(function(data){
    if(!data||data.error){
      if(statusEl) statusEl.innerHTML='<span style="color:#ff6b8a">⚠ '+esc((data&&data.error)||'Tailscale not running or not installed')+'</span>'
        +'<br><span style="font-size:10px;color:rgba(184,212,255,0.3)">Install Tailscale and run `tailscale up` to connect.</span>';
      if(selfEl)  selfEl.style.display='none';
      if(peersEl) peersEl.innerHTML='<div style="color:rgba(184,212,255,0.35);font-size:11px;padding:6px 0">—</div>';
      document.getElementById('btn-ts').style.color='';
      return;
    }
    var stateColor={'Running':'#6dffb8','Starting':'#a8d8ff','Stopped':'#ff6b8a'}[data.backendState]||'#a8d8ff';
    if(statusEl) statusEl.innerHTML='<span style="color:'+stateColor+';font-weight:500">● '+esc(data.backendState||'Unknown')+'</span>'
      +(data.currentExitNode?'<br><span style="font-size:10px;color:rgba(184,212,255,0.4)">Exit node: '+esc(data.currentExitNode)+'</span>':'');
    document.getElementById('btn-ts').style.color = data.backendState==='Running' ? '#6dffb8' : '';
    if(data.self&&selfEl){
      selfEl.style.display='block';
      document.getElementById('ts-self-name').textContent=data.self.HostName||data.self.DNSName||'';
      document.getElementById('ts-self-ip').textContent=(data.self.TailscaleIPs||[]).join('  ');
    }
    if(peersEl){
      var peers=data.peers||[];
      if(!peers.length){peersEl.innerHTML='<div style="color:rgba(184,212,255,0.35);font-size:11px;padding:6px 0">No peers on tailnet.</div>';return;}
      peersEl.innerHTML=peers.map(function(p){
        var online=p.Online||p.Active;
        var ips=(p.TailscaleIPs||[]).slice(0,1).join('');
        return '<div class="li" style="cursor:default;gap:8px">'
          +'<span style="font-size:10px;color:'+(online?'#6dffb8':'rgba(184,212,255,0.25)')+'">'+(online?'●':'○')+'</span>'
          +'<div class="rl" style="min-width:0"><span style="font-size:11.5px;font-weight:500;overflow:hidden;white-space:nowrap;text-overflow:ellipsis">'+esc(p.HostName||p.DNSName||'Unknown')+'</span>'
          +'<small style="font-size:10px;color:rgba(184,212,255,0.3)">'+esc(ips)+'</small></div>'
          +(p.ExitNodeOption?'<span style="font-size:9px;color:var(--atlas);border:1px solid rgba(77,166,255,0.3);padding:1px 5px;border-radius:4px;flex-shrink:0">Exit</span>':'')
          +(online&&ips?'<span class="inline-btn" style="font-size:9px;flex-shrink:0" onclick="navigate(\'http://'+esc(ips)+'\')">Open →</span>':'')
          +'</div>';
      }).join('');
    }
  }).catch(function(e){
    if(statusEl) statusEl.innerHTML='<span style="color:#ff6b8a">⚠ Error: '+(e&&e.message||'Unknown error')+'</span>';
  });
}
function toggleTsExit(){
  tsExitEnabled = !tsExitEnabled;
  var t=document.getElementById('tog-ts-exit');
  if(t) t.className='toggle'+(tsExitEnabled?' on':'');
}
function applyTsExit(){
  var host=document.getElementById('ts-exit-host').value.trim();
  window.pywebview.api.tailscale_set_exit_node(tsExitEnabled?host:'').then(function(res){
    var ok=res&&res.ok;
    var statusEl=document.getElementById('ts-status');
    if(ok){
      if(statusEl&&tsExitEnabled) statusEl.innerHTML=(statusEl.innerHTML||'')
        +'<br><span style="font-size:10px;color:#6dffb8">✓ Exit node set to '+esc(host)+'</span>';
    } else {
      alert('Failed to set exit node: '+(res&&res.error||'Unknown error'));
    }
    setTimeout(refreshTailscale, 600);
  }).catch(function(e){ alert('Error: '+(e&&e.message||e)); });
}

// ── Pleiades window switcher ─────────────────────────────────────────────────
var _starState = [];

function onStarState(state){
  _starState = state;
  if(document.getElementById('panel-switcher').classList.contains('open'))
    renderSwitcher();
  var active = state.find(function(s){return s.active;});
  var btn = document.getElementById('btn-switcher');
  if(btn) btn.title = 'Windows — ' + (active ? active.name : 'Pleiades') + ' (Ctrl+N)';
}

function renderSwitcher(){
  var grid = document.getElementById('switcher-grid');
  if(!grid) return;
  grid.innerHTML = _starState.map(function(s){
    var isHome = !s.url || s.url === '__home__' || s.url === 'about:blank';
    var host = '';
    try{ host = isHome ? '' : new URL(s.url).hostname; }catch(_){}
    var fav = host ? '<img src="https://icons.duckduckgo.com/ip3/'+host+'.ico" style="width:14px;height:14px;border-radius:3px;flex-shrink:0" onerror="this.style.display=\'none\'">' : '<span style="font-size:14px">'+s.glyph+'</span>';
    var activeStyle = s.active
      ? 'border-color:var(--alcyone);background:rgba(77,166,255,0.1);box-shadow:0 0 12px rgba(77,166,255,0.2);'
      : '';
    return '<div onclick="pickStar(\''+s.name+'\')" style="cursor:pointer;padding:10px 8px;border-radius:10px;'
      +'border:1px solid var(--border);background:rgba(10,15,42,0.6);transition:all .16s;'+activeStyle
      +'display:flex;flex-direction:column;gap:6px;min-height:80px;position:relative;"'
      +' onmouseenter="this.style.background=\'rgba(77,166,255,0.08)\';this.style.borderColor=\'rgba(77,166,255,0.4)\'"'
      +' onmouseleave="this.style.background=\''+(s.active?'rgba(77,166,255,0.1)':'rgba(10,15,42,0.6)')+'\';this.style.borderColor=\''+(s.active?'var(--alcyone)':'var(--border)')+'\'">  '
      +(s.active?'<span style="position:absolute;top:6px;right:7px;font-size:8px;color:var(--alcyone);font-weight:600;letter-spacing:.06em">ACTIVE</span>':'')
      +'<div style="display:flex;align-items:center;gap:5px;">'+fav
      +'<span style="font-size:10px;font-weight:600;color:'+(s.active?'var(--alcyone)':'var(--stellar)')+';letter-spacing:.04em">'+s.name.toUpperCase()+'</span></div>'
      +'<div style="font-size:10px;color:rgba(184,212,255,0.45);overflow:hidden;white-space:nowrap;text-overflow:ellipsis;">'
      +(isHome ? '<span style="color:rgba(184,212,255,0.25)">— home —</span>' : esc(s.title||host))
      +'</div>'
      +(host?'<div style="font-size:9px;color:rgba(184,212,255,0.3);font-family:\'Space Mono\',monospace;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;">'+esc(host)+'</div>':'')
      +'</div>';
  }).join('');
}
function pickStar(name){
  window.pywebview.api.switch_star(name);
  closePanel('switcher');
}
function openNewStarWindow(){
  window.pywebview.api.open_new_window('');
  closePanel('switcher');
}

function loadPersistedData(){
  window.pywebview.api.load_bookmarks().then(bms=>{bookmarks=bms||[];}).catch(()=>{});
  window.pywebview.api.load_history().then(hist=>{
    history_=(hist||[]).map(h=>({url:h.url,title:h.title,ts:h.visited*1000}));
  }).catch(()=>{});
}

window.addEventListener('pywebviewready',()=>{loadPersistedData();navigate('__home__');syncUI();});
</script>
</body>
</html>
"""
# ── Aliases expected by api.py ─────────────────────────────────────────────────
from home_html import HOME_HTML
try:
    from js_snippets import pdf_viewer_html
except ImportError:
    def pdf_viewer_html(url): return f'<html><body><embed src="{url}" width="100%" height="100%"></body></html>'