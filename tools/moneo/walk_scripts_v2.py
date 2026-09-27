#!/usr/bin/env python3
"""Deterministic FRLG script walker using pokefirered's opcode table.

Walks each map's events.scripts as proper bytecode, identifying every
text-loading instruction (message, msgbox, trainerbattle, etc.) by its
opcode rather than scanning windows for u32-shaped values. Drops the
proximity heuristic (Pass 4) entirely.

Output: tools/moneo/map_text_index.json (same schema as walk_map_scripts.py
so downstream tools work unchanged).
"""
from __future__ import annotations
import argparse
import json
import struct
import sys
from collections import defaultdict
from pathlib import Path

THIS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(THIS_DIR))
from script_opcodes import (  # type: ignore
    OPCODES,
    TEXT_LOADING_OPCODES,
    LOADWORD_OPCODE,
    CALLSTD_OPCODE,
    CALL_GOTO_OPCODES,
    CALL_GOTO_IF_OPCODES,
    END_OPCODES,
    BUFFERSTRING_OPCODES,
    TRAINERBATTLE_LAYOUTS,
)

ROOT = THIS_DIR.parents[1]
from rom_config import (  # noqa: E402
    ROM_PATH,
    GBA_BASE,
    GMAP_GROUPS,
    get_group_offsets,
    u32 as _cfg_u32,
    u16 as _cfg_u16,
    is_rom_ptr as _cfg_is_rom_ptr,
)

ROM = ROM_PATH
CORPUS_STATIC = ROOT / "app/src/main/assets/moneo/corpus.ko.json"
CORPUS_LIVE = THIS_DIR / "corpus.ko.live.json"
OUT = THIS_DIR / "map_text_index.json"

# GROUP_OFFSETS is now derived dynamically from the ROM via get_group_offsets()
# at walk-time, since the offsets differ between ROM revisions. The 2024 patch
# has gMapGroups @ 0x3526F8 with 43 groups in canonical pokefirered order.


def u32(rom: bytes, off: int) -> int:
    return struct.unpack_from("<I", rom, off)[0]


def u16(rom: bytes, off: int) -> int:
    return struct.unpack_from("<H", rom, off)[0]


def is_rom_ptr(p: int, rom_len: int) -> bool:
    return GBA_BASE <= p < GBA_BASE + rom_len


