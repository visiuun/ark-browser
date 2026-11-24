# Ark benchmarks

Browser-agnostic page-load and GPU benchmarks for an Android device or emulator. Every test page reports its own
timings (first contentful paint, load event) to a local server, so Ark, Firefox and Chrome are measured the same way.

```sh
python -I gen.py                 # writes www/ (pages, CSS/JS/images for the "real site" page, WebGL tests)
python -I server.py 8765         # serves www/, adds 80 ms latency under /net/, records beacons.jsonl
adb reverse tcp:8765 tcp:8765    # xb*.py do this too

# Interleaved A/B (what to trust): every round restarts each browser, warms it up, then loads every page once per
# browser in random order; optional "cold" opens real.html with the browser not running.
python -I xb2.py mylabel 10 arkbase,ark,firefox cold

# Per-browser run including the WebGL shader / 40k-cube 3D scene (use an emulator started with -gpu host, or a phone).
python -I xb.py mylabel ark 6 gpu
```

- Pages: `simple` (tiny), `heavy` (3,000-element DOM + 2M-iteration script), `spa` (CSS-in-JS style injection),
  `real` (6 stylesheets, 8 scripts, 20 images, each with 80 ms latency), `shader` (ray-marched fragment shader),
  `scene3d` (40,000 instanced cubes).
- Install a second Ark next to the normal one with `./gradlew assembleX64Release -ParkIdSuffix=.base` (package
  `dev.vis.ark.base`) to compare two builds side by side.
- After installing, launch Ark once (lets uBlock Origin build its filter lists) and run
  `adb shell cmd package compile -m speed-profile -f dev.vis.ark`, which is what a phone does on its own later.
- `adb shell setprop log.tag.ArkPerf DEBUG` turns on Ark's startup/page timing log lines (`adb logcat -s ArkPerf`).
- `xb2.py` and `xb.py` reset Ark's saved tabs between runs; that needs a rooted emulator (`adb root`).
