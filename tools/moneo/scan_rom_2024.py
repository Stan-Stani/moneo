#!/usr/bin/env python3
"""Scan the 2024 Korean LeafGreen ROM for dialog/text records.

SUPERSEDED for dialog text: this scanner decodes fixed-width 16-bit
codepoints and rounds odd pointers down, but dialog is variable-width
(1-byte Gen 3 chars + 2-byte hangul). Use ko_text.decode() and
redecode_corpus_2024.py, which re-decode corpus.ko.2024.json in place
(keeping record ids) and add the strings this scan missed. Re-running
this script would renumber every record.

Why this exists. The 2010 fan-translation used a F0..F6 page-byte encoding
(see build_corpus.py + glyph-map.json). The 2024-02-29 patch by 명군/tony/koi
re-encoded all text — both name tables and dialog — as 16-bit big-endian
codepoints into a custom hangul font. The page-byte decoder produces noise
when fed 2024-ROM bytes; this script is the BE-codepoint equivalent.

What this does. Scans the ROM for u32 LE pointer literals whose target
contains a sequence of 16-bit BE codepoints terminated by 0xFF00. Validates
the run by hangul density and length. Emits both a raw artifact (offset
+ hex bytes, mirroring rom-text-ko-raw.json) and a decoded preview using
the existing rom_swap/codepoint_map.json (533 codepoints, ~43% coverage of
hangul bytes — the unknowns are dumped to a separate frequency-ranked list
to drive labeling).

Outputs:
- .moneo-artifacts/rom-text-2024-raw.json   raw scan, schema-compatible with rom-text-ko-raw.json
- tools/moneo/corpus.ko.2024.json           decoded preview (records with text + unknowns + hangul)
- tools/moneo/codepoint_unknowns_2024.json  frequency-ranked unknown codepoints (labeling worklist)

Usage:
    python3 tools/moneo/scan_rom_2024.py
    python3 tools/moneo/scan_rom_2024.py --dry      # report-only, no writes
"""
from __future__ import annotations
import argparse
import json
import struct
import sys
from collections import Counter
from pathlib import Path

THIS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(THIS_DIR))
from rom_config import ROM_PATH, GBA_BASE  # noqa: E402

ROOT = THIS_DIR.parents[1]
CODEPOINT_MAP = THIS_DIR / "rom_swap/codepoint_map.json"

OUT_RAW = ROOT / ".moneo-artifacts/rom-text-2024-raw.json"
OUT_CORPUS = THIS_DIR / "corpus.ko.2024.json"
OUT_UNKNOWNS = THIS_DIR / "codepoint_unknowns_2024.json"

# === Encoding constants (empirically validated against name tables + dialog) ===
END_MARKER = 0xFF00         # message terminator
HANGUL_LO = 0x3700          # observed hangul range floor (가 = 0x3701)
HANGUL_HI = 0x40FF          # observed hangul range ceiling
PAD_VALUES = {0xFFFF, 0x0000}   # filler bytes inside / after messages

# Inline format/var codes observed in dialog: 0xFCXX, 0xFDXX, 0xFEXX (XX != 0).
# 0xFF00 itself is end-of-message; other 0xFFxx values appear to be
# scroll/clear/format codes that we want to consume rather than abort on.
def is_inline_control(cp: int) -> bool:
    if cp == END_MARKER:
        return False  # terminator, not an inline control
    return (cp & 0xFF00) in (0xFC00, 0xFD00, 0xFE00, 0xFF00)


MAX_RUN_CODEPOINTS = 400    # generous — long Pokédex / NPC speeches exist
MIN_RUN_CODEPOINTS = 2      # accept short messages (proper names, single words)

# A run is "validated" if it has at least this many consecutive known hangul
# codepoints OR enough total known hangul. Rejecting runs without any known
# hangul keeps junk pointers (palette refs, struct ptrs, etc.) out.
MIN_KNOWN_HANGUL = 2
MIN_HANGUL_DENSITY = 0.25   # known hangul / total non-control codepoints

# === Helpers ===
def is_hangul(ch: str) -> bool:
    return "가" <= ch <= "힣"


def is_hangul_codepoint(cp: int) -> bool:
    """Whether a codepoint falls in the empirical hangul-font range."""
    return HANGUL_LO <= cp <= HANGUL_HI


