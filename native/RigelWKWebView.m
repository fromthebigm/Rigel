#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>
#import <Network/Network.h>
#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

typedef void (*RigelWKEventCallback)(uint64_t token, int eventType,
                                     const char *value1, const char *value2);
typedef void (*RigelWKScriptCallback)(uint64_t token, const char *name,
                                      const char *payload);
typedef void (*RigelWKEvalCallback)(uint64_t evalToken, const char *value,
                                    const char *error);
typedef int (*RigelWKPolicyCallback)(uint64_t token, const char *url);

enum {
    RIGEL_WK_EVENT_LOAD_STARTED = 1,
    RIGEL_WK_EVENT_LOAD_FINISHED = 2,
    RIGEL_WK_EVENT_LOAD_FAILED = 3,
    RIGEL_WK_EVENT_LOCATION = 4,
    RIGEL_WK_EVENT_CLOSED = 5,
    RIGEL_WK_EVENT_PROCESS_RESTARTED = 6
};

enum {
    RIGEL_WK_EDIT_CUT = 1,
    RIGEL_WK_EDIT_COPY = 2,
    RIGEL_WK_EDIT_PASTE = 3,
    RIGEL_WK_EDIT_SELECT_ALL = 4
};

enum {
    RIGEL_WK_PROXY_DIRECT = 0,
    RIGEL_WK_PROXY_HTTP = 1,
    RIGEL_WK_PROXY_SOCKS5 = 2
};

/* ABI-compatible with WebKit's private _WKSOAuthorizationLoadPolicy. */
enum {
    RIGEL_WK_SO_AUTHORIZATION_ALLOW = 0,
    RIGEL_WK_SO_AUTHORIZATION_IGNORE = 1
};

static const char *Utf8(NSString *value) {
    return value == nil ? "" : value.UTF8String;
}

static NSString *EncodeMessagePart(NSString *value) {
    if (value == nil) return @"";
    NSCharacterSet *allowed = [NSCharacterSet alphanumericCharacterSet];
    return [value stringByAddingPercentEncodingWithAllowedCharacters:allowed] ?: @"";
}

static BOOL DiagnosticCaptureEnabled(void) {
    const char *value = getenv("RIGEL_DIAGNOSTIC_CAPTURE");
    return value != NULL && (!strcmp(value, "1") || !strcasecmp(value, "true"));
}

static void DisableExtensibleSSO(WKPreferences *preferences) {
    if (preferences == nil) return;

    /*
     * A command-line Java host has no stable application bundle for AuthKit's
     * native AppSSO sheet. When WebKit hands Apple Music login to that sheet,
     * the parent SWT window can remain main but non-key and neither the sheet
     * nor the browser accepts input. WebKit provides this guarded preference
     * so embedders can opt out and use the site's normal web authentication
     * flow instead.
     */
    SEL selector = NSSelectorFromString(@"_setExtensibleSSOEnabled:");
    if (![preferences respondsToSelector:selector]) return;
    IMP implementation = [preferences methodForSelector:selector];
    if (implementation != NULL)
        ((void (*)(id, SEL, BOOL))implementation)(preferences, selector, NO);
}

static NSString *BridgeScript(void) {
    return @"(function(){"
        "if(window.__rigelNativeBridge)return;"
        "window.__rigelNativeBridge=1;"
        "window.__rigelPreferNativeFullscreen=true;"
        "window.__rigelLastRealArrowKeyTime=0;"
        "var h=window.webkit&&window.webkit.messageHandlers||{},T=0;"
        "try{T=top===window}catch(e){T=1}"
        "function q(v){try{return encodeURIComponent(v==null?'':String(v))}catch(e){return''}}"
        "function s(n,a){try{h[n]&&h[n].postMessage([].map.call(a,q).join('|'));return true}catch(e){return false}}"
        "function u(){try{return !navigator.userActivation||navigator.userActivation.isActive}catch(e){return false}}"
        "function f(n,a){if(T)return null;try{var v=top[n];return typeof v==='function'?v.apply(top,a):false}catch(e){return false}}"
        "window.javaEnterFullscreen=function(){if(!u())return false;var r=f('javaEnterFullscreen',arguments);return r!==null?r!==false:s('javaEnterFullscreen',arguments)};"
        "window.javaExitFullscreen=function(){var r=f('javaExitFullscreen',arguments);return r!==null?r!==false:s('javaExitFullscreen',arguments)};"
        "window.rigelHomeNavigate=function(){return s('rigelHomeNavigate',arguments)};"
        "window.rigelClipboardWrite=function(){if(!u())return false;var r=f('rigelClipboardWrite',arguments);return r!==null?r!==false:s('rigelClipboardWrite',arguments)};"
        "window.rigelDownload=function(){if(!u())return false;var r=f('rigelDownload',arguments);return r!==null?r!==false:s('rigelDownload',arguments)};"
        "var k='';"
        "function r(){try{if(!T||!h.rigelEvent)return;var n=location.href+'\\n'+document.title;if(n===k)return;k=n;h.rigelEvent.postMessage(q(location.href)+'|'+q(document.title))}catch(e){}}"
        "if(T){var p=history.pushState,x=history.replaceState;history.pushState=function(){var v=p.apply(this,arguments);setTimeout(r);return v};history.replaceState=function(){var v=x.apply(this,arguments);setTimeout(r);return v};addEventListener('popstate',r);addEventListener('hashchange',r);addEventListener('DOMContentLoaded',function(){r();try{var t=document.querySelector('title')||document.head;t&&new MutationObserver(r).observe(t,{subtree:true,childList:true,characterData:true})}catch(e){}},{once:true})}"
        "document.addEventListener('contextmenu',function(e){try{var n=location.hostname.toLowerCase();if(n==='chess.com'||n.endsWith('.chess.com'))e.preventDefault()}catch(x){}},true);"
        "document.addEventListener('keydown',function(e){if(e&&(/^Arrow(Up|Down|Left|Right)$/.test(e.key)||e.keyCode>36&&e.keyCode<41))window.__rigelLastRealArrowKeyTime=Date.now()},true)"
        "})();";
}

static NSString *LoginDiagnosticsScript(void) {
    return @"(function(){"
        "if(window.__rigelLoginDiagnostics||!/(^|\\.)music\\.apple\\.com$/i.test(location.hostname))return;"
        "window.__rigelLoginDiagnostics=1;"
        "var tries=0,clicked=false,h=window.webkit&&window.webkit.messageHandlers&&window.webkit.messageHandlers.rigelDiagnostics,T=false;try{T=top===window}catch(e){T=true}"
        "function send(v){try{h&&h.postMessage(JSON.stringify(v))}catch(e){}}"
        "function tag(e){return e?String(e.tagName||e.nodeName||'')+(e.id?'#'+e.id:''):''}"
        "function wire(d,label){try{if(!d||d.__rigelLoginEventDiagnostics)return;d.__rigelLoginEventDiagnostics=1;['pointerdown','mousedown','mouseup','click','focusin','keydown','beforeinput'].forEach(function(type){d.addEventListener(type,function(e){var t=e.target,inside=t&&(t.matches&&t.matches('input,button,[role=button],[role=textbox]')||t.closest&&t.closest('[role=dialog]'));if(!inside)return;send({event:'login-'+type,frame:label,isTrusted:!!e.isTrusted,target:tag(t),active:tag(d.activeElement),defaultPrevented:!!e.defaultPrevented});},true)});}catch(e){send({event:'login-wire-failed',frame:label,error:String(e)})}}"
        "function inputState(input,d,label){var r=input.getBoundingClientRect(),s=(d.defaultView||window).getComputedStyle(input),hit=d.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return{frame:label,rect:{x:r.x,y:r.y,w:r.width,h:r.height},disabled:!!input.disabled,readOnly:!!input.readOnly,inert:!!input.closest('[inert]'),display:s.display,visibility:s.visibility,pointerEvents:s.pointerEvents,opacity:s.opacity,hit:tag(hit),hitIsInput:hit===input||input.contains(hit),focus:d.hasFocus(),active:tag(d.activeElement)}}"
        "function modalProbe(){"
          "var input=document.querySelector('input[type=email],input[autocomplete=username],input[name*=email i],input[placeholder*=email i]');"
          "if(input){send(Object.assign({event:'login-input-found'},inputState(input,document,location.href)));return}"
          "var frames=Array.from(document.querySelectorAll('iframe')).map(function(f){var r=f.getBoundingClientRect(),s=getComputedStyle(f),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2),label=String(f.src||'').slice(0,300),info={src:label,sandbox:String(f.getAttribute('sandbox')||''),rect:{x:r.x,y:r.y,w:r.width,h:r.height},display:s.display,visibility:s.visibility,pointerEvents:s.pointerEvents,hit:tag(hit),hitIsFrame:hit===f};try{var d=f.contentDocument;info.documentAccessible=!!d;if(d){wire(d,label);info.readyState=d.readyState;info.active=tag(d.activeElement);var inner=d.querySelector('input[type=email],input[autocomplete=username],input[name*=email i],input[placeholder*=email i]');if(inner)info.input=inputState(inner,d,label);info.controls=Array.from(d.querySelectorAll('input,button,[role=button],[role=textbox]')).slice(0,12).map(function(e){return{tag:tag(e),text:String(e.innerText||e.placeholder||e.getAttribute('aria-label')||'').trim().slice(0,80),disabled:!!e.disabled}})}}catch(e){info.documentError=String(e)}return info});"
          "send({event:'login-frame-state',frames:frames,focus:document.hasFocus(),active:tag(document.activeElement)});"
        "}"
        "wire(document,location.href);"
        "if(!T){send({event:'login-child-frame-ready',frame:location.href});setTimeout(modalProbe,250);setTimeout(modalProbe,1000);return}"
        "function run(){"
          "tries++;"
          "var all=Array.from(document.querySelectorAll('button,a,[role=button]'));"
          "var target=all.find(function(e){return String(e.innerText||e.textContent||'').replace(/\\s+/g,' ').trim().toLowerCase()==='sign in'});"
          "if(!target){if(tries<120)setTimeout(run,250);else send({event:'sign-in-missing',url:location.href});return}"
          "var r=target.getBoundingClientRect(),s=getComputedStyle(target),x=r.left+r.width/2,y=r.top+r.height/2,hit=document.elementFromPoint(x,y);"
          "var nav=performance.getEntriesByType('navigation')[0];"
          "send({event:'sign-in-found',url:location.href,tries:tries,timeMs:Math.round(performance.now()),loadEventMs:nav&&Math.round(nav.loadEventEnd)||0,resources:performance.getEntriesByType('resource').length,tag:target.tagName,disabled:!!target.disabled,rect:{x:r.x,y:r.y,w:r.width,h:r.height},display:s.display,visibility:s.visibility,pointerEvents:s.pointerEvents,opacity:s.opacity,hitTag:hit&&hit.tagName||'',hitText:String(hit&&(hit.innerText||hit.textContent)||'').replace(/\\s+/g,' ').trim().slice(0,80)});"
          "if(!clicked){clicked=true;target.addEventListener('click',function(e){send({event:'sign-in-click-observed',isTrusted:!!e.isTrusted});setTimeout(modalProbe,500);setTimeout(modalProbe,1500);});"
          "if(window.__rigelLoginAutotest){target.click();send({event:'sign-in-autotest-click-dispatched'});}else send({event:'sign-in-ready-for-real-click'});}"
        "}"
        "if(document.readyState==='loading')addEventListener('DOMContentLoaded',run,{once:true});else run();"
    "})();";
}

