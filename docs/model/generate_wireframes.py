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
            "width": width or max(10, int(len(max(content.split("\n"), key=len)) * size * 0.62)),
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

    def elbow(self, x1, y1, turn, x2, y2):
        """Right edge to left edge, turning once in the gap between two layers."""
        self._arrow(x1, y1, [[0, 0], [turn - x1, 0], [turn - x1, y2 - y1], [x2 - x1, y2 - y1]])

    def _arrow(self, x1, y1, points):
        self.add({
            "id": self._id("arrow"),
            "type": "arrow",
            "x": x1, "y": y1,
            "strokeColor": GREY,
            "backgroundColor": "transparent",
            "points": points,
            "width": max(abs(px) for px, _ in points),
            "height": max(abs(py) for _, py in points),
            "lastCommittedPoint": None,
            "startBinding": None,
            "endBinding": None,
            "startArrowhead": None,
            "endArrowhead": "arrow",
            # Sharp corners: a rounded corner over a long span reads as a swoop across the board
            # rather than as a line being routed around something.
            "roundness": None,
        })

    def under(self, x1, y1, floor, x2, y2):
        """Down into a clear lane, across beneath the boxes, and up into the left edge."""
        self._arrow(x1, y1, [[0, 0], [0, floor - y1], [x2 - x1 - 40, floor - y1],
                             [x2 - x1 - 40, y2 - y1], [x2 - x1, y2 - y1]])

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
    board.text(40, 80, wrap(view["statement"], 76), size=16, colour=GREY)
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

    for index, (status, word) in enumerate(
            [("Built", "works"), ("Broken", "does the wrong thing"), ("Missing", "not built")]):
        legend_x = 760 + index * 200
        board.box(legend_x, 120, 24, 24, stroke=STROKE[status], fill=FILL[status], radius=False)
        board.text(legend_x + 34, 124, word, size=13, colour=GREY)

    # States, each a small frame to sketch into.
    column_x = 420
    y = frame_y
    if states:
        board.text(column_x, frame_y - 28, "states", size=14, colour=GREY)
    for state in states:
        body = wrap(state["statement"], 36)
        height = 46 + text_height(body, 12) + 14
        board.box(column_x, y, 300, height, stroke=INK)
        board.text(column_x + 16, y + 12, state["title"], size=16)
        board.text(column_x + 16, y + 38, body, size=12, colour=GREY)
        y += height + 16

    # Requirements, coloured by status, each tied to the story it serves.
    req_x = 760
    y = frame_y
    if requirements:
        board.text(req_x, frame_y - 28, "what it has to do", size=14, colour=GREY)
    for req in requirements:
        status = req["status"]
        title = wrap(req["title"], 34)
        served = [r["to"] for r in req["relations"] if r["role"] == "Fulfils"]
        titles = [stories[uid]["title"] for uid in served if uid in stories]
        line = wrap(f"{status.lower()} · {titles[0]}" if titles else status.lower(), 44)
        height = 10 + text_height(title, 15) + 8 + text_height(line, 11) + 12
        board.box(req_x, y, 380, height, stroke=STROKE[status], fill=FILL[status])
        board.text(req_x + 16, y + 10, title, size=15)
        board.text(req_x + 16, y + height - text_height(line, 11) - 12, line, size=11, colour=GREY)
        y += height + 12
    return board


BOX_W, BOX_H = 280, 110
GUTTER = 150
ROW = 170


