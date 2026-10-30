"""
Reads Yandex's silhouette challenge: a picture with line icons drawn crisply over a soft painting,
and a strip of the same icons' outlines in the order to click them. Returns where to click.
"""
from concurrent.futures import ThreadPoolExecutor

import cv2
import numpy as np

SCALES = (0.38, 0.45, 0.52, 0.59, 0.66)
STEP = 5


def flat(png_bytes):
    im = cv2.imdecode(np.frombuffer(png_bytes, np.uint8), cv2.IMREAD_UNCHANGED)
    if im.ndim == 3 and im.shape[2] == 4:
        a = im[:, :, 3:4].astype(np.float32) / 255
        im = (im[:, :, :3] * a + 255 * (1 - a)).astype(np.uint8)
    return im


def detail(img, sigma=1.0):
    """Fine detail: the icons' one-pixel edges, which the painting's soft strokes lack."""
    f = img.astype(np.float32)
    if f.ndim == 2:
        f = f[:, :, None]
    d = np.abs(f - cv2.GaussianBlur(f, (0, 0), sigma).reshape(f.shape)).sum(2)
    return np.clip(d / (np.percentile(d, 99.5) + 1e-6), 0, 1).astype(np.float32)


def shift(a, dx, dy):
    return np.roll(np.roll(a, dy, 0), dx, 1)
def bar(img, half=(2, 3)):
    """Flat bars about four pixels wide with a sharp edge on each side: the icons' strokes. The
    painting's soft strokes have no sharp edges, its fine lines no flat middle."""
    lab = cv2.cvtColor(img, cv2.COLOR_BGR2LAB).astype(np.float32)
    out = np.zeros(img.shape[:2], np.float32)
    for dx, dy in [(1, 0), (0, 1), (1, 1), (1, -1)]:
        best = np.zeros_like(out)
        for h in half:
            c = lab
            flat = np.maximum(np.abs(c - shift(c, dx, dy)).sum(2), np.abs(c - shift(c, -dx, -dy)).sum(2))
            a = np.abs(c - shift(c, dx * (h + 1), dy * (h + 1))).sum(2)
            b = np.abs(c - shift(c, -dx * (h + 1), -dy * (h + 1))).sum(2)
            score = np.minimum(a, b) - 1.5 * flat
            best = np.maximum(best, score)
        out = np.maximum(out, best)
    out = np.clip(out, 0, None)
    return out / (np.percentile(out, 99.5) + 1e-6)


def strip_icons(strip, n=6):
    g = cv2.cvtColor(strip, cv2.COLOR_BGR2GRAY)
    ink = (g < 128).astype(np.uint8) * 255
    # The hatching across the strip is thinner than the icons' strokes.
    clean = cv2.morphologyEx(ink, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5)))
    w = strip.shape[1] // n
    icons = []
    for i in range(n):
        cell = clean[:, i * w:(i + 1) * w]
        ys, xs = np.nonzero(cell)
        icons.append(cell[ys.min():ys.max() + 1, xs.min():xs.max() + 1] if len(xs) else None)
    return [ic for ic in icons if ic is not None]


def templates(icon):
    """The icon's strokes as the picture shows them, at each size and turn: the bar filter runs once
    per size and its result is turned, which matches filtering each turned outline closely enough
    to give the same answers, in a fraction of the time."""
    h, w = icon.shape
    side = int(np.ceil(np.hypot(h, w))) + 6
    pad = np.zeros((side, side), np.uint8)
    pad[(side - h) // 2:(side - h) // 2 + h, (side - w) // 2:(side - w) // 2 + w] = icon
    for s in SCALES:
        sz = max(10, int(side * s))
        base = bar(cv2.cvtColor(cv2.resize(pad, (sz, sz), interpolation=cv2.INTER_AREA), cv2.COLOR_GRAY2BGR))
        for a in range(0, 360, STEP):
            yield cv2.warpAffine(base, cv2.getRotationMatrix2D((sz / 2, sz / 2), a, 1.0), (sz, sz))


def responses(F, icon):
    """The best match of [icon] at every place of F, over its turns and sizes, by centre."""
    best = np.full(F.shape, -1, np.float32)
    for tp in templates(icon):
        th, tw = tp.shape
        if th >= F.shape[0] or tw >= F.shape[1]:
            continue
        r = cv2.matchTemplate(F, tp, cv2.TM_CCOEFF_NORMED)
        y0, x0 = th // 2, tw // 2
        view = best[y0:y0 + r.shape[0], x0:x0 + r.shape[1]]
        np.maximum(view, r, out=view)
    return best


def peaks(M, k, sep):
    M = M.copy()
    out = []
    for _ in range(k):
        _, mv, _, (x, y) = cv2.minMaxLoc(M)
        if mv <= 0:
            break
        out.append((x, y))
        cv2.circle(M, (x, y), sep, -1, -1)
    return out


def assignment(cost):
    """The column for each row that makes the total cost least, each column used once (Hungarian
    method; rows no more than columns)."""
    n, m = cost.shape
    INF = float("inf")
    u, v = [0.0] * (n + 1), [0.0] * (m + 1)
    p, way = [0] * (m + 1), [0] * (m + 1)
    for i in range(1, n + 1):
        p[0], j0 = i, 0
        minv, used = [INF] * (m + 1), [False] * (m + 1)
        while True:
            used[j0] = True
            i0, delta, j1 = p[j0], INF, 0
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
    """Click points in the picture's pixels, in the strip's order."""
    pic, strip = flat(picture_png), flat(strip_png)
    F = np.clip(bar(pic), 0, 1).astype(np.float32)
    icons = strip_icons(strip)
    # OpenCV lets go of Python's lock while it matches, so the icons are matched side by side.
    with ThreadPoolExecutor(max_workers=len(icons)) as pool:
        maps = list(pool.map(lambda ic: responses(F, ic), icons))
    # Every icon in the picture, the ones not asked for too: places any outline fits well.
    cands = peaks(np.max(maps, axis=0), len(icons) + 6, 15)
    S = np.array([[m[max(0, y - 4):y + 5, max(0, x - 4):x + 5].max() for (x, y) in cands] for m in maps])
    pick = assignment(-S)
    return [cands[j] for j in pick]
