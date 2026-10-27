"""The text of a photographed or captured screen, one line per line of the screen, read with
RapidOCR (PaddleOCR's models on ONNX Runtime). Tesseract reads scans; a phone photo of a monitor,
tilted and striped with moire, came back from it as noise where this reads every table row.

Usage: screen_ocr.py <image>   prints the lines to stdout.
       screen_ocr.py --check   exits 0 where the reader is installed.
"""
import math
import statistics
import sys


def lines(result):
    """Boxes to lines: turned back by the median slope of the boxes' top edges, then grouped by
    the height of their centres, so each row of a tilted table stays one line, read left to right."""
    if not result:
        return []
    slopes = [math.atan2(b[1][1] - b[0][1], b[1][0] - b[0][0]) for b, _, _ in result if b[1][0] - b[0][0] > 40]
    angle = statistics.median(slopes) if slopes else 0.0
    cos, sin = math.cos(-angle), math.sin(-angle)
    items = []
    for box, text, _ in result:
        cx = sum(p[0] for p in box) / 4
        cy = sum(p[1] for p in box) / 4
        items.append((cx * sin + cy * cos, cx * cos - cy * sin, abs(box[3][1] - box[0][1]), text))
    tolerance = statistics.median(i[2] for i in items) * 0.5
    rows = []
    for y, x, _, text in sorted(items):
        if rows and abs(rows[-1][0] - y) < tolerance:
            rows[-1][1].append((x, text))
            rows[-1][0] = (rows[-1][0] + y) / 2
        else:
            rows.append([y, [(x, text)]])
    return [" ".join(t for _, t in sorted(r[1])) for r in rows]


def main():
    from rapidocr_onnxruntime import RapidOCR
    if sys.argv[1] == "--check":
        return
    result, _ = RapidOCR()(sys.argv[1])
    print("\n".join(lines(result or [])))


if __name__ == "__main__":
    main()
