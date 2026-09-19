#!/usr/bin/env python3
"""Generate Android launcher icons that match icon.svg (original brand design).

Legacy launcher PNGs = exact icon.svg (what users recognize).
Adaptive foreground = brand art inset into the 66% safe zone so Android's
mask does not crop pantograph / mic / bell and change the look.
"""
from __future__ import annotations

import io
from pathlib import Path

import cairosvg
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
SVG = ROOT / "icon.svg"
RES = ROOT / "android/app/src/main/res"

LAUNCHER = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
# Adaptive foreground canvas = 108dp * density
FOREGROUND = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}

BRAND_BLUE = (30, 58, 138, 255)  # #1e3a8a


def render_svg(size: int) -> Image.Image:
    png = cairosvg.svg2png(url=str(SVG), output_width=size, output_height=size)
    return Image.open(io.BytesIO(png)).convert("RGBA")


def round_mask(size: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, size - 1, size - 1), fill=255)
    return mask


def make_adaptive_foreground(canvas: int) -> Image.Image:
    """Place full brand icon inside adaptive safe zone (~66% of canvas)."""
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    # Safe zone diameter ≈ 72/108 of canvas. Use slightly smaller inset for margin.
    art_size = int(round(canvas * (72 / 108) * 0.96))
    art = render_svg(art_size)
    # Fill transparent corners of rounded SVG with brand blue so mask edges stay solid
    flat = Image.new("RGBA", art.size, BRAND_BLUE)
    flat = Image.alpha_composite(flat, art)
    x = (canvas - art_size) // 2
    y = (canvas - art_size) // 2
    out.paste(flat, (x, y))
    return out


def main() -> None:
    if not SVG.exists():
        raise SystemExit(f"Missing {SVG}")

    for folder, size in LAUNCHER.items():
        out_dir = RES / folder
        out_dir.mkdir(parents=True, exist_ok=True)
        icon = render_svg(size)
        # Exact original design for home-screen / launcher list
        icon.save(out_dir / "ic_launcher.png")

        rounded = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        base = Image.new("RGBA", (size, size), BRAND_BLUE)
        base = Image.alpha_composite(base, icon)
        rounded.paste(base, (0, 0))
        rounded.putalpha(round_mask(size))
        rounded.save(out_dir / "ic_launcher_round.png")

    for folder, size in FOREGROUND.items():
        out_dir = RES / folder
        make_adaptive_foreground(size).save(out_dir / "ic_launcher_foreground.png")

    (RES / "values/ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<resources>\n"
        '    <color name="ic_launcher_background">#1E3A8A</color>\n'
        "</resources>\n",
        encoding="utf-8",
    )

    # Adaptive XML: solid brand background + safe-zone foreground
    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (anydpi / name).write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@color/ic_launcher_background"/>\n'
            '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
            "</adaptive-icon>\n",
            encoding="utf-8",
        )

    # Replace Capacitor default vector so nothing accidentally uses the robot mark
    v24 = RES / "drawable-v24/ic_launcher_foreground.xml"
    if v24.exists():
        v24.write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<layer-list xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <item android:drawable="@mipmap/ic_launcher_foreground"/>\n'
            "</layer-list>\n",
            encoding="utf-8",
        )

    print("Launcher icons generated from icon.svg (original brand design)")


if __name__ == "__main__":
    main()