def read_message(rom: bytes, off: int, inv: dict[int, str], hard_end: int | None = None):
    """Walk a 2-byte-aligned message starting at `off`. Returns dict with
    text/unknown_count/hangul/length_codepoints/end_off, or None if off
    doesn't open a clean message.

    Tolerant decoding policy (informed by dialog around 박사 etc.):
      - 0xFF00 terminates the message
      - 0xFFFF / 0x0000 immediately abort (filler, not a real message)
      - 0xFCXX / 0xFDXX / 0xFEXX / other 0xFFxx = inline format/var control;
        emit a placeholder ({var:XX} for 0xFD), continue
      - Codepoints in the hangul range 0x3700..0x40FF: known -> decode,
        unknown -> [HEX] placeholder + count toward unknowns
      - Codepoints below 0x3700 or above 0x40FF (excluding the FCxx-FFxx
        controls already handled): treated as ASCII/punctuation/space/format
        bytes; emit a tiny "·" placeholder, do NOT count as hangul. We allow
        a bounded number of these before declaring junk.
    """
    if off & 1:
        return None
    if off + 4 > len(rom):
        return None
    out: list[str] = []
    unknowns: list[int] = []
    n_codepoints = 0
    n_hangul = 0
    n_known = 0
    n_other = 0
    consec_other = 0
    max_consec_known = 0
    cur_consec_known = 0
    i = off
    end_off = None
    limit = len(rom) if hard_end is None else min(len(rom), hard_end)
    while i + 2 <= limit and n_codepoints < MAX_RUN_CODEPOINTS:
        cp = struct.unpack_from(">H", rom, i)[0]
        if cp == END_MARKER:
            end_off = i
            break
        if cp == 0xFFFF or cp == 0x0000:
            # Filler bytes are never inside a real message
            return None
        if is_inline_control(cp):
            hi, lo = cp >> 8, cp & 0xFF
            if hi == 0xFD:
                out.append(f"{{var:{lo:02X}}}")
            elif hi == 0xFC:
                # format code (color/font); silently consume
                pass
            elif hi == 0xFE:
                out.append("\n")
            else:  # 0xFFxx with xx != 00
                out.append("\n")
            consec_other = 0
            cur_consec_known = 0
            i += 2
            n_codepoints += 1
            continue
        if cp in inv:
            ch = inv[cp]
            out.append(ch)
            n_known += 1
            cur_consec_known += 1
            max_consec_known = max(max_consec_known, cur_consec_known)
            if is_hangul(ch):
                n_hangul += 1
            consec_other = 0
        elif HANGUL_LO <= cp <= HANGUL_HI:
            out.append(f"[{cp:04X}]")
            unknowns.append(cp)
            consec_other = 0
            cur_consec_known = 0
        else:
            # Out-of-range value: treat as ASCII/punctuation/format byte
            out.append("·")
            n_other += 1
            consec_other += 1
            cur_consec_known = 0
            # Too many consecutive non-text codepoints = we're scanning
            # bytecode, not a message. Bail. (Real dialog has up to ~10 in
            # a row between paragraphs for color/format/var sequences.)
            if consec_other >= 16:
                return None
        i += 2
        n_codepoints += 1

    # If we ran into hard_end (next pointer target) without seeing 0xFF00,
    # accept what we have so far. Tables in this ROM (e.g. trainer-dialog
    # at 0x230000+, multi-line menu text at 0x72C000+) bound entries by the
    # next pointer literal rather than by 0xFF00. Treat hard_end as a soft
    # terminator.
    if end_off is None:
        if hard_end is not None and i >= limit:
            end_off = i
        else:
            return None
    if n_codepoints < MIN_RUN_CODEPOINTS:
        return None
    text_codepoints = n_known + len(unknowns)  # codepoints in the text class
    if text_codepoints == 0:
        return None
    if n_known < MIN_KNOWN_HANGUL:
        return None
    if max_consec_known < MIN_KNOWN_HANGUL:
        return None
    density = n_known / max(text_codepoints, 1)
    if density < MIN_HANGUL_DENSITY:
        return None
    return {
        "off": off,
        "end_off": end_off,
        "text": "".join(out),
        "n_codepoints": n_codepoints,
        "hangul": n_hangul,
        "unknown": len(unknowns),
        "unknown_codepoints": unknowns,
    }


