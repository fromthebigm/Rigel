"""
js_snippets.py — JavaScript strings injected into content pages by Rigel Browser
"""

import urllib.parse


# ── Content-page injections ────────────────────────────────────────────────────

ADBLOCK_JS = """
(function(){
  if(window.__rigel_adblock) return; window.__rigel_adblock=true;
  const BAD=['doubleclick','googlesyndication','adnxs','moatads','amazon-adsystem',
    'scorecardresearch','quantserve','chartbeat','outbrain','taboola','adsafeprotected',
    'rubiconproject','openx','pubmatic','criteo','tracking','tracker','analytics',
    'pixel','telemetry','beacon','statsig','segment.io','mixpanel','amplitude',
    'hotjar','fullstory','logrocket','newrelic'];
  function kill(el){
    const src=(el.src||el.href||'').toLowerCase(),cls=(el.className||'').toLowerCase(),id=(el.id||'').toLowerCase();
    if(BAD.some(b=>(src+cls+id).includes(b))){el.remove();return true;}return false;
  }
  document.querySelectorAll('script,iframe,img,link,div[id*="ad"],div[class*="ad-"]').forEach(kill);
  new MutationObserver(ms=>ms.forEach(m=>m.addedNodes.forEach(n=>{
    if(n.nodeType===1){kill(n);n.querySelectorAll&&n.querySelectorAll('script,iframe').forEach(kill);}
  }))).observe(document.documentElement,{childList:true,subtree:true});
})();
"""

POPUP_BLOCKER_JS = """
(function(){
  if(window.__rigel_popblock) return; window.__rigel_popblock=true;
  // Block window.open() calls to ad/tracking domains; allow auth flows
  var AUTH=['accounts.google','appleid.apple','apple.com','facebook.com/dialog',
    'facebook.com/login','connect.facebook','tiktok.com','twitter.com/i/oauth',
    'github.com/login','microsoft','live.com','discord.com/oauth','oauth','auth',
    'login','signin','sso','recaptcha','hcaptcha','cloudflare'];
  var _origOpen = window.open.bind(window);
  window.open = function(url, target, features){
    if(!url||url==='about:blank') return null;
    var isAuth = AUTH.some(function(d){return (url||'').toLowerCase().includes(d);});
    if(isAuth) return _origOpen(url, target, features);
    // Non-auth window.open → open as new Rigel window
    try{
      window.pywebview && window.pywebview.api &&
      window.pywebview.api.open_url_new_window(url);
    }catch(_){}
    return {closed:false,location:{href:url},focus:function(){},close:function(){}};
  };
})();
"""

DARKMODE_JS = """
(function(){
  if(document.getElementById('__rigel_dm')) return;
  const s=document.createElement('style'); s.id='__rigel_dm';
  s.textContent='html{filter:invert(1) hue-rotate(180deg)!important;}img,video,canvas,svg,picture{filter:invert(1) hue-rotate(180deg)!important;}';
  document.head.appendChild(s);
})();
"""

READER_JS = """
(function(){
  const a=document.querySelector('article')||document.querySelector('[role="main"]')||document.querySelector('main')||document.body;
  const text=a?a.innerText:document.body.innerText, title=document.title;
  document.open();
  document.write(`<!DOCTYPE html><html><head><meta charset="UTF-8"><title>${title}</title>
  <style>body{max-width:740px;margin:70px auto;font-family:Georgia,serif;font-size:19px;line-height:1.85;
  color:#1a1a2e;background:#f9f9f7;padding:0 28px}h1{font-size:2em;margin-bottom:.6em;line-height:1.25}</style>
  </head><body><h1>${title}</h1><pre style="white-space:pre-wrap;font-family:inherit">${text.replace(/</g,'&lt;')}</pre>
  </body></html>`);
  document.close();
})();
"""

