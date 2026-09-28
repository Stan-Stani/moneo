"""Build app/src/main/assets/moneo/area_lemma_counts.json for the area gate.

The gate opens an area once the player knows enough of the words its text
actually uses, weighted by how often each word occurs (see
gate_coverage_sim.py for the thresholds this was tuned with). This writes,
per area, the token count of every content-word lemma (mecab nouns, verbs,
adjectives, adverbs) in the area's reachable dialog, restricted to lemmas that
exist as a card in some seed deck -- other lemmas (mostly names) can't be
learned in the app, so they don't count toward coverage.

`storyOrder` is the order a first playthrough enters areas; the app ramps the
gate threshold along it. areas.json ordinals are not play order.

Run with .venv-moneo/bin/python; re-running with no input changes is a no-op.
"""
import collections
import glob
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/moneo"))
from mine_vocab import is_hangul, mecab_lemmatize  # noqa: E402

ASSETS = ROOT / "app/src/main/assets/moneo"
OUT = ASSETS / "area_lemma_counts.json"

STORY_ORDER = """
pallet_town route_1 viridian_city route_2 viridian_forest pewter_city route_3
mt_moon route_4 cerulean_city route_24 route_25 route_5 route_6 vermilion_city
ss_anne route_11 digletts_cave route_9 route_10 rock_tunnel lavender_town
route_8 route_7 celadon_city rocket_hideout pokemon_tower route_12 route_13
route_14 route_15 fuchsia_city safari_zone route_16 route_17 route_18
saffron_city silph_co route_19 route_20 seafoam_islands cinnabar_island
pokemon_mansion route_21 route_22 route_23 indigo_plateau kanto_pokemon_league
power_plant cerulean_cave
""".split()


def main():
    recs = {}
    for p in [ROOT / "tools/moneo/corpus.ko.live.json", ASSETS / "corpus.ko.json"]:
        for r in json.load(open(p, encoding="utf-8"))["records"]:
            recs.setdefault(r["id"], r)
    areas = json.load(open(ROOT / "tools/moneo/map_area_index.json", encoding="utf-8"))["resolved_areas"]

    deck = set()
    for f in sorted(glob.glob(str(ASSETS / "seed-vocab-ko-*.json"))):
        for e in json.load(open(f, encoding="utf-8"))["entries"]:
            deck.add(e["korean"])

    tokens_by_rec = {}
    out_areas = {}
    for area in STORY_ORDER:
        c = collections.Counter()
        for rid in areas.get(area, {}).get("recIds", []):
            r = recs.get(rid)
            if not r or not r.get("text"):
                continue
            if rid not in tokens_by_rec:
                try:
                    toks = mecab_lemmatize(r["text"])
                except Exception:
                    toks = []
                tokens_by_rec[rid] = [l for l, _, _ in toks if all(is_hangul(ch) for ch in l)]
            c.update(l for l in tokens_by_rec[rid] if l in deck)
        out_areas[area] = dict(sorted(c.items(), key=lambda kv: (-kv[1], kv[0])))

    doc = {
        "version": 1,
        "notes": "Per-area token counts of deck lemmas in reachable dialog. "
                 "Built by tools/moneo/build_area_lemma_counts.py.",
        "storyOrder": STORY_ORDER,
        "areas": out_areas,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
        f.write("\n")
    n = sum(len(v) for v in out_areas.values())
    print(f"wrote {OUT.relative_to(ROOT)}: {len(out_areas)} areas, {n} (area, lemma) counts")


if __name__ == "__main__":
    main()
