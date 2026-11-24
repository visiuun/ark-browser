"""Cross-browser benchmark. Usage: python -I xb.py <label> <browser> [runs] [tests]
browser: ark | firefox | chrome
Tests (comma list, default all):
  coldload  browser killed, link opened from another app: ms from intent to page 'load' beacon (wall clock)
  load      browser running: each page opened as a link; page-reported fcp/load, plus wall ms to beacon
  gpu       shader.html and scene3d.html: sustained fps and frame-time percentiles
Every page reports through /beacon to server.py, so all browsers are measured the same way.
"""
import json, os, statistics, subprocess, sys, time, uuid

ADB = os.environ.get("ADB") or (os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe") if os.name == "nt" else "adb")
HERE = os.path.dirname(os.path.abspath(__file__))
BEACONS = os.path.join(HERE, "beacons.jsonl")
BASE = "http://127.0.0.1:8765/"
BROWSERS = {
    "ark": ("dev.vis.ark", "dev.vis.ark/.MainActivity"),
    "firefox": ("org.mozilla.firefox", "org.mozilla.firefox/org.mozilla.fenix.IntentReceiverActivity"),
    "chrome": ("com.android.chrome", "com.android.chrome/com.google.android.apps.chrome.IntentDispatcher"),
}


def sh(*a):
    return subprocess.run([ADB, "shell", *a], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def beacon(rid, kind, timeout):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if os.path.exists(BEACONS):
            with open(BEACONS) as f:
                for line in f:
                    if rid in line:
                        b = json.loads(line)
                        if b.get("id") == rid and b.get("kind") == kind:
                            return b
        time.sleep(0.1)
    return None


def open_url(br, url):
    pkg, comp = BROWSERS[br]
    t = time.time() * 1000
    sh("am", "start", "-a", "android.intent.action.VIEW", "-d", f"'{url}'", "-n", comp)
    return t


def coldload(br, runs, page="real.html"):
    pkg, _ = BROWSERS[br]
    out = []
    for i in range(runs):
        sh("am", "force-stop", pkg)
        time.sleep(1.5)
        rid = uuid.uuid4().hex[:10]
        t = open_url(br, f"{BASE}{page}?id={rid}")
        b = beacon(rid, "load", 60)
        if b:
            out.append(round(b["_t"] - t))
        time.sleep(1)
    return out


def load(br, runs, pages=("simple.html", "heavy.html", "spa.html", "real.html")):
    res = {}
    rid = uuid.uuid4().hex[:10]
    open_url(br, f"{BASE}simple.html?id={rid}")  # make sure it's running
    beacon(rid, "load", 60)
    time.sleep(2)
    for page in pages:
        wall, fcp, ld = [], [], []
        for i in range(runs):
            rid = uuid.uuid4().hex[:10]
            t = open_url(br, f"{BASE}{page}?id={rid}")
            b = beacon(rid, "load", 40)
            if b:
                wall.append(round(b["_t"] - t))
                fcp.append(int(b["fcp"]))
                ld.append(int(b["load"]))
            time.sleep(0.8)
        res[page] = {"wall": wall, "fcp": fcp, "load": ld}
    return res


def gpu(br, runs):
    res = {}
    for page in ("shader.html", "scene3d.html"):
        rows = []
        for i in range(runs):
            rid = uuid.uuid4().hex[:10]
            open_url(br, f"{BASE}{page}?id={rid}")
            b = beacon(rid, "gpu", 40)
            if b:
                rows.append({k: float(b[k]) for k in ("fps", "p50", "p95", "p99", "long")})
            time.sleep(1)
        res[page] = rows
    return res


def med(v):
    return round(statistics.median(v), 1) if v else None


def summarize(r):
    s = {}
    if "coldload" in r:
        s["coldload_ms"] = med(r["coldload"])
    for page, v in r.get("load", {}).items():
        s[f"{page}"] = {"wall": med(v["wall"]), "fcp": med(v["fcp"]), "load": med(v["load"])}
    for page, rows in r.get("gpu", {}).items():
        s[page] = {k: med([x[k] for x in rows]) for k in ("fps", "p95", "p99", "long")} if rows else None
    return s


if __name__ == "__main__":
    label, br = sys.argv[1], sys.argv[2]
    runs = int(sys.argv[3]) if len(sys.argv) > 3 else 5
    tests = sys.argv[4].split(",") if len(sys.argv) > 4 else ["coldload", "load", "gpu"]
    subprocess.run([ADB, "reverse", "tcp:8765", "tcp:8765"], capture_output=True)
    # Only one browser alive at a time, so the emulator never runs out of memory.
    for other, (pkg, _) in BROWSERS.items():
        sh("am", "force-stop", pkg)
    if br == "ark":  # start without the previous run's tabs (root emulator)
        sh("sh", "-c", "sed -i '/<string name=\"tabs\">/,/<.string>/d' /data/data/dev.vis.ark/shared_prefs/ark.xml; rm -f /data/data/dev.vis.ark/files/tabs.json")
    time.sleep(2)
    r = {"label": label, "browser": br, "time": time.strftime("%H:%M:%S")}
    if "coldload" in tests: r["coldload"] = coldload(br, runs)
    if "load" in tests: r["load"] = load(br, runs)
    if "gpu" in tests: r["gpu"] = gpu(br, max(2, runs // 2))
    sh("am", "force-stop", BROWSERS[br][0])
    r["summary"] = summarize(r)
    with open(os.path.join(HERE, "xb_results.jsonl"), "a") as f:
        f.write(json.dumps(r) + "\n")
    print(json.dumps(r["summary"], indent=1))