FIX_BLANK = """
(function(){
  if(window.__rigel_blank) return; window.__rigel_blank=true;
  // Auth/OAuth domains that MUST open in a popup — never intercept these
  var AUTH=['accounts.google','appleid.apple','apple.com','facebook.com/dialog',
    'facebook.com/login','connect.facebook','tiktok.com','twitter.com/i/oauth',
    'github.com/login','microsoft','live.com','discord.com/oauth','oauth','auth',
    'login','signin','sso','recaptcha','hcaptcha','cloudflare'];
  function isAuth(href){
    href=(href||'').toLowerCase();
    return AUTH.some(function(d){return href.includes(d);});
  }
  // Intercept _blank links: open in a new Rigel window instead
  function patchLink(a){
    if(a.__rigel_patched) return; a.__rigel_patched=true;
    if(a.target!=='_blank') return;
    if(isAuth(a.href)) return; // let auth popups through normally
    a.removeAttribute('target');
    a.addEventListener('click',function(e){
      e.preventDefault();
      try{ window.pywebview && window.pywebview.api &&
        window.pywebview.api.open_url_new_window(a.href); }catch(_){}
    });
  }
  document.querySelectorAll('a[target="_blank"]').forEach(patchLink);
  new MutationObserver(function(ms){
    ms.forEach(function(m){
      m.addedNodes.forEach(function(n){
        if(n.nodeType!==1) return;
        var as=n.tagName==='A'?[n]:(n.querySelectorAll?Array.from(n.querySelectorAll('a[target="_blank"]')):[]);
        as.forEach(patchLink);
      });
    });
  }).observe(document.documentElement,{childList:true,subtree:true});
})();
"""

DOWNLOAD_JS = r"""
(function(){
  if(window.__rigel_dl) return; window.__rigel_dl=true;
  var EXTS=/\.(zip|gz|tar|rar|7z|dmg|pkg|exe|msi|deb|rpm|apk|ipa|mp4|mp3|mov|avi|mkv|webm|iso|bin|img|xlsx|docx|pptx|csv|woff|woff2|ttf|otf|sh|bat|ps1)$/i;
  document.addEventListener('click', function(e){
    var a=e.target.closest('a');
    if(!a) return;
    var href=a.href||'';
    var hasDl=a.hasAttribute('download');
    var isFile=EXTS.test(href.split('?')[0]);
    if(hasDl||isFile){
      e.preventDefault(); e.stopPropagation();
      var name=a.getAttribute('download')||href.split('?')[0].split('/').pop()||'download';
      window.pywebview.api.handle_download(href, name);
    }
  }, true);
})();
"""

AUTOFILL_JS = """
(function(){
  if(window.__rigel_autofill) return; window.__rigel_autofill=true;
  function tryFill(){
    document.querySelectorAll('form').forEach(function(form){
      var pw=form.querySelector('input[type="password"]');
      if(!pw||pw.__rigel_w) return;
      pw.__rigel_w=true;
      var user=form.querySelector('input[type="email"],input[type="text"],input[name*="user"],input[name*="email"],input[id*="user"],input[id*="email"],input[autocomplete*="username"],input[autocomplete*="email"]');
      window.pywebview.api.get_credentials(location.hostname).then(function(creds){
        if(!creds||!creds.length) return;
        var c=creds[0];
        if(user){user.value=c.username;user.dispatchEvent(new Event('input',{bubbles:true}));}
        pw.value=c.password; pw.dispatchEvent(new Event('input',{bubbles:true}));
      }).catch(function(){});
      form.addEventListener('submit',function(){
        var u=user?user.value:'', p=pw.value;
        if(u&&p) window.pywebview.api.offer_save_password(location.hostname,u,p);
      });
    });
  }
  tryFill();
  new MutationObserver(tryFill).observe(document.body||document.documentElement,{childList:true,subtree:true});
})();
"""

