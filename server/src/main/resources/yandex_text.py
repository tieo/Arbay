"""
Reads Yandex's typed-text puzzle: two dictionary words set along a curve over a grey picture, to be
typed into the puzzle's field.

A small network trained on such pictures (yandex_text_enc.onnx turns the picture into a grid of
features, yandex_text_step.onnx writes the next letter from it and the letters so far) reads the
line, bent or upright alike. Each word read is then taken as the nearest dictionary word
(yandex_words.tsv: fewest letters changed first, the more common word between equals), since the
puzzle's words are dictionary words and the network's slips are a letter here and there.
"""
import os

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
CH = " abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
BOS, EOS = len(CH) + 1, len(CH) + 2
MAXLEN = 26
W, H = 248, 80
_enc = _step = None
_words = None


def _sessions():
    global _enc, _step
    if _enc is None:
        import onnxruntime
        opts = ["CPUExecutionProvider"]
        _enc = onnxruntime.InferenceSession(os.path.join(HERE, "yandex_text_enc.onnx"), providers=opts)
        _step = onnxruntime.InferenceSession(os.path.join(HERE, "yandex_text_step.onnx"), providers=opts)
    return _enc, _step


def read_raw(png_bytes):
    """The line as the network reads it, letter by letter."""
    enc, step = _sessions()
    img = cv2.imdecode(np.frombuffer(png_bytes, np.uint8), cv2.IMREAD_GRAYSCALE)
    x = cv2.resize(img, (W, H)).astype(np.float32)[None, None] / 255
    mem = enc.run(None, {"x": x})[0]
    y = [BOS]
    for _ in range(MAXLEN - 2):
        padded = np.zeros((1, MAXLEN - 1), np.int64)
        padded[0, :len(y)] = y
        logits = step.run(None, {"y": padded, "mem": mem})[0]
        c = int(logits[0, len(y) - 1].argmax())
        if c == EOS:
            break
        y.append(c)
    return "".join(CH[c - 1] for c in y[1:] if 1 <= c <= len(CH))


def _load_words():
    global _words
    if _words is None:
        _words = {}
        with open(os.path.join(HERE, "yandex_words.tsv"), encoding="utf-8") as f:
            for line in f:
                w, z = line.rstrip("\n").split("\t")
                _words[w] = float(z)
    return _words


def _distance(a, b, cap):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i] + [0] * len(b)
        for j, cb in enumerate(b, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb))
        if min(cur) > cap:
            return cap + 1
        prev = cur
    return prev[-1]


def snap_word(word):
    """The nearest dictionary word, its first letter's case kept."""
    words = _load_words()
    low = word.lower()
    if low in words:
        out = low
    else:
        cap = max(1, len(low) // 3)
        best = None
        for w, z in words.items():
            if abs(len(w) - len(low)) > cap:
                continue
            d = _distance(low, w, cap)
            if d <= cap and (best is None or (d, -z) < best[0]):
                best = ((d, -z), w)
        out = best[1] if best else low
    return out.capitalize() if word[:1].isupper() else out


def solve(png_bytes):
    """What to type: the two words as read, each the nearest dictionary word."""
    return " ".join(snap_word(w) for w in read_raw(png_bytes).split())
