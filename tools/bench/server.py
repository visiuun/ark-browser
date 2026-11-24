"""Benchmark web server. Serves ./www, adds per-request latency for /net/* (simulated real-world RTT),
and records beacons (GET /beacon?...) with arrival time into beacons.jsonl."""
import json, os, sys, time, threading
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

HERE = os.path.dirname(os.path.abspath(__file__))
WWW = os.path.join(HERE, "www")
LOG = os.path.join(HERE, "beacons.jsonl")
lock = threading.Lock()


class H(SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def __init__(self, *a, **k):
        super().__init__(*a, directory=WWW, **k)

    def log_message(self, *a):
        pass

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def do_GET(self):
        u = urlparse(self.path)
        if u.path == "/beacon":
            q = {k: v[0] for k, v in parse_qs(u.query).items()}
            q["_t"] = time.time() * 1000
            q["_ua"] = self.headers.get("User-Agent", "")
            with lock, open(LOG, "a") as f:
                f.write(json.dumps(q) + "\n")
            self.send_response(204)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if u.path.startswith("/net/"):
            time.sleep(0.08)  # 80 ms server/RTT latency per request
            self.path = self.path.replace("/net/", "/", 1)
        return super().do_GET()


if __name__ == "__main__":
    ThreadingHTTPServer.daemon_threads = True
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 8765), H).serve_forever()
