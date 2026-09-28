#!/usr/bin/env python3
"""Pick an authentic, cleanly decoded game line as the ROM example for each
mined / TOPIK card whose current ROM example is a placeholder.

Before ko_text.py existed, ROM examples were built from text garbled by the
fixed-width decoder: single glued tokens ("상대를쪽") with a "(ROM example,
recN)" placeholder gloss, or kana decoded as hangul ("꽝꽤몬겊") glossed
"(TOPIK example from ROM)". This script only *selects* replacement lines;
translations are added separately (translations file, see apply step).

Selection, per card:
  * candidate = one sentence (split on . ! ? …) from a clean dialog record
    whose mecab lemmas include the card's lemma
  * no {PLAYER}/{VAR}-style placeholders or particle notation like 은(는)
  * 6..45 characters; prefer records reached from the card's first area,
    then shorter, then lower record id (stable)

Output: tools/moneo/rom_example_candidates.json
    [{"vocabId", "lemma", "recId", "korean", "surface", "area"}]

Usage (needs the mecab venv):
    .venv-moneo/bin/python tools/moneo/select_rom_examples.py
"""
from __future__ import annotations

import json
import re
import sys
from collections import defaultdict
from pathlib import Path

THIS_DIR = Path(__file__).resolve().parent
ROOT = THIS_DIR.parents[1]
sys.path.insert(0, str(THIS_DIR))
from mine_vocab import mecab_lemmatize  # noqa: E402

ASSETS = ROOT / "app/src/main/assets/moneo"
CORPUS = ASSETS / "corpus.ko.json"
MAP_AREA_INDEX = THIS_DIR / "map_area_index.json"
OUT = THIS_DIR / "rom_example_candidates.json"
DECKS = [("seed-vocab-ko-mined.json", "sentences-ko-mined.json"),
         ("seed-vocab-ko-topik.json", "sentences-ko-topik.json")]

PLACEHOLDER_GLOSS = re.compile(r"\(.*\bexample\b.*\)", re.I)
SENTENCE_END = re.compile(r"(?<=[.!?…])\s+")
BAD = re.compile(r"[{}()\[\]<>]|[A-Za-z]")
HANGUL = re.compile("[가-힣]")


def sentences_of(text: str) -> list[str]:
    flat = re.sub(r"\s*\n\s*", " ", text).strip()
    return [s.strip() for s in SENTENCE_END.split(flat) if s.strip()]


def main() -> int:
    recs = json.loads(CORPUS.read_text())["records"]
    rec_area: dict[int, set[str]] = defaultdict(set)
    for area, v in json.loads(MAP_AREA_INDEX.read_text())["resolved_areas"].items():
        for rid in v["recIds"]:
            rec_area[rid].add(area)

    # lemma -> [(sentence, surface, rec_id)]
    by_lemma: dict[str, list[tuple[str, str, int]]] = defaultdict(list)
    for r in recs:
        if r.get("source") or r.get("unknown") or "·" in r["text"]:
            continue  # name tables / undecodable
        for s in sentences_of(r["text"]):
            if not 6 <= len(s) <= 45 or BAD.search(s) or len(HANGUL.findall(s)) < 4:
                continue
            for lemma, _pos, surface in mecab_lemmatize(s):
                by_lemma[lemma].append((s, surface, r["id"]))

    out = []
    missing = []
    for vocab_file, sent_file in DECKS:
        vocab = {f"{d['sourceTag']}:{e['korean']}": e
                 for d in [json.loads((ASSETS / vocab_file).read_text())] for e in d["entries"]}
        for s in json.loads((ASSETS / sent_file).read_text())["entries"]:
            if not (s.get("source") or "").startswith("rom-rec") or not PLACEHOLDER_GLOSS.fullmatch(s["gloss"]):
                continue
            v = vocab.get(s["vocabId"])
            if v is None:
                continue
            lemma = v["korean"]
            cands = by_lemma.get(lemma, [])
            if not cands:
                missing.append(lemma)
                continue
            first = v.get("firstAreaEncountered")
            best = min(set(cands), key=lambda c: (first not in rec_area[c[2]], len(c[0]), c[2]))
            area = first if first in rec_area[best[2]] else next(iter(sorted(rec_area[best[2]])), None)
            out.append({"vocabId": s["vocabId"], "lemma": lemma, "recId": best[2],
                        "korean": best[0], "surface": best[1], "area": area})

    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n")
    print(f"{len(out)} candidates, {len(missing)} cards with no clean line: {missing[:20]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
