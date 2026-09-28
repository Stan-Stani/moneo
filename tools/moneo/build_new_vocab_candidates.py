#!/usr/bin/env python3
"""Build a review list of new vocab cards mined from the clean dialog corpus.

Inputs:
  new_vocab_keep.tsv        lemma<TAB>partOfSpeech<TAB>gloss, hand-reviewed from
                            clean-corpus lemmas (>= 5 records, not already a
                            card in any seed-vocab-ko-*.json; names, fragments,
                            interjections and menu labels dropped)
  lemma_area_index.json     areas / first area per lemma
  rom_example_glosses.json  {korean line: English}, shared with the ROM
                            example sentences
  rom_example_rejects.txt   lines judged unusable (start mid-word etc.)

Output: new_vocab_candidates.json (+ NEW_VOCAB_REVIEW.md) -- NOT merged
into the shipped decks.
Each entry carries a clean game line containing the lemma; `exampleGloss` is
null for lines that still need translating (--missing prints them).

Usage:
    .venv-moneo/bin/python tools/moneo/build_new_vocab_candidates.py [--missing]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from mine_vocab import mecab_lemmatize  # noqa: E402
from select_rom_examples import BAD, HANGUL, sentences_of  # noqa: E402

ROOT = HERE.parents[1]
CORPUS = ROOT / "app/src/main/assets/moneo/corpus.ko.json"
AREAS = ROOT / "app/src/main/assets/moneo/areas.json"
OUT = HERE / "new_vocab_candidates.json"
REVIEW = HERE / "NEW_VOCAB_REVIEW.md"
DECKS = ROOT / "app/src/main/assets/moneo"


def write_review(out: list[dict], areas: list[dict]) -> None:
    """Human-readable review page: per-area impact + every candidate."""
    current = []
    for deck in sorted(DECKS.glob("seed-vocab-ko-*.json")):
        for e in json.loads(deck.read_text())["entries"]:
            current.append((e.get("firstAreaEncountered") or e.get("areaId"),
                            set(e.get("areasReferenced") or [])))
    new = [(e["firstAreaEncountered"], set(e["areasReferenced"])) for e in out]
    shown = lambda deck, a: sum(1 for first, refs in deck if first == a or a in refs)
    lines = [
        "# New vocab candidates (review before merging)",
        "",
        f"{len(out)} words from the clean dialog corpus that are not cards yet "
        "(>= 5 records; names, fragments, interjections and menu labels removed). "
        "Examples are real game lines; English is machine-translated.",
        "",
        "## Per-area impact",
        "",
        "`deck now` / `+new` count cards the way the app does (first area *or* any "
        "referenced area); `+first seen` counts only words whose first area is this one.",
        "",
        "| Area | deck now | +new | +first seen |",
        "|---|---:|---:|---:|",
    ]
    for a in areas:
        if a["ordinal"] > 50:
            continue
        aid = a["id"]
        lines.append(f"| {a['englishName']} | {shown(current, aid)} | +{shown(new, aid)} | "
                     f"+{sum(1 for f, _ in new if f == aid)} |")
    lines += ["", "## Candidates", "", "| # | Word | POS | Gloss | Freq | First area | Example |",
              "|---:|---|---|---|---:|---|---|"]
    for i, e in enumerate(out, 1):
        ex = f"{e['example']} — {e['exampleGloss']}" if e["example"] else "—"
        lines.append(f"| {i} | {e['korean']} | {e['partOfSpeech']} | {e['gloss']} | "
                     f"{e['frequency']} | {e['firstAreaEncountered'] or '—'} | {ex} |")
    REVIEW.write_text("\n".join(lines) + "\n")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--missing", action="store_true", help="print lines without a translation")
    args = ap.parse_args()

    keep = [line.split("\t") for line in (HERE / "new_vocab_keep.tsv").read_text().splitlines() if line]
    lemmas = json.loads((HERE / "lemma_area_index.json").read_text())["lemmas"]
    glosses = json.loads((HERE / "rom_example_glosses.json").read_text())
    rejects = set((HERE / "rom_example_rejects.txt").read_text().splitlines())
    area_list = json.loads(AREAS.read_text())["areas"]
    ordinal = {a["id"]: a["ordinal"] for a in area_list}

    rec_areas: dict[int, set[str]] = defaultdict(set)
    for area, v in json.loads((HERE / "map_area_index.json").read_text())["resolved_areas"].items():
        for rid in v["recIds"]:
            rec_areas[rid].add(area)

    wanted = {k[0] for k in keep}
    freq: dict[str, set[int]] = defaultdict(set)
    lines: dict[str, list[tuple[str, int]]] = defaultdict(list)
    for r in json.loads(CORPUS.read_text())["records"]:
        if r.get("source") or r.get("unknown") or "·" in r["text"]:
            continue
        for s in sentences_of(r["text"]):
            usable = (6 <= len(s) <= 45 and not BAD.search(s)
                      and len(HANGUL.findall(s)) >= 4 and s not in rejects)
            for lemma, _pos, _surface in mecab_lemmatize(s):
                if lemma in wanted:
                    freq[lemma].add(r["id"])
                    if usable:
                        lines[lemma].append((s, r["id"]))

    out, missing = [], set()
    for lemma, pos, gloss in keep:
        info = lemmas.get(lemma, {})
        # Prefer an already-translated line, then shorter, then stable by record id.
        cands = sorted(set(lines[lemma]), key=lambda c: (c[0] not in glosses, len(c[0]), c[1]))
        example = cands[0] if cands else None
        if example and example[0] not in glosses:
            missing.add(example[0])
        areas = set(info.get("areas", []))
        if not areas:
            # lemma_area_index filters some lemmas out; fall back to the map
            # areas of the records the word occurs in.
            areas = set().union(*(rec_areas[rid] for rid in freq[lemma]))
        areas = sorted(areas, key=lambda a: (ordinal.get(a, 999), a))
        first = info.get("first_area") or next(
            (a for a in areas if a in ordinal and ordinal[a] <= 50), None)
        out.append({
            "korean": lemma,
            "partOfSpeech": pos,
            "gloss": gloss,
            "frequency": len(freq[lemma]),
            "firstAreaEncountered": first,
            "areasReferenced": areas,
            "example": example[0] if example else None,
            "exampleSource": f"rom-rec{example[1]}" if example else None,
            "exampleGloss": glosses.get(example[0]) if example else None,
        })
    out.sort(key=lambda e: -e["frequency"])
    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n")
    write_review(out, area_list)
    print(f"{len(out)} candidates -> {OUT.name}; {len(missing)} example lines need translating; "
          f"{sum(1 for e in out if not e['example'])} without a usable line")
    if args.missing:
        for i, s in enumerate(sorted(missing)):
            print(f"{i}|{s}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
