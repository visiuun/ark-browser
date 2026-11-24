var c = document.getElementById('c'); c.width = innerWidth * devicePixelRatio; c.height = innerHeight * devicePixelRatio;
var gl = c.getContext('webgl2');
function sh(t, s) { var o = gl.createShader(t); gl.shaderSource(o, s); gl.compileShader(o); if (!gl.getShaderParameter(o, gl.COMPILE_STATUS)) throw gl.getShaderInfoLog(o); return o; }
var p = gl.createProgram();
gl.attachShader(p, sh(gl.VERTEX_SHADER, '#version 300 es\nin vec2 a;void main(){gl_Position=vec4(a,0,1);}'));
gl.attachShader(p, sh(gl.FRAGMENT_SHADER, `#version 300 es
precision highp float;uniform vec2 r;uniform float t;out vec4 o;
float map(vec3 p){vec3 q=p;float d=1e9;for(int i=0;i<4;i++){q=abs(q)-vec3(.6,.4,.5);q.xy*=mat2(cos(t*.3),-sin(t*.3),sin(t*.3),cos(t*.3));d=min(d,length(q)-.25);}return min(d,p.y+1.);}
vec3 nrm(vec3 p){vec2 e=vec2(.001,0);return normalize(vec3(map(p+e.xyy)-map(p-e.xyy),map(p+e.yxy)-map(p-e.yxy),map(p+e.yyx)-map(p-e.yyx)));}
void main(){vec2 uv=(gl_FragCoord.xy*2.-r)/r.y;vec3 ro=vec3(0,0,-4),rd=normalize(vec3(uv,1.6));float d=0.;
for(int i=0;i<96;i++){float h=map(ro+rd*d);if(h<.001||d>20.)break;d+=h;}
vec3 col=vec3(0);if(d<20.){vec3 p=ro+rd*d,n=nrm(p);col=vec3(.6,.4,.8)*max(dot(n,normalize(vec3(1,2,-1))),0.)+.1;}o=vec4(pow(col,vec3(.45)),1);}`));
gl.linkProgram(p); gl.useProgram(p);
var b = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, b); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
var a = gl.getAttribLocation(p, 'a'); gl.enableVertexAttribArray(a); gl.vertexAttribPointer(a, 2, gl.FLOAT, false, 0, 0);
var ur = gl.getUniformLocation(p, 'r'), ut = gl.getUniformLocation(p, 't');
function draw(t) { gl.viewport(0, 0, c.width, c.height); gl.uniform2f(ur, c.width, c.height); gl.uniform1f(ut, t / 1000); gl.drawArrays(gl.TRIANGLES, 0, 3); }