def walk_maps(rom: bytes):
    group_offsets = get_group_offsets(rom)
    n_groups = len(group_offsets) - 1
    for g in range(n_groups):
        start, end = group_offsets[g], group_offsets[g + 1]
        for i in range((end - start) // 4):
            p = u32(rom, start + i * 4)
            if not is_rom_ptr(p, len(rom)):
                continue
            mh = p - GBA_BASE
            mapsec = rom[mh + 20]
            music = u16(rom, mh + 16)
            yield g, i, mh, mapsec, music


def collect_seed_scripts(rom: bytes, header_off: int) -> list[int]:
    seeds: list[int] = []
    rom_len = len(rom)
    events_ptr = u32(rom, header_off + 4)
    map_scripts_ptr = u32(rom, header_off + 8)
    if is_rom_ptr(map_scripts_ptr, rom_len):
        ms = map_scripts_ptr - GBA_BASE
        for _ in range(32):
            if ms + 1 > rom_len:
                break
            t = rom[ms]
            if t == 0:
                break
            for delta in (1, 5):
                if ms + delta + 4 <= rom_len:
                    p = u32(rom, ms + delta)
                    if is_rom_ptr(p, rom_len):
                        seeds.append(p - GBA_BASE)
            ms += 5
    if is_rom_ptr(events_ptr, rom_len):
        evt = events_ptr - GBA_BASE
        if evt + 20 <= rom_len:
            n_obj = rom[evt + 0]
            n_coord = rom[evt + 2]
            n_sign = rom[evt + 3]
            obj_ptr = u32(rom, evt + 4)
            cs_ptr = u32(rom, evt + 12)
            sg_ptr = u32(rom, evt + 16)
            if is_rom_ptr(obj_ptr, rom_len) and n_obj > 0:
                base = obj_ptr - GBA_BASE
                for j in range(min(n_obj, 64)):
                    rec = base + j * 24
                    if rec + 24 > rom_len:
                        break
                    p = u32(rom, rec + 16)
                    if is_rom_ptr(p, rom_len):
                        seeds.append(p - GBA_BASE)
            if is_rom_ptr(cs_ptr, rom_len) and n_coord > 0:
                base = cs_ptr - GBA_BASE
                for j in range(min(n_coord, 64)):
                    rec = base + j * 16
                    if rec + 16 > rom_len:
                        break
                    p = u32(rom, rec + 12)
                    if is_rom_ptr(p, rom_len):
                        seeds.append(p - GBA_BASE)
            if is_rom_ptr(sg_ptr, rom_len) and n_sign > 0:
                base = sg_ptr - GBA_BASE
                for j in range(min(n_sign, 64)):
                    rec = base + j * 12
                    if rec + 12 > rom_len:
                        break
                    kind = rom[rec + 5]
                    if kind in (0, 1):
                        p = u32(rom, rec + 8)
                        if is_rom_ptr(p, rom_len):
                            seeds.append(p - GBA_BASE)
    return seeds


# Maximum bytes to walk per script blob before bailing (safety bound).
MAX_SCRIPT_BYTES = 2048
# Maximum recursion depth (CALL/GOTO chains).
MAX_DEPTH = 2


def disassemble_script(rom: bytes, script_off: int, corpus_offsets: set,
                       visited: set | None = None,
                       depth: int = 0) -> set:
    """Return the set of corpus-offsets referenced by the script starting
    at script_off, walking opcode-by-opcode and following CALL/GOTO."""
    if visited is None:
        visited = set()
    if script_off in visited or depth > MAX_DEPTH:
        return set()
    visited.add(script_off)
    rom_len = len(rom)
    end = min(script_off + MAX_SCRIPT_BYTES, rom_len)

    found: set = set()
    sub_calls: list[int] = []
    i = script_off
    last_was_loadword_zero = False
    last_loadword_text: int | None = None

    while i < end:
        op = rom[i]
        if op in END_OPCODES:
            break
        info = OPCODES.get(op)
        # Fallback for unknown opcode bytes: stop -- we can't safely continue.
        if info is None:
            break

        # Trainerbattle: variable layout by subtype
        if op == 0x5C:
            if i + 2 > rom_len:
                break
            subtype = rom[i + 1]
            layout = TRAINERBATTLE_LAYOUTS.get(subtype)
            if layout is None:
                # Unknown subtype: bail, scan rest as unknown
                break
            length, text_ptr_offsets = layout
            for ptr_off in text_ptr_offsets:
                ptr_at = i + ptr_off
                if ptr_at + 4 > rom_len:
                    continue
                v = u32(rom, ptr_at)
                if is_rom_ptr(v, rom_len):
                    t = v - GBA_BASE
                    if t in corpus_offsets:
                        found.add(t)
            i += length
            last_was_loadword_zero = False
            continue

        # Bufferstring (0x85/0xBF): .byte op, .byte stringVarId, .4byte text
        if op in BUFFERSTRING_OPCODES:
            if i + 6 > rom_len:
                break
            v = u32(rom, i + 2)
            if is_rom_ptr(v, rom_len):
                t = v - GBA_BASE
                if t in corpus_offsets:
                    found.add(t)
            i += 6
            last_was_loadword_zero = False
            continue

        # Direct text-loading opcodes (message, braillemessage, etc.):
        if op in TEXT_LOADING_OPCODES:
            length = info["length"] or 5
            for ptr_off in info.get("ptr_offsets", []):
                ptr_at = i + ptr_off
                if ptr_at + 4 > rom_len:
                    continue
                v = u32(rom, ptr_at)
                if is_rom_ptr(v, rom_len):
                    t = v - GBA_BASE
                    if t in corpus_offsets:
                        found.add(t)
            i += length
            last_was_loadword_zero = False
            continue

        # loadword: if dest=0, the word might be a text ptr (msgbox pattern)
        if op == LOADWORD_OPCODE:
            length = info["length"] or 6
            if i + 6 <= rom_len:
                dest = rom[i + 1]
                v = u32(rom, i + 2)
                if dest == 0 and is_rom_ptr(v, rom_len):
                    t = v - GBA_BASE
                    if t in corpus_offsets:
                        # Confirmed text only if next instruction is callstd.
                        # But save it tentatively and emit once verified.
                        last_was_loadword_zero = True
                        last_loadword_text = t
                    else:
                        last_was_loadword_zero = False
            i += length
            continue

        # callstd: confirms the previous loadword was a text load
        if op == CALLSTD_OPCODE:
            if last_was_loadword_zero and last_loadword_text is not None:
                found.add(last_loadword_text)
            last_was_loadword_zero = False
            last_loadword_text = None
            length = info["length"] or 2
            i += length
            continue

        # CALL/GOTO: recurse into target
        if op in CALL_GOTO_OPCODES:
            length = info["length"] or 5
            if i + 5 <= rom_len:
                v = u32(rom, i + 1)
                if is_rom_ptr(v, rom_len):
                    sub_calls.append(v - GBA_BASE)
            if op == 0x05:  # goto = unconditional, stop linear walk
                break
            i += length
            last_was_loadword_zero = False
            continue

        # CALL_IF / GOTO_IF: condition byte at +1, ptr at +2
        if op in CALL_GOTO_IF_OPCODES:
            length = info["length"] or 6
            if i + 6 <= rom_len:
                v = u32(rom, i + 2)
                if is_rom_ptr(v, rom_len):
                    sub_calls.append(v - GBA_BASE)
            i += length
            last_was_loadword_zero = False
            continue

        # Default: step by opcode length
        length = info["length"]
        if length is None:
            # Variable-length opcode we don't statically handle.
            # Bail to hybrid window-scan from here.
            break
        i += length
        last_was_loadword_zero = False

    # Hybrid fallback: from the bail point (i) to the end of our
    # MAX_SCRIPT_BYTES window, scan for u32 LE values that hit corpus
    # offsets. Catches text refs in unknown/variable opcodes' bodies and
    # any inline data we couldn't disassemble.
    if i < end:
        for j in range(i, end - 3):
            v = struct.unpack_from("<I", rom, j)[0]
            if (v & 0xFE000000) != GBA_BASE:
                continue
            t = v - GBA_BASE
            if t in corpus_offsets:
                found.add(t)

    # Recurse into CALL/GOTO targets
    for sub in sub_calls:
        found |= disassemble_script(rom, sub, corpus_offsets, visited, depth + 1)
    return found


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    args = ap.parse_args()
    rom = ROM.read_bytes()

    static = json.loads(CORPUS_STATIC.read_text())
    live = json.loads(CORPUS_LIVE.read_text())

    rec_by_offset: dict[int, dict] = {}
    for r in static["records"]:
        if r.get("offset") is not None:
            rec_by_offset[r["offset"]] = {**r, "region": "static"}
    for r in live["records"]:
        if r.get("offset") is not None:
            rec_by_offset[r["offset"]] = {**r, "region": "live"}
    corpus_offsets = set(rec_by_offset.keys())
    print(f"corpus offsets: {len(corpus_offsets):,} "
          f"(static={len(static['records'])}, live={len(live['records'])})")

    mapsec_to_recids: dict[int, set[int]] = defaultdict(set)
    map_records: list[dict] = []
    n_seeds_total = 0

    for g, m, mh, mapsec, music in walk_maps(rom):
        seeds = collect_seed_scripts(rom, mh)
        n_seeds_total += len(seeds)
        text_offs: set[int] = set()
        visited: set = set()
        for s in seeds:
            text_offs |= disassemble_script(rom, s, corpus_offsets, visited)
        rec_ids = {rec_by_offset[o]["id"] for o in text_offs}
        mapsec_to_recids[mapsec] |= rec_ids
        map_records.append({
            "group": g, "mapNum": m, "mapsec": mapsec,
            "header": mh, "music": music,
            "seedScripts": len(seeds),
            "textRefs": sorted(text_offs),
            "recIds": sorted(rec_ids),
        })

    print(f"Maps walked: {len(map_records)}")
    print(f"Total seed scripts: {n_seeds_total}")

    out = {
        "version": 2,
        "rom": ROM.name,
        "method": "deterministic-disassembly-via-pokefirered-opcode-table",
        "stats": {
            "maps": len(map_records),
            "seed_scripts_total": n_seeds_total,
            "distinct_mapsecs_with_refs":
                sum(1 for s in mapsec_to_recids.values() if s),
        },
        "mapsec_to_rec_ids": {
            f"0x{ms:02X}": sorted(recs)
            for ms, recs in sorted(mapsec_to_recids.items())
        },
        "maps": map_records,
    }
    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n")
    print(f"\nwrote {OUT}")

    # Quick stat: total live records reached
    live_ids = {r["id"] for r in live["records"]}
    all_reached = set()
    for m in map_records:
        all_reached.update(m["recIds"])
    print(f"Live records reached: {len(live_ids & all_reached)} / {len(live_ids)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
