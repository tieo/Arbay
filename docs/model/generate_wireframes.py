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


def load_screenshots(folder="small", prefix="shot"):
    """The renders of the real screens, embedded so a board is one self-contained file."""
    shots = {}
    for path in sorted((HERE / "img" / folder).glob("*.png")):
        raw = path.read_bytes()
        width, height = png_size(raw)
        shots[path.name] = {
            "id": f"{prefix}-{path.stem}",
            "width": width,
            "height": height,
            "file": {
                "mimeType": "image/png",
                "id": f"{prefix}-{path.stem}",
                "dataURL": "data:image/png;base64," + base64.b64encode(raw).decode(),
                "created": 1,
                "lastRetrieved": 1,
            },
        }
    return shots


SHOTS = load_screenshots()
# The same screens cropped to their top, for the index: a whole scroll shrunk into a card is a
# sliver nobody can recognise.
CARDS = load_screenshots("card", "card")


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

    # What the rework does to this view, beside what it is today.
    plan = view.get("plan")
    if plan:
        plan_x = 1200
        y = frame_y
        board.text(plan_x, frame_y - 28, "after the rework", size=14, colour=GREY)
        for heading, lines, colour in [
                ("on the canvas", plan["canvas"], BUILT),
                ("one tap away", plan["behind"], INK),
                ("gone", plan["gone"], BROKEN)]:
            if not lines:
                continue
            body = "\n".join(f"·  {wrap(line, 44)}".replace("\n", "\n   ") for line in lines)
            height = 40 + text_height(body, 12) + 16
            board.box(plan_x, y, 460, height, stroke=colour)
            board.text(plan_x + 16, y + 12, heading, size=14, colour=colour)
            board.text(plan_x + 16, y + 38, body, size=12, colour=GREY)
            y += height + 14
    return board


CARD_W, CARD_H = 300, 460
CARD_GAP = 28


def index_board(views):
    """The way in: every view as its own thumbnail, linking to that view's board.

    A flow diagram said how the views connect, which is worth one line of prose and not a page. What
    is worth a page is seeing all nine screens at once as they render today, and getting to any of
    them in one click.
    """
    board = Board("00-views")
    board.files = {}
    board.text(40, 30, "The views", size=36)
    board.text(
        40, 84,
        "Every screen of the app as it renders today. Click a card to open that view's board.",
        size=15, colour=GREY,
    )

    for index, view in enumerate(views):
        column, row = index % 4, index // 4
        x = 40 + column * (CARD_W + CARD_GAP)
        y = 150 + row * (CARD_H + CARD_GAP + 40)
        missing = view["status"] == "Missing"
        link = f"#{slug(view['uid'])}"

        card = board.box(
            x, y, CARD_W, CARD_H,
            stroke=MISSING if missing else INK,
            fill=FILL["Missing"] if missing else "transparent",
            dashed=missing,
        )
        card["link"] = link

        board.text(x + 20, y + 16, view["title"], size=22)
        board.text(x + 20, y + 46, wrap(view["statement"].split(".")[0], 36), size=11, colour=GREY)

        shot = view.get("screenshot")
        image = CARDS.get(shot) if shot else None
        top = y + 92
        if image:
            scale = min((CARD_W - 32) / image["width"], (CARD_H - 104) / image["height"])
            width, height = int(image["width"] * scale), int(image["height"] * scale)
            board.image(x + (CARD_W - width) / 2, top, width, height, image["id"])
            board.files[image["id"]] = image["file"]
        else:
            board.text(x + 70, top + 120, "nothing renders\nthis yet", size=14, colour=GREY)

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
    index = index_board(views)
    written = [index.write(files=index.files), markets_board().write()]
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
