#!/usr/bin/env python3
"""Resolve per-map area attribution by chaining warps.

Reads map_text_index.json, mapsec_areas.json, areas.json, and the ROM.
For each map without a direct mapsec→area mapping, follow warp exits
(up to 2 hops) to find the lowest-ordinal area. Writes a map_area_index.json
with aggregated rec_ids and resolution metadata.
"""
from __future__ import annotations

import json
import struct
import sys
from collections import defaultdict, deque
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from rom_config import ROM_PATH  # noqa: E402
MAP_TEXT_INDEX = ROOT / "tools/moneo/map_text_index.json"
MAPSEC_AREAS_JSON = ROOT / "tools/moneo/mapsec_areas.json"
AREAS_JSON = ROOT / "app/src/main/assets/moneo/areas.json"
OUT = ROOT / "tools/moneo/map_area_index.json"
# Slim (bank:mapId -> area_id) runtime table read by MapAreaLookup.
APP_MAP_TO_AREA = ROOT / "app/src/main/assets/moneo/map_to_area.json"

GBA_BASE = 0x08000000
MAX_WARP_HOPS = 6  # interior building chains (e.g. Trainer Tower) need 4-5 hops


def u32(rom: bytes, off: int) -> int:
    return struct.unpack_from("<I", rom, off)[0]


def build_maps_lookup(rom: bytes, map_text_index: list[dict]) -> dict[tuple[int, int], dict]:
    """Return (group, mapNum) -> map_entry with parsed warps."""
    lookup: dict[tuple[int, int], dict] = {}
    rom_len = len(rom)

    for map_meta in map_text_index:
        group = map_meta["group"]
        map_num = map_meta["mapNum"]
        header = map_meta["header"]
        rec_ids = map_meta.get("recIds", [])

        warps = []
        # Parse events block to extract warp destinations
        events_ptr_rom = u32(rom, header + 4)
        if GBA_BASE <= events_ptr_rom < GBA_BASE + rom_len:
            evt = events_ptr_rom - GBA_BASE
            if evt + 20 <= rom_len:
                n_warps = rom[evt + 1]
                warps_ptr_rom = u32(rom, evt + 8)
                if GBA_BASE <= warps_ptr_rom < GBA_BASE + rom_len:
                    warbase = warps_ptr_rom - GBA_BASE
                    for i in range(min(n_warps, 64)):  # safety cap
                        off = warbase + i * 8
                        if off + 8 > rom_len:
                            break
                        # Warp struct: s16 x, s16 y, u8 elevation, u8 destWarp, u8 destMapNum, u8 destMapGroup
                        dest_map_num = rom[off + 6]
                        dest_map_group = rom[off + 7]
                        warps.append({
                            "destGroup": dest_map_group,
                            "destMapNum": dest_map_num,
                        })

        lookup[(group, map_num)] = {
            "group": group,
            "mapNum": map_num,
            "mapsec": map_meta["mapsec"],
            "header": header,
            "recIds": rec_ids,
            "warps": warps,
        }

    return lookup


def resolve_area_for_map(
    start_group: int,
    start_map_num: int,
    maps_lookup: dict[tuple[int, int], dict],
    mapsec_to_area: dict[str, int],
    area_ordinal: dict[int, int],
) -> tuple[int | None, str]:
    """Resolve area_id and path for a map via BFS over warps (max 2 hops).

    Returns (area_id, path) where path is 'mapsec_direct', 'warp_hop1',
    'warp_hop2', or 'unresolved'.
    """
    # Check direct mapsec
    start_entry = maps_lookup.get((start_group, start_map_num))
    if not start_entry:
        return None, "unresolved"

    mapsec_str = f"0x{start_entry['mapsec']:02X}"
    if mapsec_str in mapsec_to_area:
        area_id = mapsec_to_area[mapsec_str]
        return area_id, "mapsec_direct"

    # BFS over warps
    queue = deque()
    visited: set[tuple[int, int]] = set()
    # We already know the start map has no direct area, so begin at its
    # warp destinations with depth=1
    for warp in start_entry["warps"]:
        dg, dm = warp["destGroup"], warp["destMapNum"]
        # Korean ROM warp dest_g/dest_m use pokefirered's canonical group
        # indexing, not gMapGroups indexing. The Korean fan-build dropped
        # pokefirered's first 2 groups (Link, Dungeons), so subtract 2 to
        # align with our walker. Try the translated form first; fall back
        # to the raw value if no map matches (some warps may be already
        # in walker indexing for special-case maps).
        for cand_g, cand_m in [(dg - 2, dm), (dg, dm)]:
            if (cand_g, cand_m) in maps_lookup and (cand_g, cand_m) not in visited:
                visited.add((cand_g, cand_m))
                queue.append((cand_g, cand_m, 1))
                break

    candidates: list[tuple[int, int, int]] = []  # (area_id, ordinal, depth)

    while queue:
        g, m, depth = queue.popleft()
        if depth > MAX_WARP_HOPS:
            continue
        entry = maps_lookup.get((g, m))
        if not entry:
            continue

        ms_str = f"0x{entry['mapsec']:02X}"
        aid = mapsec_to_area.get(ms_str)
        if aid is not None:
            ordinal = area_ordinal.get(aid, 999999)
            candidates.append((aid, ordinal, depth))
            # Continue exploring so we can find potentially lower-ordinal areas
            # via other branches even if this map has an area.

        # Expand warps (apply same pokefirered-group-offset translation)
        if depth < MAX_WARP_HOPS:
            for warp in entry["warps"]:
                dg2, dm2 = warp["destGroup"], warp["destMapNum"]
                for cand_g, cand_m in [(dg2 - 2, dm2), (dg2, dm2)]:
                    if (cand_g, cand_m) in maps_lookup and (cand_g, cand_m) not in visited:
                        visited.add((cand_g, cand_m))
                        queue.append((cand_g, cand_m, depth + 1))
                        break

    if not candidates:
        return None, "unresolved"

    # Lowest ordinal first
    candidates.sort(key=lambda x: (x[1], x[2]))
    best_aid = candidates[0][0]
    best_depth = candidates[0][2]
    path = "warp_hop1" if best_depth == 1 else "warp_hop2"
    return best_aid, path


