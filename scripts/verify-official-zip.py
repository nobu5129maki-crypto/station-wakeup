#!/usr/bin/env python3
"""公式ZIPのファイル名・本文が文字化けしないことを検証する。"""
from __future__ import annotations

import struct
import sys
import zipfile
from pathlib import Path

NEEDLE = "はじめにお読みください"


def _local_names(path: Path) -> list[tuple[bytes, int]]:
    data = path.read_bytes()
    out = []
    i = 0
    while True:
        pos = data.find(b"PK\x03\x04", i)
        if pos < 0:
            break
        flag = struct.unpack_from("<H", data, pos + 6)[0]
        nlen = struct.unpack_from("<H", data, pos + 26)[0]
        name = data[pos + 30 : pos + 30 + nlen]
        out.append((name, flag))
        i = pos + 4
    return out


def verify(path: Path) -> None:
    if not path.is_file():
        raise SystemExit(f"ZIP not found: {path}")

    names = _local_names(path)
    raws = [n for n, _ in names]

    for raw, flag in names:
        try:
            raw.decode("ascii")
        except UnicodeDecodeError:
            raise SystemExit(f"FAIL: non-ASCII filename {raw!r} (will mojibake)")
        if flag & 0x0800:
            raise SystemExit(f"FAIL: UTF-8 filename flag on {raw.decode('ascii')}")

    required = {b"README.txt", b"README-UTF8.txt", b"StationWakeUp-Official.apk"}
    missing = required - set(raws)
    if missing:
        raise SystemExit(f"FAIL: missing entries {missing}")

    with zipfile.ZipFile(path) as zf:
        sjis = zf.read("README.txt")
        utf8 = zf.read("README-UTF8.txt")
        try:
            sjis_text = sjis.decode("cp932")
        except UnicodeDecodeError as e:
            raise SystemExit(f"FAIL: README.txt is not CP932: {e}")
        if NEEDLE not in sjis_text:
            raise SystemExit("FAIL: README.txt missing Japanese title")
        # UTF-8 として開くとタイトルは出ない（日本語ビューア向けSJISだから）
        try:
            if NEEDLE in sjis.decode("utf-8"):
                raise SystemExit("FAIL: README.txt is UTF-8 (SJIS viewer will mojibake)")
        except UnicodeDecodeError:
            pass

        if not utf8.startswith(b"\xef\xbb\xbf"):
            raise SystemExit("FAIL: README-UTF8.txt missing UTF-8 BOM")
        if NEEDLE not in utf8.decode("utf-8-sig"):
            raise SystemExit("FAIL: README-UTF8.txt missing Japanese title")

        if "StationWakeUp-Official.apk" not in zf.namelist():
            raise SystemExit("FAIL: APK missing")

    print("ZIP_ENCODING_OK")
    print(f"  filenames: ASCII only ({', '.join(n.decode('ascii') for n in raws)})")
    print(f"  README.txt = CP932, title={NEEDLE}")
    print("  README-UTF8.txt = UTF-8 BOM")


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    target = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "downloads/StationWakeUp-Official.zip"
    if not target.is_absolute():
        target = root / target
    verify(target)
