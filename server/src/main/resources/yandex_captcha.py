"""
Reads Yandex's silhouette challenge: a picture with line symbols drawn over a painting, some of
them not asked for (faint ones, and others drawn just like the asked ones), and a strip of the
asked symbols' outlines in the order to click them. Returns where to click.

Where the symbols are comes from a detector (yandex_centres.onnx, trained on generated pictures
and real puzzles checked by eye) whose heatmap peaks at each symbol's centre; its strongest peaks
are the candidates, decoys among them. A stroke network (yandex_symbols.onnx) marks the symbols'
strokes; each marked part, and each group of two or three parts close together (a symbol of
separate strokes, three bars or two chevrons, comes out of it in pieces), is compared with each
outline of the strip over every turn, and a peak on such a group carries that fit. A matcher
(yandex_match_icon.onnx and yandex_match_crop.onnx, trained on real puzzles against the detector's
peaks and on generated pictures whose unasked symbols are drawn like the asked ones) looks at the
picture around each peak, turned eight ways, and says how well it shows each outline. Both
judgements and the peak's height together decide, and the outlines get the peaks that fit them
best, no stroke part twice. Should the detector find fewer peaks than outlines, the matcher alone scans the
picture on a grid instead.
"""
import itertools
import os

import cv2
import numpy as np

# Every constant from picture to clicks is named below, so the path can be reproduced outside
# Python. Conventions: pictures decode with OpenCV, colours in BGR order all the way into the
# networks; a transparent strip is laid on white; network inputs are channels first and scaled to
# 0..1 by dividing by 255, no mean or deviation taken off; network outputs are logits, put through
# the logistic function where a probability is wanted. OpenCV operations whose definition matters:
# MORPH_ELLIPSE structuring elements (MORPH_CROSS in thin()), distanceTransform(DIST_L2, 3) (the 3x3 mask approximation
# of Euclidean distance), getRotationMatrix2D (degrees counter-clockwise, y pointing down) with
# warpAffine's bilinear sampling and BORDER_REFLECT (fedcba|abcdef, the edge pixel repeated),
# resize with INTER_AREA for the matcher's icons, and connectedComponents with 8-connectivity.
MODEL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "yandex_symbols.onnx")
# The side of the square a shape is drawn on for the outline fit, and of the matcher's inputs; a
# drawn shape keeps this margin to the canvas edge.
SIDE = 64
CANVAS_MARGIN = 4
# The matcher's icon: the outline scaled into a square this wide, centred in SIDE.
ICON_INNER = 56
# Stroke probability above which a pixel is marked, the smallest part kept in pixels, the closing
# kernel that fills gaps in a stroke, and the dilation kernel that joins a symbol's separate strokes.
STROKE_THRESHOLD = 0.5
MIN_PART = 40
CLOSE_KERNEL = 3
JOIN_KERNEL = 7
# Strip ink is grey below this level; the opening kernel that wipes out the thin hatching; columns
# this close are one run; a run narrower than NARROW_RUN outlines joins a neighbour while both fit
# in JOINED_RUN outlines; touching outlines are split at the emptiest column within SPLIT_SEARCH
# of the even split, their count rounded up from a remainder of 1 - SPLIT_ROUND.
INK_BELOW = 128
HATCH_KERNEL = 5
RUN_GAP = 4
NARROW_RUN = 0.55
JOINED_RUN = 1.3
SPLIT_ROUND = 0.25
SPLIT_SEARCH = 12
# A shape's points are scaled by this many times their root mean square distance from the centre,
# and the outline fit tries every turn this many degrees apart.
SPREAD = 1.6
TURN_STEP = 3
# Pixels of a blob left of the hatching at most, of an outline at least, and the width of one outline.
MIN_PIECE = 80
MIN_OUTLINE = 300
ONE_WIDE = 75
# Parts whose centres lie this close and whose joint box is at most this wide may be one symbol,
# and what each part beyond the first costs a group's fit.
GROUP_NEAR = 42
GROUP_BOX = 62
EXTRA_PART = 0.5
MATCHER = [os.path.join(os.path.dirname(os.path.abspath(__file__)), f"yandex_match_{half}.onnx") for half in ("icon", "crop")]
# How much the matcher's judgement (log probability of a group among the picture's groups) counts
# against the outline fit: measured on real puzzles checked by eye, each half scored by a matcher
# trained on the other.
MATCH_WEIGHT = 0.5
TURNS = 8
# A peak takes the stroke group whose centre lies within this many pixels.
ON_GROUP = 16
# The assignment search looks at each outline's this many best candidates.
CHOOSE_TOP = 10
CENTRES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "yandex_centres.onnx")
# The detector's strongest peaks taken as candidates, how far apart two peaks must be, the outline
# fit given a peak on no stroke group, and how much the log of a peak's height counts (the detector
# learnt from real puzzles to mark the asked symbols above the unasked ones), all measured on the
# real puzzles; PEAK_FLOOR keeps the log finite.
PEAKS = 10
PEAK_APART = 12
UNGROUPED = -8.0
HEAT_WEIGHT = 0.5
PEAK_FLOOR = 1e-3
# The grid the matcher scans when the stroke network finds fewer parts than outlines: its step and
# turns, each outline's best spots kept, and how far apart two spots must be to be two symbols.
SCAN_STEP = 12
SCAN_EDGE = 14
SCAN_TURNS = 4
SCAN_PEAKS = 3
SCAN_APART = 22
_session = None
_matcher = None
_centres = None


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


