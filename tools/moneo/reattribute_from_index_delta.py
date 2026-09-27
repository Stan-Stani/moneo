#!/usr/bin/env python3
"""Apply the difference between two lemma_area_index.json builds to the
shipped decks, without re-running the whole attribution pipeline.

attribute_existing_decks.py only ever *unions* areas into a card, so it can
add newly reachable areas but never retract areas that an older index got
wrong. This script diffs an old index against the current one, per lemma:

  added   = new_areas - old_areas   -> unioned into areasReferenced
  removed = old_areas - new_areas   -> dropped from areasReferenced

firstAreaEncountered is replaced with the new index's first_area only when
it was index-derived (equal to the old index's first_area) or when it names
an area the new index retracted. Attribution from other sources (TM
locations, themed decks, hand curation) is left alone.

Everything else in each entry (glosses, senses, audits, key order) and each
file's indentation is preserved.

Usage:
    git show HEAD:tools/moneo/lemma_area_index.json > /tmp/old_index.json
    python3 tools/moneo/reattribute_from_index_delta.py --old-index /tmp/old_index.json
"""
from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/moneo"
NEW_INDEX = ROOT / "tools/moneo/lemma_area_index.json"

DECKS = [
    "seed-vocab-ko-mined.json",
    "seed-vocab-ko-topik.json",
    "seed-vocab-ko-species.json",
    "seed-vocab-ko-etymology.json",
    "sentences-ko-mined.json",
    "sentences-ko-topik.json",
    "sentences-ko-species.json",
    "sentences-ko-etymology.json",
    "sentences-ko-themed-mined.json",
    "sentences-ko-themed-topik.json",
    "sentences-ko-themed-species.json",
    "sentences-ko-themed.json",
]


def lemma_of(entry: dict) -> str | None:
    vid = entry.get("vocabId")
    if vid:
        return vid.split(":", 1)[1] if ":" in vid else vid
    return entry.get("korean")


def detect_indent(text: str) -> int:
    second = text.split("\n", 2)[1]
    return len(second) - len(second.lstrip(" "))


def species_areas(obtain: dict, shift: int = 0) -> dict[int, tuple[str, list[str]]]:
    """Mirror of build_name_table_decks.species_first_area for wild species,
    keyed by gSpeciesNames index. Starters and GIVEN_OVERRIDES are never
    touched here because their cards don't match either derivation.

    shift=1 reproduces the old builder, which looked species i up at
    pokedex_table index i-1 and so gave every card its predecessor's areas."""
    # Same order as build_name_table_decks.AREA_PRIORITY: the story areas,
    # excluding the trainer_dialog / rom_mined / topik pseudo-areas.
    areas = json.loads((ASSETS / "areas.json").read_text())["areas"]
    AREA_RANK = {a["id"]: a["ordinal"] for a in areas if a["ordinal"] <= 50}

    table = json.loads((ROOT / "tools/moneo/pokedex_table.json").read_text())
    rec_to_species = {e["description_rec_id"]: e["species_index"] for e in table["entries"]}
    by_species: dict[int, set[str]] = {}
    for area, rec_ids in obtain["area_to_pokedex_rec_ids"].items():
        for rid in rec_ids:
            sp = rec_to_species.get(rid)
            if sp is not None:
                by_species.setdefault(sp, set()).add(area)
    out = {}
    for pdx, areas in by_species.items():
        ranked = sorted(areas, key=lambda a: (AREA_RANK.get(a, 999), a))
        first = ranked[0]
        if first not in AREA_RANK:
            out[pdx + shift] = ("rom_mined", [])
        else:
            out[pdx + shift] = (first, ranked)
    return out


