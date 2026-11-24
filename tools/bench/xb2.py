"""Interleaved A/B page-load benchmark. Usage: python -I xb2.py <label> <rounds> <browser,browser,...> [cold]
Every round: restart each browser (Ark tabs reset), one warm-up load, then each page once per browser in random
order. Optional cold test: per round, each browser killed and opened straight into real.html.
Reports medians and the mean of per-round differences vs the first browser listed."""
import json, os, random, statistics, subprocess, sys, time, uuid

ADB = os.environ.get("ADB") or (os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe") if os.name == "nt" else "adb")
HERE = os.path.dirname(os.path.abspath(__file__))
BEACONS = os.path.join(HERE, "beacons.jsonl")
BASE = "http://127.0.0.1:8765/"
B = {
    "ark": ("dev.vis.ark", "dev.vis.ark/dev.vis.ark.MainActivity"),
    "arkbase": ("dev.vis.ark.base", "dev.vis.ark.base/dev.vis.ark.MainActivity"),
    "arkx": ("dev.vis.ark.x", "dev.vis.ark.x/dev.vis.ark.MainActivity"),
    "firefox": ("org.mozilla.firefox", "org.mozilla.firefox/org.mozilla.fenix.IntentReceiverActivity"),
    "chrome": ("com.android.chrome", "com.android.chrome/com.google.android.apps.chrome.IntentDispatcher"),
}
PAGES = ["simple.html", "heavy.html", "spa.html", "real.html"]


def sh(*a):
    return subprocess.run([ADB, "shell", *a], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def beacon(rid, timeout):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            with open(BEACONS) as f:
                for line in f:
                    if rid in line:
                        b = json.loads(line)
                        if b.get("id") == rid and b.get("kind") == "load":
                            return b
        except FileNotFoundError:
            pass
        time.sleep(0.05)
    return None


def reset(br):
    pkg = B[br][0]
    sh("am", "force-stop", pkg)
    if br.startswith("ark"):
        sh("sh", "-c", f"sed -i '/<string name=\"tabs\">/,/<.string>/d' /data/data/{pkg}/shared_prefs/ark.xml; rm -f /data/data/{pkg}/files/tabs.json")


def load(br, page, timeout=40):
    rid = uuid.uuid4().hex[:10]
    t = time.time() * 1000
    sh("am", "start", "-a", "android.intent.action.VIEW", "-d", f"'{BASE}{page}?id={rid}'", "-n", B[br][1])
    b = beacon(rid, timeout)
    if not b:
        return None
    return {"wall": round(b["_t"] - t), "fcp": int(b["fcp"]), "load": int(b["load"])}


def main():
    label, rounds, brs = sys.argv[1], int(sys.argv[2]), sys.argv[3].split(",")
    cold = len(sys.argv) > 4 and sys.argv[4] == "cold"
    subprocess.run([ADB, "reverse", "tcp:8765", "tcp:8765"], capture_output=True)
    data = {br: {p: [] for p in PAGES + (["cold"] if cold else [])} for br in brs}
    for r in range(rounds):
        if cold:
            for br in random.sample(brs, len(brs)):
                for o in brs:
                    sh("am", "force-stop", B[o][0])
                reset(br)
                time.sleep(2)
                x = load(br, "real.html", 60)
                data[br]["cold"].append(x["wall"] if x else None)
                time.sleep(1)
        for br in brs:
            reset(br)
        time.sleep(1)
        for br in brs:
            load(br, "simple.html")  # warm-up: process and engine started
            time.sleep(1)
        for page in PAGES:
            for br in random.sample(brs, len(brs)):
                data[br][page].append(load(br, page))
                time.sleep(0.7)
        for br in brs:
            sh("am", "force-stop", B[br][0])
        print(f"round {r + 1}/{rounds} done", flush=True)
    out = {"label": label, "time": time.strftime("%H:%M:%S"), "data": data}
    with open(os.path.join(HERE, "xb2_results.jsonl"), "a") as f:
        f.write(json.dumps(out) + "\n")
    ref = brs[0]
    print(f"\nmedians (ms); diff = mean per-round difference vs {ref} (negative = faster than {ref})")
    for page in data[ref]:
        for metric in (["wall"] if page == "cold" else ["load", "fcp", "wall"]):
            row = []
            for br in brs:
                vals = [(v if page == "cold" else (v or {}).get(metric)) for v in data[br][page]]
                ok = [v for v in vals if v is not None]
                med = statistics.median(ok) if ok else None
                refv = [(v if page == "cold" else (v or {}).get(metric)) for v in data[ref][page]]
                diffs = [a - b for a, b in zip(vals, refv) if a is not None and b is not None]
                d = f" ({statistics.mean(diffs):+.0f})" if br != ref and diffs else ""
                row.append(f"{br}={med}{d}")
            print(f"{page:12s} {metric:5s} " + "  ".join(row))


if __name__ == "__main__":
    main()
