#!/usr/bin/env python3
"""Re-decode corpus.ko.2024.json with the variable-width decoder (ko_text)
and add the dialog strings the fixed-width scan never found.

scan_rom_2024.py decoded dialog as fixed 16-bit codepoints and rounded odd
pointer targets down to even. The 2024 patch actually mixes 1-byte Gen 3
characters (space, punctuation, controls) with 2-byte hangul, so

  * existing records lost everything after their first space until the
    stream happened to realign ("쓰레기통에··· 나무열매가 ···있었는데···"),
  * every string starting at an odd address (about half of them) was
    either missing or recorded at offset-1, where the decode is garbage.

This keeps record ids stable, because map/lemma indexes and the shipped
sentence decks refer to them:

  1. A record whose offset+1 is a clean pointer-target string (the old
     round-down) moves to offset+1 and takes that text.
  2. A record whose own offset decodes cleanly gets the clean text.
  3. Anything else (name tables in the fixed-width font, non-text bytes the
     old scan accepted) is left untouched.
  4. Clean pointer-target strings not covered above are appended with new
     ids, in offset order.

"Clean" = reaches an 0xFF terminator with no unmapped bytes and at least
two hangul syllables.

Usage:
    python3 tools/moneo/redecode_corpus_2024.py [--dry]
"""
from __future__ import annotations

import argparse
import json
import re
import struct
import sys
from collections import Counter
from pathlib import Path

THIS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(THIS_DIR))
from ko_text import decode, load_codepoint_map  # noqa: E402
from rom_config import GBA_BASE, ROM_PATH  # noqa: E402

CORPUS = THIS_DIR / "corpus.ko.2024.json"
# Shipped copy: the same dialog records plus name-table records appended by
# extend_corpus_with_name_tables.py (ids 7015+). Kept in sync here, and new
# dialog ids start after its highest id so they never collide.
APP_CORPUS = THIS_DIR.parents[1] / "app/src/main/assets/moneo/corpus.ko.json"
HANGUL = re.compile("[가-힣]")


def pointer_targets(rom: bytes) -> set[int]:
    """Every u32 LE value that points into the ROM, at any byte alignment:
    event scripts embed text pointers unaligned (e.g. after a msgbox opcode)."""
    out = set()
    for i in range(0, len(rom) - 3):
        p = struct.unpack_from("<I", rom, i)[0]
        if GBA_BASE <= p < GBA_BASE + len(rom):
            out.add(p - GBA_BASE)
    return out


def clean_decode(rom: bytes, off: int, cmap: dict[int, str]) -> str | None:
    text, end, unknown = decode(rom, off, cmap, max_len=1500)
    if end is None or unknown or len(HANGUL.findall(text)) < 2:
        return None
    return text


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry", action="store_true")
    args = ap.parse_args()

    rom = ROM_PATH.read_bytes()
    cmap = load_codepoint_map()
    corpus = json.loads(CORPUS.read_text())
    records = corpus["records"]

    targets = pointer_targets(rom)
    clean_targets = {}
    for t in targets:
        s = clean_decode(rom, t, cmap)
        if s is not None:
            clean_targets[t] = s

    stats = Counter()
    claimed: set[int] = set()
    for r in records:
        off = r["offset"]
        moved = clean_targets.get(off + 1)
        if moved is None and rom[off] == 0xFF:
            # Old scan rounded an odd pointer down onto the previous string's
            # terminator; the real string starts one byte later.
            moved = clean_decode(rom, off + 1, cmap)
        if moved is not None and off + 1 not in claimed:
            r["offset"], r["text"] = off + 1, moved
            stats["moved_to_odd_offset"] += 1
        elif (s := clean_targets.get(off)) is not None or (s := clean_decode(rom, off, cmap)) is not None:
            if off in claimed:
                continue
            r["text"] = s
            stats["redecoded"] += 1
        else:
            stats["untouched"] += 1
            continue
        r["unknown"] = 0
        r["hangul"] = len(HANGUL.findall(r["text"]))
        claimed.add(r["offset"])

    app = json.loads(APP_CORPUS.read_text())
    dialog_ids = {r["id"] for r in records}
    name_table_records = [r for r in app["records"] if r["id"] not in dialog_ids]
    next_id = max(r["id"] for r in app["records"] + records) + 1
    for t in sorted(set(clean_targets) - claimed):
        text = clean_targets[t]
        records.append({"id": next_id, "offset": t, "text": text, "unknown": 0,
                        "hangul": len(HANGUL.findall(text))})
        next_id += 1
        stats["added"] += 1

    corpus["encoding"] = "gen3-1byte + be16-hangul (ko_text.py)"
    corpus["stats"].update({
        "record_count": len(records),
        "hangul_chars": sum(r["hangul"] for r in records),
        "unknown_glyphs": sum(r["unknown"] for r in records),
    })
    print(dict(stats), "total records:", len(records))
    if not args.dry:
        CORPUS.write_text(json.dumps(corpus, ensure_ascii=False, indent=1) + "\n")
        print(f"wrote {CORPUS}")
        app["records"] = sorted(records + name_table_records, key=lambda r: r["id"])
        app["encoding"] = corpus["encoding"]
        APP_CORPUS.write_text(json.dumps(app, ensure_ascii=False, indent=1))
        print(f"wrote {APP_CORPUS} ({len(app['records'])} records)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