def main() -> int:
    print("Loading input files...")
    rom = ROM_PATH.read_bytes()

    map_text_index_data = json.loads(MAP_TEXT_INDEX.read_text())
    map_entries: list[dict] = map_text_index_data["maps"]

    mapsec_areas_raw = json.loads(MAPSEC_AREAS_JSON.read_text())["mapsecs"]
    # Keys are strings like "0x01", values are area_id (int)
    mapsec_to_area: dict[str, str] = {k: v["area_id"] for k, v in mapsec_areas_raw.items() if v.get("area_id")}

    areas_list = json.loads(AREAS_JSON.read_text())["areas"]
    area_ordinal: dict[int, int] = {a["id"]: a["ordinal"] for a in areas_list}

    # Build enriched map lookup
    maps_lookup = build_maps_lookup(rom, map_entries)
    print(f"Processed {len(maps_lookup)} maps from index.")

    # Resolve each map
    per_map = []
    area_recids: dict[int, set[int]] = defaultdict(set)
    stats = {
        "maps_total": len(map_entries),
        "maps_with_mapsec_area": 0,
        "maps_resolved_via_warp": 0,
        "maps_unresolved": 0,
    }

    unresolved_list = []

    for entry in map_entries:
        g, m = entry["group"], entry["mapNum"]
        area_id, path = resolve_area_for_map(
            g, m, maps_lookup, mapsec_to_area, area_ordinal
        )
        rec_count = len(entry.get("recIds", []))

        per_map.append({
            "group": g,
            "mapNum": m,
            "mapsec": entry["mapsec"],
            "resolved_area_id": area_id,
            "resolution_path": path,
            "recIds_count": rec_count,
        })

        if path == "mapsec_direct":
            stats["maps_with_mapsec_area"] += 1
        elif path.startswith("warp"):
            stats["maps_resolved_via_warp"] += 1
        else:
            stats["maps_unresolved"] += 1
            unresolved_list.append({
                "group": g,
                "mapNum": m,
                "mapsec_hex": f"0x{entry['mapsec']:02X}",
                "recIds_count": rec_count,
            })

        if area_id is not None:
            area_recids[area_id].update(entry.get("recIds", []))

    # Build resolved_areas output
    resolved_areas_out = {}
    for area_id, rec_set in area_recids.items():
        ordinal = area_ordinal.get(area_id, -1)
        resolved_areas_out[str(area_id)] = {
            "ordinal": ordinal,
            "recIds": sorted(rec_set),
        }

    output = {
        "version": 1,
        "rom": ROM_PATH.name,
        "stats": stats,
        "resolved_areas": resolved_areas_out,
        "unresolved_maps": unresolved_list,
        "per_map": per_map,
    }

    OUT.write_text(json.dumps(output, ensure_ascii=False, indent=1) + "\n")

    slim = {
        "maps": {f"{m['group']}:{m['mapNum']}": m["resolved_area_id"]
                 for m in per_map if m["resolved_area_id"]},
        "rom": ROM_PATH.name,
        "version": 1,
    }
    APP_MAP_TO_AREA.write_text(json.dumps(slim, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    print(f"\nWrote {OUT}")

    # Summary
    print(f"\nResolution summary:")
    print(f"  Total maps: {stats['maps_total']}")
    print(f"  Direct mapsec -> area: {stats['maps_with_mapsec_area']}")
    print(f"  Resolved via warp (1-2 hops): {stats['maps_resolved_via_warp']}")
    print(f"  Unresolved: {stats['maps_unresolved']}")

    # Top 10 areas by recIds count
    top_areas = sorted(area_recids.items(), key=lambda kv: len(kv[1]), reverse=True)[:10]
    print("\nTop 10 areas by recIds count:")
    for area_id, rec_set in top_areas:
        ordinal = area_ordinal.get(area_id, -1)
        print(f"  area {area_id} (ordinal {ordinal}): {len(rec_set)} recIds")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