static NSString *MediaTraceScript(BOOL normalizeAllSites) {
    NSString *prefix = normalizeAllSites
        ? @"window.__rigelAv1NormalizeAll=true;"
        : @"window.__rigelAv1NormalizeAll=false;";
    NSString *body = @"(function(){"
        "if(window.__rigelMediaTrace)return;"
        "var S={version:2,startedAt:new Date().toISOString(),frameUrl:location.href,compatibility:{av1LevelNormalization:false,av1AllSites:!!window.__rigelAv1NormalizeAll},events:[]},seen={};"
        "window.__rigelMediaTrace=S;"
        "function text(v){try{return v==null?'':String(v)}catch(e){return''}}"
        "function errorText(e){return e?text(e.name)+(e.message?': '+text(e.message):''):''}"
        "function push(e,relay){try{e.timeMs=Math.round(performance.now());S.events.push(e);if(S.events.length>160)S.events.shift();if(relay&&top!==window)top.postMessage({__rigelMediaTraceEvent:e},'*')}catch(x){}}"
        "addEventListener('message',function(e){var d=e.data;if(window===top&&d&&d.__rigelMediaTraceEvent){var v=d.__rigelMediaTraceEvent;v.frameOrigin=text(e.origin);push(v,false)}},false);"
        "addEventListener('error',function(e){var t=e.target;if(t&&/^(VIDEO|AUDIO|SOURCE)$/.test(t.tagName||'')){var m=t.error;push({event:'mediaElementError',tag:t.tagName,src:text(t.currentSrc||t.src),code:m&&m.code||0,message:m&&m.message||''},true);return}if(e.message)push({event:'windowError',message:text(e.message),source:text(e.filename),line:e.lineno||0,column:e.colno||0},true)},true);"
        "addEventListener('unhandledrejection',function(e){push({event:'unhandledRejection',error:errorText(e.reason)||text(e.reason)},true)},true);"
        "try{"
          "if(typeof MediaSource==='undefined')return;"
          "var mseCompat=!!window.__rigelAv1NormalizeAll||/(^|\\.)anime\\.nexus$/i.test(location.hostname);"
          "S.compatibility.av1LevelNormalization=mseCompat;"
          "if(!mseCompat)return;"
          "var nativeIs=MediaSource.isTypeSupported;"
          "if(typeof nativeIs==='function'){"
            "var isDesc=Object.getOwnPropertyDescriptor(MediaSource,'isTypeSupported');"
            "var wrappedIs=function(type){var result=Reflect.apply(nativeIs,this,arguments),mime=text(type),key=mime+'|'+result;if(!seen[key]){seen[key]=1;push({event:'isTypeSupported',mime:mime,result:!!result},true)}return result};"
            "try{Object.defineProperty(MediaSource,'isTypeSupported',{value:wrappedIs,writable:isDesc?isDesc.writable:true,enumerable:isDesc?isDesc.enumerable:false,configurable:isDesc?isDesc.configurable:true})}catch(e){}"
          "}"
          "function watchBuffer(sb,mime){try{sb.addEventListener('error',function(){push({event:'sourceBufferError',mime:mime},true)},true);sb.addEventListener('abort',function(){push({event:'sourceBufferAbort',mime:mime},true)},true)}catch(e){}}"
          "function normalizeAv1Mime(mime){"
            "if(!(window.__rigelAv1NormalizeAll||/(^|\\.)anime\\.nexus$/i.test(location.hostname))||typeof nativeIs!=='function')return'';"
            "var m=mime.match(/^(video\\/mp4\\s*;\\s*codecs\\s*=\\s*[\"']?)(av01\\.[0-2]\\.)(\\d{2})([MH])(\\.(?:08|10|12)(?:\\.\\d+)*)([\"']?\\s*)$/i);"
            "if(!m)return'';"
            "var original=parseInt(m[3],10),originalTier=m[4].toUpperCase();"
            "for(var level=original;level>=0;level--){for(var t=0;t<2;t++){var tier=t===0?'H':'M';if(level===original&&(originalTier!=='H'||tier==='H'))continue;if(originalTier==='M'&&tier==='H')continue;var candidate=m[1]+m[2]+String(level).padStart(2,'0')+tier+m[5]+m[6];try{if(Reflect.apply(nativeIs,MediaSource,[candidate]))return candidate}catch(e){}}}"
            "return'';"
          "}"
          "var p=MediaSource.prototype,nativeAdd=p&&p.addSourceBuffer;"
          "if(typeof nativeAdd==='function'){"
            "var addDesc=Object.getOwnPropertyDescriptor(p,'addSourceBuffer');"
            "var wrappedAdd=function(type){var mime=text(type),supported=null;try{supported=!!Reflect.apply(nativeIs,MediaSource,[type])}catch(e){}try{var sb=Reflect.apply(nativeAdd,this,arguments);push({event:'addSourceBuffer',mime:mime,supported:supported,result:'created'},true);watchBuffer(sb,mime);return sb}catch(e){var normalized=text(e&&e.name)==='NotSupportedError'&&supported===false?normalizeAv1Mime(mime):'';if(normalized){try{var args=Array.prototype.slice.call(arguments);args[0]=normalized;var retry=Reflect.apply(nativeAdd,this,args);push({event:'addSourceBufferNormalization',originalMime:mime,normalizedMime:normalized,result:'created',originalError:errorText(e)},true);watchBuffer(retry,normalized);return retry}catch(retryError){push({event:'addSourceBufferNormalization',originalMime:mime,normalizedMime:normalized,result:'threw',error:errorText(retryError)},true)}}push({event:'addSourceBuffer',mime:mime,supported:supported,result:'threw',error:errorText(e)},true);throw e}};"
            "try{Object.defineProperty(p,'addSourceBuffer',{value:wrappedAdd,writable:addDesc?addDesc.writable:true,enumerable:addDesc?addDesc.enumerable:false,configurable:addDesc?addDesc.configurable:true})}catch(e){}"
          "}"
        "}catch(e){push({event:'traceInstallError',error:errorText(e)},true)}"
    "})();";
    return [prefix stringByAppendingString:body];
}