CONTEXT_MENU_JS = """
(function(){
  if(window.__rigel_ctx) return; window.__rigel_ctx=true;
  var menu=document.createElement('div');
  menu.id='__rigel_ctx';
  menu.style.cssText='position:fixed;z-index:2147483647;background:#0a0f2a;border:1px solid rgba(184,212,255,0.2);'
    +'border-radius:10px;padding:4px;min-width:185px;box-shadow:0 16px 48px rgba(0,0,0,0.85);display:none;'
    +'font-family:Space Grotesk,system-ui,sans-serif;font-size:12.5px;color:#e8f0ff;user-select:none;';
  document.body.appendChild(menu);

  function item(label,icon,fn){
    var el=document.createElement('div');
    el.style.cssText='padding:7px 12px;border-radius:7px;cursor:pointer;display:flex;align-items:center;gap:9px;';
    el.innerHTML='<span style="font-size:13px;width:16px;text-align:center;flex-shrink:0">'+icon+'</span>'+label;
    el.onmouseenter=function(){el.style.background='rgba(77,166,255,0.15)';};
    el.onmouseleave=function(){el.style.background='';};
    el.onmousedown=function(e){e.preventDefault();hide();fn();};
    return el;
  }
  function sep(){var s=document.createElement('div');s.style.cssText='height:1px;background:rgba(184,212,255,0.08);margin:3px 6px;';return s;}
  function hide(){menu.style.display='none';}

  document.addEventListener('contextmenu',function(e){
    e.preventDefault();
    var t=e.target;
    menu.innerHTML='';
    var link=t.closest('a'), isImg=t.tagName==='IMG', isEditable=t.isContentEditable||t.tagName==='INPUT'||t.tagName==='TEXTAREA';
    var sel=window.getSelection().toString().trim();

    if(link){
      menu.appendChild(item('Open in new window','🔗',function(){window.pywebview.api.open_url_new_window(link.href);}));
      menu.appendChild(item('Copy link address','📋',function(){navigator.clipboard&&navigator.clipboard.writeText(link.href);}));
      menu.appendChild(sep());
    }
    if(isImg){
      menu.appendChild(item('Save image','💾',function(){window.pywebview.api.handle_download(t.src,t.src.split('/').pop()||'image.png');}));
      menu.appendChild(item('Copy image URL','📋',function(){navigator.clipboard&&navigator.clipboard.writeText(t.src);}));
      menu.appendChild(sep());
    }
    if(sel){
      menu.appendChild(item('Copy','📋',function(){navigator.clipboard&&navigator.clipboard.writeText(sel);}));
      var q=sel.length>28?sel.slice(0,28)+'…':sel;
      menu.appendChild(item('Search "'+q+'"','🔍',function(){window.pywebview.api.open_url_new_window('https://search.brave.com/search?q='+encodeURIComponent(sel));}));
      menu.appendChild(sep());
    }
    if(isEditable&&!sel){
      menu.appendChild(item('Cut','✂',function(){document.execCommand('cut');}));
      menu.appendChild(item('Copy','📋',function(){document.execCommand('copy');}));
      menu.appendChild(item('Paste','📄',function(){document.execCommand('paste');}));
      menu.appendChild(sep());
    }
    menu.appendChild(item('Back','←',function(){history.back();}));
    menu.appendChild(item('Forward','→',function(){history.forward();}));
    menu.appendChild(item('Reload','↺',function(){location.reload();}));
    menu.appendChild(sep());
    menu.appendChild(item('View page source','📝',function(){window.pywebview.api.view_source();}));
    menu.appendChild(item('Print page','🖨',function(){window.print();}));

    var x=e.clientX,y=e.clientY;
    menu.style.display='block';
    var mw=menu.offsetWidth,mh=menu.offsetHeight;
    if(x+mw>innerWidth) x=innerWidth-mw-8;
    if(y+mh>innerHeight) y=innerHeight-mh-8;
    menu.style.left=x+'px';menu.style.top=y+'px';
  });
  document.addEventListener('click',hide);
  document.addEventListener('keydown',function(e){if(e.key==='Escape')hide();});
})();
"""

