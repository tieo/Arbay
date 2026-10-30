"""
Reads reCAPTCHA's picture grids ("select all squares with traffic lights"): which squares of a 3x3
or 4x4 grid show the asked object, and whether a single new square does.

Things an object detector trained on COCO knows (cars, buses, bicycles, motorcycles, traffic
lights, fire hydrants, boats) are found by YOLOX-s on the whole grid picture, and a square is
chosen where a found box covers a part of it. CLIP missed those where they are small in a street
photo, the street itself taking its probability. The others (crosswalks, stairs, chimneys,
bridges, palms, mountains, tractors) are read by CLIP ViT-B/32 square by square: each square is
compared with "a photo of <thing>" for every object reCAPTCHA asks for and for things a street photo
shows besides (road, sky, trees...), and chosen where the asked object takes at least THRESHOLD of
the probability among them, or a tenth of what the grid's best square gets.

Models: CLIP's picture encoder is the one Arbay already keeps for listing photos
(clip-vit-b32-vision.onnx in its models directory); its text side never changes, so its embeddings
were computed once with the same model's text encoder (immich-app/ViT-B-32__openai,
textual/model.onnx) and ship as recaptcha_words.json. YOLOX-s (Apache-2.0) is downloaded into the
models directory on first use from Megvii's release and checked against its SHA-256.
"""
import hashlib
import json
import os
import urllib.request

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
MODELS = os.environ.get("ARBAY_MODELS_DIR", "")
MEAN = np.array([0.48145466, 0.4578275, 0.40821073], np.float32)
STD = np.array([0.26862954, 0.26130258, 0.27577711], np.float32)
# CLIP's logit scale.
SCALE = 100.0
THRESHOLD = float(os.environ.get("ARBAY_RECAPTCHA_THRESHOLD", "0.25"))
# Within one grid a square also shows the object where it gets this share of the best square's
# likelihood, and at least FLOOR: the steps beside a house came at 0.06 to the porch stairs' 0.49,
# where the squares without any stood at 0.00.
SHARE_OF_BEST = 0.10
FLOOR = 0.04

YOLOX_URL = "https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_s.onnx"
YOLOX_SHA256 = "c5c2d13e59ae883e6af3b45daea64af4833a4951c92d116ec270d9ddbe998063"
YOLOX_SIDE = 640
# A found box counts from this confidence, and covers a square where it overlaps it, in width and in
# height, by this share of the square's side or by half the box's own, whichever is less: YOLOX's
# boxes run a few pixels past the object, and a bus's box reaching 7 pixels into the next column
# chose a square it was not in, while a small car wholly inside a square overlaps it by less.
BOX_CONFIDENCE = 0.3
# A 4x4 grid is one picture whose objects are large, where a box under 0.4 was a vehicle's hood taken
# for a bicycle; a 3x3 grid's small cars came at 0.36.
BOX_CONFIDENCE_WHOLE = 0.4
BOX_COVER = 0.15
# The COCO classes standing for each object; a pickup is a "truck" to COCO and a car to reCAPTCHA.
DETECTED = {"bicycle": [1], "car": [2, 7], "taxi": [2], "motorcycle": [3], "bus": [5], "boat": [8],
            "traffic light": [9], "hydrant": [10]}

# The word reCAPTCHA puts in bold, by a stem of it in German or English, to the object it names.
STEMS = [("motorr", "motorcycle"), ("motorcycle", "motorcycle"), ("bus", "bus"), ("hydrant", "hydrant"),
         ("fußgänger", "crosswalk"), ("zebra", "crosswalk"), ("crosswalk", "crosswalk"), ("fahrr", "bicycle"), ("bicycle", "bicycle"),
         ("taxi", "taxi"), ("pkw", "car"), ("auto", "car"), ("car", "car"), ("ampel", "traffic light"), ("traffic light", "traffic light"),
         ("treppe", "stairs"), ("stair", "stairs"), ("brücke", "bridge"), ("bridge", "bridge"), ("boot", "boat"), ("boat", "boat"),
         ("schornstein", "chimney"), ("chimney", "chimney"), ("berg", "mountain"), ("hügel", "mountain"), ("mountain", "mountain"),
         ("palme", "palm"), ("palm", "palm"), ("traktor", "tractor"), ("tractor", "tractor")]

_clip = None
_words = None
_yolox = None


def obj(word: str):
    """The object a bold instruction word names, or None for one not known."""
    w = word.lower()
    return next((o for stem, o in STEMS if stem in w), None)


def available() -> bool:
    """Whether CLIP's picture encoder is there; the server downloads it for listing photos."""
    return os.path.exists(os.path.join(MODELS, "clip-vit-b32-vision.onnx"))


def decode(data: bytes):
    """A picture from its file bytes, or None where they hold none (a fetch that came back empty)."""
    if not data:
        return None
    return cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)