static NSString *TikTokCompatibilityScript(void) {
    return @"(function(){"
        "if(window.__rigelTikTokCompatibility||!/(^|\\.)tiktok\\.com$/i.test(location.hostname))return;"
        "window.__rigelTikTokCompatibility={version:3,heAacV2AudioClock:true,commentRetry:true,stickerFallback:true};"
        "var active=new WeakMap(),checks=new WeakMap(),audioContext=null,seenMedia={},pendingMedia=[],stickers=[],seenStickerIds={},stickerRenderQueued=false,stickerRoots=[];"
        "function u32(b,o){return ((b[o]<<24)|(b[o+1]<<16)|(b[o+2]<<8)|b[o+3])>>>0}"
        "function durationOf(bytes){"
          "for(var i=4;i+40<bytes.length;i++){if(bytes[i]!==109||bytes[i+1]!==118||bytes[i+2]!==104||bytes[i+3]!==100)continue;"
            "var version=bytes[i+4],scale=0,duration=0;if(version===0){scale=u32(bytes,i+16);duration=u32(bytes,i+20)}else if(version===1){scale=u32(bytes,i+24);duration=u32(bytes,i+32)*4294967296+u32(bytes,i+36)}"
            "if(scale&&duration)return duration/scale}return 0;"
        "}"
        "function sttsClockScale(bytes,start){"
          "var limit=Math.min(bytes.length,start+16384);"
          "for(var i=start;i+20<limit;i++){if(bytes[i]!==115||bytes[i+1]!==116||bytes[i+2]!==116||bytes[i+3]!==115)continue;"
            "var entries=u32(bytes,i+8),p=i+12;if(!entries||entries>10000||p+entries*8>bytes.length)return 0;"
            "var samples=0,ticks=0;for(var e=0;e<entries;e++,p+=8){var count=u32(bytes,p),delta=u32(bytes,p+4);samples+=count;ticks+=count*delta}"
            "var scale=samples?ticks/samples/2048:0;return scale>1.75&&scale<2.25?scale:0}return 0;"
        "}"
        "function heAacV2ClockScale(bytes){"
          "for(var i=0;i+8<bytes.length;i++){"
            "if(bytes[i]!==101||bytes[i+1]!==115||bytes[i+2]!==100||bytes[i+3]!==115)continue;"
            "var end=Math.min(bytes.length,i+180);"
            "for(var j=i+8;j+3<end;j++){"
              "if(bytes[j]!==5)continue;"
              "var k=j+1,n=0,b=0;"
              "do{if(k>=end)break;b=bytes[k++];n=(n<<7)|(b&127)}while((b&128)&&k<j+6);"
              "if(n<2||k+1>=end)continue;"
              "var objectType=bytes[k]>>3;"
              "if(objectType===5||objectType===29)return sttsClockScale(bytes,i+8);"
            "}"
          "}"
          "return 0;"
        "}"
        "function inspect(src){"
          "if(!/^https?:/i.test(src))return Promise.resolve(0);"
          "return fetch(src,{headers:{Range:'bytes=0-65535'},credentials:'include',cache:'force-cache'})"
            ".then(function(r){if(!r.ok&&r.status!==206)throw 0;return r.arrayBuffer()})"
            ".then(function(b){return heAacV2ClockScale(new Uint8Array(b))},function(){return 0});"
        "}"
        "function chooseVideo(duration){"
          "var list=document.querySelectorAll('video'),best=null,bestScore=-1e9;"
          "for(var i=0;i<list.length;i++){var v=list[i];if(active.has(v)||!v.duration||!isFinite(v.duration))continue;"
            "var r=v.getBoundingClientRect(),visible=r.bottom>0&&r.right>0&&r.top<innerHeight&&r.left<innerWidth;"
            "var delta=duration?Math.abs(v.duration-duration):0,score=(v.paused?0:100)+(visible?50:0)-delta*20;"
            "if(duration&&delta>1.2)continue;if(score>bestScore){best=v;bestScore=score}}return best;"
        "}"
        "function applyMedia(url,duration,clockScale){var v=chooseVideo(duration);if(v)enable(v,url,clockScale);else pendingMedia.push({url:url,duration:duration,clockScale:clockScale})}"
        "function tryPending(){for(var i=pendingMedia.length-1;i>=0;i--){var v=chooseVideo(pendingMedia[i].duration);if(v){var m=pendingMedia.splice(i,1)[0];enable(v,m.url,m.clockScale)}}}"
        "function inspectMediaResponse(response,url){"
          "url=String(response.url||url||'');if(seenMedia[url])return;"
          "var type=response.headers&&response.headers.get('content-type')||'';"
          "if(!/video\\/(?:mp4|quicktime)/i.test(type)&&!/\\/video\\/tos\\//i.test(url))return;seenMedia[url]=1;"
          "var clone=response.clone(),reader=clone.body&&clone.body.getReader?clone.body.getReader():null;if(!reader)return;"
          "var chunks=[],length=0;function next(){reader.read().then(function(x){"
            "if(x.done||length>=65536){try{reader.cancel()}catch(e){}var bytes=new Uint8Array(length),p=0;for(var i=0;i<chunks.length;i++){bytes.set(chunks[i],p);p+=chunks[i].length}var clockScale=heAacV2ClockScale(bytes);if(clockScale)applyMedia(url,durationOf(bytes),clockScale);return}"
            "var c=x.value;if(length+c.length>65536)c=c.slice(0,65536-length);chunks.push(c);length+=c.length;next();"
          "}).catch(function(){})}next();"
        "}"
        "function firstStickerURL(value){"
          "if(!value||typeof value!=='object')return'';"
          "if(Array.isArray(value.url_list)&&value.url_list.length)return String(value.url_list[0]||'');"
          "var preferred=['animated_url','static_url','mid_resolution_url','high_resolution_url','low_resolution_url'];"
          "for(var i=0;i<preferred.length;i++){var u=firstStickerURL(value[preferred[i]]);if(u)return u}"
          "var keys=Object.keys(value);for(var j=0;j<keys.length;j++){if(preferred.indexOf(keys[j])>=0)continue;var v=firstStickerURL(value[keys[j]]);if(v)return v}"
          "return'';"
        "}"
        "function ingestComments(value){"
          "if(!value||typeof value!=='object')return;"
          "if(value.cid&&value.cmt_sticker_struct&&!seenStickerIds[value.cid]){"
            "var url=firstStickerURL(value.cmt_sticker_struct);if(url){seenStickerIds[value.cid]=1;stickers.push({cid:String(value.cid),text:String(value.text||'').trim(),user:String(value.user&&value.user.unique_id||''),nickname:String(value.user&&value.user.nickname||''),url:url});if(stickers.length>300)stickers.shift()}"
          "}"
          "if(Array.isArray(value)){for(var i=0;i<value.length;i++)ingestComments(value[i]);return}"
          "var keys=Object.keys(value);for(var j=0;j<keys.length;j++){var k=keys[j];if(k==='cmt_sticker_struct'||k==='user'||k==='share_info')continue;if(k==='comments'||k==='reply_comment'||k==='comment'||Array.isArray(value[k]))ingestComments(value[k])}"
        "}"
        "function inspectCommentResponse(response,url){"
          "url=String(response.url||url||'');if(url.indexOf('/api/comment/list')<0&&url.indexOf('/api/comment/publish')<0)return;"
          "response.clone().json().then(function(data){ingestComments(data);scheduleStickerRender(document.body)},function(){});"
        "}"
        "function normalizeCommentText(v){return String(v||'').replace(/\\[sticker\\]/ig,'').replace(/\\s+/g,' ').trim()}"
        "function stickerForNode(node){"
          "var own=normalizeCommentText(node.nodeValue),element=node.parentElement,context='';"
          "for(var depth=0;element&&depth<7;depth++,element=element.parentElement){context=String(element.textContent||'').replace(/\\s+/g,' ').trim();if(context.length>own.length+30)break}"
          "var best=null,score=-1;for(var i=0;i<stickers.length;i++){var s=stickers[i],n=normalizeCommentText(s.text),v=0;"
            "if(own){if(n===own)v+=30;else if(n&&own.indexOf(n)>=0)v+=15;else continue}else if(n){if(context.indexOf(n)<0)continue;v+=12}else v+=4;"
            "if(s.nickname&&context.indexOf(s.nickname)>=0)v+=20;if(s.user&&context.indexOf(s.user)>=0)v+=20;if(v>score){score=v;best=s}}return best;"
        "}"
        "function renderStickers(){"
          "stickerRenderQueued=false;if(!stickers.length||!document.body){stickerRoots.length=0;return}"
          "var roots=stickerRoots.splice(0),nodes=[];if(!roots.length)roots=[document.body];"
          "for(var r=0;r<roots.length;r++){var root=roots[r];if(!root||!root.isConnected)continue;if(root.nodeType===3){if(/\\[sticker\\]/i.test(root.nodeValue||''))nodes.push(root);continue}var walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT),n;while((n=walker.nextNode()))if(/\\[sticker\\]/i.test(n.nodeValue||''))nodes.push(n)}"
          "for(var i=0;i<nodes.length;i++){var node=nodes[i],parent=node.parentElement;if(!parent||parent.dataset.rigelStickerLoading)continue;var s=stickerForNode(node);if(!s)continue;"
            "var img=document.createElement('img');parent.dataset.rigelStickerLoading=s.cid;img.alt='Sticker';img.src=s.url;img.dataset.rigelStickerCid=s.cid;img.style.cssText='display:block;width:96px;height:96px;max-width:100%;object-fit:contain;object-position:left center;margin:4px 0';"
            "img.addEventListener('load',function(){var p=this.parentElement;if(p){var texts=p.childNodes;for(var j=0;j<texts.length;j++)if(texts[j].nodeType===3&&/\\[sticker\\]/i.test(texts[j].nodeValue||'')){texts[j].nodeValue=texts[j].nodeValue.replace(/\\[sticker\\]\\s*/i,'');break}delete p.dataset.rigelStickerLoading}},{once:true});"
            "img.addEventListener('error',function(){var p=this.parentElement;if(p)delete p.dataset.rigelStickerLoading;this.remove()},{once:true});parent.insertBefore(img,node);"
          "}"
        "}"
        "function scheduleStickerRender(root){if(root)stickerRoots.push(root);if(stickerRenderQueued)return;stickerRenderQueued=true;requestAnimationFrame(renderStickers)}"
        "function silenceOriginal(video,record){"
          "try{"
            "audioContext=audioContext||new (window.AudioContext||window.webkitAudioContext)();"
            "var source=audioContext.createMediaElementSource(video),gain=audioContext.createGain();"
            "gain.gain.value=0;source.connect(gain);gain.connect(audioContext.destination);"
            "record.context=audioContext;record.source=source;record.gain=gain;"
          "}catch(e){"
            "record.originalVolume=video.volume;video.volume=0.0001;record.volumeFallback=true;"
          "}"
        "}"
        "function installRateCorrection(video,record){"
          "try{"
            "var d=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'playbackRate');if(!d||!d.get||!d.set)throw 0;"
            "var scale=record.clockScale,logical=Math.max(.0625,Math.min(8,Number(d.get.call(video))||1));record.rateDescriptor=d;"
            "Object.defineProperty(video,'playbackRate',{configurable:true,enumerable:false,get:function(){return d.get.call(this)/scale},set:function(value){var n=Number(value);if(isFinite(n)&&n>0)d.set.call(this,Math.max(.125,Math.min(16,n*scale)))}});"
            "d.set.call(video,logical*scale);record.rateOverride=true;"
          "}catch(e){record.rateOverride=false;record.logicalRate=Number(video.playbackRate)||1;video.playbackRate=Math.max(.125,Math.min(16,record.logicalRate*record.clockScale))}"
        "}"
        "function effectiveVideoRate(record){"
          "try{if(record.rateDescriptor)return Number(record.rateDescriptor.get.call(record.video))||record.clockScale}catch(e){}"
          "return Number(record.video.playbackRate)||record.clockScale;"
        "}"
        "function sync(record,hard){"
          "var v=record.video,a=record.audio;if(!v.isConnected||record.videoSrc!==(v.currentSrc||v.src)){stop(v);return}"
          "var target=Math.max(0,v.currentTime/record.clockScale);"
          "if(hard||Math.abs(a.currentTime-target)>.3){try{a.currentTime=target}catch(e){}}"
          "var wanted=Math.max(.25,Math.min(4,effectiveVideoRate(record)/record.clockScale));"
          "if(Math.abs(a.playbackRate-wanted)>.01)a.playbackRate=wanted;"
          "a.muted=v.muted;a.volume=Math.max(0,Math.min(1,record.volumeFallback?record.originalVolume:v.volume));"
        "}"
        "function play(record){"
          "sync(record,true);"
          "var p=record.audio.play();if(p&&p.catch)p.catch(function(){});"
          "if(record.context&&record.context.state==='suspended')record.context.resume().catch(function(){});"
        "}"
        "function stop(video){"
          "var r=active.get(video);if(!r)return;r.audio.pause();r.audio.remove();"
          "if(r.volumeFallback)try{video.volume=r.originalVolume}catch(e){}"
          "if(r.rateOverride&&r.rateDescriptor)try{var logical=r.rateDescriptor.get.call(video)/r.clockScale;delete video.playbackRate;r.rateDescriptor.set.call(video,logical)}catch(e){}"
          "active.delete(video);"
        "}"
        "function enable(video,src,clockScale){"
          "if(active.has(video))return;"
          "if(!(clockScale>1.75&&clockScale<2.25))return;var audio=document.createElement('audio'),r={video:video,audio:audio,src:src,videoSrc:(video.currentSrc||video.src),clockScale:clockScale,volumeFallback:false,originalVolume:video.volume};"
          "audio.preload='auto';audio.src=src;audio.style.display='none';audio.setAttribute('aria-hidden','true');"
          "(document.documentElement||document).appendChild(audio);active.set(video,r);video.dataset.rigelHeAacClock='fixed';video.dataset.rigelHeAacScale=clockScale.toFixed(4);installRateCorrection(video,r);silenceOriginal(video,r);"
          "video.addEventListener('play',function(){if(active.get(video)===r)play(r)});"
          "video.addEventListener('pause',function(){if(active.get(video)===r)audio.pause()});"
          "video.addEventListener('seeking',function(){if(active.get(video)===r)sync(r,true)});"
          "video.addEventListener('ratechange',function(){if(active.get(video)===r)sync(r,false)});"
          "video.addEventListener('volumechange',function(){if(active.get(video)===r)sync(r,false)});"
          "video.addEventListener('timeupdate',function(){if(active.get(video)===r)sync(r,false)});"
          "video.addEventListener('ended',function(){if(active.get(video)===r)audio.pause()});"
          "audio.addEventListener('loadedmetadata',function(){sync(r,true);if(!video.paused)play(r)},{once:true});"
          "if(!video.paused)play(r);"
        "}"
        "function check(video){"
          "var src=video.currentSrc||video.src;if(!src||checks.get(video)===src||active.has(video))return;"
          "checks.set(video,src);inspect(src).then(function(clockScale){if(clockScale)enable(video,src,clockScale)});"
        "}"
        "function scan(root){"
          "if(root&&root.tagName==='VIDEO')check(root);"
          "var list=(root&&root.querySelectorAll?root:document).querySelectorAll('video');"
          "for(var i=0;i<list.length;i++)check(list[i]);"
        "}"
        "document.addEventListener('loadedmetadata',function(e){if(e.target&&e.target.tagName==='VIDEO'){check(e.target);tryPending()}},true);"
        "document.addEventListener('emptied',function(e){if(e.target&&e.target.tagName==='VIDEO')stop(e.target)},true);"
        "new MutationObserver(function(ms){for(var i=0;i<ms.length;i++)for(var j=0;j<ms[i].addedNodes.length;j++){var node=ms[i].addedNodes[j];scan(node);if(stickers.length&&/\\[sticker\\]/i.test(node.nodeValue||node.textContent||''))scheduleStickerRender(node)}}).observe(document,{subtree:true,childList:true});"
        "var nativeFetch=window.fetch;window.fetch=function(){var args=arguments,u=args[0]&&args[0].url||args[0];return nativeFetch.apply(this,args).then(function(r){inspectMediaResponse(r,u);inspectCommentResponse(r,u);return r})};"
        "function commentCount(){"
          "var nodes=document.querySelectorAll('[data-e2e*=comment-count],[data-e2e*=comment]');"
          "for(var i=0;i<nodes.length;i++){var m=(nodes[i].textContent||'').match(/([0-9][0-9,.]*)([KMB])?/i);if(m){var n=parseFloat(m[1].replace(/,/g,''));if(m[2])n*=m[2].toUpperCase()==='K'?1e3:m[2].toUpperCase()==='M'?1e6:1e9;if(n>0)return n}}"
          "return 0;"
        "}"
        "var lastRepair=0;"
        "function repairEmptyComments(){"
          "if(Date.now()-lastRepair<12000||commentCount()<1)return;"
          "var all=document.querySelectorAll('div,p,span'),empty=null;"
          "for(var i=0;i<all.length;i++){var t=(all[i].textContent||'').trim();if(/^no comments(?: yet)?[.!]?$/i.test(t)){empty=all[i];break}}"
          "if(!empty)return;var comments=null,other=null,tabs=document.querySelectorAll('[role=tab],button');"
          "for(var j=0;j<tabs.length;j++){var s=(tabs[j].textContent||'').trim();if(/^comments$/i.test(s))comments=tabs[j];else if(/^you may like$/i.test(s))other=tabs[j]}"
          "if(!comments)return;lastRepair=Date.now();if(other){other.click();setTimeout(function(){comments.click()},180)}else comments.click();"
        "}"
        "setInterval(repairEmptyComments,2500);"
        "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',function(){scan(document)},{once:true});else scan(document);"
    "})();";
}

static NSString *TikTokNetworkDiagnosticsScript(void) {
    return @"(function(){"
        "if(window.__rigelTikTokNetworkDiagnostics||!/(^|\\.)tiktok\\.com$/i.test(location.hostname))return;"
        "var h=window.webkit&&window.webkit.messageHandlers&&window.webkit.messageHandlers.rigelDiagnostics;if(!h)return;"
        "window.__rigelTikTokNetworkDiagnostics=1;"
        "function wanted(u){return /(?:api\\/comment|comment\\/list|sticker)/i.test(String(u||''))}"
        "function compact(text){"
          "try{var root=JSON.parse(text),matches=[],count=0,seen=[];"
            "function walk(v){if(!v||typeof v!=='object'||seen.indexOf(v)>=0)return;seen.push(v);"
              "if(v.cid&&typeof v.text==='string'){count++;var special=/^\\s*\\[sticker\\]/i.test(v.text)||v.image_list&&v.image_list.length;"
                "var keys=Object.keys(v),extra={};for(var i=0;i<keys.length;i++)if(/sticker|image/i.test(keys[i])&&v[keys[i]]!=null){extra[keys[i]]=v[keys[i]];special=true}"
                "if(special&&matches.length<20)matches.push({cid:v.cid,text:v.text,user:v.user&&v.user.unique_id||'',keys:keys,media:extra})}"
              "var all=Object.keys(v);for(var j=0;j<all.length;j++)walk(v[all[j]])}walk(root);"
            "return JSON.stringify({commentObjects:count,specialComments:matches,topLevelKeys:Object.keys(root)})"
          "}catch(e){return String(text).slice(0,2000)}"
        "}"
        "function send(kind,url,text){try{h.postMessage(JSON.stringify({kind:kind,url:String(url).slice(0,1500),summary:compact(text)}))}catch(e){}}"
        "var nativeFetch=window.fetch;window.fetch=function(){var args=arguments,u=args[0]&&args[0].url||args[0];return nativeFetch.apply(this,args).then(function(r){if(wanted(u))r.clone().text().then(function(t){send('fetch',u,t)});return r})};"
        "var open=XMLHttpRequest.prototype.open,sendX=XMLHttpRequest.prototype.send;"
        "XMLHttpRequest.prototype.open=function(m,u){this.__rigelURL=u;return open.apply(this,arguments)};"
        "XMLHttpRequest.prototype.send=function(){var x=this;if(wanted(x.__rigelURL))x.addEventListener('load',function(){send('xhr',x.__rigelURL,x.responseText||'')},{once:true});return sendX.apply(this,arguments)};"
    "})();";
}


@interface RigelWKNativeView : WKWebView
@property(nonatomic, assign) NSTimeInterval rigelLastTrustedInputTime;
@property(nonatomic, strong) NSEvent *rigelLastKeyDownEvent;
- (BOOL)rigelConsumeRecentUserInput;
@end

