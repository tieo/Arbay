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
# Pixels of a blob left of the hatching at most, of an outline at least, and the width of one outline.
MIN_PIECE = 80
MIN_OUTLINE = 300
ONE_WIDE = 75
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


def outlines(strip):
    """The strip's outlines, left to right. The hatching across it is thinner than their strokes and
    goes with the opening, but for small blobs where its lines cross; the outlines stand apart by a
    few empty columns, not on an even grid, and two that touch make a run of ink too wide for one."""
    g = cv2.cvtColor(strip, cv2.COLOR_BGR2GRAY)
    ink = (g < 128).astype(np.uint8)
    ink = cv2.morphologyEx(ink, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5)))
    count, lab, st, _ = cv2.connectedComponentsWithStats(ink)
    for c in range(1, count):
        if st[c][4] < MIN_PIECE:
            ink[lab == c] = 0
    columns = ink.sum(0)
    runs = []
    for x in np.nonzero(columns)[0]:
        if runs and x - runs[-1][1] <= 4:
            runs[-1][1] = x
        else:
            runs.append([x, x])
    # An outline of separate strokes (three bars) leaves runs too narrow for one: each joins the
    # nearer of its neighbours while the two together still fit one outline.
    merged = True
    while merged:
        merged = False
        for i, (a, b) in enumerate(runs):
            if b - a >= ONE_WIDE * 0.55:
                continue
            near = [j for j in (i - 1, i + 1) if 0 <= j < len(runs)
                    and max(b, runs[j][1]) - min(a, runs[j][0]) <= ONE_WIDE * 1.3]
            if near:
                j = min(near, key=lambda j: runs[j][0] - b if j > i else a - runs[j][1])
                lo, hi = min(i, j), max(i, j)
                runs[lo:hi + 1] = [[runs[lo][0], runs[hi][1]]]
                merged = True
                break
    runs = [(a, b + 1) for a, b in runs if columns[a:b + 1].sum() >= MIN_OUTLINE]
    cut = []
    for a, b in runs:
        k = max(1, int((b - a) / ONE_WIDE + 0.25))
        edges = [a]
        for i in range(1, k):
            # where touching outlines meet: the emptiest column near the even split
            mid = a + (b - a) * i // k
            lo, hi = max(a + 1, mid - 12), min(b - 1, mid + 12)
            edges.append(lo + int(np.argmin(columns[lo:hi])))
        edges.append(b)
        cut += list(zip(edges[:-1], edges[1:]))
    out = []
    for a, b in cut:
        part = np.zeros_like(ink)
        part[:, a:b] = ink[:, a:b]
        ys, xs = np.nonzero(part)
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
    """A shape's centre line around its centroid, scaled by its spread (the root mean square of its
    points' distances) rather than its farthest point, which one stray stroke would set."""
    ys, xs = np.nonzero(thin(mask))
    if len(xs) < 5:
        return None
    mo = cv2.moments(mask.astype(np.uint8))
    cx, cy = (mo["m10"] / mo["m00"], mo["m01"] / mo["m00"]) if mo["m00"] else (xs.mean(), ys.mean())
    p = np.stack([xs - cx, ys - cy], 1).astype(np.float32)
    spread = float(np.sqrt(((p ** 2).sum(1)).mean())) * 1.6
    return p / max(spread, 1e-6)


def drawn(p, angle=0.0):
    """Shape p turned by [angle] on a canvas: the canvas (its line at 0, the rest 1) and the points."""
    a = np.deg2rad(angle)
    rot = np.array([[np.cos(a), -np.sin(a)], [np.sin(a), np.cos(a)]], np.float32)
    q = np.clip(((p @ rot.T) * (SIDE / 2 - 4) + SIDE / 2).astype(int), 0, SIDE - 1)
    c = np.ones((SIDE, SIDE), np.uint8)
    c[q[:, 1], q[:, 0]] = 0
    return c, q


def similarity(a, b):
    """How well shape b fits shape a at its best turn: minus the mean distance from each one's line
    to the other's, both ways (chamfer distance). It tells a face from an envelope where the
    overlap of the two lines did not."""
    ca, qa = drawn(a)
    da = cv2.distanceTransform(ca, cv2.DIST_L2, 3)
    best = float("inf")
    for angle in range(0, 360, 3):
        cb, qb = drawn(b, angle)
        db = cv2.distanceTransform(cb, cv2.DIST_L2, 3)
        best = min(best, float(da[qb[:, 1], qb[:, 0]].mean() + db[qa[:, 1], qa[:, 0]].mean()))
    return -best


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
    score = np.array([[similarity(s, a) if a is not None and s is not None else -1e9 for s in shapes] for a in asked])
    return [found[j][1] for j in assignment(-score)]