CONTROLLER_JS = r"""
(function(){
  if(window.__rigel_ctrl) return; window.__rigel_ctrl = true;

  // ── Detect controller type by vendor/product ID or name ──
  function detectType(gp){
    var id=(gp.id||'').toLowerCase();
    if(/054c.*(0ce6|0df2|09cc|05c4)/i.test(gp.id)||/dualsense|dualshock|playstation/i.test(id))
      return id.includes('0ce6')||id.includes('0df2')||id.includes('dualsense')?'dualsense':'dualshock';
    if(/057e.*(2006|2007|2009)/i.test(gp.id)||/joy-con|joycon/i.test(id)){
      if(/2006/i.test(gp.id)) return 'joycon-l';
      if(/2007/i.test(gp.id)) return 'joycon-r';
      return 'joycon';
    }
    if(/045e|xbox/i.test(id)) return 'xbox';
    if(/8bitdo/i.test(id)) return '8bitdo';
    return 'generic';
  }

  var ICONS = {
    dualsense:'🎮', dualshock:'🎮', 'joycon-l':'🕹', 'joycon-r':'🕹',
    joycon:'🕹', xbox:'🎮', '8bitdo':'🎮', generic:'🕹'
  };

  // ── HUD overlay ──
  var hud = document.createElement('div');
  hud.id = '__rigel_ctrl_hud';
  hud.style.cssText = 'position:fixed;bottom:12px;right:12px;z-index:2147483646;'
    +'display:none;flex-direction:column;gap:4px;font-family:monospace;font-size:11px;'
    +'pointer-events:none;';
  document.body.appendChild(hud);

  function updateHUD(){
    var gpList = navigator.getGamepads ? Array.from(navigator.getGamepads()).filter(Boolean) : [];
    if(!gpList.length){ hud.style.display='none'; return; }
    hud.style.display='flex';
    hud.innerHTML = gpList.map(function(gp){
      var type = detectType(gp);
      var axes = gp.axes.map(function(a){ return (a>=0?'+':'')+a.toFixed(2); }).join(' ');
      var btns = gp.buttons.map(function(b,i){ return b.pressed?i:''; }).filter(String).join(',');
      return '<div style="background:rgba(8,12,30,0.88);border:1px solid rgba(77,166,255,0.3);'
        +'border-radius:8px;padding:5px 10px;color:#a8d8ff;backdrop-filter:blur(8px);">'
        +ICONS[type]+' <b style="color:#e8f0ff">'+gp.id.split('(')[0].trim().slice(0,28)+'</b><br>'
        +'Axes: '+axes+'<br>'
        +(btns?'Btns: '+btns:'')
        +'</div>';
    }).join('');
  }

  // ── Poll loop (60fps) ──
  var _raf;
  function poll(){
    updateHUD();
    _raf = requestAnimationFrame(poll);
  }

  window.addEventListener('gamepadconnected', function(e){
    console.log('[Rigel] Controller connected:', e.gamepad.id);
    poll();
    try{ window.pywebview && window.pywebview.api &&
      window.pywebview.api.controller_connected(e.gamepad.id, detectType(e.gamepad)); }catch(_){}
  });

  window.addEventListener('gamepaddisconnected', function(e){
    console.log('[Rigel] Controller disconnected:', e.gamepad.id);
    cancelAnimationFrame(_raf);
    updateHUD();
    try{ window.pywebview && window.pywebview.api &&
      window.pywebview.api.controller_disconnected(e.gamepad.id); }catch(_){}
  });

  if(navigator.getGamepads){
    var _origGGP = navigator.getGamepads.bind(navigator);
    Object.defineProperty(navigator,'getGamepads',{
      value: function(){ return _origGGP(); },
      writable: false, configurable: true
    });
  }

  window.addEventListener('gamepadconnected', function(e){
    document.dispatchEvent(new GamepadEvent('gamepadconnected',{gamepad:e.gamepad}));
  });
  window.addEventListener('gamepaddisconnected', function(e){
    document.dispatchEvent(new GamepadEvent('gamepaddisconnected',{gamepad:e.gamepad}));
  });

  if(navigator.getGamepads && Array.from(navigator.getGamepads()).some(Boolean)) poll();
})();
"""

POPUP_BRIDGE_JS = """
(function(){
  if(window.__rigel_popup) return; window.__rigel_popup=true;
  var _orig = window.open.bind(window);
  window.open = function(url, target, features){
    if(!url||url==='about:blank') return _orig(url,target,features)||{};
    var w = null;
    try{ w = _orig(url, target, features); }catch(_){}
    if(!w||w.closed){
      try{
        window.pywebview && window.pywebview.api &&
        window.pywebview.api.open_popup(url);
      }catch(_){}
      return {closed:false,location:{href:url},focus:function(){},close:function(){}};
    }
    return w;
  };
})();
"""


# ── Utility ────────────────────────────────────────────────────────────────────

def pdf_viewer_html(url: str) -> str:
    encoded = urllib.parse.quote(url, safe='')
    return (
        "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>PDF</title>"
        "<style>*{margin:0;padding:0;box-sizing:border-box;}body{background:#1a1a1a;height:100vh;display:flex;flex-direction:column;}"
        "iframe{flex:1;border:none;width:100%;height:100%;}</style></head>"
        f"<body><iframe src='https://mozilla.github.io/pdf.js/web/viewer.html?file={encoded}'></iframe></body></html>"
    )
# ── Aliases / extras expected by api.py ───────────────────────────────────────
from constants import KEEPALIVE_JS