def parts(prob, threshold=STROKE_THRESHOLD, min_area=MIN_PART):
    """The symbols the network marked, each a mask over the whole picture; a symbol's separate
    strokes (a face's eyes) join it when they are a few pixels apart."""
    m = (prob > threshold).astype(np.uint8)
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (CLOSE_KERNEL, CLOSE_KERNEL)))
    joined = cv2.dilate(m, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (JOIN_KERNEL, JOIN_KERNEL)))
    n, lab = cv2.connectedComponents(joined)
    out = []
    for c in range(1, n):
        part = (lab == c) & (m > 0)
        if part.sum() >= min_area:
            out.append(part)
    return out


def shape(mask):
    """A mask cut to its box, its centre and its box (x0, y0, x1, y1)."""
    ys, xs = np.nonzero(mask)
    return mask[ys.min():ys.max() + 1, xs.min():xs.max() + 1], (float(xs.mean()), float(ys.mean())), (xs.min(), ys.min(), xs.max(), ys.max())


def groups(masks):
    """Each part alone, and each two or three parts close enough together to be one symbol: the
    indices of the parts and their joint mask."""
    info = [shape(m) for m in masks]
    out = [((i,), m) for i, m in enumerate(masks)]
    for k in (2, 3):
        for g in itertools.combinations(range(len(masks)), k):
            centres = [info[i][1] for i in g]
            if any(np.hypot(a[0] - b[0], a[1] - b[1]) > GROUP_NEAR for a, b in itertools.combinations(centres, 2)):
                continue
            x0, y0 = min(info[i][2][0] for i in g), min(info[i][2][1] for i in g)
            x1, y1 = max(info[i][2][2] for i in g), max(info[i][2][3] for i in g)
            if x1 - x0 > GROUP_BOX or y1 - y0 > GROUP_BOX:
                continue
            out.append((g, np.logical_or.reduce([masks[i] for i in g])))
    return out


def outlines(strip):
    """The strip's outlines, left to right. The hatching across it is thinner than their strokes and
    goes with the opening, but for small blobs where its lines cross; the outlines stand apart by a
    few empty columns, not on an even grid, and two that touch make a run of ink too wide for one."""
    g = cv2.cvtColor(strip, cv2.COLOR_BGR2GRAY)
    ink = (g < INK_BELOW).astype(np.uint8)
    ink = cv2.morphologyEx(ink, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (HATCH_KERNEL, HATCH_KERNEL)))
    count, lab, st, _ = cv2.connectedComponentsWithStats(ink)
    for c in range(1, count):
        if st[c][4] < MIN_PIECE:
            ink[lab == c] = 0
    columns = ink.sum(0)
    runs = []
    for x in np.nonzero(columns)[0]:
        if runs and x - runs[-1][1] <= RUN_GAP:
            runs[-1][1] = x
        else:
            runs.append([x, x])
    # An outline of separate strokes (three bars) leaves runs too narrow for one: each joins the
    # nearer of its neighbours while the two together still fit one outline.
    merged = True
    while merged:
        merged = False
        for i, (a, b) in enumerate(runs):
            if b - a >= ONE_WIDE * NARROW_RUN:
                continue
            near = [j for j in (i - 1, i + 1) if 0 <= j < len(runs)
                    and max(b, runs[j][1]) - min(a, runs[j][0]) <= ONE_WIDE * JOINED_RUN]
            if near:
                j = min(near, key=lambda j: runs[j][0] - b if j > i else a - runs[j][1])
                lo, hi = min(i, j), max(i, j)
                runs[lo:hi + 1] = [[runs[lo][0], runs[hi][1]]]
                merged = True
                break
    runs = [(a, b + 1) for a, b in runs if columns[a:b + 1].sum() >= MIN_OUTLINE]
    cut = []
    for a, b in runs:
        k = max(1, int((b - a) / ONE_WIDE + SPLIT_ROUND))
        edges = [a]
        for i in range(1, k):
            # where touching outlines meet: the emptiest column near the even split
            mid = a + (b - a) * i // k
            lo, hi = max(a + 1, mid - SPLIT_SEARCH), min(b - 1, mid + SPLIT_SEARCH)
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
    spread = float(np.sqrt(((p ** 2).sum(1)).mean())) * SPREAD
    return p / max(spread, 1e-6)


