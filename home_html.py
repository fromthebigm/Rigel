"""
home_html.py — Rigel Browser home/new-tab page HTML
"""

HOME_HTML = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
<style>
@import url('https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@300;400;500;700&family=Space+Mono&display=swap');
*{box-sizing:border-box;margin:0;padding:0;}
body{font-family:'Space Grotesk',sans-serif;background:#03040d;color:#e8f0ff;
  min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;
  gap:36px;overflow:hidden;position:relative;}
canvas{position:fixed;inset:0;pointer-events:none;z-index:0;}
.content{position:relative;z-index:1;display:flex;flex-direction:column;align-items:center;gap:32px;}
.logo{display:flex;flex-direction:column;align-items:center;gap:12px;}
.logo-cluster{position:relative;width:80px;height:80px;filter:drop-shadow(0 0 28px rgba(77,166,255,0.5));}
.star-dot{position:absolute;border-radius:50%;background:radial-gradient(circle,#fff,#b8d4ff);}
.logo h1{font-size:32px;font-weight:700;background:linear-gradient(135deg,#b8d4ff 0%,#4da6ff 40%,#6de0ff 100%);
  -webkit-background-clip:text;-webkit-text-fill-color:transparent;}
.logo p{font-size:12px;color:rgba(184,212,255,0.45);font-weight:300;letter-spacing:.08em;text-transform:uppercase;}
.box{display:flex;width:560px;max-width:92vw;background:rgba(10,15,42,0.8);border-radius:16px;
  border:1px solid rgba(184,212,255,0.15);overflow:hidden;backdrop-filter:blur(20px);box-shadow:0 20px 60px rgba(0,0,0,0.5);}
.box:focus-within{border-color:rgba(77,166,255,0.5);box-shadow:0 0 0 4px rgba(77,166,255,0.08);}
.box input{flex:1;background:transparent;border:none;outline:none;color:#e8f0ff;
  font-size:15px;font-family:'Space Grotesk',sans-serif;padding:15px 18px;}
.box input::placeholder{color:rgba(184,212,255,0.35);}
.box button{background:linear-gradient(135deg,#2979e8,#4da6ff);border:none;color:#fff;
  padding:0 24px;font-size:13px;font-weight:500;cursor:pointer;font-family:'Space Grotesk',sans-serif;transition:opacity .12s;}
.box button:hover{opacity:.85;}
.shortcuts{display:flex;gap:10px;flex-wrap:wrap;justify-content:center;max-width:600px;}
.sc{display:flex;flex-direction:column;align-items:center;gap:8px;width:84px;padding:12px 6px;
  border-radius:14px;cursor:pointer;text-decoration:none;color:inherit;
  background:rgba(10,15,42,0.6);border:1px solid rgba(184,212,255,0.08);transition:all .18s;}
.sc:hover{background:rgba(77,166,255,0.1);border-color:rgba(77,166,255,0.3);transform:translateY(-3px);}
.sc-icon{width:46px;height:46px;border-radius:13px;background:rgba(15,21,53,0.8);
  display:flex;align-items:center;justify-content:center;font-size:22px;border:1px solid rgba(184,212,255,0.06);}
.sc span{font-size:10.5px;color:rgba(184,212,255,0.45);}
.clock{font-family:'Space Mono',monospace;font-size:11px;color:rgba(184,212,255,0.22);
  position:fixed;bottom:16px;right:20px;letter-spacing:.05em;}
</style>
</head>
<body>
<canvas id="c"></canvas>
<div class="content">
  <div class="logo">
    <div class="logo-cluster" id="cluster"></div>
    <h1>Rigel</h1>
    <p>Fast. Private. Yours.</p>
  </div>
  <div class="box">
    <input id="q" type="text" placeholder="Search or enter a URL…" onkeydown="if(event.key==='Enter') go()" autofocus/>
    <button onclick="go()">Launch</button>
  </div>
  <div class="shortcuts">
    <a class="sc" href="https://reddit.com"><div class="sc-icon">🤖</div><span>Reddit</span></a>
    <a class="sc" href="https://github.com"><div class="sc-icon">🐙</div><span>GitHub</span></a>
    <a class="sc" href="https://youtube.com"><div class="sc-icon">▶️</div><span>YouTube</span></a>
    <a class="sc" href="https://x.com"><div class="sc-icon">𝕏</div><span>X</span></a>
    <a class="sc" href="https://news.ycombinator.com"><div class="sc-icon">🔶</div><span>Hacker News</span></a>
    <a class="sc" href="https://maps.google.com"><div class="sc-icon">🗺️</div><span>Maps</span></a>
    <a class="sc" href="https://twitch.tv"><div class="sc-icon">🎮</div><span>Twitch</span></a>
    <a class="sc" href="https://en.wikipedia.org"><div class="sc-icon">📚</div><span>Wikipedia</span></a>
  </div>
</div>
<div class="clock" id="clk"></div>
<script>
(function(){
  const c=document.getElementById('c'),ctx=c.getContext('2d');let stars=[];
  function resize(){c.width=innerWidth;c.height=innerHeight;init();}
  function init(){
    stars=[];const n=Math.floor(c.width*c.height/2200);
    for(let i=0;i<n;i++) stars.push({x:Math.random()*c.width,y:Math.random()*c.height,
      r:.2+Math.random()*1.3,a:Math.random(),da:(Math.random()-.5)*.007,h:200+Math.random()*80});
    const cx=c.width*.5,cy=c.height*.38;
    [[0,-55],[38,-28],[65,18],[28,58],[-28,48],[-60,8],[-38,-42]].forEach(([dx,dy])=>
      stars.push({x:cx+dx+Math.random()*16-8,y:cy+dy+Math.random()*16-8,
        r:1.5+Math.random()*1.8,a:.75+Math.random()*.25,da:(Math.random()-.5)*.011,h:210+Math.random()*30,bright:true}));
  }
  function draw(){
    ctx.clearRect(0,0,c.width,c.height);
    stars.forEach(s=>{
      s.a+=s.da;if(s.a<=0||s.a>=1)s.da*=-1;ctx.save();
      if(s.bright){const g=ctx.createRadialGradient(s.x,s.y,0,s.x,s.y,s.r*10);
        g.addColorStop(0,`hsla(${s.h},85%,92%,${s.a*.5})`);g.addColorStop(1,'transparent');
        ctx.fillStyle=g;ctx.beginPath();ctx.arc(s.x,s.y,s.r*10,0,Math.PI*2);ctx.fill();}
      ctx.globalAlpha=s.a;ctx.fillStyle=`hsl(${s.h},${s.bright?75:60}%,${s.bright?94:78}%)`;
      ctx.beginPath();ctx.arc(s.x,s.y,s.r,0,Math.PI*2);ctx.fill();ctx.restore();
    });
    requestAnimationFrame(draw);
  }
  window.addEventListener('resize',resize);resize();draw();
})();
(function(){
  const wrap=document.getElementById('cluster');
  [[45,20,9],[68,35,6],[75,58,5],[55,70,7],[30,65,5],[18,45,6],[28,25,5]].forEach(([x,y,r])=>{
    const el=document.createElement('div');el.className='star-dot';
    el.style.cssText=`left:${x}%;top:${y}%;width:${r*2}px;height:${r*2}px;transform:translate(-50%,-50%);
      box-shadow:0 0 ${r*3}px rgba(184,212,255,0.8),0 0 ${r*8}px rgba(77,166,255,0.4);`;
    wrap.appendChild(el);
  });
})();
function go(){
  const v=document.getElementById('q').value.trim();if(!v)return;
  location.href=/^https?:\/\//.test(v)?v
    :/^[a-zA-Z0-9]([a-zA-Z0-9\-]*\.)+[a-zA-Z]{2,}/.test(v)?'https://'+v
    :'https://search.brave.com/search?q='+encodeURIComponent(v);
}
function tick(){const now=new Date();
  document.getElementById('clk').textContent=
    now.toLocaleDateString(undefined,{weekday:'long',month:'short',day:'numeric'})+' · '+now.toLocaleTimeString();}
tick();setInterval(tick,1000);
</script>
</body>
</html>
"""