@implementation RigelWKNativeView

- (void)rigelRecordTrustedInput {
    self.rigelLastTrustedInputTime = [NSDate timeIntervalSinceReferenceDate];
}

- (BOOL)rigelConsumeRecentUserInput {
    NSTimeInterval then = self.rigelLastTrustedInputTime;
    self.rigelLastTrustedInputTime = 0;
    return then > 0 && [NSDate timeIntervalSinceReferenceDate] - then <= 1.5;
}

- (BOOL)acceptsFirstResponder {
    return YES;
}

- (BOOL)acceptsFirstMouse:(NSEvent *)event {
    // Login controls should respond to the click that activates a Rigel
    // window. Without this AppKit can consume the first click solely to make
    // the window key, which looks like Apple Music's Sign In button is dead.
    return YES;
}

- (void)mouseDown:(NSEvent *)event {
    [self rigelRecordTrustedInput];
    // A terminal-launched SWT process can own a visible/main NSWindow without
    // becoming the active application. In that state the window stays non-key
    // even after a click and WebKit intentionally withholds DOM mouse events.
    // Activate only in response to a real click inside this web view, then make
    // its window key before handing the original NSEvent to WebKit.
    if (self.window != nil) {
        if (!self.window.isKeyWindow) {
            if (!NSApp.isActive) [NSApp activateIgnoringOtherApps:YES];
            [self.window makeKeyAndOrderFront:nil];
        }
        // The July/August builds explicitly returned the native WebKit view
        // to the first-responder chain before forwarding every real click.
        [self.window makeFirstResponder:self];
    }
    [super mouseDown:event];
}

- (void)rightMouseDown:(NSEvent *)event {
    if (self.window != nil) [self.window makeFirstResponder:self];
    [super rightMouseDown:event];
}

- (void)otherMouseDown:(NSEvent *)event {
    if (self.window != nil) [self.window makeFirstResponder:self];
    [super otherMouseDown:event];
}

- (NSMenu *)menuForEvent:(NSEvent *)event {
    NSString *host = self.URL.host.lowercaseString ?: @"";
    if ([host isEqualToString:@"chess.com"] || [host hasSuffix:@".chess.com"]) {
        // Chess.com uses right-button drags for board arrows. Returning no
        // native menu leaves those mouse events with the page instead of
        // covering the board when WebKit's context-menu gesture completes.
        return nil;
    }
    return [super menuForEvent:event];
}

- (BOOL)rigelShouldMirrorArrowKeys {
    NSString *host = self.URL.host.lowercaseString ?: @"";
    NSString *path = self.URL.path.lowercaseString ?: @"";
    return [host containsString:@"tiktok.com"] ||
           ([host containsString:@"youtube.com"] && [path hasPrefix:@"/shorts"]);
}

- (NSString *)rigelArrowMirrorScriptForEvent:(NSEvent *)event {
    NSString *key = nil;
    NSString *code = nil;
    NSInteger keyCode = 0;
    switch (event.keyCode) {
        case 123: key = @"ArrowLeft";  code = @"ArrowLeft";  keyCode = 37; break;
        case 124: key = @"ArrowRight"; code = @"ArrowRight"; keyCode = 39; break;
        case 125: key = @"ArrowDown";  code = @"ArrowDown";  keyCode = 40; break;
        case 126: key = @"ArrowUp";    code = @"ArrowUp";    keyCode = 38; break;
        default: return nil;
    }
    return [NSString stringWithFormat:
        @"(function(){"
          "try{"
            "var now=Date.now();"
            "if(now-(window.__rigelLastRealArrowKeyTime||0)<160)return;"
            "window.__rigelLastRealArrowKeyTime=now;"
            "var key='%@',code='%@',kc=%ld;"
            "var init={key:key,code:code,keyCode:kc,which:kc,bubbles:true,cancelable:true,composed:true};"
            "function ev(type){"
              "var e=new KeyboardEvent(type,init);"
              "try{Object.defineProperty(e,'keyCode',{get:function(){return kc;}});Object.defineProperty(e,'which',{get:function(){return kc;}});}catch(x){}"
              "return e;"
            "}"
            "var target=document.activeElement||document.body||document.documentElement;"
            "if(!target||target===document.documentElement)target=document.body||document.documentElement;"
            "if(target&&(target.isContentEditable||(target.matches&&target.matches('input,textarea,select,[contenteditable]:not([contenteditable=\"false\"])'))))return;"
            "try{target.dispatchEvent(ev('keydown'));}catch(x){}"
            "try{target.dispatchEvent(ev('keyup'));}catch(x){}"
          "}catch(e){}"
        "})();",
        key, code, (long)keyCode];
}

- (void)rigelRecoverAppleLoginOnEscape {
    NSString *host = self.URL.host.lowercaseString ?: @"";
    if (!([host isEqualToString:@"music.apple.com"]
            || [host hasSuffix:@".music.apple.com"])) return;
    NSString *script =
        @"(function(){try{"
          "var frames=Array.from(document.querySelectorAll('iframe'));"
          "var frame=frames.find(function(f){var u=String(f.src||'');if(u.indexOf('/includes/commerce/navigator')<0)return false;var r=f.getBoundingClientRect(),s=getComputedStyle(f);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden'&&s.pointerEvents!=='none'});"
          "if(!frame)return false;location.reload();return true;"
        "}catch(e){return false}})();";
    [self evaluateJavaScript:script completionHandler:^(id result, __unused NSError *error) {
        if (getenv("RIGEL_LOGIN_DIAGNOSTICS") != NULL && [result boolValue])
            NSLog(@"[Rigel login diagnostics] Esc recovered the Apple login overlay");
    }];
}

- (void)keyDown:(NSEvent *)event {
    // SWT and AppKit can both route the same native event to an embedded view.
    // Let WebKit process each NSEvent once so contenteditable editors do not
    // receive duplicate insertions, while preserving separate repeat events.
    if (event == self.rigelLastKeyDownEvent) return;
    self.rigelLastKeyDownEvent = event;
    [self rigelRecordTrustedInput];
    if (event.keyCode == 53) [self rigelRecoverAppleLoginOnEscape];
    NSString *mirrorScript = [self rigelShouldMirrorArrowKeys] ? [self rigelArrowMirrorScriptForEvent:event] : nil;
    [super keyDown:event];
    if (mirrorScript.length == 0) return;
    __weak RigelWKNativeView *weakSelf = self;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(25 * NSEC_PER_MSEC)),
                   dispatch_get_main_queue(), ^{
        RigelWKNativeView *strongSelf = weakSelf;
        if (strongSelf == nil || strongSelf.window == nil) return;
        [strongSelf evaluateJavaScript:mirrorScript completionHandler:nil];
    });
}

@end

@interface RigelWKDownloadState : NSObject
@property(nonatomic, copy) NSString *identifier;
@property(nonatomic, copy) NSString *name;
@property(nonatomic, copy) NSString *sourceURL;
@property(nonatomic, copy) NSString *path;
@property(nonatomic, strong) WKDownload *download API_AVAILABLE(macos(11.3));
@property(nonatomic, assign) int64_t lastCompleted;
@property(nonatomic, assign) int64_t lastTotal;
@property(nonatomic, assign) BOOL cancelled;
@end

@implementation RigelWKDownloadState
@end

@interface RigelWKController : NSObject
    <WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler, NSWindowDelegate,
     WKDownloadDelegate>
@property(nonatomic, strong) WKWebView *webView;
@property(nonatomic, assign) uint64_t token;
@property(nonatomic, assign) RigelWKEventCallback eventCallback;
@property(nonatomic, assign) RigelWKScriptCallback scriptCallback;
@property(nonatomic, assign) RigelWKPolicyCallback policyCallback;
@property(nonatomic, assign) BOOL normalizeAv1AllSites;
@property(nonatomic, assign) BOOL contentBlockingEnabled;
@property(nonatomic, assign) NSUInteger contentRuleGeneration;
@property(nonatomic, strong) NSMutableSet<WKWebView *> *pendingPopups;
@property(nonatomic, strong) NSMapTable<WKWebView *, NSWindow *> *popupWindows;
@property(nonatomic, copy) NSString *downloadDirectory;
@property(nonatomic, assign) BOOL askBeforeDownload;
@property(nonatomic, strong) NSMutableDictionary<NSString *, RigelWKDownloadState *> *downloads;
@property(nonatomic, strong) id loginEventMonitor;
@end

@implementation RigelWKController

- (void)emit:(int)type first:(NSString *)first second:(NSString *)second {
    if (_eventCallback != NULL) _eventCallback(_token, type, Utf8(first), Utf8(second));
}

- (void)emitDownloadEvent:(NSString *)event
                    state:(RigelWKDownloadState *)state
                    error:(NSString *)error {
    if (_scriptCallback == NULL || state == nil) return;
    NSArray<NSString *> *parts = @[
        event ?: @"", state.identifier ?: @"", state.name ?: @"",
        state.sourceURL ?: @"", state.path ?: @"",
        [NSString stringWithFormat:@"%lld", state.lastCompleted],
        [NSString stringWithFormat:@"%lld", state.lastTotal], error ?: @""
    ];
    NSMutableArray<NSString *> *encoded = [NSMutableArray arrayWithCapacity:parts.count];
    for (NSString *part in parts) [encoded addObject:EncodeMessagePart(part)];
    NSString *payload = [encoded componentsJoinedByString:@"|"];
    _scriptCallback(_token, "rigelDownloadEvent", Utf8(payload));
}

- (RigelWKDownloadState *)stateForDownload:(WKDownload *)download API_AVAILABLE(macos(11.3)) {
    for (RigelWKDownloadState *state in _downloads.allValues) {
        if (state.download == download) return state;
    }
    return nil;
}

- (RigelWKDownloadState *)registerDownload:(WKDownload *)download API_AVAILABLE(macos(11.3)) {
    RigelWKDownloadState *existing = [self stateForDownload:download];
    if (existing != nil) return existing;
    RigelWKDownloadState *state = [[RigelWKDownloadState alloc] init];
    state.identifier = NSUUID.UUID.UUIDString;
    state.sourceURL = download.originalRequest.URL.absoluteString ?: @"";
    state.download = download;
    state.lastCompleted = 0;
    state.lastTotal = -1;
    if (_downloads == nil) _downloads = [NSMutableDictionary dictionary];
    _downloads[state.identifier] = state;
    download.delegate = self;
    return state;
}

- (void)pollDownload:(RigelWKDownloadState *)state API_AVAILABLE(macos(11.3)) {
    if (state == nil || _downloads[state.identifier] != state) return;
    NSProgress *progress = state.download.progress;
    int64_t completed = progress.completedUnitCount;
    int64_t total = progress.totalUnitCount;
    if (completed != state.lastCompleted || total != state.lastTotal) {
        state.lastCompleted = completed;
        state.lastTotal = total;
        [self emitDownloadEvent:@"progress" state:state error:@""];
    }
    __weak RigelWKController *weakSelf = self;
    __weak RigelWKDownloadState *weakState = state;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(350 * NSEC_PER_MSEC)),
                   dispatch_get_main_queue(), ^{
        RigelWKController *strongSelf = weakSelf;
        RigelWKDownloadState *strongState = weakState;
        if (strongSelf != nil && strongState != nil) [strongSelf pollDownload:strongState];
    });
}

- (void)completeDownload:(WKDownload *)download
                    event:(NSString *)event
                    error:(NSString *)error API_AVAILABLE(macos(11.3)) {
    RigelWKDownloadState *state = [self stateForDownload:download];
    if (state == nil) return;
    NSProgress *progress = download.progress;
    state.lastCompleted = progress.completedUnitCount;
    state.lastTotal = progress.totalUnitCount;
    if (![event isEqualToString:@"finished"] && state.path.length > 0) {
        [NSFileManager.defaultManager removeItemAtPath:state.path error:nil];
    }
    [self emitDownloadEvent:event state:state error:error];
    [_downloads removeObjectForKey:state.identifier];
}

static NSString *SafeDownloadName(NSString *suggested) {
    NSString *name = suggested.lastPathComponent;
    if (name.length == 0 || [name isEqualToString:@"."] || [name isEqualToString:@".."]) {
        name = @"download";
    }
    NSCharacterSet *invalid = [NSCharacterSet characterSetWithCharactersInString:@"/:\\"];
    NSArray<NSString *> *pieces = [name componentsSeparatedByCharactersInSet:invalid];
    name = [pieces componentsJoinedByString:@"_"];
    return name.length == 0 ? @"download" : name;
}