def drawn(p, angle=0.0):
    """Shape p turned by [angle] on a canvas: the canvas (its line at 0, the rest 1) and the points."""
    a = np.deg2rad(angle)
    rot = np.array([[np.cos(a), -np.sin(a)], [np.sin(a), np.cos(a)]], np.float32)
    q = np.clip(((p @ rot.T) * (SIDE / 2 - CANVAS_MARGIN) + SIDE / 2).astype(int), 0, SIDE - 1)
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
    for angle in range(0, 360, TURN_STEP):
        cb, qb = drawn(b, angle)
        db = cv2.distanceTransform(cb, cv2.DIST_L2, 3)
        best = min(best, float(da[qb[:, 1], qb[:, 0]].mean() + db[qa[:, 1], qa[:, 0]].mean()))
    return -best


def choose(score, members, top=CHOOSE_TOP):
    """For each outline (row) a group (column) so that no part serves two outlines and the summed
    fit is greatest: a search over each outline's best groups, cut off where even the best of the
    rest could no longer beat the best choice found."""
    rows = sorted(range(score.shape[0]), key=lambda i: -score[i].max())
    best_rest = [sum(score[i].max() for i in rows[k:]) for k in range(len(rows) + 1)]
    tops = {i: np.argsort(-score[i])[:top] for i in rows}
    best = [-float("inf"), None]

    def search(k, used, total, pick):
        if total + best_rest[k] <= best[0]:
            return
        if k == len(rows):
            best[0], best[1] = total, dict(pick)
            return
        i = rows[k]
        for j in tops[i]:
            if used & set(members[j]):
                continue
            pick[i] = j
            search(k + 1, used | set(members[j]), total + score[i, j], pick)
            del pick[i]

    search(0, set(), 0.0, {})
    return best[1]


