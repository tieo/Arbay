#!/usr/bin/env python3
"""Serves the model: every view of the app, what each has to do, and how each renders today.

    python3 docs/model/server.py [--port 8099]

Everything it serves is a file in the repository. GET /api/model reads the model, PUT writes it, so
a note typed in the browser is a diff. Screenshots are served as files rather than embedded in
anything, and the sketch canvases keep their own endpoints because a drawing is a different kind of
thing from a paragraph.
"""

import argparse
import http.server
import json
import pathlib
import re
import socketserver

NAME = re.compile(r"^[a-z0-9][a-z0-9-]*$")


class Handler(http.server.SimpleHTTPRequestHandler):
    root: pathlib.Path
    web: pathlib.Path

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(self.web), **kwargs)

    def log_message(self, fmt, *args):
        pass

    def send_json(self, payload, status=200):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_file(self, path, content_type):
        if not path.is_file():
            return self.send_json({"error": "no such file"}, 404)
        body = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(body)

    def sketch_path(self, name):
        if not NAME.match(name):
            return None
        return self.root / "wireframes" / f"{name}.excalidraw"

    def do_GET(self):
        if self.path == "/api/model":
            return self.send_file(self.root / "model.json", "application/json")
        if self.path == "/api/markets":
            return self.send_file(self.root / "markets.json", "application/json")
        if self.path.startswith("/img/"):
            name = self.path.removeprefix("/img/").split("?")[0]
            path = (self.root / "img" / name).resolve()
            if not str(path).startswith(str((self.root / "img").resolve())):
                return self.send_json({"error": "outside"}, 400)
            return self.send_file(path, "image/png")
        if self.path.startswith("/api/sketch/"):
            path = self.sketch_path(self.path.removeprefix("/api/sketch/"))
            if path is None:
                return self.send_json({"error": "bad name"}, 400)
            if not path.exists():
                return self.send_json({"type": "excalidraw", "version": 2, "elements": [], "files": {}})
            return self.send_file(path, "application/json")
        # Anything that is not a file is the app itself, so reloading /#/view/results works.
        if not (self.web / self.path.lstrip("/").split("?")[0]).is_file():
            self.path = "/index.html"
        return super().do_GET()

    def do_PUT(self):
        length = int(self.headers["Content-Length"] or 0)
        try:
            payload = json.loads(self.rfile.read(length))
        except ValueError:
            return self.send_json({"error": "not json"}, 400)

        if self.path == "/api/model":
            if not isinstance(payload, dict) or "views" not in payload:
                return self.send_json({"error": "not a model"}, 400)
            return self.write_json(self.root / "model.json", payload)

        if self.path.startswith("/api/sketch/"):
            path = self.sketch_path(self.path.removeprefix("/api/sketch/"))
            if path is None:
                return self.send_json({"error": "bad name"}, 400)
            if payload.get("type") != "excalidraw":
                return self.send_json({"error": "not a scene"}, 400)
            path.parent.mkdir(parents=True, exist_ok=True)
            return self.write_json(path, payload)

        return self.send_json({"error": "not writable"}, 404)

    def write_json(self, path, payload):
        # Written whole and moved into place, so a file is never half a file.
        temporary = path.with_suffix(path.suffix + ".writing")
        temporary.write_text(json.dumps(payload, indent=2, ensure_ascii=False))
        temporary.replace(path)
        return self.send_json({"saved": path.name})


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    here = pathlib.Path(__file__).parent
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--root", type=pathlib.Path, default=here)
    parser.add_argument("--web", type=pathlib.Path, default=here / "web" / "dist")
    arguments = parser.parse_args()

    Handler.root = arguments.root.resolve()
    Handler.web = arguments.web.resolve()
    print(f"model {Handler.root}\nweb   {Handler.web}\nhttp://{arguments.host}:{arguments.port}")
    with Server((arguments.host, arguments.port), Handler) as server:
        server.serve_forever()


if __name__ == "__main__":
    main()
