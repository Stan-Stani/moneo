#!/usr/bin/env python3
"""Render ROM font glyphs next to their codepoint_map labels, for eyeballing.

Each cell: the glyph as the game draws it (atlas 1, see FONT_HUNT.md:
internal index = ((hi - 0x35) << 8) | lo, 64 bytes per slot; hangul use
the top-left 8x13 pixels, raw 2bpp 1 = ink, 2 = shadow),
the current label drawn in Noto Sans CJK, and the storage codepoint.

Usage:
    glyph_sheet.py --suspects            # labels out of KS X 1001 order
    glyph_sheet.py --all                 # every labeled hangul code
    glyph_sheet.py --codes 3747 41EE     # specific codes
Writes PNG sheets (60 cells each) to --out (default: /tmp/glyph_sheets).
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from render_jamo_atlas import decode_2bpp_tile_8x8

HERE = Path(__file__).resolve().parent
ROM = HERE / "leafgreen_J-K_2024.gba"
CMAP = HERE / "codepoint_map.json"
ATLAS1 = 0xF18800
FONT = "/usr/share/fonts/google-noto-sans-cjk-fonts/NotoSansCJK-Regular.ttc"
SCALE, COLS, PER_SHEET = 9, 10, 60
GW, GH = 8, 13          # hangul glyphs occupy the top-left 8x13 of the 16x16 cell
CELL_W, CELL_H = GW * SCALE + 70, GH * SCALE + 22
INK = {1: 0, 2: 200}    # raw 2bpp: 1 = ink, 2 = drop shadow, 0/3 = paper


def glyph(rom: bytes, cp: int) -> Image.Image:
    idx = (((cp >> 8) - 0x35) << 8) | (cp & 0xFF)
    src = rom[ATLAS1 + idx * 64: ATLAS1 + idx * 64 + 64]
    im = Image.new("L", (GW, GH), 255)
    px = im.load()
    tl, bl = decode_2bpp_tile_8x8(src, 0), decode_2bpp_tile_8x8(src, 32)
    for y in range(GH):
        row = tl[y] if y < 8 else bl[y - 8]
        for x in range(GW):
            px[x, y] = INK.get(row[x], 255)
    return im.resize((GW * SCALE, GH * SCALE), Image.NEAREST)


def ks(ch: str) -> int | None:
    try:
        b = ch.encode("euc-kr")
    except UnicodeEncodeError:
        return None
    return (b[0] - 0xB0) * 94 + (b[1] - 0xA1) if len(b) == 2 and b[0] >= 0xB0 else None


def suspects(cmap: dict[int, str]) -> list[int]:
    hangul = sorted((k, v) for k, v in cmap.items() if ks(v) is not None)
    out = set()
    for i in range(1, len(hangul) - 1):
        a, b, c = (ks(hangul[j][1]) for j in (i - 1, i, i + 1))
        if a < c and not a < b < c:
            out.add(hangul[i][0])
    return sorted(out)


def main() -> int:
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--suspects", action="store_true")
    g.add_argument("--all", action="store_true")
    g.add_argument("--codes", nargs="+")
    ap.add_argument("--out", type=Path, default=Path("/tmp/glyph_sheets"))
    args = ap.parse_args()

    rom = ROM.read_bytes()
    cmap = {int(k, 16): v for k, v in json.loads(CMAP.read_text()).items()}
    if args.codes:
        codes = [int(c, 16) for c in args.codes]
    elif args.suspects:
        codes = suspects(cmap)
    else:
        codes = sorted(k for k, v in cmap.items() if ks(v) is not None)

    font = ImageFont.truetype(FONT, 44)
    small = ImageFont.truetype(FONT, 16)
    args.out.mkdir(parents=True, exist_ok=True)
    for s in range(0, len(codes), PER_SHEET):
        chunk = codes[s:s + PER_SHEET]
        rows = (len(chunk) + COLS - 1) // COLS
        sheet = Image.new("L", (COLS * CELL_W, rows * CELL_H), 255)
        d = ImageDraw.Draw(sheet)
        for i, cp in enumerate(chunk):
            x, y = (i % COLS) * CELL_W, (i // COLS) * CELL_H
            sheet.paste(glyph(rom, cp), (x + 4, y + 4))
            d.text((x + GW * SCALE + 12, y + 20), cmap.get(cp, "?"), font=font, fill=0)
            d.text((x + 4, y + GH * SCALE + 2), f"{cp:04X}", font=small, fill=90)
            d.rectangle([x, y, x + CELL_W - 1, y + CELL_H - 1], outline=200)
        path = args.out / f"sheet_{s // PER_SHEET:02d}.png"
        sheet.save(path)
        print(path, len(chunk))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
