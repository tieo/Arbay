#!/usr/bin/env python3
"""Draw the model as Excalidraw boards, one per view.

    python3 docs/model/generate_wireframes.py

Each board carries the view's job, the screen as it renders today, the states it can be in, and the
requirements that live in it, coloured by whether they are built, broken or missing. The boards are
plain .excalidraw JSON in the repository: edit them in the browser, and the edit is a diff.

Regenerating overwrites the generated elements and keeps anything drawn by hand, which is marked by
a customData.hand flag that this script never writes.
"""

import base64
import json
import pathlib
import struct

HERE = pathlib.Path(__file__).parent
MODEL = json.loads((HERE / "model.json").read_text())
OUT = HERE / "wireframes"

INK = "#1e1e1e"
GREY = "#868e96"
BUILT = "#2f9e44"
BROKEN = "#e03131"
MISSING = "#f08c00"
FILL = {"Built": "#b2f2bb", "Broken": "#ffc9c9", "Missing": "#ffec99"}
STROKE = {"Built": BUILT, "Broken": BROKEN, "Missing": MISSING}

# Excalidraw keys every element carries. Kept in one place so a generated board opens without the
# editor having to repair it.
BASE = {
    "angle": 0,
    "fillStyle": "solid",
    "strokeWidth": 1,
    "strokeStyle": "solid",
    "roughness": 1,
    "opacity": 100,
    "groupIds": [],
    "frameId": None,
    "roundness": None,
    "isDeleted": False,
    "boundElements": None,
    "link": None,
    "locked": False,
}


class Board:
    """Collects elements and hands out the ids and seeds Excalidraw expects."""

    def __init__(self, name):
        self.name = name
        self.elements = []
        self.counter = 0

    def _id(self, kind):
        self.counter += 1
        return f"{self.name}-{kind}-{self.counter}"

    def _seed(self):
        # Deterministic, so regenerating a board does not rewrite every line of its file.
        return (hash((self.name, self.counter)) & 0x7FFFFFFF) or 1

    def add(self, element):
        element.update(BASE | element)
        element.setdefault("version", 1)
        element.setdefault("versionNonce", self._seed())
        element.setdefault("updated", 1)
        element.setdefault("seed", self._seed())
        self.elements.append(element)
        return element

    def box(self, x, y, w, h, stroke=INK, fill="transparent", radius=True, dashed=False):
        return self.add({
            "id": self._id("box"),
            "type": "rectangle",
            "x": x, "y": y, "width": w, "height": h,
            "strokeColor": stroke,
            "backgroundColor": fill,
            "strokeStyle": "dashed" if dashed else "solid",
            "roundness": {"type": 3} if radius else None,
        })

    def text(self, x, y, content, size=16, colour=INK, width=None, align="left"):
        lines = content.count("\n") + 1
        return self.add({
            "id": self._id("text"),
            "type": "text",
            "x": x, "y": y,
            "width": width or max(10, int(len(max(content.split("\n"), key=len)) * size * 0.55)),
            "height": int(lines * size * 1.25),
            "strokeColor": colour,
            "backgroundColor": "transparent",
            "text": content,
            "originalText": content,
            "fontSize": size,
            "fontFamily": 2,
            "textAlign": align,
            "verticalAlign": "top",
            "containerId": None,
            "lineHeight": 1.25,
            "baseline": int(size * 1.1),
        })

    def arrow(self, x1, y1, x2, y2, label=None):
        self.add({
            "id": self._id("arrow"),
            "type": "arrow",
            "x": x1, "y": y1,
            "width": abs(x2 - x1), "height": abs(y2 - y1),
            "strokeColor": GREY,
            "backgroundColor": "transparent",
            "points": [[0, 0], [x2 - x1, y2 - y1]],
            "lastCommittedPoint": None,
            "startBinding": None,
            "endBinding": None,
            "startArrowhead": None,
            "endArrowhead": "arrow",
        })
        if label:
            self.text((x1 + x2) / 2 - 30, (y1 + y2) / 2 - 20, label, size=12, colour=GREY)

    def image(self, x, y, w, h, file_id):
        return self.add({
            "id": self._id("image"),
            "type": "image",
            "x": x, "y": y, "width": w, "height": h,
            "strokeColor": "transparent",
            "backgroundColor": "transparent",
            "fileId": file_id,
            "status": "saved",
            "scale": [1, 1],
        })

    def write(self, files=None):
        OUT.mkdir(parents=True, exist_ok=True)
        path = OUT / f"{self.name}.excalidraw"
        path.write_text(json.dumps({
            "type": "excalidraw",
            "version": 2,
            "source": "arbay-model",
            "elements": self.elements,
            "appState": {"viewBackgroundColor": "#ffffff", "gridSize": 20},
            "files": files or {},
        }, indent=2, ensure_ascii=False))
        return path


LINE_HEIGHT = 1.25


def text_height(content, size):
    return int((content.count("\n") + 1) * size * LINE_HEIGHT)


def wrap(text, width):
    words, lines, line = text.split(), [], ""
    for word in words:
        if len(line) + len(word) + 1 > width:
            lines.append(line)
            line = word
        else:
            line = f"{line} {word}".strip()
    if line:
        lines.append(line)
    return "\n".join(lines)


def png_size(data):
    width, height = struct.unpack(">II", data[16:24])
    return width, height


