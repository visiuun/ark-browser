var c = document.getElementById('c'); c.width = innerWidth * devicePixelRatio; c.height = innerHeight * devicePixelRatio;
var gl = c.getContext('webgl2', { antialias: true });
function sh(t, s) { var o = gl.createShader(t); gl.shaderSource(o, s); gl.compileShader(o); if (!gl.getShaderParameter(o, gl.COMPILE_STATUS)) throw gl.getShaderInfoLog(o); return o; }
var p = gl.createProgram();
gl.attachShader(p, sh(gl.VERTEX_SHADER, `#version 300 es
in vec3 pos;in vec3 nor;in vec4 inst;uniform mat4 vp;uniform float t;out vec3 n;out vec3 col;
void main(){float a=t*inst.w;mat3 R=mat3(cos(a),0,sin(a),0,1,0,-sin(a),0,cos(a));vec3 w=R*(pos*.35)+inst.xyz;n=R*nor;col=.5+.5*cos(inst.w*6.+vec3(0,2,4));gl_Position=vp*vec4(w,1);}`));
gl.attachShader(p, sh(gl.FRAGMENT_SHADER, `#version 300 es
precision highp float;in vec3 n;in vec3 col;out vec4 o;void main(){float l=max(dot(normalize(n),normalize(vec3(.5,1,.3))),0.)*.8+.2;o=vec4(col*l,1);}`));
gl.linkProgram(p); gl.useProgram(p);
var V = [], F = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];
F.forEach(function (f) {
  var u = f[0] ? [0, 1, 0] : [1, 0, 0], v = [f[1] * u[2] - f[2] * u[1], f[2] * u[0] - f[0] * u[2], f[0] * u[1] - f[1] * u[0]];
  [[-1, -1], [1, -1], [1, 1], [-1, -1], [1, 1], [-1, 1]].forEach(function (s) {
    V.push(f[0] + s[0] * u[0] + s[1] * v[0], f[1] + s[0] * u[1] + s[1] * v[1], f[2] + s[0] * u[2] + s[1] * v[2], f[0], f[1], f[2]);
  });
});
var vb = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vb); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(V), gl.STATIC_DRAW);
var lp = gl.getAttribLocation(p, 'pos'), ln = gl.getAttribLocation(p, 'nor'), li = gl.getAttribLocation(p, 'inst');
gl.enableVertexAttribArray(lp); gl.vertexAttribPointer(lp, 3, gl.FLOAT, false, 24, 0);
gl.enableVertexAttribArray(ln); gl.vertexAttribPointer(ln, 3, gl.FLOAT, false, 24, 12);
var N = 40000, I = new Float32Array(N * 4);
for (var i = 0; i < N; i++) { I[i * 4] = (Math.random() - .5) * 60; I[i * 4 + 1] = (Math.random() - .5) * 60; I[i * 4 + 2] = (Math.random() - .5) * 60; I[i * 4 + 3] = Math.random() * 2 + .2; }
var ib = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, ib); gl.bufferData(gl.ARRAY_BUFFER, I, gl.STATIC_DRAW);
gl.enableVertexAttribArray(li); gl.vertexAttribPointer(li, 4, gl.FLOAT, false, 0, 0); gl.vertexAttribDivisor(li, 1);
gl.enable(gl.DEPTH_TEST);
var uvp = gl.getUniformLocation(p, 'vp'), ut = gl.getUniformLocation(p, 't');
function persp(f, a, n, fa) { var t = 1 / Math.tan(f / 2); return [t / a, 0, 0, 0, 0, t, 0, 0, 0, 0, (fa + n) / (n - fa), -1, 0, 0, 2 * fa * n / (n - fa), 0]; }
function mul(a, b) { var o = new Array(16); for (var i = 0; i < 4; i++) for (var j = 0; j < 4; j++) { var s = 0; for (var k = 0; k < 4; k++) s += a[k * 4 + j] * b[i * 4 + k]; o[i * 4 + j] = s; } return o; }
function draw(t) {
  var s = t / 1000, ex = Math.sin(s * .2) * 70, ez = Math.cos(s * .2) * 70;
  var z = [ex / 70, 0, ez / 70], x = [z[2], 0, -z[0]], y = [0, 1, 0];
  var view = [x[0], y[0], z[0], 0, x[1], y[1], z[1], 0, x[2], y[2], z[2], 0, -(x[0] * ex + x[2] * ez), 0, -(z[0] * ex + z[2] * ez), 1];
  gl.viewport(0, 0, c.width, c.height); gl.clearColor(.02, .02, .05, 1); gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
  gl.uniformMatrix4fv(uvp, false, mul(persp(1, c.width / c.height, .1, 300), view)); gl.uniform1f(ut, s);
  gl.drawArraysInstanced(gl.TRIANGLES, 0, 36, N);
}
