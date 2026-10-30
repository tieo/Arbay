"""
Reads Yandex's silhouette challenge: a picture with line symbols drawn over a painting, a few faint
ones among them that are not asked for, and a strip of the asked symbols' outlines in the order to
click them. Returns where to click.

A small network (yandex_symbols.onnx, trained on paintings with symbols drawn on them) marks the
symbols' strokes and leaves the painting and the faint symbols out; each marked part is then
compared with each outline of the strip over every turn, and each outline gets the part that fits
it, no part twice.
"""
import os

import cv2
import numpy as np

MODEL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "yandex_symbols.onnx")
SIDE = 64
_session = None


def flat(png_bytes):
    im = cv2.imdecode(np.frombuffer(png_bytes, np.uint8), cv2.IMREAD_UNCHANGED)
    if im.ndim == 3 and im.shape[2] == 4:
        a = im[:, :, 3:4].astype(np.float32) / 255
        im = (im[:, :, :3] * a + 255 * (1 - a)).astype(np.uint8)
    return im


def strokes(picture):
    """How likely each pixel is part of an asked symbol's stroke."""
    global _session
    if _session is None:
        import onnxruntime
        _session = onnxruntime.InferenceSession(MODEL, providers=["CPUExecutionProvider"])
    x = picture.astype(np.float32).transpose(2, 0, 1)[None] / 255
    y = _session.run(None, {"x": x})[0][0, 0]
    return 1 / (1 + np.exp(-y))


def parts(prob, threshold=0.5, min_area=40):
    """The symbols the network marked, each with its centre; a symbol's separate strokes (a face's
    eyes) join it when they are a few pixels apart."""
    m = (prob > threshold).astype(np.uint8)
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3)))
    joined = cv2.dilate(m, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7)))
    n, lab = cv2.connectedComponents(joined)
    out = []
    for c in range(1, n):
        part = (lab == c) & (m > 0)
        if part.sum() < min_area:
            continue
        ys, xs = np.nonzero(part)
        out.append((part[ys.min():ys.max() + 1, xs.min():xs.max() + 1], (float(xs.mean()), float(ys.mean()))))
    return out


def outlines(strip, n=6):
    """The strip's outlines, left to right; the hatching across it is thinner than their strokes."""
    g = cv2.cvtColor(strip, cv2.COLOR_BGR2GRAY)
    ink = (g < 128).astype(np.uint8)
    ink = cv2.morphologyEx(ink, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5)))
    count, lab, st, _ = cv2.connectedComponentsWithStats(ink)
    w = strip.shape[1] // n
    out = []
    for i in range(n):
        part = np.zeros_like(ink)
        for c in range(1, count):
            x, _, cw, _, area = st[c]
            if area >= 15 and i * w <= x + cw / 2 < (i + 1) * w:
                part[lab == c] = 1
        ys, xs = np.nonzero(part)
        if len(xs):
            out.append(part[ys.min():ys.max() + 1, xs.min():xs.max() + 1])
    return out


def thin(mask):
    """A mask's strokes worn down to about a pixel, so a thick outline and a thin symbol compare."""
    m = (mask > 0).astype(np.uint8)
    skel = np.zeros_like(m)
    cross = cv2.getStructuringElement(cv2.MORPH_CROSS, (3, 3))
    while m.any():
        eroded = cv2.erode(m, cross)
        skel |= m & ~cv2.dilate(eroded, cross)
        m = eroded
    return skel


def points(mask):
    """A shape's centre line around its centroid, scaled so its farthest point is at radius 1."""
    ys, xs = np.nonzero(thin(mask))
    if len(xs) < 5:
        return None
    mo = cv2.moments(mask.astype(np.uint8))
    cx, cy = (mo["m10"] / mo["m00"], mo["m01"] / mo["m00"]) if mo["m00"] else (xs.mean(), ys.mean())
    p = np.stack([xs - cx, ys - cy], 1).astype(np.float32)
    return p / max(float(np.sqrt((p ** 2).sum(1)).max()), 1e-6)


def render(p, angle=0.0):
    a = np.deg2rad(angle)
    rot = np.array([[np.cos(a), -np.sin(a)], [np.sin(a), np.cos(a)]], np.float32)
    q = ((p @ rot.T) * (SIDE / 2 - 4) + SIDE / 2).astype(int)
    c = np.zeros((SIDE, SIDE), np.uint8)
    for x, y in q:
        cv2.circle(c, (int(x), int(y)), 2, 1, -1)
    return c


def similarity(a, b):
    """How well shape b fits shape a at its best turn: the overlap of their drawn centre lines."""
    ra = render(a)
    best = 0.0
    for angle in range(0, 360, 5):
        rb = render(b, angle)
        best = max(best, (ra & rb).sum() / max(1, (ra | rb).sum()))
    return best


def assignment(cost):
    """The column for each row that makes the total cost least, each column used once (Hungarian
    method; rows no more than columns)."""
    n, m = cost.shape
    inf = float("inf")
    u, v = [0.0] * (n + 1), [0.0] * (m + 1)
    p, way = [0] * (m + 1), [0] * (m + 1)
    for i in range(1, n + 1):
        p[0], j0 = i, 0
        minv, used = [inf] * (m + 1), [False] * (m + 1)
        while True:
            used[j0] = True
            i0, delta, j1 = p[j0], inf, 0
            for j in range(1, m + 1):
                if not used[j]:
                    cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
                    if cur < minv[j]:
                        minv[j], way[j] = cur, j0
                    if minv[j] < delta:
                        delta, j1 = minv[j], j
            for j in range(m + 1):
                if used[j]:
                    u[p[j]] += delta
                    v[j] -= delta
                else:
                    minv[j] -= delta
            j0 = j1
            if p[j0] == 0:
                break
        while j0:
            j1 = way[j0]
            p[j0] = p[j1]
            j0 = j1
    pick = [0] * n
    for j in range(1, m + 1):
        if p[j]:
            pick[p[j] - 1] = j - 1
    return pick


def solve(picture_png, strip_png):
    """Click points in the picture's pixels, in the strip's order; empty where the picture holds
    fewer symbols than the strip asks for."""
    picture, strip = flat(picture_png), flat(strip_png)
    found = parts(strokes(picture))
    asked = [points(o) for o in outlines(strip)]
    shapes = [points(m) for m, _ in found]
    if len(found) < len(asked):
        return []
    score = np.array([[similarity(s, a) if a is not None and s is not None else 0.0 for s in shapes] for a in asked])
    return [found[j][1] for j in assignment(-score)]