static NSURL *UniqueDownloadURL(NSURL *directory, NSString *fileName) {
    NSFileManager *files = NSFileManager.defaultManager;
    NSString *stem = fileName.stringByDeletingPathExtension;
    NSString *extension = fileName.pathExtension;
    for (NSUInteger suffix = 0; suffix < 10000; suffix++) {
        NSString *candidate = fileName;
        if (suffix > 0) {
            NSString *base = [NSString stringWithFormat:@"%@ (%lu)", stem,
                              (unsigned long)suffix];
            candidate = extension.length == 0
                ? base : [base stringByAppendingPathExtension:extension];
        }
        NSURL *url = [directory URLByAppendingPathComponent:candidate isDirectory:NO];
        if (![files fileExistsAtPath:url.path]) return url;
    }
    return [directory URLByAppendingPathComponent:
        [NSString stringWithFormat:@"%@-%@", NSUUID.UUID.UUIDString, fileName]
                                      isDirectory:NO];
}

- (BOOL)isPendingPopup:(WKWebView *)webView {
    return webView != nil && webView != _webView && [_pendingPopups containsObject:webView];
}

- (void)closePendingPopup:(WKWebView *)popup {
    if (popup == nil) return;
    [popup stopLoading];
    popup.navigationDelegate = nil;
    popup.UIDelegate = nil;
    NSWindow *window = [_popupWindows objectForKey:popup];
    if (window != nil) {
        window.delegate = nil;
        [window close];
        [_popupWindows removeObjectForKey:popup];
    }
    [_pendingPopups removeObject:popup];
}

- (void)closeAllPendingPopups {
    NSArray<WKWebView *> *popups = _pendingPopups.allObjects ?: @[];
    for (WKWebView *popup in popups) [self closePendingPopup:popup];
}

- (WKWebView *)createPopupWebViewWithConfiguration:(WKWebViewConfiguration *)configuration {
    // WebKit supplies a separate configuration for window.open()/OAuth views.
    // Do not rely on it inheriting the main view's AppSSO preference.
    DisableExtensibleSSO(configuration.preferences);
    NSRect frame = NSMakeRect(0, 0, 1050, 720);
    WKWebView *popup = [[RigelWKNativeView alloc] initWithFrame:frame
                                                  configuration:configuration];
    popup.navigationDelegate = self;
    popup.UIDelegate = self;
    popup.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    popup.allowsBackForwardNavigationGestures = YES;
    if (@available(macOS 13.3, *)) popup.inspectable = YES;

    NSWindow *window = [[NSWindow alloc]
        initWithContentRect:frame
                  styleMask:(NSWindowStyleMaskTitled |
                             NSWindowStyleMaskClosable |
                             NSWindowStyleMaskMiniaturizable |
                             NSWindowStyleMaskResizable)
                    backing:NSBackingStoreBuffered
                      defer:NO];
    window.title = @"Rigel";
    window.delegate = self;
    window.releasedWhenClosed = NO;
    window.sharingType = DiagnosticCaptureEnabled()
        ? NSWindowSharingReadOnly
        : NSWindowSharingNone;
    window.contentView = popup;
    [window center];
    [window makeKeyAndOrderFront:nil];

    if (_pendingPopups == nil) _pendingPopups = [NSMutableSet set];
    if (_popupWindows == nil) _popupWindows = [NSMapTable strongToStrongObjectsMapTable];
    [_pendingPopups addObject:popup];
    [_popupWindows setObject:window forKey:popup];
    if (getenv("RIGEL_LOGIN_DIAGNOSTICS") != NULL)
        NSLog(@"[Rigel login diagnostics] popup created");
    return popup;
}

- (NSWindow *)popupWindowForWebView:(WKWebView *)webView {
    return webView == nil ? nil : [_popupWindows objectForKey:webView];
}

- (void)updatePopupWindowForWebView:(WKWebView *)webView loading:(BOOL)loading {
    NSWindow *window = [self popupWindowForWebView:webView];
    if (window == nil) return;
    NSString *title = webView.title;
    if (title.length == 0) title = webView.URL.host;
    if (title.length == 0) title = loading ? @"Loading…" : @"Rigel";
    window.title = title;
}

- (void)windowWillClose:(NSNotification *)notification {
    NSWindow *closingWindow = notification.object;
    WKWebView *closingPopup = nil;
    for (WKWebView *popup in [_popupWindows keyEnumerator]) {
        if ([_popupWindows objectForKey:popup] == closingWindow) {
            closingPopup = popup;
            break;
        }
    }
    if (closingPopup == nil) return;
    closingPopup.navigationDelegate = nil;
    closingPopup.UIDelegate = nil;
    [closingPopup stopLoading];
    [_pendingPopups removeObject:closingPopup];
    [_popupWindows removeObjectForKey:closingPopup];
}

- (void)downloadURL:(NSURL *)url {
    if (_scriptCallback == NULL || url == nil) return;
    NSString *absolute = url.absoluteString;
    if (absolute.length == 0) return;
    _scriptCallback(_token, "rigelDownload", Utf8(EncodeMessagePart(absolute)));
}

- (BOOL)shouldAllowNavigationToURL:(NSURL *)url {
    if (_policyCallback == NULL || url == nil) return YES;
    NSString *absolute = url.absoluteString;
    if (absolute.length == 0) return YES;
    return _policyCallback(_token, Utf8(absolute)) != 0;
}

- (void)emitLocationForWebView:(WKWebView *)webView {
    [self emit:RIGEL_WK_EVENT_LOCATION
          first:webView.URL.absoluteString ?: @""
         second:webView.title ?: @""];
}

- (void)runTikTokDiagnostics:(NSInteger)remaining {
    if (remaining <= 0 || _webView == nil || _webView.window == nil) return;
    NSString *host = _webView.URL.host.lowercaseString ?: @"";
    if (!([host isEqualToString:@"tiktok.com"] || [host hasSuffix:@".tiktok.com"])) return;
    NSString *script =
        @"(function(){try{"
          "var videos=Array.from(document.querySelectorAll('video')).map(function(v){"
            "var r=v.getBoundingClientRect(),a=0,q=null;"
            "for(var i=0;i<v.buffered.length;i++){if(v.currentTime>=v.buffered.start(i)&&v.currentTime<=v.buffered.end(i)){a=Math.max(0,v.buffered.end(i)-v.currentTime);break}}"
            "try{q=v.getVideoPlaybackQuality&&v.getVideoPlaybackQuality()}catch(e){}"
            "return{paused:v.paused,ended:v.ended,rate:v.playbackRate,time:+v.currentTime.toFixed(2),duration:isFinite(v.duration)?+v.duration.toFixed(2):null,ready:v.readyState,network:v.networkState,ahead:+a.toFixed(2),width:v.videoWidth,height:v.videoHeight,onscreen:r.bottom>0&&r.right>0&&r.top<innerHeight&&r.left<innerWidth,dropped:q?q.droppedVideoFrames:(v.webkitDroppedFrameCount||0),total:q?q.totalVideoFrames:0,heAacClock:v.dataset.rigelHeAacClock||'',heAacScale:v.dataset.rigelHeAacScale||'',src:(v.currentSrc||v.src||'').slice(0,300)};"
          "});"
          "var stickers=[],walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);"
          "while(stickers.length<8&&walker.nextNode()){var t=(walker.currentNode.nodeValue||'').trim();if(t==='[sticker]'){var p=walker.currentNode.parentElement;stickers.push((p&&p.closest('[data-e2e*=comment],[class*=Comment]')||p).outerHTML.slice(0,1200))}}"
          "var resources=performance.getEntriesByType('resource').filter(function(e){return /comment|sticker|emoji|webcast/i.test(e.name)}).slice(-40).map(function(e){return{name:e.name,kind:e.initiatorType,status:e.responseStatus||0,duration:Math.round(e.duration),bytes:e.transferSize||0}});"
          "return JSON.stringify({visibility:document.visibilityState,focused:document.hasFocus(),userAgent:navigator.userAgent,videos:videos,commentNodes:document.querySelectorAll('[data-e2e*=comment],[class*=Comment]').length,stickerFallbacks:stickers,commentResources:resources});"
        "}catch(e){return JSON.stringify({error:String(e)})}})();";
    __weak RigelWKController *weakSelf = self;
    [_webView evaluateJavaScript:script completionHandler:^(id result, NSError *error) {
        RigelWKController *strongSelf = weakSelf;
        if (strongSelf == nil) return;
        NSLog(@"[Rigel TikTok diagnostics] %@%@", result ?: @"",
              error == nil ? @"" : [@" error=" stringByAppendingString:error.localizedDescription]);
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(2 * NSEC_PER_SEC)),
                       dispatch_get_main_queue(), ^{
            [strongSelf runTikTokDiagnostics:remaining - 1];
        });
    }];
}

- (void)webView:(WKWebView *)webView didStartProvisionalNavigation:(WKNavigation *)navigation {
    if ([self isPendingPopup:webView]) {
        [self updatePopupWindowForWebView:webView loading:YES];
        return;
    }
    [self emit:RIGEL_WK_EVENT_LOAD_STARTED
          first:webView.URL.absoluteString ?: @"" second:@""];
}

- (void)webView:(WKWebView *)webView didCommitNavigation:(WKNavigation *)navigation {
    if ([self isPendingPopup:webView]) {
        [self updatePopupWindowForWebView:webView loading:YES];
        return;
    }
    [self emitLocationForWebView:webView];
}

- (void)webView:(WKWebView *)webView didFinishNavigation:(WKNavigation *)navigation {
    if ([self isPendingPopup:webView]) {
        [self updatePopupWindowForWebView:webView loading:NO];
        __weak RigelWKController *weakSelf = self;
        __weak WKWebView *weakWebView = webView;
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(100 * NSEC_PER_MSEC)),
                       dispatch_get_main_queue(), ^{
            RigelWKController *strongSelf = weakSelf;
            WKWebView *strongWebView = weakWebView;
            if (strongSelf != nil && strongWebView != nil)
                [strongSelf updatePopupWindowForWebView:strongWebView loading:NO];
        });
        if (getenv("RIGEL_LOGIN_DIAGNOSTICS") != NULL)
            NSLog(@"[Rigel login diagnostics] popup loaded %@", webView.URL.absoluteString ?: @"");
        return;
    }
    [self emitLocationForWebView:webView];
    [self emit:RIGEL_WK_EVENT_LOAD_FINISHED
          first:webView.URL.absoluteString ?: @""
         second:webView.title ?: @""];
    if (getenv("RIGEL_TIKTOK_DIAGNOSTICS") != NULL) [self runTikTokDiagnostics:12];
}

- (void)webView:(WKWebView *)webView didFailNavigation:(WKNavigation *)navigation
      withError:(NSError *)error {
    if ([error.domain isEqualToString:NSURLErrorDomain] && error.code == NSURLErrorCancelled) return;
    if ([self isPendingPopup:webView]) {
        [self updatePopupWindowForWebView:webView loading:NO];
        NSLog(@"[Rigel] Popup navigation failed: %@", error.localizedDescription);
        return;
    }
    [self emit:RIGEL_WK_EVENT_LOAD_FAILED
          first:webView.URL.absoluteString ?: @""
         second:error.localizedDescription ?: @"Navigation failed"];
}

- (void)webView:(WKWebView *)webView didFailProvisionalNavigation:(WKNavigation *)navigation
      withError:(NSError *)error {
    if ([error.domain isEqualToString:NSURLErrorDomain] && error.code == NSURLErrorCancelled) return;
    if ([self isPendingPopup:webView]) {
        [self updatePopupWindowForWebView:webView loading:NO];
        NSLog(@"[Rigel] Popup provisional navigation failed: %@", error.localizedDescription);
        return;
    }
    [self emit:RIGEL_WK_EVENT_LOAD_FAILED
          first:webView.URL.absoluteString ?: @""
         second:error.localizedDescription ?: @"Navigation failed"];
}

- (void)webViewWebContentProcessDidTerminate:(WKWebView *)webView {
    if ([self isPendingPopup:webView]) {
        [self updatePopupWindowForWebView:webView loading:YES];
        [webView reload];
        return;
    }
    [self emit:RIGEL_WK_EVENT_PROCESS_RESTARTED
          first:webView.URL.absoluteString ?: @"" second:@""];
    [webView reload];
}

