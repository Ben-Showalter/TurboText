#!/usr/bin/env python3
"""Draws the TurboText mark for the Kyocera cover card's icon slot.

The cover template draws the icon from a resource id only (no runtime
images), scaled into a small square slot; MatChat found a 96x96 nodpi square
drew best on the same phones. This is the bubble from
app/src/main/res/drawable/ic_launcher_square.xml, solid black with its three
dots cut out (transparent), on transparent, scaled to fill most of the
square. Re-run when the launcher mark changes:

    python3 tools/gen_cover_icon.py

Needs Pillow.
"""
from pathlib import Path

from PIL import Image, ImageDraw

SIZE = 96
SUPERSAMPLE = 8
OUT = Path(__file__).resolve().parent.parent / "app/src/main/res/drawable-nodpi/ic_cover_turbotext.png"

# The bubble in ic_launcher_square.xml's 108-unit viewport (before its group
# scale): a square-cornered body with a tail, and three square dots.
BUBBLE = [(20, 30), (88, 30), (88, 70), (50, 70), (36, 84), (36, 70), (20, 70)]
DOTS = [(32, 46, 40, 54), (50, 46, 58, 54), (68, 46, 76, 54)]
BOX = (20, 30, 88, 84)
FILL = 0.9  # share of the square the bubble's larger side takes


def main() -> None:
    big = SIZE * SUPERSAMPLE
    w, h = BOX[2] - BOX[0], BOX[3] - BOX[1]
    scale = big * FILL / max(w, h)
    off_x = (big - w * scale) / 2 - BOX[0] * scale
    off_y = (big - h * scale) / 2 - BOX[1] * scale

    def pt(x: float, y: float) -> tuple[float, float]:
        return (x * scale + off_x, y * scale + off_y)

    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.polygon([pt(x, y) for x, y in BUBBLE], fill=(0, 0, 0, 255))
    for x0, y0, x1, y1 in DOTS:
        draw.rectangle([pt(x0, y0), pt(x1, y1)], fill=(0, 0, 0, 0))  # transparent dots
    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.resize((SIZE, SIZE), Image.LANCZOS).save(OUT, optimize=True)
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
