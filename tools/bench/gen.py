import zlib, struct, random, os, sys
WWW = os.path.join(os.path.dirname(os.path.abspath(__file__)), "www")
os.makedirs(WWW, exist_ok=True)
os.chdir(WWW)
random.seed(2)
B = r"""<script>(function(){var q=new URLSearchParams(location.search),id=q.get('id')||'';
function send(o){var s='/beacon?id='+encodeURIComponent(id)+'&page='+encodeURIComponent(location.pathname)+'&ts='+Date.now();for(var k in o)s+='&'+k+'='+encodeURIComponent(o[k]);new Image().src=s;}
window.__send=send;
function fcpOf(){var p=performance.getEntriesByType('paint').filter(function(e){return e.name==='first-contentful-paint'})[0];return p?p.startTime:0;}
addEventListener('load',function(){var t0=Date.now();(function wait(){var f=fcpOf();if(!f&&Date.now()-t0<3000){requestAnimationFrame(function(){setTimeout(wait,16)});return;}
var n=performance.getEntriesByType('navigation')[0]||{};
send({kind:'load',ttfb:Math.round(n.responseStart||0),dcl:Math.round(n.domContentLoadedEventEnd||0),load:Math.round(n.loadEventStart||0),fcp:Math.round(f)});})();});})();</script>"""


# Base pages: small, big DOM + script, CSS-in-JS app, long scrolling page.
open('simple.html', 'w').write('<!doctype html><meta name=viewport content="width=device-width"><title>Simple</title><h1>Hello</h1><p>Small page.</p>')
_rows = ''.join(f'<div class="card c{i % 7}"><h3>Item {i}</h3><p>{"lorem ipsum dolor sit amet " * (i % 5 + 2)}</p><a href="#a{i}">link {i}</a></div>' for i in range(3000))
_css = '\n'.join(f'.c{k}{{background:hsl({k * 50},60%,95%);border:1px solid hsl({k * 50},50%,70%);padding:8px;margin:4px;border-radius:8px}}' for k in range(7))
open('heavy.html', 'w').write(f'<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width"><meta name="theme-color" content="#336699"><title>Heavy</title><link rel=icon href="data:,"><style>{_css} body{{font-family:sans-serif}}</style></head><body><h1>Heavy page</h1>{_rows}<script>let x=0;for(let i=0;i<2e6;i++)x+=Math.sqrt(i);document.title="Heavy "+(x|0)%7;</script></body></html>')
open('spa.html', 'w').write('<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width"><title>SPA</title></head><body><div id=app></div><script>let n=0;const t0=performance.now();function tick(){for(let k=0;k<20;k++){const s=document.createElement("style");s.textContent=".x"+(n++)+"{color:red}";document.head.appendChild(s);}const d=document.createElement("div");d.textContent="row "+n;document.getElementById("app").appendChild(d);if(performance.now()-t0<3000)requestAnimationFrame(tick);else{document.title="SPA done "+n;}}requestAnimationFrame(tick);</script></body></html>')
open('scroll.html', 'w').write('<!doctype html><meta name=viewport content="width=device-width"><title>Scroll</title><body style="margin:0">' + ''.join(f'<div style="height:120px;background:linear-gradient(90deg,hsl({i * 13 % 360},70%,60%),hsl({i * 29 % 360},70%,40%));box-shadow:0 2px 6px #0005;margin:6px;border-radius:12px;font:24px sans-serif;color:white;padding:12px">Row {i}</div>' for i in range(600)))


def inject(name):
    import re
    s = open(name, encoding='utf-8').read()
    s = re.sub(r"<script>\(function\(\)\{var q=new URLSearchParams.*?</script>", "", s, flags=re.S)
    s = s.replace('<title>', B + '<title>', 1) if '<title>' in s else B + s
    open(name, 'w', encoding='utf-8').write(s)


for n in ['simple.html', 'heavy.html', 'spa.html', 'scroll.html']:
    inject(n)


def png(path, w, h, seed):
    r = random.Random(seed)
    raw = b''
    for y in range(h):
        raw += b'\x00' + bytes((r.randrange(256) if x % 7 == 0 else (x * 3 + y * seed) % 256) for x in range(w * 3))

    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    open(path, 'wb').write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 6)) + chunk(b'IEND', b''))


os.makedirs('res', exist_ok=True)
for i in range(20):
    png(f'res/img{i}.png', 160, 120, i + 1)
for i in range(6):
    open(f'res/s{i}.css', 'w').write('\n'.join(f'.k{i}_{j}{{margin:{j % 5}px;color:hsl({j * 7 % 360},60%,40%)}}' for j in range(800)))
for i in range(8):
    open(f'res/j{i}.js', 'w').write(f'window.v{i}=(function(){{var a=[];for(var k=0;k<20000;k++)a.push(k*{i + 1}%97);return a.reduce(function(x,y){{return x+y}},0)}})();\n' + '//' + 'x' * 20000)
css = ''.join(f'<link rel=stylesheet href="/net/res/s{i}.css">' for i in range(6))
js = ''.join(f'<script src="/net/res/j{i}.js"></script>' for i in range(8))
imgs = ''.join(f'<img src="/net/res/img{i}.png" width=160 height=120 style="margin:4px">' for i in range(20))
body = ''.join(f'<article class="k0_{i}"><h2>Story {i}</h2><p>' + 'Some realistic article text with words. ' * 20 + '</p></article>' for i in range(60))
open('real.html', 'w', encoding='utf-8').write(f'<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width">{B}<title>Real</title>{css}</head><body><h1>News site</h1>{imgs}{body}{js}</body></html>')

GPU = open(os.path.join('..', 'gpu_common.js')).read()
for name in ['shader', 'scene3d']:
    src = open(os.path.join('..', name + '.js')).read()
    open(name + '.html', 'w').write('<!doctype html><html><head><meta name=viewport content="width=device-width">' + B +
        '<title>' + name + '</title><style>html,body{margin:0;height:100%;background:#000}canvas{width:100%;height:100%;display:block}</style></head><body><canvas id=c></canvas><script>\n' +
        src + '\n' + GPU + '</script></body></html>')
print(sorted(os.listdir('.')))