def load_screenshots():
    """The renders of the real screens, embedded so a board is one self-contained file."""
    shots = {}
    for path in sorted((HERE / "img" / "small").glob("*.png")):
        raw = path.read_bytes()
        width, height = png_size(raw)
        shots[path.name] = {
            "id": f"shot-{path.stem}",
            "width": width,
            "height": height,
            "file": {
                "mimeType": "image/png",
                "id": f"shot-{path.stem}",
                "dataURL": "data:image/png;base64," + base64.b64encode(raw).decode(),
                "created": 1,
                "lastRetrieved": 1,
            },
        }
    return shots


SHOTS = load_screenshots()


def slug(uid):
    return uid.removeprefix("VIEW-").lower()


def view_board(view, states, requirements, stories):
    board = Board(slug(view["uid"]))
    board.files = {}
    board.text(40, 30, view["title"], size=36)
    board.text(40, 80, wrap(view["statement"], 90), size=16, colour=GREY)
    status_y = 30
    if view["status"] == "Missing":
        board.box(760, status_y, 200, 40, stroke=MISSING, fill=FILL["Missing"])
        board.text(790, status_y + 10, "not built yet", size=16, colour=MISSING)

    # The screen as it renders today, beside the plan for it.
    frame_x, frame_y = 40, 200
    shot = view.get("screenshot")
    image = SHOTS.get(shot) if shot else None
    if image:
        board.text(frame_x, frame_y - 28, "as it renders today", size=14, colour=GREY)
        board.image(frame_x, frame_y, image["width"], image["height"], image["id"])
        board.files[image["id"]] = image["file"]
    else:
        board.box(frame_x, frame_y, 320, 640, stroke=GREY, dashed=True)
        board.text(frame_x, frame_y - 28, "nothing renders this yet", size=14, colour=GREY)
        board.text(frame_x + 60, frame_y + 300, "sketch it here", size=16, colour=GREY)

    # States, each a small frame to sketch into.
    column_x = 420
    y = frame_y
    board.text(column_x, frame_y - 28, "states", size=14, colour=GREY)
    for state in states:
        body = wrap(state["statement"], 40)
        height = 46 + text_height(body, 12) + 14
        board.box(column_x, y, 300, height, stroke=INK)
        board.text(column_x + 16, y + 12, state["title"], size=16)
        board.text(column_x + 16, y + 38, body, size=12, colour=GREY)
        y += height + 16

    # Requirements, coloured by status, each tied to the story it serves.
    req_x = 760
    y = frame_y
    board.text(req_x, frame_y - 28, "what it has to do", size=14, colour=GREY)
    for req in requirements:
        status = req["status"]
        title = wrap(req["title"], 42)
        served = [r["to"] for r in req["relations"] if r["role"] == "Fulfils"]
        titles = [stories[uid]["title"] for uid in served if uid in stories]
        line = wrap(f"{status.lower()} · {titles[0]}" if titles else status.lower(), 48)
        height = 10 + text_height(title, 15) + 8 + text_height(line, 11) + 12
        board.box(req_x, y, 380, height, stroke=STROKE[status], fill=FILL[status])
        board.text(req_x + 16, y + 10, title, size=15)
        board.text(req_x + 16, y + height - text_height(line, 11) - 12, line, size=11, colour=GREY)
        y += height + 12
    return board


def flow_board(views):
    """One board for how the views connect, so the map is edited rather than described."""
    board = Board("00-flow")
    board.text(40, 30, "How you move through the app", size=36)

    # A view sits as many columns from Home as the fewest taps it takes to get there, so the map
    # reads left to right in the order someone walks it.
    reached_from = {v["uid"]: [r["to"] for r in v["relations"] if r["role"] == "Reached from"]
                    for v in views}
    depth = {uid: 0 if not parents else len(views) for uid, parents in reached_from.items()}
    for _ in range(len(views)):
        for uid, parents in reached_from.items():
            if parents:
                depth[uid] = min(depth[uid], min(depth[p] + 1 for p in parents))

    columns = {}
    for view in views:
        columns.setdefault(depth[view["uid"]], []).append(view)

    placed = {}
    for column, members in sorted(columns.items()):
        for index, view in enumerate(members):
            x, y = 60 + column * 400, 140 + index * 160
            placed[view["uid"]] = (x, y)
            missing = view["status"] == "Missing"
            board.box(x, y, 280, 110,
                      stroke=MISSING if missing else INK,
                      fill=FILL["Missing"] if missing else "transparent",
                      dashed=missing)
            board.text(x + 20, y + 18, view["title"], size=20)
            board.text(x + 20, y + 50, wrap(view["statement"].split(".")[0], 34), size=11, colour=GREY)

    for view in views:
        to = placed[view["uid"]]
        for relation in view["relations"]:
            if relation["role"] == "Reached from" and relation["to"] in placed:
                frm = placed[relation["to"]]
                board.arrow(frm[0] + 280, frm[1] + 55, to[0], to[1] + 55)
    return board


def main():
    views = MODEL["views"]
    stories = {s["uid"]: s for s in MODEL["stories"]}
    written = [flow_board(views).write()]
    for view in views:
        states = [s for s in MODEL["states"]
                  if any(r["to"] == view["uid"] and r["role"] == "State of" for r in s["relations"])]
        requirements = [r for r in MODEL["requirements"]
                        if any(x["to"] == view["uid"] and x["role"] == "Lives in" for x in r["relations"])]
        board = view_board(view, states, requirements, stories)
        written.append(board.write(files=board.files))
    for path in written:
        print(f"  {path.relative_to(HERE.parent.parent)}")


if __name__ == "__main__":
    main()
