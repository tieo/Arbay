"""The text of a photographed or captured screen, one line per line of the screen, read with
RapidOCR (PaddleOCR's models on ONNX Runtime). Tesseract reads scans; a phone photo of a monitor,
tilted and striped with moire, came back from it as noise where this reads every table row.

A table of raw values in hex is read again from a crop of that column alone, at several letter
heights: in the whole photo of an Intel 660p the OCR read 00000000000064 as 9000000000000 at every
scale, which the column alone read right at every height, while its 00000000000006 came back as
90000000000000 at some heights and right at others, with no one height right for both photos tried.
Every reading is printed, apart by a form feed line, and the caller believes a value where the
readings agree, past those no drive can show.

Usage: screen_ocr.py <image>   prints the lines to stdout.
       screen_ocr.py --check   exits 0 where the reader is installed.
"""
import math
import re
import statistics
import sys
import tempfile


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


HEX = re.compile(r"[0-9A-FOQ]{10,16}")

# Letter heights in pixels the hex column is read at again.
COLUMN_HEIGHTS = (24, 32, 48)


def column_read_again(ocr, path, result, letter_height):
    """The result with each raw hex value replaced by its reading in a crop of the hex column alone,
    enlarged to letters [letter_height] pixels high, or None where the screen has no such column."""
    from PIL import Image
    hexes = [i for i, (_, text, _) in enumerate(result) if HEX.fullmatch(text)]
    if len(hexes) < 3:
        return None
    boxes = [result[i][0] for i in hexes]
    height = statistics.median(abs(b[3][1] - b[0][1]) for b in boxes)
    left = max(0, int(min(p[0] for b in boxes for p in b) - height))
    top = max(0, int(min(p[1] for b in boxes for p in b) - height))
    right = int(max(p[0] for b in boxes for p in b) + height)
    bottom = int(max(p[1] for b in boxes for p in b) + height)
    image = Image.open(path).convert("L")
    crop = image.crop((left, top, min(right, image.width), min(bottom, image.height)))
    scale = letter_height / height
    crop = crop.resize((round(crop.width * scale), round(crop.height * scale)), Image.BICUBIC)
    with tempfile.NamedTemporaryFile(suffix=".png") as file:
        crop.save(file.name)
        column, _ = ocr(file.name)
    # Each value of the crop goes back to the box whose centre is nearest its own, within half a line.
    again = [(top + sum(p[1] for p in b) / 4 / scale, text) for b, text, _ in column or [] if HEX.fullmatch(text)]
    replaced = list(result)
    for i in hexes:
        box, text, confidence = result[i]
        centre = sum(p[1] for p in box) / 4
        near = min(again, key=lambda a: abs(a[0] - centre), default=None)
        if near and abs(near[0] - centre) < height / 2:
            replaced[i] = (box, near[1], confidence)
    return replaced


def main():
    from rapidocr_onnxruntime import RapidOCR
    if sys.argv[1] == "--check":
        return
    ocr = RapidOCR()
    result, _ = ocr(sys.argv[1])
    print("\n".join(lines(result or [])))
    for letter_height in COLUMN_HEIGHTS:
        again = column_read_again(ocr, sys.argv[1], result or [], letter_height)
        if again is None:
            break
        print("\f")
        print("\n".join(lines(again)))


if __name__ == "__main__":
    main()