def squares(picture, n: int):
    """The n x n squares of a grid picture, row by row."""
    h, w = picture.shape[:2]
    return [picture[r * h // n:(r + 1) * h // n, c * w // n:(c + 1) * w // n] for r in range(n) for c in range(n)]


def _clip_model():
    global _clip, _words
    if _clip is None:
        import onnxruntime
        _clip = onnxruntime.InferenceSession(os.path.join(MODELS, "clip-vit-b32-vision.onnx"), providers=["CPUExecutionProvider"])
        with open(os.path.join(HERE, "recaptcha_words.json")) as f:
            _words = json.load(f)
    return _clip, _words


def likelihood(square, thing: str) -> float:
    """How much of CLIP's probability over all known things goes to [thing] for one square."""
    session, words = _clip_model()
    rgb = cv2.cvtColor(square, cv2.COLOR_BGR2RGB)
    x = cv2.resize(rgb, (224, 224), interpolation=cv2.INTER_CUBIC).astype(np.float32) / 255
    x = ((x - MEAN) / STD).transpose(2, 0, 1)[None]
    e = session.run(None, {session.get_inputs()[0].name: x})[0][0]
    e = e / np.linalg.norm(e)
    names = list(words["objects"])
    bank = np.array([words["objects"][k] for k in names] + list(words["others"].values()), np.float32)
    logits = SCALE * bank @ e
    p = np.exp(logits - logits.max())
    p /= p.sum()
    return float(p[names.index(thing)])


def _yolox_model():
    global _yolox
    if _yolox is None:
        import onnxruntime
        path = os.path.join(MODELS, "yolox_s.onnx")
        if not os.path.exists(path):
            data = urllib.request.urlopen(YOLOX_URL, timeout=120).read()
            if hashlib.sha256(data).hexdigest() != YOLOX_SHA256:
                raise RuntimeError("yolox_s.onnx from the release does not match its checksum")
            os.makedirs(MODELS, exist_ok=True)
            with open(path + ".part", "wb") as f:
                f.write(data)
            os.replace(path + ".part", path)
        _yolox = onnxruntime.InferenceSession(path, providers=["CPUExecutionProvider"])
    return _yolox


def boxes(picture, classes: list, confidence: float = BOX_CONFIDENCE) -> list:
    """The boxes YOLOX-s finds of [classes] in a picture, each (left, top, right, bottom) in its pixels."""
    session = _yolox_model()
    h, w = picture.shape[:2]
    r = min(YOLOX_SIDE / h, YOLOX_SIDE / w)
    padded = np.full((YOLOX_SIDE, YOLOX_SIDE, 3), 114, np.uint8)
    padded[:int(h * r), :int(w * r)] = cv2.resize(picture, (int(w * r), int(h * r)))
    out = session.run(None, {"images": padded.transpose(2, 0, 1)[None].astype(np.float32)})[0][0]
    grids, strides = [], []
    for s in (8, 16, 32):
        g = YOLOX_SIDE // s
        yv, xv = np.meshgrid(np.arange(g), np.arange(g), indexing="ij")
        grids.append(np.stack((xv, yv), 2).reshape(-1, 2))
        strides.append(np.full((g * g, 1), s))
    grid, stride = np.concatenate(grids), np.concatenate(strides)
    centre = (out[:, :2] + grid) * stride
    size = np.exp(out[:, 2:4]) * stride
    scores = out[:, 4:5] * out[:, 5:]
    cls, score = scores.argmax(1), scores.max(1)
    keep = (score >= confidence) & np.isin(cls, classes)
    found = np.concatenate([centre - size / 2, centre + size / 2], 1)[keep] / r
    kept = cv2.dnn.NMSBoxes([[float(b[0]), float(b[1]), float(b[2] - b[0]), float(b[3] - b[1])] for b in found],
                            score[keep].tolist(), confidence, 0.45)
    return [found[i].tolist() for i in np.array(kept, dtype=int).flatten()]


def covered(picture, n: int, found: list) -> list:
    """The squares of an n x n grid any of the boxes covers a part of."""
    h, w = picture.shape[:2]
    out = []
    for i in range(n * n):
        r, c = divmod(i, n)
        x0, x1, y0, y1 = c * w / n, (c + 1) * w / n, r * h / n, (r + 1) * h / n
        if any(min(b[2], x1) - max(b[0], x0) >= min(BOX_COVER * (x1 - x0), (b[2] - b[0]) / 2)
               and min(b[3], y1) - max(b[1], y0) >= min(BOX_COVER * (y1 - y0), (b[3] - b[1]) / 2) for b in found):
            out.append(i)
    return out


def found(picture, thing: str, confidence: float = BOX_CONFIDENCE) -> list:
    """The boxes of [thing] in a picture. A taxi is a car to COCO, so each car is kept only where
    CLIP takes it for a taxi rather than a car."""
    found = boxes(picture, DETECTED[thing], confidence)
    if thing == "taxi":
        found = [b for b in found if _taxi(picture[max(0, int(b[1])):int(b[3]) + 1, max(0, int(b[0])):int(b[2]) + 1])]
    return found


def _taxi(crop) -> bool:
    return crop.size > 0 and likelihood(crop, "taxi") > likelihood(crop, "car")


def choose(picture, n: int, thing: str) -> list:
    """The indexes (row by row, from 0) of the squares showing [thing]."""
    if thing in DETECTED:
        return covered(picture, n, found(picture, thing, BOX_CONFIDENCE_WHOLE if n == 4 else BOX_CONFIDENCE))
    scores = [likelihood(sq, thing) for sq in squares(picture, n)]
    bar = min(THRESHOLD, max(FLOOR, SHARE_OF_BEST * max(scores)))
    return [i for i, s in enumerate(scores) if s >= bar]


def shows(square, thing: str) -> bool:
    """Whether a single square (a dynamic grid's new one) shows [thing]."""
    if thing in DETECTED:
        return bool(found(square, thing))
    return likelihood(square, thing) >= THRESHOLD


if __name__ == "__main__":
    import sys
    # recaptcha_tiles.py <grid picture> <3 or 4> <object>: the chosen squares as JSON.
    print(json.dumps(choose(cv2.imread(sys.argv[1]), int(sys.argv[2]), sys.argv[3])))