- (void)webView:(WKWebView *)webView
    decidePolicyForNavigationAction:(WKNavigationAction *)navigationAction
                    decisionHandler:(void (^)(WKNavigationActionPolicy))decisionHandler {
    if ([self isPendingPopup:webView]) {
        // Keep popup navigation inside the exact WKWebView WebKit created for
        // it. OAuth and Apple Music use window.opener/postMessage while the
        // popup crosses several authentication origins. Running the main-tab
        // policy callback here both corrupts its progress state and breaks the
        // opener relationship when the URL is recreated elsewhere.
        decisionHandler(WKNavigationActionPolicyAllow);
        return;
    }
    if (@available(macOS 11.3, *)) {
        if (navigationAction.shouldPerformDownload) {
            if (![self shouldAllowNavigationToURL:navigationAction.request.URL]) {
                decisionHandler(WKNavigationActionPolicyCancel);
                return;
            }
            decisionHandler(WKNavigationActionPolicyDownload);
            return;
        }
    }
    if (navigationAction.targetFrame == nil && navigationAction.request.URL != nil) {
        // Let WKUIDelegate create the requested web view with WebKit's supplied
        // configuration. Replacing this with an unrelated Rigel tab loses the
        // opener, transient user activation, cookies, and OAuth callbacks.
        decisionHandler(WKNavigationActionPolicyAllow);
        return;
    }
    if (navigationAction.targetFrame != nil && !navigationAction.targetFrame.mainFrame) {
        decisionHandler(WKNavigationActionPolicyAllow);
        return;
    }
    if (![self shouldAllowNavigationToURL:navigationAction.request.URL]) {
        decisionHandler(WKNavigationActionPolicyCancel);
        return;
    }
    decisionHandler(WKNavigationActionPolicyAllow);
}

/*
 * Defense in depth for WebKit's Extensible SSO interception. The preference on
 * each configuration prevents the interception up front. If WebKit still asks
 * its private navigation-delegate policy (for example for a supplied popup
 * configuration), Ignore deterministically resumes the ordinary web request
 * instead of presenting AuthKit's unusable native sheet from an anonymous Java
 * host. The enum and block parameter are NSInteger-sized in WebKit's ABI.
 */
- (void)_webView:(WKWebView *)webView
    decidePolicyForSOAuthorizationLoadWithCurrentPolicy:(NSInteger)currentPolicy
                                           forExtension:(NSString *)extension
                                      completionHandler:(void (^)(NSInteger))completionHandler {
    (void)webView;
    (void)currentPolicy;
    if (getenv("RIGEL_LOGIN_DIAGNOSTICS") != NULL) {
        NSLog(@"[Rigel login diagnostics] ignored native AppSSO extension %@",
              extension ?: @"(unknown)");
    }
    completionHandler(RIGEL_WK_SO_AUTHORIZATION_IGNORE);
}

- (void)webView:(WKWebView *)webView
    decidePolicyForNavigationResponse:(WKNavigationResponse *)navigationResponse
                      decisionHandler:(void (^)(WKNavigationResponsePolicy))decisionHandler {
    NSURLResponse *response = navigationResponse.response;
    NSURL *url = response.URL;
    BOOL attachment = NO;
    if ([response isKindOfClass:[NSHTTPURLResponse class]]) {
        NSString *disposition = ((NSHTTPURLResponse *)response).allHeaderFields[@"Content-Disposition"];
        attachment = [disposition.lowercaseString containsString:@"attachment"];
    }
    if ((attachment || !navigationResponse.canShowMIMEType) && url != nil) {
        if (@available(macOS 11.3, *)) {
            decisionHandler(WKNavigationResponsePolicyDownload);
        } else {
            decisionHandler(WKNavigationResponsePolicyCancel);
            dispatch_async(dispatch_get_main_queue(), ^{ [self downloadURL:url]; });
        }
        return;
    }
    decisionHandler(WKNavigationResponsePolicyAllow);
}

- (void)webView:(WKWebView *)webView
        navigationAction:(WKNavigationAction *)navigationAction
       didBecomeDownload:(WKDownload *)download API_AVAILABLE(macos(11.3)) {
    (void)webView;
    (void)navigationAction;
    [self registerDownload:download];
}

- (void)webView:(WKWebView *)webView
      navigationResponse:(WKNavigationResponse *)navigationResponse
       didBecomeDownload:(WKDownload *)download API_AVAILABLE(macos(11.3)) {
    (void)webView;
    (void)navigationResponse;
    [self registerDownload:download];
}

- (void)download:(WKDownload *)download
    decideDestinationUsingResponse:(NSURLResponse *)response
                 suggestedFilename:(NSString *)suggestedFilename
                 completionHandler:(void (^)(NSURL *destination))completionHandler
    API_AVAILABLE(macos(11.3)) {
    RigelWKDownloadState *state = [self registerDownload:download];
    state.name = SafeDownloadName(suggestedFilename);
    if (state.sourceURL.length == 0) state.sourceURL = response.URL.absoluteString ?: @"";

    NSString *configured = _downloadDirectory.length == 0
        ? @"~/Downloads" : _downloadDirectory;
    NSURL *directory = [NSURL fileURLWithPath:configured.stringByExpandingTildeInPath
                                  isDirectory:YES];
    NSError *directoryError = nil;
    [NSFileManager.defaultManager createDirectoryAtURL:directory
                           withIntermediateDirectories:YES attributes:nil
                                                error:&directoryError];
    if (directoryError != nil) {
        [self completeDownload:download event:@"failed"
                         error:directoryError.localizedDescription ?: @"Could not create download folder"];
        completionHandler(nil);
        return;
    }

    void (^begin)(NSURL *) = ^(NSURL *destination) {
        if (destination == nil) {
            state.cancelled = YES;
            [self completeDownload:download event:@"cancelled" error:@""];
            completionHandler(nil);
            return;
        }
        state.path = destination.path ?: @"";
        state.name = destination.lastPathComponent ?: state.name;
        [self emitDownloadEvent:@"started" state:state error:@""];
        completionHandler(destination);
        [self pollDownload:state];
    };

    if (!_askBeforeDownload) {
        begin(UniqueDownloadURL(directory, state.name));
        return;
    }

    NSSavePanel *panel = [NSSavePanel savePanel];
    panel.directoryURL = directory;
    panel.nameFieldStringValue = state.name;
    panel.canCreateDirectories = YES;
    void (^finishPanel)(NSModalResponse) = ^(NSModalResponse result) {
        if (result != NSModalResponseOK || panel.URL == nil) {
            begin(nil);
            return;
        }
        NSURL *selected = panel.URL;
        if ([NSFileManager.defaultManager fileExistsAtPath:selected.path]) {
            NSError *removeError = nil;
            [NSFileManager.defaultManager removeItemAtURL:selected error:&removeError];
            if (removeError != nil) {
                [self completeDownload:download event:@"failed"
                                 error:removeError.localizedDescription ?: @"Could not replace file"];
                completionHandler(nil);
                return;
            }
        }
        begin(selected);
    };
    if (_webView.window != nil) {
        [panel beginSheetModalForWindow:_webView.window completionHandler:finishPanel];
    } else {
        finishPanel([panel runModal]);
    }
}

- (void)downloadDidFinish:(WKDownload *)download API_AVAILABLE(macos(11.3)) {
    [self completeDownload:download event:@"finished" error:@""];
}

- (void)download:(WKDownload *)download didFailWithError:(NSError *)error
      resumeData:(NSData *)resumeData API_AVAILABLE(macos(11.3)) {
    (void)resumeData;
    RigelWKDownloadState *state = [self stateForDownload:download];
    NSString *event = state.cancelled ? @"cancelled" : @"failed";
    [self completeDownload:download event:event
                     error:state.cancelled ? @"" : (error.localizedDescription ?: @"Download failed")];
}

- (void)download:(WKDownload *)download
    willPerformHTTPRedirection:(NSHTTPURLResponse *)response
                    newRequest:(NSURLRequest *)request
               decisionHandler:(void (^)(WKDownloadRedirectPolicy))decisionHandler
    API_AVAILABLE(macos(11.3)) {
    (void)download;
    (void)response;
    (void)request;
    decisionHandler(WKDownloadRedirectPolicyAllow);
}

- (void)download:(WKDownload *)download
    didReceiveAuthenticationChallenge:(NSURLAuthenticationChallenge *)challenge
                    completionHandler:(void (^)(NSURLSessionAuthChallengeDisposition disposition,
                                                NSURLCredential *credential))completionHandler
    API_AVAILABLE(macos(11.3)) {
    (void)download;
    (void)challenge;
    completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, nil);
}

- (void)userContentController:(WKUserContentController *)userContentController
      didReceiveScriptMessage:(WKScriptMessage *)message {
    if (message == nil || !message.frameInfo.isMainFrame) return;
    if (message.webView != nil && message.webView != _webView) return;

    NSString *name = message.name ?: @"";
    if ([name isEqualToString:@"rigelDiagnostics"]
            && (getenv("RIGEL_TIKTOK_DIAGNOSTICS") != NULL
                || getenv("RIGEL_LOGIN_DIAGNOSTICS") != NULL
                || getenv("RIGEL_LOGIN_AUTOTEST") != NULL)) {
        if ([message.body isKindOfClass:[NSString class]])
            NSLog(@"[Rigel diagnostics] %@", (NSString *)message.body);
        return;
    }
    if (_scriptCallback == NULL) return;
    if ([name isEqualToString:@"rigelEvent"]) {
        if ([message.body isKindOfClass:[NSString class]]) {
            NSString *payload = (NSString *)message.body;
            if (payload.length <= (64u * 1024u))
                _scriptCallback(_token, "rigelEvent", Utf8(payload));
        } else {
            [self emitLocationForWebView:_webView];
        }
        return;
    }
    if (![message.body isKindOfClass:[NSString class]]) return;
    BOOL privileged = [name isEqualToString:@"javaEnterFullscreen"]
            || [name isEqualToString:@"rigelClipboardWrite"]
            || [name isEqualToString:@"rigelDownload"];
    if (privileged
            && ![(RigelWKNativeView *)_webView rigelConsumeRecentUserInput]) return;
    NSString *payload = (NSString *)message.body;
    NSUInteger limit = [name isEqualToString:@"rigelClipboardWrite"]
            ? (4u * 1024u * 1024u) : (64u * 1024u);
    if (payload.length > limit) return;
    _scriptCallback(_token, Utf8(name), Utf8(payload));
}

- (WKWebView *)webView:(WKWebView *)webView
      createWebViewWithConfiguration:(WKWebViewConfiguration *)configuration
                 forNavigationAction:(WKNavigationAction *)navigationAction
                      windowFeatures:(WKWindowFeatures *)windowFeatures {
    return [self createPopupWebViewWithConfiguration:configuration];
}

- (void)webViewDidClose:(WKWebView *)webView {
    if ([self isPendingPopup:webView]) {
        [self closePendingPopup:webView];
        return;
    }
    __weak RigelWKController *weakSelf = self;
    dispatch_async(dispatch_get_main_queue(), ^{
        RigelWKController *strongSelf = weakSelf;
        if (strongSelf != nil)
            [strongSelf emit:RIGEL_WK_EVENT_CLOSED first:@"" second:@""];
    });
}

- (void)webView:(WKWebView *)webView
      runJavaScriptAlertPanelWithMessage:(NSString *)message
                       initiatedByFrame:(WKFrameInfo *)frame
                       completionHandler:(void (^)(void))completionHandler {
    NSAlert *alert = [[NSAlert alloc] init];
    alert.messageText = message ?: @"";
    [alert addButtonWithTitle:@"OK"];
    if (webView.window != nil) {
        [alert beginSheetModalForWindow:webView.window completionHandler:^(__unused NSModalResponse result) {
            completionHandler();
        }];
    } else {
        [alert runModal];
        completionHandler();
    }
}

- (void)webView:(WKWebView *)webView
      runJavaScriptConfirmPanelWithMessage:(NSString *)message
                          initiatedByFrame:(WKFrameInfo *)frame
                          completionHandler:(void (^)(BOOL result))completionHandler {
    NSAlert *alert = [[NSAlert alloc] init];
    alert.messageText = message ?: @"";
    [alert addButtonWithTitle:@"OK"];
    [alert addButtonWithTitle:@"Cancel"];
    void (^finish)(NSModalResponse) = ^(NSModalResponse response) {
        completionHandler(response == NSAlertFirstButtonReturn);
    };
    if (webView.window != nil) [alert beginSheetModalForWindow:webView.window completionHandler:finish];
    else finish([alert runModal]);
}

- (void)webView:(WKWebView *)webView
      runJavaScriptTextInputPanelWithPrompt:(NSString *)prompt
                                defaultText:(NSString *)defaultText
                           initiatedByFrame:(WKFrameInfo *)frame
                           completionHandler:(void (^)(NSString *result))completionHandler {
    NSAlert *alert = [[NSAlert alloc] init];
    alert.messageText = prompt ?: @"";
    NSTextField *field = [[NSTextField alloc] initWithFrame:NSMakeRect(0, 0, 320, 24)];
    field.stringValue = defaultText ?: @"";
    alert.accessoryView = field;
    [alert addButtonWithTitle:@"OK"];
    [alert addButtonWithTitle:@"Cancel"];
    void (^finish)(NSModalResponse) = ^(NSModalResponse response) {
        completionHandler(response == NSAlertFirstButtonReturn ? field.stringValue : nil);
    };
    if (webView.window != nil) [alert beginSheetModalForWindow:webView.window completionHandler:finish];
    else finish([alert runModal]);
}