def reattribute_species(data: dict, old_obtain: dict, new_obtain: dict,
                        old_shift: int) -> Counter:
    old, new = species_areas(old_obtain, shift=old_shift), species_areas(new_obtain)
    stats = Counter()
    for e in data["entries"]:
        src = e.get("source", "")
        if not src.startswith("gSpeciesNames["):
            continue
        i = int(src[len("gSpeciesNames["):-1])
        o = old.get(i, ("rom_mined", []))
        n = new.get(i, ("rom_mined", []))
        cur_areas = e.get("areasReferenced") or []
        if o == n:
            continue
        if (e.get("firstAreaEncountered"), set(cur_areas)) == (o[0], set(o[1])):
            e["firstAreaEncountered"], e["areasReferenced"] = n[0], n[1]
            stats["species"] += 1
        elif i not in SPECIES_OVERRIDES and n[0] != "rom_mined":
            # A later text-mention pass replaced the (shifted) wild area.
            # Wild encounters are the better first-encounter signal for a
            # species, so they win; the shifted areas are retracted.
            e["firstAreaEncountered"] = n[0]
            kept = [a for a in cur_areas if a not in o[1]]
            e["areasReferenced"] = kept + [a for a in n[1] if a not in kept]
            stats["species_overwritten"] += 1
    return stats


# build_name_table_decks: starter families -> pallet_town, Eevee family and
# GIVEN_OVERRIDES are hand-assigned, not wild-derived.
SPECIES_OVERRIDES = set(range(1, 10)) | {106, 107, 131, 133, 134, 135, 136,
                                         142, 143, 144, 145, 146, 150, 151}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--old-index", required=True, type=Path)
    ap.add_argument("--old-obtain", required=True, type=Path,
                    help="previous pokedex_obtain_index.json, for species cards")
    ap.add_argument("--old-species-shift", type=int, default=0,
                    help="1 when the old decks were built before the species-index "
                         "fix in build_name_table_decks (pdx = i - 1)")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    old = json.loads(args.old_index.read_text())["lemmas"]
    new = json.loads(NEW_INDEX.read_text())["lemmas"]
    old_obtain = json.loads(args.old_obtain.read_text())
    new_obtain = json.loads((ROOT / "tools/moneo/pokedex_obtain_index.json").read_text())

    for name in DECKS:
        path = ASSETS / name
        text = path.read_text()
        data = json.loads(text)
        stats = Counter()
        if name == "sentences-ko-themed-species.json":
            # Themed sentences carry no gSpeciesNames source; mirror the
            # (already rewritten) species deck by vocabId instead.
            deck = json.loads((ASSETS / "seed-vocab-ko-species.json").read_text())
            by_id = {f"{deck['sourceTag']}:{v['korean']}": v for v in deck["entries"]}
            for e in data["entries"]:
                v = by_id.get(e.get("vocabId"))
                if v and (e.get("firstAreaEncountered"), e.get("areasReferenced")) != (
                        v.get("firstAreaEncountered"), v.get("areasReferenced")):
                    e["firstAreaEncountered"] = v.get("firstAreaEncountered")
                    e["areasReferenced"] = v.get("areasReferenced")
                    stats["species_sync"] += 1
        elif "species" in name:
            stats += reattribute_species(data, old_obtain, new_obtain,
                                         args.old_species_shift)
        for e in data["entries"]:
            lemma = lemma_of(e)
            if lemma not in old or lemma not in new:
                continue
            o_areas, n_areas = set(old[lemma]["areas"]), set(new[lemma]["areas"])
            added, removed = n_areas - o_areas, o_areas - n_areas
            if "areasReferenced" in e and (added or removed):
                cur = list(e["areasReferenced"])
                kept = [a for a in cur if a not in removed]
                kept += sorted(a for a in added if a not in kept)
                if kept != cur:
                    e["areasReferenced"] = kept
                    stats["areasReferenced"] += 1
            fa = e.get("firstAreaEncountered")
            new_fa = new[lemma].get("first_area")
            if fa and new_fa and fa != new_fa and (
                fa == old[lemma].get("first_area") or fa in removed
            ):
                e["firstAreaEncountered"] = new_fa
                stats["firstAreaEncountered"] += 1
        print(f"{name:40} {dict(stats)}")
        if not args.dry_run and stats:
            out = json.dumps(data, ensure_ascii=False, indent=detect_indent(text))
            path.write_text(out + ("\n" if text.endswith("\n") else ""))


if __name__ == "__main__":
    main()
