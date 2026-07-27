#!/usr/bin/env python3
"""Serves the model editor and the boards it edits.

    python3 docs/model/server.py [--port 8099] [--wireframes DIR] [--web DIR]

The boards are files in the repository; this process is the only thing that writes them, so an edit
made in the browser is a diff. GET /api/boards lists them, GET and PUT /api/boards/<name> read and
write one. Everything else is the built editor.
"""

import argparse
import http.server
import json
import pathlib
import re
import socketserver

NAME = re.compile(r"^[a-z0-9][a-z0-9-]*$")

# Boards are listed in the order someone walks the app, under the name the view carries.
ORDER = ["00-views", "01-markets", "home", "discovery", "car-search", "results",
         "filters", "market-detail", "price-detail", "free-items", "settings"]

TITLES = {
    "00-views": "The views",
    "01-markets": "The markets",
    "discovery": "Search",
    "car-search": "Vehicle search",
    "market-detail": "Markets",
    "price-detail": "Price",
    "free-items": "Free items",
}


def title_of(name):
    return TITLES.get(name) or name.replace("-", " ").capitalize()


class Handler(http.server.SimpleHTTPRequestHandler):
    wireframes: pathlib.Path
    web: pathlib.Path

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(self.web), **kwargs)

    def log_message(self, fmt, *args):
        pass

    def board_path(self, name):
        if not NAME.match(name):
            return None
        path = self.wireframes / f"{name}.excalidraw"
        return path if path.parent == self.wireframes else None

    def send_json(self, payload, status=200):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/api/boards":
            found = {p.stem for p in self.wireframes.glob("*.excalidraw")}
            boards = [n for n in ORDER if n in found] + sorted(found - set(ORDER))
            return self.send_json([{"name": n, "title": title_of(n)} for n in boards])
        if self.path.startswith("/api/boards/"):
            path = self.board_path(self.path.removeprefix("/api/boards/"))
            if path is None or not path.exists():
                return self.send_json({"error": "no such board"}, 404)
            return self.send_json(json.loads(path.read_text()))
        # Anything the editor asks for that is not a file is the editor itself, so a reload of
        # /#results does not 404.
        if not (self.web / self.path.lstrip("/")).is_file():
            self.path = "/index.html"
        return super().do_GET()

    def do_PUT(self):
        if not self.path.startswith("/api/boards/"):
            return self.send_json({"error": "not writable"}, 404)
        path = self.board_path(self.path.removeprefix("/api/boards/"))
        if path is None:
            return self.send_json({"error": "bad name"}, 400)
        try:
            scene = json.loads(self.rfile.read(int(self.headers["Content-Length"] or 0)))
        except (ValueError, TypeError):
            return self.send_json({"error": "not a scene"}, 400)
        if scene.get("type") != "excalidraw" or not isinstance(scene.get("elements"), list):
            return self.send_json({"error": "not a scene"}, 400)
        # Written whole and moved into place, so a board is never half a file if this dies mid-write.
        temporary = path.with_suffix(".excalidraw.writing")
        temporary.write_text(json.dumps(scene, indent=2, ensure_ascii=False))
        temporary.replace(path)
        return self.send_json({"saved": path.name, "elements": len(scene["elements"])})


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    here = pathlib.Path(__file__).parent
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--wireframes", type=pathlib.Path, default=here / "wireframes")
    parser.add_argument("--web", type=pathlib.Path, default=here / "web" / "dist")
    arguments = parser.parse_args()

    Handler.wireframes = arguments.wireframes.resolve()
    Handler.web = arguments.web.resolve()
    print(f"boards {Handler.wireframes}\neditor {Handler.web}\nhttp://{arguments.host}:{arguments.port}")
    with Server((arguments.host, arguments.port), Handler) as server:
        server.serve_forever()


if __name__ == "__main__":
    main()