- (void)webView:(WKWebView *)webView
      runOpenPanelWithParameters:(WKOpenPanelParameters *)parameters
               initiatedByFrame:(WKFrameInfo *)frame
               completionHandler:(void (^)(NSArray<NSURL *> *URLs))completionHandler {
    NSOpenPanel *panel = [NSOpenPanel openPanel];
    panel.allowsMultipleSelection = parameters.allowsMultipleSelection;
    panel.canChooseDirectories = parameters.allowsDirectories;
    panel.canChooseFiles = YES;
    void (^finish)(NSModalResponse) = ^(NSModalResponse response) {
        completionHandler(response == NSModalResponseOK ? panel.URLs : @[]);
    };
    if (webView.window != nil) [panel beginSheetModalForWindow:webView.window completionHandler:finish];
    else finish([panel runModal]);
}

#if __MAC_OS_X_VERSION_MAX_ALLOWED >= 120000
static NSString *RigelMediaCaptureLabel(WKMediaCaptureType type) API_AVAILABLE(macos(12.0)) {
    switch (type) {
        case WKMediaCaptureTypeCamera: return @"camera";
        case WKMediaCaptureTypeMicrophone: return @"microphone";
        case WKMediaCaptureTypeCameraAndMicrophone: return @"camera and microphone";
    }
    return @"camera or microphone";
}

static NSString *RigelOriginLabel(WKSecurityOrigin *origin, WKFrameInfo *frame, WKWebView *webView) {
    NSString *host = origin.host;
    if (host.length == 0) host = frame.request.URL.host;
    if (host.length == 0) host = webView.URL.host;
    if (host.length == 0) host = origin.protocol;
    return host.length == 0 ? @"this site" : host;
}

- (void)webView:(WKWebView *)webView
      requestMediaCapturePermissionForOrigin:(WKSecurityOrigin *)origin
                           initiatedByFrame:(WKFrameInfo *)frame
                                      type:(WKMediaCaptureType)type
                           decisionHandler:(void (^)(WKPermissionDecision decision))decisionHandler
      API_AVAILABLE(macos(12.0)) {
    NSString *kind = RigelMediaCaptureLabel(type);
    NSString *site = RigelOriginLabel(origin, frame, webView);
    NSAlert *alert = [[NSAlert alloc] init];
    alert.messageText = [NSString stringWithFormat:@"Allow %@ to use the %@?", site, kind];
    alert.informativeText = @"Rigel will let WebKit use the device only for this site request.";
    [alert addButtonWithTitle:@"Allow"];
    [alert addButtonWithTitle:@"Don't Allow"];
    void (^finish)(NSModalResponse) = ^(NSModalResponse response) {
        decisionHandler(response == NSAlertFirstButtonReturn
            ? WKPermissionDecisionGrant
            : WKPermissionDecisionDeny);
    };
    if (webView.window != nil) [alert beginSheetModalForWindow:webView.window completionHandler:finish];
    else finish([alert runModal]);
}
#endif

@end

static NSArray<NSString *> *HandlerNames(void) {
    return @[@"javaEnterFullscreen", @"javaExitFullscreen", @"rigelHomeNavigate",
             @"rigelClipboardWrite", @"rigelDownload", @"rigelEvent", @"rigelDiagnostics"];
}

static void InstallUserScripts(WKUserContentController *controller, BOOL normalizeAv1AllSites) {
    if (controller == nil) return;
    WKUserScript *bridge = [[WKUserScript alloc] initWithSource:BridgeScript()
        injectionTime:WKUserScriptInjectionTimeAtDocumentStart forMainFrameOnly:NO];
    [controller addUserScript:bridge];
    WKUserScript *mediaTrace = [[WKUserScript alloc]
        initWithSource:MediaTraceScript(normalizeAv1AllSites)
        injectionTime:WKUserScriptInjectionTimeAtDocumentStart forMainFrameOnly:NO];
    [controller addUserScript:mediaTrace];
    WKUserScript *tiktokCompatibility = [[WKUserScript alloc]
        initWithSource:TikTokCompatibilityScript()
        injectionTime:WKUserScriptInjectionTimeAtDocumentStart forMainFrameOnly:NO];
    [controller addUserScript:tiktokCompatibility];
    if (getenv("RIGEL_TIKTOK_DIAGNOSTICS") != NULL) {
        WKUserScript *tiktokDiagnostics = [[WKUserScript alloc]
            initWithSource:TikTokNetworkDiagnosticsScript()
            injectionTime:WKUserScriptInjectionTimeAtDocumentStart forMainFrameOnly:YES];
        [controller addUserScript:tiktokDiagnostics];
    }
    if (getenv("RIGEL_LOGIN_DIAGNOSTICS") != NULL
            || getenv("RIGEL_LOGIN_AUTOTEST") != NULL) {
        NSString *source = LoginDiagnosticsScript();
        if (getenv("RIGEL_LOGIN_AUTOTEST") != NULL)
            source = [@"window.__rigelLoginAutotest=true;" stringByAppendingString:source];
        WKUserScript *loginDiagnostics = [[WKUserScript alloc]
            initWithSource:source
            injectionTime:WKUserScriptInjectionTimeAtDocumentStart forMainFrameOnly:NO];
        [controller addUserScript:loginDiagnostics];
    }
}


static void ConfigureProxy(WKWebsiteDataStore *dataStore, int proxyPort, int proxyMode) {
    if (@available(macOS 14.0, *)) {
        static WKWebsiteDataStore *configuredStore;
        static int configuredPort = INT_MIN;
        static int configuredMode = INT_MIN;
        @synchronized(dataStore) {
            // The default data store is shared by every Rigel tab. Reassigning
            // the same proxyConfigurations array for every new WKWebView can
            // synchronously restart WebKit networking and stall page startup.
            if (configuredStore == dataStore && configuredPort == proxyPort
                    && configuredMode == proxyMode) return;
            if (proxyPort > 0 && proxyMode != RIGEL_WK_PROXY_DIRECT) {
                NSString *port = [NSString stringWithFormat:@"%d", proxyPort];
                nw_endpoint_t endpoint = nw_endpoint_create_host("127.0.0.1", port.UTF8String);
                nw_proxy_config_t proxy = proxyMode == RIGEL_WK_PROXY_SOCKS5
                    ? nw_proxy_config_create_socksv5(endpoint)
                    : nw_proxy_config_create_http_connect(endpoint, nil);
                if (proxy != nil) nw_proxy_config_set_failover_allowed(proxy, false);
                dataStore.proxyConfigurations = proxy == nil ? @[] : @[proxy];
            } else {
                dataStore.proxyConfigurations = @[];
            }
            configuredStore = dataStore;
            configuredPort = proxyPort;
            configuredMode = proxyMode;
        }
    }
}

static NSString *SafariApplicationName(void) {
    static NSString *applicationName;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        NSBundle *safari = [NSBundle bundleWithPath:@"/Applications/Safari.app"];
        NSString *version = [safari objectForInfoDictionaryKey:@"CFBundleShortVersionString"];
        if (version.length == 0) version = @"18.0";
        applicationName = [NSString stringWithFormat:@"Version/%@ Safari/605.1.15", version];
    });
    return applicationName;
}

static WKProcessPool *SharedProcessPool(void) {
    static WKProcessPool *processPool;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{ processPool = [[WKProcessPool alloc] init]; });
    return processPool;
}

static NSString *RigelContentRuleJSON(void) {
    static NSString *json;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        NSArray<NSString *> *domains = @[
            @"*2mdn.net",
            @"*adform.net",
            @"*adnxs.com",
            @"*adservice.google.com",
            @"*ads-twitter.com",
            @"*adsafeprotected.com",
            @"*adsrvr.org",
            @"*adzerk.net",
            @"*advertising.com",
            @"*amazon-adsystem.com",
            @"*analytics.google.com",
            @"*app-measurement.com",
            @"*bidswitch.net",
            @"*bluekai.com",
            @"*casalemedia.com",
            @"*chartbeat.com",
            @"*clarity.ms",
            @"*criteo.com",
            @"*criteo.net",
            @"*demdex.net",
            @"*doubleclick.net",
            @"*everesttech.net",
            @"*eyeota.net",
            @"*flashtalking.com",
            @"*google-analytics.com",
            @"*googleadservices.com",
            @"*googlesyndication.com",
            @"*googletagservices.com",
            @"*hotjar.com",
            @"*imrworldwide.com",
            @"*krxd.net",
            @"*lijit.com",
            @"*mathtag.com",
            @"*media.net",
            @"*mixpanel.com",
            @"*moatads.com",
            @"*newrelic.com",
            @"*openx.net",
            @"*optimizely.com",
            @"*outbrain.com",
            @"*pubmatic.com",
            @"*quantserve.com",
            @"*rlcdn.com",
            @"*rubiconproject.com",
            @"*scorecardresearch.com",
            @"*segment.com",
            @"*segment.io",
            @"*serving-sys.com",
            @"*sharethrough.com",
            @"*smartadserver.com",
            @"*spotxchange.com",
            @"*taboola.com",
            @"*tapad.com",
            @"*teads.tv",
            @"*tradedoubler.com",
            @"*undertone.com",
            @"*yieldmanager.com",
            @"*yieldmo.com",
            @"*zedo.com"
        ];
        NSArray *rules = @[
            @{
                @"trigger": @{@"url-filter": @".*", @"if-domain": domains},
                @"action": @{@"type": @"block"}
            },
            @{
                @"trigger": @{@"url-filter": @".*"},
                @"action": @{
                    @"type": @"css-display-none",
                    @"selector": @".adsbygoogle,[data-ad-client],[id^='google_ads_'],[id^='div-gpt-ad']"
                }
            }
        ];
        NSError *error = nil;
        NSData *data = [NSJSONSerialization dataWithJSONObject:rules options:0 error:&error];
        if (data == nil) {
            NSLog(@"[Rigel] Could not encode built-in content rules: %@", error.localizedDescription);
            json = @"[]";
        } else {
            json = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] ?: @"[]";
        }
    });
    return json;
}

static void ConfigureContentBlocking(RigelWKController *controller, BOOL enabled) {
    if (controller == nil || controller.webView == nil) return;
    controller.contentBlockingEnabled = enabled;
    NSUInteger generation = ++controller.contentRuleGeneration;
    WKUserContentController *contentController =
        controller.webView.configuration.userContentController;
    [contentController removeAllContentRuleLists];

    if (!enabled) {
        if (controller.webView.URL != nil) [controller.webView reload];
        return;
    }

    [[WKContentRuleListStore defaultStore]
        compileContentRuleListForIdentifier:@"com.rigel.builtin-content-blocker.v1"
        encodedContentRuleList:RigelContentRuleJSON()
        completionHandler:^(WKContentRuleList *ruleList, NSError *error) {
            dispatch_async(dispatch_get_main_queue(), ^{
                if (controller.webView == nil || !controller.contentBlockingEnabled ||
                        controller.contentRuleGeneration != generation) return;
                if (ruleList == nil) {
                    NSLog(@"[Rigel] Could not compile built-in content rules: %@",
                          error.localizedDescription);
                    return;
                }
                [controller.webView.configuration.userContentController
                    addContentRuleList:ruleList];
                if (controller.webView.URL != nil) [controller.webView reload];
            });
        }];
}

__attribute__((visibility("default")))
int rigel_wk_supported(void) {
    return NSClassFromString(@"WKWebView") != Nil;
}

__attribute__((visibility("default")))
void rigel_wk_set_proxy_port(int proxyPort) {
    ConfigureProxy([WKWebsiteDataStore defaultDataStore], proxyPort, RIGEL_WK_PROXY_HTTP);
}

__attribute__((visibility("default")))
void rigel_wk_set_proxy(int proxyPort, int proxyMode) {
    ConfigureProxy([WKWebsiteDataStore defaultDataStore], proxyPort, proxyMode);
}

