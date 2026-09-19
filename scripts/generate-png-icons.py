#!/usr/bin/env python3
"""icon.svg（原版）から、配布に使う PNG アイコン一式を生成する。

出力:
  icons/icon-192.png, icons/icon-512.png       … PWA / Android ブラウザ
  icons/icon-maskable-512.png                  … Android マスク対応（余白付き）
  icons/apple-touch-icon.png (180x180)         … iPhone ホーム画面追加
  icons/favicon-32.png, icons/favicon-16.png   … タブ / ブックマーク
  icon.png (512x512)                           … 通知・汎用
  ios/App/App/Assets.xcassets/AppIcon.appiconset/AppIcon-512@2x.png (1024x1024, 不透明)
"""
from __future__ import annotations

import io
from pathlib import Path

import cairosvg
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SVG = ROOT / "icon.svg"
OUT = ROOT / "icons"
BRAND_BLUE = (30, 58, 138, 255)


def render(size: int) -> Image.Image:
    png = cairosvg.svg2png(url=str(SVG), output_width=size, output_height=size)
    return Image.open(io.BytesIO(png)).convert("RGBA")


def flatten(img: Image.Image) -> Image.Image:
    """角丸の透明部分をブランド色で埋める（iOS は不透明 PNG が必須）。"""
    base = Image.new("RGBA", img.size, BRAND_BLUE)
    return Image.alpha_composite(base, img).convert("RGB")


def maskable(size: int) -> Image.Image:
    """Android のマスク（円・角丸）で欠けないよう 80% に縮めて中央配置。"""
    canvas = Image.new("RGBA", (size, size), BRAND_BLUE)
    art_size = int(size * 0.8)
    art = flatten(render(art_size)).convert("RGBA")
    off = (size - art_size) // 2
    canvas.paste(art, (off, off))
    return canvas.convert("RGB")


def main() -> None:
    if not SVG.exists():
        raise SystemExit(f"Missing {SVG}")
    OUT.mkdir(parents=True, exist_ok=True)

    render(192).save(OUT / "icon-192.png", optimize=True)
    render(512).save(OUT / "icon-512.png", optimize=True)
    maskable(512).save(OUT / "icon-maskable-512.png", optimize=True)
    flatten(render(180)).save(OUT / "apple-touch-icon.png", optimize=True)
    render(32).save(OUT / "favicon-32.png", optimize=True)
    render(16).save(OUT / "favicon-16.png", optimize=True)
    render(512).save(ROOT / "icon.png", optimize=True)

    ios_dir = ROOT / "ios/App/App/Assets.xcassets/AppIcon.appiconset"
    if ios_dir.exists():
        flatten(render(1024)).save(ios_dir / "AppIcon-512@2x.png", optimize=True)

    print("PNG icons generated from icon.svg")


if __name__ == "__main__":
    main()