def find_pointer_targets(rom: bytes, scan_end: int | None = None) -> set[int]:
    """Scan ROM for u32 LE values that look like in-ROM pointers; return
    the set of file-offset targets. We use this as the universe of
    candidate message starts (proven to work for the 2010 ROM in
    extend_corpus_live.py).

    The 2024 patch sometimes stores dialog pointers as odd-aligned values
    (0x72CF41 etc.) — likely Thumb-mode-style convention reused for data.
    We round odd targets down to the nearest 2-byte boundary so the BE
    codepoint stream decodes cleanly.
    """
    targets: set[int] = set()
    if scan_end is None:
        scan_end = len(rom) - 4
    rom_len = len(rom)
    for off in range(0, scan_end):
        v = struct.unpack_from("<I", rom, off)[0]
        if (v & 0xFE000000) == GBA_BASE:
            target = v - GBA_BASE
            if 0xC0 < target < rom_len:
                targets.add(target & ~1)
    return targets


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--dry", action="store_true", help="report-only; no writes")
    args = ap.parse_args()

    rom = ROM_PATH.read_bytes()
    print(f"ROM: {ROM_PATH}  ({len(rom):,} bytes)")
    cm = json.loads(CODEPOINT_MAP.read_text(encoding="utf-8"))
    inv = {int(k, 16): v for k, v in cm.items()}
    print(f"codepoint_map: {len(inv)} entries (covering {sum(1 for v in inv.values() if is_hangul(v))} hangul)")

    print("\n[1/3] scanning ROM for u32 LE pointer literals...")
    targets = find_pointer_targets(rom)
    print(f"   pointer targets: {len(targets):,}")

    print("\n[2/3] decoding each target as a BE-codepoint message...")
    records: list[dict] = []
    seen_offs: set[int] = set()
    unknown_freq: Counter = Counter()
    sorted_targets = sorted(targets)
    # For each target, the message extends up to (but not including) the
    # next pointer target — many tables (trainer dialog, menu text) lack a
    # 0xFF00 within their entry and instead rely on table-stride layout.
    for idx, off in enumerate(sorted_targets):
        if off in seen_offs:
            continue
        # Find next target strictly greater than `off`. Cap the read length
        # at MAX_RUN_CODEPOINTS*2 bytes regardless, to bound short-table
        # adjacent entries from merging into mega-records.
        next_off = sorted_targets[idx + 1] if idx + 1 < len(sorted_targets) else len(rom)
        hard_end = min(next_off, off + MAX_RUN_CODEPOINTS * 2)
        msg = read_message(rom, off, inv, hard_end=hard_end)
        if msg is None:
            continue
        records.append(msg)
        seen_offs.add(off)
        for cp in msg["unknown_codepoints"]:
            unknown_freq[cp] += 1
    print(f"   accepted {len(records)} messages")
    print(f"   distinct unknown hangul codepoints: {len(unknown_freq)}")
    if unknown_freq:
        print("   top 10 unknowns:")
        for cp, n in unknown_freq.most_common(10):
            print(f"     0x{cp:04X} -> {n} occurrences")

    print("\n[3/3] sample dialog (first 8 records with hangul>=4 unknown<=2):")
    shown = 0
    for r in records:
        if r["hangul"] >= 4 and r["unknown"] <= 2:
            text = r["text"].replace("\n", " | ")
            print(f"     0x{r['off']:06X}  {text[:90]}")
            shown += 1
            if shown >= 8:
                break

    if args.dry:
        print("\n--dry: skipping writes")
        return 0

    OUT_RAW.parent.mkdir(parents=True, exist_ok=True)

    # Raw artifact: schema mirrors rom-text-ko-raw.json
    raw_records = []
    for i, r in enumerate(records):
        end = r["end_off"]
        hex_str = rom[r["off"]:end + 2].hex()  # include FF00 terminator
        raw_records.append({
            "id": i,
            "offset": r["off"],
            "len": end - r["off"] + 2,
            "hex": hex_str,
        })
    OUT_RAW.write_text(json.dumps({
        "rom": ROM_PATH.name,
        "encoding": "be16-codepoint",
        "stats": {
            "records": len(raw_records),
            "rom_bytes": len(rom),
        },
        "records": raw_records,
    }, ensure_ascii=False, indent=1))
    print(f"\nwrote {OUT_RAW}")

    # Decoded corpus: schema mirrors corpus.ko.json
    corpus_records = [{
        "id": i,
        "offset": r["off"],
        "text": r["text"],
        "unknown": r["unknown"],
        "hangul": r["hangul"],
    } for i, r in enumerate(records)]
    coverage = (sum(r["hangul"] for r in records) /
                max(sum(r["hangul"] + r["unknown"] for r in records), 1))
    OUT_CORPUS.write_text(json.dumps({
        "version": 1,
        "rom": ROM_PATH.name,
        "encoding": "be16-codepoint",
        "records": corpus_records,
        "stats": {
            "record_count": len(corpus_records),
            "hangul_chars": sum(r["hangul"] for r in records),
            "unknown_glyphs": sum(r["unknown"] for r in records),
            "coverage": round(coverage, 4),
            "map_size": len(inv),
        },
    }, ensure_ascii=False, indent=1))
    print(f"wrote {OUT_CORPUS}")

    # Unknowns worklist
    OUT_UNKNOWNS.write_text(json.dumps({
        "rom": ROM_PATH.name,
        "n_distinct_unknowns": len(unknown_freq),
        "unknowns": [{"codepoint": f"0x{cp:04X}", "occurrences": n}
                     for cp, n in unknown_freq.most_common()],
    }, ensure_ascii=False, indent=1))
    print(f"wrote {OUT_UNKNOWNS}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