__attribute__((visibility("default")))
void *rigel_wk_create(uintptr_t parentView, uint64_t token,
                      int proxyPort,
                      int proxyMode,
                      int mode,
                      uint64_t unusedSessionId,
                      RigelWKEventCallback eventCallback,
                      RigelWKScriptCallback scriptCallback,
                      RigelWKPolicyCallback policyCallback) {
    if (!rigel_wk_supported() || parentView == 0) return NULL;
    NSView *parent = (__bridge NSView *)(void *)parentView;

    RigelWKController *controller = [[RigelWKController alloc] init];
    controller.token = token;
    (void)unusedSessionId;
    controller.normalizeAv1AllSites = (mode & 1) != 0;
    controller.contentBlockingEnabled = (mode & 2) != 0;
    controller.eventCallback = eventCallback;
    controller.scriptCallback = scriptCallback;
    controller.policyCallback = policyCallback;
    controller.downloadDirectory = @"~/Downloads";
    controller.downloads = [NSMutableDictionary dictionary];

    WKWebViewConfiguration *configuration = [[WKWebViewConfiguration alloc] init];
    configuration.websiteDataStore = [WKWebsiteDataStore defaultDataStore];
    ConfigureProxy(configuration.websiteDataStore, proxyPort, proxyMode);
    configuration.applicationNameForUserAgent = SafariApplicationName();
    configuration.processPool = SharedProcessPool();
    configuration.suppressesIncrementalRendering = NO;
    configuration.allowsAirPlayForMediaPlayback = YES;
    configuration.preferences.javaScriptCanOpenWindowsAutomatically = YES;
    if (@available(macOS 12.3, *)) {
        configuration.preferences.elementFullscreenEnabled = YES;
        configuration.preferences.siteSpecificQuirksModeEnabled = YES;
    }
    if (@available(macOS 11.3, *)) {
        configuration.preferences.textInteractionEnabled = YES;
    }
    DisableExtensibleSSO(configuration.preferences);
    if ([configuration respondsToSelector:@selector(setMediaTypesRequiringUserActionForPlayback:)]) {
        configuration.mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeNone;
    }

    WKUserContentController *userContentController = [[WKUserContentController alloc] init];
    configuration.userContentController = userContentController;
    for (NSString *name in HandlerNames()) {
        [userContentController addScriptMessageHandler:controller name:name];
    }
    InstallUserScripts(userContentController, controller.normalizeAv1AllSites);
    WKWebView *webView = [[RigelWKNativeView alloc] initWithFrame:parent.bounds configuration:configuration];
    controller.webView = webView;
    webView.navigationDelegate = controller;
    webView.UIDelegate = controller;
    webView.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    webView.allowsBackForwardNavigationGestures = YES;
    webView.allowsMagnification = YES;
    if (@available(macOS 13.3, *)) webView.inspectable = YES;
    [parent addSubview:webView];
    if (getenv("RIGEL_LOGIN_DIAGNOSTICS") != NULL) {
        __weak WKWebView *weakView = webView;
        controller.loginEventMonitor = [NSEvent
            addLocalMonitorForEventsMatchingMask:(NSEventMaskLeftMouseDown |
                                                   NSEventMaskLeftMouseUp)
            handler:^NSEvent *(NSEvent *event) {
                WKWebView *view = weakView;
                if (view == nil || event.window != view.window) return event;
                NSPoint point = [view convertPoint:event.locationInWindow fromView:nil];
                if (!NSPointInRect(point, view.bounds)) return event;
                NSView *hit = [view hitTest:point];
                NSResponder *first = event.window.firstResponder;
                NSLog(@"[Rigel login diagnostics] native-%@ key=%d main=%d "
                       "point=(%.1f,%.1f) hit=%@ firstResponder=%@",
                      event.type == NSEventTypeLeftMouseDown ? @"mousedown" : @"mouseup",
                      event.window.isKeyWindow, event.window.isMainWindow,
                      point.x, point.y,
                      hit == nil ? @"nil" : NSStringFromClass(hit.class),
                      first == nil ? @"nil" : NSStringFromClass(first.class));
                return event;
            }];
    }
    if (controller.contentBlockingEnabled) ConfigureContentBlocking(controller, YES);
    return (__bridge_retained void *)controller;
}

static RigelWKController *Controller(void *handle) {
    return handle == NULL ? nil : (__bridge RigelWKController *)handle;
}

__attribute__((visibility("default")))
void rigel_wk_set_av1_normalization(void *handle, int enabled) {
    RigelWKController *controller = Controller(handle);
    if (controller == nil || controller.webView == nil) return;
    BOOL normalizeAllSites = enabled != 0;
    controller.normalizeAv1AllSites = normalizeAllSites;

    WKUserContentController *scripts = controller.webView.configuration.userContentController;
    [scripts removeAllUserScripts];
    InstallUserScripts(scripts, normalizeAllSites);

    NSString *value = normalizeAllSites ? @"true" : @"false";
    NSString *javascript = [NSString stringWithFormat:
        @"(function(){window.__rigelAv1NormalizeAll=%@;try{if(window.__rigelMediaTrace&&window.__rigelMediaTrace.compatibility)window.__rigelMediaTrace.compatibility.av1AllSites=%@}catch(e){}})();",
        value, value];
    [controller.webView evaluateJavaScript:javascript completionHandler:nil];
}

__attribute__((visibility("default")))
void rigel_wk_set_content_blocking(void *handle, int enabled) {
    RigelWKController *controller = Controller(handle);
    if (controller == nil || controller.webView == nil) return;
    ConfigureContentBlocking(controller, enabled != 0);
}

__attribute__((visibility("default")))
void rigel_wk_set_download_options(void *handle, const char *directory, int askBeforeDownload) {
    RigelWKController *controller = Controller(handle);
    if (controller == nil) return;
    NSString *value = directory == NULL ? @"" : [NSString stringWithUTF8String:directory];
    controller.downloadDirectory = value.length == 0 ? @"~/Downloads" : value;
    controller.askBeforeDownload = askBeforeDownload != 0;
}

__attribute__((visibility("default")))
int rigel_wk_cancel_download(void *handle, const char *identifier) {
    RigelWKController *controller = Controller(handle);
    if (controller == nil || identifier == NULL) return 0;
    NSString *downloadId = [NSString stringWithUTF8String:identifier];
    RigelWKDownloadState *state = controller.downloads[downloadId];
    if (state == nil) return 0;
    if (@available(macOS 11.3, *)) {
        state.cancelled = YES;
        __weak RigelWKController *weakController = controller;
        __weak WKDownload *weakDownload = state.download;
        [state.download cancel:^(__unused NSData *resumeData) {
            dispatch_async(dispatch_get_main_queue(), ^{
                RigelWKController *strongController = weakController;
                WKDownload *strongDownload = weakDownload;
                if (strongController != nil && strongDownload != nil) {
                    [strongController completeDownload:strongDownload
                                                 event:@"cancelled" error:@""];
                }
            });
        }];
        return 1;
    }
    return 0;
}

__attribute__((visibility("default")))
void rigel_wk_destroy(void *handle) {
    RigelWKController *controller = Controller(handle);
    if (controller == nil) return;
    if (controller.loginEventMonitor != nil) {
        [NSEvent removeMonitor:controller.loginEventMonitor];
        controller.loginEventMonitor = nil;
    }
    if (@available(macOS 11.3, *)) {
        for (RigelWKDownloadState *state in controller.downloads.allValues) {
            state.cancelled = YES;
            [state.download cancel:nil];
            if (state.path.length > 0)
                [NSFileManager.defaultManager removeItemAtPath:state.path error:nil];
        }
    }
    [controller.downloads removeAllObjects];
    [controller closeAllPendingPopups];
    WKUserContentController *scripts = controller.webView.configuration.userContentController;
    for (NSString *name in HandlerNames()) [scripts removeScriptMessageHandlerForName:name];
    controller.webView.navigationDelegate = nil;
    controller.webView.UIDelegate = nil;
    [controller.webView stopLoading];
    [controller.webView removeFromSuperview];
    controller.webView = nil;
    controller.eventCallback = NULL;
    controller.scriptCallback = NULL;
    controller.policyCallback = NULL;
    CFBridgingRelease(handle);
}

__attribute__((visibility("default")))
int rigel_wk_load_url(void *handle, const char *url) {
    RigelWKController *controller = Controller(handle);
    if (controller == nil || url == NULL) return 0;
    NSString *text = [NSString stringWithUTF8String:url];
    NSURL *target = [NSURL URLWithString:text];
    if (target == nil) return 0;
    [controller.webView loadRequest:[NSURLRequest requestWithURL:target]];
    return 1;
}

__attribute__((visibility("default")))
int rigel_wk_load_html(void *handle, const char *html) {
    RigelWKController *controller = Controller(handle);
    if (controller == nil || html == NULL) return 0;
    [controller.webView loadHTMLString:[NSString stringWithUTF8String:html] baseURL:nil];
    return 1;
}

__attribute__((visibility("default")))
void rigel_wk_back(void *handle) { [Controller(handle).webView goBack]; }
__attribute__((visibility("default")))
void rigel_wk_forward(void *handle) { [Controller(handle).webView goForward]; }
__attribute__((visibility("default")))
void rigel_wk_reload(void *handle) { [Controller(handle).webView reload]; }
__attribute__((visibility("default")))
void rigel_wk_reload_from_origin(void *handle) {
    WKWebView *view = Controller(handle).webView;
    if ([view respondsToSelector:@selector(reloadFromOrigin)]) [view reloadFromOrigin];
    else [view reload];
}
__attribute__((visibility("default")))
void rigel_wk_stop(void *handle) { [Controller(handle).webView stopLoading]; }
__attribute__((visibility("default")))
int rigel_wk_can_back(void *handle) { return Controller(handle).webView.canGoBack ? 1 : 0; }
__attribute__((visibility("default")))
int rigel_wk_can_forward(void *handle) { return Controller(handle).webView.canGoForward ? 1 : 0; }

__attribute__((visibility("default")))
void rigel_wk_set_frame(void *handle, double x, double y, double width, double height) {
    WKWebView *view = Controller(handle).webView;
    view.frame = NSMakeRect(x, y, MAX(1.0, width), MAX(1.0, height));
}

__attribute__((visibility("default")))
void rigel_wk_focus(void *handle) {
    WKWebView *view = Controller(handle).webView;
    if (view == nil || view.window == nil) return;
    BOOL wasActive = NSApp.isActive;
    BOOL wasKey = view.window.isKeyWindow;
    if (!NSApp.isActive) [NSApp activateIgnoringOtherApps:YES];
    [view.window makeKeyAndOrderFront:nil];
    [view.window makeFirstResponder:view];
    if (getenv("RIGEL_LOGIN_DIAGNOSTICS") != NULL)
        NSLog(@"[Rigel login diagnostics] native-focus active=%d->%d key=%d->%d main=%d firstResponder=%@",
              wasActive, NSApp.isActive, wasKey, view.window.isKeyWindow,
              view.window.isMainWindow,
              view.window.firstResponder == nil ? @"nil" : NSStringFromClass(view.window.firstResponder.class));
}

__attribute__((visibility("default")))
int rigel_wk_edit_action(void *handle, int action) {
    WKWebView *view = Controller(handle).webView;
    if (view == nil || view.window == nil) return 0;

    SEL selector = NULL;
    switch (action) {
        case RIGEL_WK_EDIT_CUT: selector = @selector(cut:); break;
        case RIGEL_WK_EDIT_COPY: selector = @selector(copy:); break;
        case RIGEL_WK_EDIT_PASTE: selector = @selector(paste:); break;
        case RIGEL_WK_EDIT_SELECT_ALL: selector = @selector(selectAll:); break;
        default: return 0;
    }

    [view.window makeKeyWindow];
    if (view.window.firstResponder == nil) [view.window makeFirstResponder:view];
    if ([NSApp sendAction:selector to:nil from:view]) return 1;
    [view.window makeFirstResponder:view];
    return [NSApp sendAction:selector to:nil from:view] ? 1 : 0;
}

__attribute__((visibility("default")))
void rigel_wk_execute(void *handle, const char *javascript) {
    RigelWKController *controller = Controller(handle);
    if (controller == nil || javascript == NULL) return;
    [controller.webView evaluateJavaScript:[NSString stringWithUTF8String:javascript]
                         completionHandler:nil];
}

__attribute__((visibility("default")))
void rigel_wk_evaluate(void *handle, const char *javascript, uint64_t evalToken,
                       RigelWKEvalCallback callback) {
    RigelWKController *controller = Controller(handle);
    if (callback == NULL) return;
    if (controller == nil || javascript == NULL) {
        callback(evalToken, "", "WKWebView is unavailable");
        return;
    }
    [controller.webView evaluateJavaScript:[NSString stringWithUTF8String:javascript]
        completionHandler:^(id result, NSError *error) {
            NSString *value = @"";
            if (result != nil) {
                if ([result isKindOfClass:[NSString class]]) value = result;
                else if ([NSJSONSerialization isValidJSONObject:result]) {
                    NSData *data = [NSJSONSerialization dataWithJSONObject:result options:0 error:nil];
                    if (data != nil) value = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
                } else value = [result description] ?: @"";
            }
            callback(evalToken, Utf8(value), Utf8(error.localizedDescription));
        }];
}