def flow_board(views):
    """How the views connect. Laid out in layers so every arrow points forward, and routed through
    the gap between layers so no line crosses a box."""
    board = Board("00-flow")
    board.text(40, 30, "How you move through the app", size=36)
    board.text(40, 84, "Amber and dashed: does not exist yet.", size=14, colour=GREY)

    reached_from = {v["uid"]: [r["to"] for r in v["relations"] if r["role"] == "Reached from"]
                    for v in views}
    by_uid = {v["uid"]: v for v in views}

    # A view sits one layer past the furthest view that leads to it, so an arrow never points back.
    layer = {}
    while len(layer) < len(views):
        for uid, parents in reached_from.items():
            if uid not in layer and all(p in layer for p in parents):
                layer[uid] = max((layer[p] + 1 for p in parents), default=0)

    layers = {}
    for uid, depth in layer.items():
        layers.setdefault(depth, []).append(uid)

    # Within a layer, sit each view level with the views it comes from, which is what removes most
    # of the crossings.
    order = {uid: index for depth in sorted(layers) for index, uid in enumerate(layers[depth])}
    for _ in range(4):
        for depth in sorted(layers)[1:]:
            layers[depth].sort(key=lambda uid: sum(order[p] for p in reached_from[uid]) /
                               max(1, len(reached_from[uid])))
            order.update({uid: index for index, uid in enumerate(layers[depth])})

    placed = {}
    for depth in sorted(layers):
        for index, uid in enumerate(layers[depth]):
            x, y = 40 + depth * (BOX_W + GUTTER), 150 + index * ROW
            placed[uid] = (x, y)
            view = by_uid[uid]
            missing = view["status"] == "Missing"
            board.box(x, y, BOX_W, BOX_H,
                      stroke=MISSING if missing else INK,
                      fill=FILL["Missing"] if missing else "transparent",
                      dashed=missing)
            board.text(x + 20, y + 18, view["title"], size=20)
            board.text(x + 20, y + 52, wrap(view["statement"].split(".")[0], 36), size=11, colour=GREY)

    # Each arrow leaves the right edge, turns in the gap, and arrives at the left edge. Arrows
    # sharing a gap get their own lane so two of them never lie on top of each other.
    floor = 110 + max(len(m) for m in layers.values()) * ROW
    lanes, below = {}, 0
    for uid, parents in reached_from.items():
        for parent in parents:
            x1, y1 = placed[parent]
            x2, y2 = placed[uid]
            gap = x1 + BOX_W
            if layer[uid] - layer[parent] > 1:
                # Skipping a column: go under everything rather than through whatever stands there.
                below += 1
                board.under(gap, y1 + BOX_H / 2, floor + below * 26, x2, y2 + BOX_H / 2)
            else:
                lane = lanes.setdefault(gap, 0)
                lanes[gap] = lane + 1
                board.elbow(gap, y1 + BOX_H / 2, gap + 30 + lane * 22, x2, y2 + BOX_H / 2)
    return board


def markets_board():
    """The markets as one board: what each can do, grouped by the capability, since a filter a
    market applies itself and one applied over its first pages are not the same promise."""
    markets = json.loads((HERE / "markets.json").read_text())
    board = Board("01-markets")
    board.text(40, 30, "The markets", size=36)
    board.text(40, 80, wrap(
        f"{len(markets['markets'])} markets carry a crawler. What each can do is declared in the "
        "crawler and read from there, so this board cannot quietly stop being true.", 90),
        size=16, colour=GREY)

    flags = [
        ("paginates", "fetches every page"),
        ("nativeCriteria", "filters at the source"),
        ("soldListings", "knows what sold"),
        ("relatedSearches", "suggests related searches"),
        ("listingAge", "knows an ad's age"),
        ("location", "knows where the thing is"),
        ("detailSpecs", "carries specs on the detail page"),
    ]
    x, y = 40, 200
    for flag, title in flags:
        holders = [m["name"] for m in markets["markets"] if m[flag]]
        body = wrap(", ".join(holders) or "none", 52)
        height = 44 + text_height(body, 12) + 16
        board.box(x, y, 420, height, stroke=INK)
        board.text(x + 16, y + 12, f"{title} — {len(holders)}", size=15)
        board.text(x + 16, y + 40, body, size=12, colour=GREY)
        y += height + 14

    # Markets that declare nothing: a search reaches them, and everything about them is guessed
    # from the first page it gets back.
    plain = [m["name"] for m in markets["markets"]
             if not any(m[flag] for flag, _ in flags)]
    body = wrap(", ".join(plain), 52)
    height = 44 + text_height(body, 12) + 16
    board.box(x, y, 420, height, stroke=GREY)
    board.text(x + 16, y + 12, f"declares nothing — {len(plain)}", size=15, colour=GREY)
    board.text(x + 16, y + 40, body, size=12, colour=GREY)

    # A market offered without a crawler answers nothing, and the app says nothing about it.
    y = 200
    for platform in markets["withoutCrawler"]:
        board.box(500, y, 380, 78, stroke=BROKEN, fill=FILL["Broken"])
        board.text(516, y + 14, platform["name"], size=16)
        board.text(516, y + 44, "offered in the app, no crawler exists", size=11, colour=GREY)
        y += 92
    return board


def main():
    views = MODEL["views"]
    stories = {s["uid"]: s for s in MODEL["stories"]}
    written = [flow_board(views).write(), markets_board().write()]
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
