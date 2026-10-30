"""
Reads Brave Search's slider check ("Switch to traditional CAPTCHA"): a picture of grey noise with a
disc of brighter noise somewhere along its middle, and a red disc on a canvas of its own at the
left edge that the slider moves sideways. The answer is how far the slider moves the red disc onto
the bright one.

The bright disc is the place where the picture is brightest on average over a disc the size of
the red one; the noise elsewhere averages out to its mid grey. Positions are in the canvases' own
pixels (310 wide); the page shows them a little narrower, which the caller scales for.

Run as a script on the picture and the piece (PNG files) it prints the piece's and the target's
centres as JSON.
"""
import json
import sys

import cv2
import numpy as np


def _decode(png_bytes, flags):
    return cv2.imdecode(np.frombuffer(png_bytes, np.uint8), flags)


def solve(picture_png: bytes, piece_png: bytes) -> dict:
    """The red disc's centre and the bright disc's centre, in canvas pixels."""
    picture = _decode(picture_png, cv2.IMREAD_GRAYSCALE).astype(np.float32)
    piece = _decode(piece_png, cv2.IMREAD_UNCHANGED)
    alpha = piece[:, :, 3] if piece.ndim == 3 and piece.shape[2] == 4 else cv2.cvtColor(piece, cv2.COLOR_BGR2GRAY) < 250
    ys, xs = np.nonzero(alpha > 0)
    radius = (xs.max() - xs.min()) / 2
    side = int(2 * radius) + 1
    disc = np.zeros((side, side), np.float32)
    # A little inside the rim, which the red disc's outline covers.
    cv2.circle(disc, (side // 2, side // 2), max(1, int(radius) - 2), 1, -1)
    disc /= disc.sum()
    mean = cv2.filter2D(picture, -1, disc, borderType=cv2.BORDER_REFLECT)
    # A disc whose centre is closer to an edge than its radius cannot lie wholly in the picture.
    edge = int(radius) + 1
    mean[:, :edge] = -1
    mean[:, -edge:] = -1
    mean[:edge, :] = -1
    mean[-edge:, :] = -1
    ty, tx = np.unravel_index(int(np.argmax(mean)), mean.shape)
    return {"piece": [float(xs.mean()), float(ys.mean())], "target": [float(tx), float(ty)], "width": picture.shape[1]}


if __name__ == "__main__":
    with open(sys.argv[1], "rb") as a, open(sys.argv[2], "rb") as b:
        print(json.dumps(solve(a.read(), b.read())))
