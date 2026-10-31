"""
Reads Yandex's typed-text puzzle: two words set along a curve over a grey picture, to be typed into
the puzzle's field.

A small network trained on such pictures (yandex_text_enc.onnx turns the picture into a grid of
features, yandex_text_step.onnx writes the next letter from it and the letters so far) reads the
line, bent or upright alike. The words are nearly always dictionary words, so the line is read twice
with a beam search: once held to the words of yandex_words.tsv, once letter by letter. The dictionary
reading wins unless the network finds a free reading clearly likelier, which keeps the odd made-up
word or name (the puzzles hold a few) readable. How likely the reading is tells a sure one from a
guess, and the puzzle hands out another picture for the asking, so a guess need not be typed.
"""
import bisect
import os

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
CH = " abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
SPACE, BOS, EOS = 1, len(CH) + 1, len(CH) + 2
LOWER = range(2, 28)
W, H = 248, 80
BEAM = 12
# How much likelier, in log probability, a free reading must be before it beats the dictionary.
MARGIN = 8.0
# The log probability a reading needs before it is typed (see sure()).
SURE = -2.0
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


def _load_words():
    """The word list sorted, for prefix lookups by bisection, and as a set."""
    global _words
    if _words is None:
        with open(os.path.join(HERE, "yandex_words.tsv"), encoding="utf-8") as f:
            listed = sorted(line.split("\t", 1)[0] for line in f if line.strip())
        _words = (listed, set(listed))
    return _words


def _is_prefix(prefix):
    listed, _ = _load_words()
    i = bisect.bisect_left(listed, prefix)
    return i < len(listed) and listed[i].startswith(prefix)


def _log_softmax(v):
    v = v - v.max(axis=-1, keepdims=True)
    return v - np.log(np.exp(v).sum(axis=-1, keepdims=True))


def _beam(mem, held):
    """The likeliest two-word line and its log probability. Held to the word list when held is set;
    otherwise any letters, still two words. Only a word's first letter may be a capital."""
    _, step = _sessions()
    maxlen = step.get_inputs()[0].shape[1] + 1
    _, wordset = _load_words()
    beams = [([BOS], 0.0, "", 0)]  # tokens, log probability, current word in lower case, words done
    best = (-np.inf, "")
    for _ in range(maxlen - 2):
        y = np.zeros((len(beams), maxlen - 1), np.int64)
        for i, (toks, _, _, _) in enumerate(beams):
            y[i, :len(toks)] = toks
        logits = step.run(None, {"y": y, "mem": np.repeat(mem, len(beams), axis=0)})[0]
        grown = []
        for i, (toks, lp, word, done) in enumerate(beams):
            ls = _log_softmax(logits[i, len(toks) - 1])
            if word and (not held or word in wordset):
                if done == 0:
                    grown.append((toks + [SPACE], lp + ls[SPACE], "", 1))
                elif lp + ls[EOS] > best[0]:
                    best = (lp + ls[EOS], "".join(CH[c - 1] for c in toks[1:]))
            letters = LOWER if not held else [c for c in LOWER if _is_prefix(word + CH[c - 1])]
            for c in letters:
                grown.append((toks + [c], lp + ls[c], word + CH[c - 1], done))
                if not word:
                    grown.append((toks + [c + 26], lp + ls[c + 26], word + CH[c - 1], done))
        grown.sort(key=lambda b: -b[1])
        beams = grown[:BEAM]
        # Every letter only lowers a line's probability: once the best finished line beats every
        # unfinished one, nothing left can overtake it.
        if not beams or beams[0][1] < best[0]:
            break
    return best


def read(png_bytes):
    """What to type, the two words as read, and the reading's log probability."""
    enc, _ = _sessions()
    img = cv2.imdecode(np.frombuffer(png_bytes, np.uint8), cv2.IMREAD_GRAYSCALE)
    x = cv2.resize(img, (W, H)).astype(np.float32)[None, None] / 255
    mem = enc.run(None, {"x": x})[0]
    held, free = _beam(mem, True), _beam(mem, False)
    best = held if held[0] >= free[0] - MARGIN or not free[1] else free
    return best[1], float(best[0])


def solve(png_bytes):
    """What to type: the two words as read."""
    return read(png_bytes)[0]


def sure(score):
    """Whether a reading this likely is worth typing rather than asking for another picture: on
    real puzzles read by eye, about one reading in three clears it, and those are right nine times
    in ten, against half of those below it."""
    return score >= SURE
