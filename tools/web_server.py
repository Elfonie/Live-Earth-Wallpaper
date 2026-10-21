"""Dev server for web/ (python tools/web_server.py [port]); a POST to /capture.png saves the posted PNG to tools/build/web_capture.png."""
import sys
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent


class Handler(SimpleHTTPRequestHandler):
    def do_POST(self):
        data = self.rfile.read(int(self.headers["Content-Length"]))
        (ROOT / "build" / "web_capture.png").write_bytes(data)
        self.send_response(204)
        self.end_headers()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8791
    ThreadingHTTPServer(("127.0.0.1", port), partial(Handler, directory=str(ROOT.parent / "web"))).serve_forever()
