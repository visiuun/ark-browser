var frames = [], last = 0, t0 = 0, warm = 1000, dur = 6000;
function loop(t) {
  if (!t0) t0 = t;
  if (last && t - t0 > warm) frames.push(t - last);
  last = t;
  draw(t);
  if (t - t0 < warm + dur) requestAnimationFrame(loop); else done();
}
function done() {
  gl.finish();
  frames.sort(function (a, b) { return a - b; });
  var sum = frames.reduce(function (a, b) { return a + b; }, 0);
  var p = function (x) { return frames[Math.min(frames.length - 1, Math.floor(frames.length * x))].toFixed(1); };
  window.__send({ kind: 'gpu', fps: (1000 * frames.length / sum).toFixed(1), p50: p(.5), p95: p(.95), p99: p(.99),
    long: frames.filter(function (f) { return f > 34; }).length, n: frames.length, w: c.width, h: c.height });
  document.title = 'done';
}
requestAnimationFrame(loop);
