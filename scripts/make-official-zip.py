#!/usr/bin/env python3
"""公式配布ZIPを、Android/Windows で文字化けしない形で作る。

原因:
  「はじめにお読みください.txt」を UTF-8 ファイル名で入れると、
  日本語端末の ZIP アプリが Shift_JIS として開いて文字化けする。

対策:
  ファイル名は ASCII のみ。
  README.txt は日本語 Windows/Android 向けに CP932（Shift_JIS）。
  README-UTF8.txt は UTF-8 BOM（新しいビューア向け）。
  本文の1行目は「はじめにお読みください」。
"""
from __future__ import annotations

import argparse
import zipfile
from datetime import datetime
from pathlib import Path

README_LINES = [
    "はじめにお読みください",
    "",
    "Station WakeUp 公式アプリ",
    "",
    "このZIPは有料ノート購入者向けの公式配布です。",
    "Androidの確認画面（保護のためブロック等）は、Playストア外アプリへの標準的な案内です。",
    "危険なウイルスという意味ではありません。",
    "",
    "手順:",
    "1. このZIPを開き StationWakeUp-Official.apk を取り出す",
    "2. APKを開く",
    "3. 「詳細」→「それでもインストール」で進める",
    "4. 「開く」→ ホーム追加の確認が出たら「追加」",
    "5. 見当たらないときはアプリ一覧の当初アイコン（電車＋ベル）「Station WakeUp」",
    "6. アプリ内の「ホーム画面に追加する」でも追加できます",
    "",
    "ホーム画面のアイコンは当初デザイン（青い背景に電車＋ベル）です。",
]


def _info(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(filename=name, date_time=datetime.now().timetuple()[:6])
    info.create_system = 0
    info.flag_bits = 0
    info.compress_type = zipfile.ZIP_DEFLATED
    return info


def make_zip(apk_path: Path, zip_path: Path) -> None:
    if not apk_path.is_file():
        raise SystemExit(f"APK not found: {apk_path}")

    text = "\r\n".join(README_LINES) + "\r\n"
    zip_path.parent.mkdir(parents=True, exist_ok=True)
    if zip_path.exists():
        zip_path.unlink()

    with zipfile.ZipFile(zip_path, "w") as zf:
        zf.writestr(_info("README.txt"), text.encode("cp932"))
        zf.writestr(_info("README-UTF8.txt"), text.encode("utf-8-sig"))
        zf.writestr(_info("StationWakeUp-Official.apk"), apk_path.read_bytes())

    print(f"Wrote {zip_path} ({zip_path.stat().st_size} bytes)")


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--apk", default="downloads/StationWakeUp-Official.apk")
    p.add_argument("--out", default="dist/StationWakeUp-Official.zip")
    args = p.parse_args()
    root = Path(__file__).resolve().parents[1]
    apk = Path(args.apk) if Path(args.apk).is_absolute() else root / args.apk
    out = Path(args.out) if Path(args.out).is_absolute() else root / args.out
    if not apk.exists():
        alt = root / "public/downloads/StationWakeUp-Official.apk"
        if alt.exists():
            apk = alt
    make_zip(apk, out)


if __name__ == "__main__":
    main()
