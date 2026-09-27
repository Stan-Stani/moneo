#!/usr/bin/env python3
"""Build app/src/main/assets/moneo/boundary_tiles.json.

Per source map, list every tile coordinate where stepping in a given direction
would cross into a *different* Moneo Area, plus the destination area_id. The
runtime hard-area-gate uses this to pre-emptively mask the player's d-pad input
at the boundary tile so they never physically cross into a not-yet-mature area.

Two boundary kinds:
  1. "edge"  — connection-edge tiles. The player presses (up/down/left/right)
               while standing on this tile and the press would scroll them onto
               a connected map that lives in a different area.
  2. "warp"  — warp_event tiles. The player steps *onto* the warp tile, which
               teleports them into a different map (in a different area).

Numbering scheme: pokefirered's canonical (bank, mapId), i.e. the index into
`data/maps/map_groups.json` group_order and the position within that group.
Both the 2024 Korean patch and LeafGreen US Rev 1 keep this order (43 groups,
425 maps), and it is what `LeafGreenRam.read` reports from
SaveBlock1.location. `map_to_area.json` uses the same keys.

World geometry source: pokefirered's `data/maps/<MapName>/map.json` and
`data/layouts/layouts.json`.

Output schema (deterministic, sorted keys):

    {
      "version": 1,
      "rom": "leafgreen-kr-2024",
      "stats": {...},
      "boundaries": {
        "<bank>:<mapId>": [
          {"x":5,"y":0,"dir":"up","destBank":1,"destMapId":18,
           "destArea":"route_1","kind":"edge"},
          {"x":12,"y":8,"dir":null,"destBank":2,"destMapId":0,
           "destArea":"viridian_forest","kind":"warp"}
        ]
      }
    }

`dir` is the press-direction the player would input; warps have dir=null
because they trigger on stepping onto the tile from any direction. dest
bank/mapId use the same numbering as the source side.

Usage:
    python3 tools/moneo/build_boundary_tiles.py            # write json
    python3 tools/moneo/build_boundary_tiles.py --dry-run  # print stats only
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[2]
POKEFIRERED = Path.home() / ".cache" / "pokefirered"
MAPS_DIR = POKEFIRERED / "data" / "maps"
LAYOUTS_FILE = POKEFIRERED / "data" / "layouts" / "layouts.json"
MAP_GROUPS = MAPS_DIR / "map_groups.json"
AREA_INDEX = ROOT / "tools" / "moneo" / "map_area_index.json"
OUT = ROOT / "app" / "src" / "main" / "assets" / "moneo" / "boundary_tiles.json"

# Kept as a named constant so a future build with a genuinely reordered
# gMapGroups has one place to change. Both supported ROMs use 0.
KOREAN_TO_ENGLISH_GROUP_OFFSET = 0


def load_json(path: Path):
    with path.open("rb") as f:
        return json.loads(f.read().decode("utf-8"))


def build_name_to_korean_idx(map_groups: dict) -> dict[str, tuple[int, int]]:
    """pokefirered symbolic map name ('PalletTown') -> (bank, mapId)."""
    order: list[str] = map_groups["group_order"]
    out: dict[str, tuple[int, int]] = {}
    for english_bank, group_name in enumerate(order):
        korean_bank = english_bank - KOREAN_TO_ENGLISH_GROUP_OFFSET
        if korean_bank < 0:
            continue
        for map_id, name in enumerate(map_groups[group_name]):
            out[name] = (korean_bank, map_id)
    return out


def build_korean_idx_to_name(map_groups: dict) -> dict[tuple[int, int], str]:
    """Inverse of build_name_to_korean_idx — (koreanBank, koreanMapId) -> name."""
    return {idx: name for name, idx in build_name_to_korean_idx(map_groups).items()}


def build_name_to_area(
    area_index: dict, korean_idx_to_name: dict[tuple[int, int], str],
) -> dict[str, str]:
    """Symbolic name -> area_id, via map_area_index.json (Korean-numbered)."""
    out: dict[str, str] = {}
    for entry in area_index["per_map"]:
        area = entry.get("resolved_area_id")
        if not area:
            continue
        kkey = (entry["group"], entry["mapNum"])
        name = korean_idx_to_name.get(kkey)
        if name is None:
            continue
        out[name] = area
    return out


def load_layout_dims(layouts: list[dict]) -> dict[str, tuple[int, int]]:
    """LAYOUT_FOO -> (width, height)."""
    out: dict[str, tuple[int, int]] = {}
    for l in layouts:
        if not l:
            continue
        lid = l.get("id")
        if lid:
            out[lid] = (int(l["width"]), int(l["height"]))
    return out


def map_id_to_name(symbolic_id: str) -> str:
    """Convert MAP_PALLET_TOWN symbolic id -> 'PalletTown' map dir name.

    pokefirered preprocs MAP_FOO_BAR <-> FooBar via uppercase/CamelCase.
    Easier to read it from each map.json's `name` field directly.
    """
    raise NotImplementedError("use name_by_map_symbol instead")


def edge_tiles(
    direction: str, src_w: int, src_h: int, dst_w: int, dst_h: int, offset: int,
) -> list[tuple[int, int]]:
    """Return the (x, y) tiles on the source map's edge from which a press in
    `direction` would cross onto the destination map.

    pokefirered connection geometry:
      - direction "up":    source's top edge (y=0). Source tiles at x in
                           [max(0, -offset), min(src_w, dst_w - offset)) cross
                           into the destination's bottom edge.
      - direction "down":  source's bottom edge (y=src_h-1). Same x range.
      - direction "left":  source's left edge (x=0). Source tiles at y in
                           [max(0, -offset), min(src_h, dst_h - offset)).
      - direction "right": source's right edge (x=src_w-1). Same y range.

    `offset` is signed: it shifts the destination map along the perpendicular
    axis. A positive offset on an "up" connection means dst is shifted right
    relative to source (so source tile at source-x corresponds to dst-x =
    source-x + offset, but we typically want the inverse: which source-x
    overlaps the destination at all → source-x s.t. 0 <= source-x + offset
    < dst_w, i.e. source-x in [-offset, dst_w - offset)).
    """
    if direction == "up":
        x_lo = max(0, -offset)
        x_hi = min(src_w, dst_w - offset)
        return [(x, 0) for x in range(x_lo, x_hi)]
    if direction == "down":
        x_lo = max(0, -offset)
        x_hi = min(src_w, dst_w - offset)
        return [(x, src_h - 1) for x in range(x_lo, x_hi)]
    if direction == "left":
        y_lo = max(0, -offset)
        y_hi = min(src_h, dst_h - offset)
        return [(0, y) for y in range(y_lo, y_hi)]
    if direction == "right":
        y_lo = max(0, -offset)
        y_hi = min(src_h, dst_h - offset)
        return [(src_w - 1, y) for y in range(y_lo, y_hi)]
    # "dive"/"emerge" — vertical map transitions for diving (Hoenn). FRLG has
    # only horizontal connections in practice, but skip silently.
    return []


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dry-run", action="store_true",
                    help="print stats but don't write the JSON")
    args = ap.parse_args()

    if not MAPS_DIR.exists():
        print(f"ERROR: pokefirered checkout missing at {MAPS_DIR}")
        return 1

    map_groups = load_json(MAP_GROUPS)
    area_index = load_json(AREA_INDEX)
    layouts_doc = load_json(LAYOUTS_FILE)
    layouts = layouts_doc["layouts"]

    name_to_korean_idx = build_name_to_korean_idx(map_groups)
    korean_idx_to_name = build_korean_idx_to_name(map_groups)
    name_to_area = build_name_to_area(area_index, korean_idx_to_name)
    layout_dims = load_layout_dims(layouts)

    # Collect per-map data: name -> (map.json content, layout dims). Skip any
    # map whose pokefirered group is 0 or 1 (Link / Dungeons — no Korean
    # numbering equivalent).
    per_map: dict[str, dict] = {}
    missing_layout = 0
    for map_dir in sorted(MAPS_DIR.iterdir()):
        mj = map_dir / "map.json"
        if not mj.is_file():
            continue
        data = load_json(mj)
        name = data.get("name")
        if not name or name not in name_to_korean_idx:
            continue
        layout = data.get("layout")
        if not layout or layout not in layout_dims:
            missing_layout += 1
            continue
        per_map[name] = {
            "data": data,
            "dims": layout_dims[layout],
        }

    # symbolic dest_map id like "MAP_PALLET_TOWN" -> "PalletTown" reverse via
    # iterating per_map data's `id` field.
    id_to_name: dict[str, str] = {
        m["data"]["id"]: name for name, m in per_map.items()
    }

    # Build boundaries.
    boundaries: dict[str, list[dict]] = {}
    edge_count = 0
    edge_skipped_unknown_area = 0
    warp_count = 0
    warp_skipped_unknown_area = 0
    same_area_edges = 0
    same_area_warps = 0

    for src_name in sorted(per_map.keys()):
        src = per_map[src_name]
        src_bank, src_mapid = name_to_korean_idx[src_name]
        src_w, src_h = src["dims"]
        src_area = name_to_area.get(src_name)

        entries: list[dict] = []

        # 1. Connection edges
        for conn in src["data"].get("connections", []) or []:
            direction = conn.get("direction")
            dest_id = conn.get("map")
            offset = int(conn.get("offset", 0))
            if direction in ("dive", "emerge"):
                continue
            dest_name = id_to_name.get(dest_id)
            if not dest_name or dest_name not in per_map:
                continue
            dest_area = name_to_area.get(dest_name)
            if dest_area is None:
                edge_skipped_unknown_area += 1
                continue
            if src_area == dest_area:
                same_area_edges += 1
                continue
            dst_w, dst_h = per_map[dest_name]["dims"]
            dest_bank, dest_mapid = name_to_korean_idx[dest_name]
            for (x, y) in edge_tiles(direction, src_w, src_h, dst_w, dst_h, offset):
                entries.append({
                    "x": x, "y": y, "dir": direction,
                    "destBank": dest_bank, "destMapId": dest_mapid,
                    "destArea": dest_area, "kind": "edge",
                })
                edge_count += 1

        # 2. Warps
        for warp in src["data"].get("warp_events", []) or []:
            try:
                wx = int(warp["x"]); wy = int(warp["y"])
            except (KeyError, ValueError, TypeError):
                continue
            dest_id = warp.get("dest_map")
            dest_name = id_to_name.get(dest_id) if dest_id else None
            # Some warps target MAP_DYNAMIC / MAP_UNDEFINED — skip those
            # (they're set by script at runtime, can't be statically resolved).
            if not dest_name or dest_name not in per_map:
                continue
            dest_area = name_to_area.get(dest_name)
            if dest_area is None:
                warp_skipped_unknown_area += 1
                continue
            if src_area == dest_area:
                same_area_warps += 1
                continue
            dest_bank, dest_mapid = name_to_korean_idx[dest_name]
            entries.append({
                "x": wx, "y": wy, "dir": None,
                "destBank": dest_bank, "destMapId": dest_mapid,
                "destArea": dest_area, "kind": "warp",
            })
            warp_count += 1

        if entries:
            # Deterministic ordering — sort by (kind, y, x, dir)
            entries.sort(key=lambda e: (
                e["kind"], e["y"], e["x"], e["dir"] or "",
            ))
            boundaries[f"{src_bank}:{src_mapid}"] = entries

    # Sort top-level by numeric (bank, mapId)
    sorted_boundaries = dict(
        sorted(boundaries.items(),
               key=lambda kv: tuple(int(p) for p in kv[0].split(":")))
    )

    stats = {
        "src_maps_with_boundaries": len(sorted_boundaries),
        "edges_emitted": edge_count,
        "edges_skipped_unknown_area": edge_skipped_unknown_area,
        "edges_same_area": same_area_edges,
        "warps_emitted": warp_count,
        "warps_skipped_unknown_area": warp_skipped_unknown_area,
        "warps_same_area": same_area_warps,
        "maps_missing_layout": missing_layout,
        "total_maps_with_known_area": sum(
            1 for n in per_map if name_to_area.get(n)
        ),
    }
    print("== build_boundary_tiles.py ==")
    for k, v in stats.items():
        print(f"  {k}: {v}")

    if args.dry_run:
        return 0

    out = {
        "version": 1,
        "rom": "leafgreen-us-rev1",
        "stats": stats,
        "boundaries": sorted_boundaries,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    # Pretty enough to diff cleanly; sort_keys=True for deterministic output.
    with OUT.open("w", encoding="utf-8") as f:
        json.dump(out, f, indent=2, sort_keys=True, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