def _icon(mask):
    """An outline as the matcher takes it: squared, ICON_INNER pixels across, centred in SIDE."""
    h, w = mask.shape
    side = max(h, w)
    sq = np.zeros((side, side), np.float32)
    sq[(side - h) // 2:(side - h) // 2 + h, (side - w) // 2:(side - w) // 2 + w] = mask > 0
    out = np.zeros((SIDE, SIDE), np.float32)
    m = (SIDE - ICON_INNER) // 2
    out[m:m + ICON_INNER, m:m + ICON_INNER] = cv2.resize(sq, (ICON_INNER, ICON_INNER), interpolation=cv2.INTER_AREA)
    return out


def _crop(picture, cx, cy, angle):
    m = cv2.getRotationMatrix2D((float(cx), float(cy)), angle, 1.0)
    m[0, 2] += SIDE / 2 - cx
    m[1, 2] += SIDE / 2 - cy
    return cv2.warpAffine(picture, m, (SIDE, SIDE), borderMode=cv2.BORDER_REFLECT)


def peaks(picture):
    """The detector's strongest peaks, strongest first, at least PEAK_APART apart: their (x, y) and
    their heights (0..1)."""
    global _centres
    if _centres is None:
        import onnxruntime
        _centres = onnxruntime.InferenceSession(CENTRES, providers=["CPUExecutionProvider"])
    x = picture.transpose(2, 0, 1)[None].astype(np.float32) / 255
    heat = 1 / (1 + np.exp(-_centres.run(None, {"x": x})[0][0, 0]))
    found, heights = [], []
    for _ in range(PEAKS):
        y, x = np.unravel_index(np.argmax(heat), heat.shape)
        if heat[y, x] <= 0:
            break
        found.append((float(x), float(y)))
        heights.append(float(heat[y, x]))
        cv2.circle(heat, (int(x), int(y)), PEAK_APART, 0, -1)
    return found, heights


def _sessions_matcher():
    global _matcher
    if _matcher is None:
        import onnxruntime
        _matcher = [onnxruntime.InferenceSession(m, providers=["CPUExecutionProvider"]) for m in MATCHER]
    return _matcher


def scanned(picture, outlines_):
    """Candidate centres found by the matcher alone, for a picture whose strokes the first network
    missed: every grid spot scored against every outline over a few turns, each outline's best spots
    kept, spots on the same symbol counted once."""
    icon, crop = _sessions_matcher()
    h, w = picture.shape[:2]
    spots = [(x, y) for y in range(SCAN_EDGE, h - SCAN_EDGE + 1, SCAN_STEP) for x in range(SCAN_EDGE, w - SCAN_EDGE + 1, SCAN_STEP)]
    a = icon.run(None, {"x": np.stack([_icon(o) for o in outlines_])[:, None]})[0]
    best = None
    for k in range(SCAN_TURNS):
        crops = np.stack([_crop(picture, x, y, 360 / SCAN_TURNS * k).transpose(2, 0, 1) for x, y in spots]).astype(np.float32) / 255
        z = np.concatenate([crop.run(None, {"x": crops[i:i + 256]})[0] for i in range(0, len(crops), 256)])
        best = a @ z.T if best is None else np.maximum(best, a @ z.T)
    near = lambda p, q: (p[0] - q[0]) ** 2 + (p[1] - q[1]) ** 2 <= SCAN_APART ** 2
    found = []
    for row in best:
        mine = []
        for j in np.argsort(-row):
            if not any(near(spots[j], q) for q in mine):
                mine.append(spots[j])
            if len(mine) == SCAN_PEAKS:
                break
        found += [p for p in mine if not any(near(p, q) for q in found)]
    return found


def matched(picture, outlines_, centres):
    """For each outline (row), the log probability the matcher gives each group (column) of being
    the one it shows, the best of eight turns of the picture around the group."""
    icons = np.stack([_icon(o) for o in outlines_])[:, None]
    crops = np.stack([_crop(picture, x, y, 360 / TURNS * k).transpose(2, 0, 1) for k in range(TURNS) for x, y in centres])
    icon, crop = _sessions_matcher()
    a = icon.run(None, {"x": icons})[0]
    z = crop.run(None, {"x": crops.astype(np.float32) / 255})[0]
    logits = (a @ z.T).reshape(len(icons), TURNS, len(centres)).max(1)
    logits = logits - logits.max(1, keepdims=True)
    return logits - np.log(np.exp(logits).sum(1, keepdims=True))


def solve(picture_png, strip_png):
    """Click points in the picture's pixels, in the strip's order; empty where not even the
    matcher's scan finds as many symbols as the strip asks for."""
    picture, strip = flat(picture_png), flat(strip_png)
    shown = outlines(strip)
    asked = [points(o) for o in shown]
    spots, heights = peaks(picture)
    if len(spots) < len(asked):
        spots = scanned(picture, shown)
        if len(spots) < len(asked):
            return []
        pick = choose(matched(picture, shown, spots), [[k] for k in range(len(spots))])
        return [spots[pick[i]] for i in range(len(asked))] if pick else []
    candidates = groups(parts(strokes(picture)))
    cut = [shape(m) for _, m in candidates]
    shapes = [points(c[0]) for c in cut]
    fit = np.array([[similarity(s, a) - EXTRA_PART * (len(g) - 1) if a is not None and s is not None else -1e9
                     for s, (g, _) in zip(shapes, candidates)] for a in asked]).reshape(len(asked), len(cut))
    # Each peak on the stroke group whose centre lies within ON_GROUP pixels takes that group's fit and parts.
    on = []
    for x, y in spots:
        near = [j for j, c in enumerate(cut) if (c[1][0] - x) ** 2 + (c[1][1] - y) ** 2 < ON_GROUP ** 2]
        on.append(min(near, key=lambda j: (cut[j][1][0] - x) ** 2 + (cut[j][1][1] - y) ** 2) if near else None)
    score = np.array([[fit[i, j] if j is not None else UNGROUPED for j in on] for i in range(len(asked))])
    score = score + MATCH_WEIGHT * matched(picture, shown, spots) + HEAT_WEIGHT * np.log(np.array(heights) + PEAK_FLOOR)[None]
    members = [candidates[j][0] if j is not None else [("peak", k)] for k, j in enumerate(on)]
    pick = choose(score, members)
    return [spots[pick[i]] for i in range(len(asked))] if pick else []
