#!/usr/bin/env python3
"""Generate Android launcher icons from brand icon.svg (visible on home screen)."""
from __future__ import annotations

import io
from pathlib import Path

import cairosvg
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SVG = ROOT / "icon.svg"
RES = ROOT / "android/app/src/main/res"

# mipmap density -> launcher icon size
LAUNCHER = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
# adaptive foreground is 108dp canvas; safe zone ~72dp. Use 108 * density scale.
FOREGROUND = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}

BG = (30, 58, 138, 255)  # #1e3a8a brand blue


def render_svg(size: int) -> Image.Image:
    png = cairosvg.svg2png(url=str(SVG), output_width=size, output_height=size)
    return Image.open(io.BytesIO(png)).convert("RGBA")


def round_mask(size: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    # Simple circle
    from PIL import ImageDraw

    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, size - 1, size - 1), fill=255)
    return mask


def main() -> None:
    if not SVG.exists():
        raise SystemExit(f"Missing {SVG}")

    for folder, size in LAUNCHER.items():
        out_dir = RES / folder
        out_dir.mkdir(parents=True, exist_ok=True)
        icon = render_svg(size)
        icon.save(out_dir / "ic_launcher.png")

        rounded = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        rounded.paste(icon, (0, 0))
        rounded.putalpha(round_mask(size))
        # Keep brand art inside circle with brand blue outside transparent already
        rounded.save(out_dir / "ic_launcher_round.png")

    for folder, size in FOREGROUND.items():
        out_dir = RES / folder
        # Full-bleed brand art for adaptive icon foreground
        fg = render_svg(size)
        fg.save(out_dir / "ic_launcher_foreground.png")

    # Adaptive background color matches brand
    bg_xml = RES / "values/ic_launcher_background.xml"
    bg_xml.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<resources>\n"
        '    <color name="ic_launcher_background">#1E3A8A</color>\n'
        "</resources>\n",
        encoding="utf-8",
    )

    print("Launcher icons generated from icon.svg")


if __name__ == "__main__":
    main()
