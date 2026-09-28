#!/usr/bin/env python3
"""Merge reviewed new_vocab_candidates.json into the shipped mined deck.

Appends each candidate to seed-vocab-ko-mined.json (sourceTag rom-mine-v3)
and its translated game line to sentences-ko-mined.json (glossGenerator
marks the machine translation). targetForm is the surface form mecab finds
for the lemma in the example. Idempotent: vocab already in the deck is
skipped.

Usage:
    .venv-moneo/bin/python tools/moneo/merge_new_vocab.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from mine_vocab import mecab_lemmatize  # noqa: E402

ASSETS = HERE.parents[1] / "app/src/main/assets/moneo"
VOCAB = ASSETS / "seed-vocab-ko-mined.json"
SENTENCES = ASSETS / "sentences-ko-mined.json"
GLOSS_GENERATOR = "llm-claude-opus-5-5"


def split_senses(gloss: str) -> tuple[str, list[str]]:
    """Top-level ';' separates senses (SeedVocabSenseStructureTest): the first
    becomes `gloss`, the rest `senses`."""
    parts, depth, cur = [], 0, ""
    for ch in gloss:
        depth += ch in "([" and 1 or (ch in ")]" and -1 or 0)
        if ch == ";" and depth == 0:
            parts.append(cur.strip())
            cur = ""
        else:
            cur += ch
    parts.append(cur.strip())
    return parts[0], [p for p in parts[1:] if p]


def load(path: Path) -> tuple[dict, str]:
    text = path.read_text()
    return json.loads(text), text


def save(path: Path, data: dict, original: str) -> None:
    second = original.split("\n", 2)[1]
    indent = len(second) - len(second.lstrip(" "))
    path.write_text(json.dumps(data, ensure_ascii=False, indent=indent)
                    + ("\n" if original.endswith("\n") else ""))


def main() -> int:
    cands = json.loads((HERE / "new_vocab_candidates.json").read_text())
    vocab, vtext = load(VOCAB)
    sents, stext = load(SENTENCES)
    tag = vocab["sourceTag"]
    have = {e["korean"] for e in vocab["entries"]}
    added_v = added_s = 0
    for c in cands:
        if c["korean"] in have:
            continue
        areas = c["areasReferenced"]
        gloss, senses = split_senses(c["gloss"])
        entry = {
            "korean": c["korean"],
            "gloss": gloss,
            "partOfSpeech": c["partOfSpeech"],
            "areaId": "rom_mined",
            "frequency": c["frequency"],
            "firstAreaEncountered": c["firstAreaEncountered"],
            "areasReferenced": areas,
            "provenance": "clean-corpus-2026-09 (new_vocab_candidates.json)",
        }
        if senses:
            entry["senses"] = senses
        vocab["entries"].append(entry)
        added_v += 1
        if c["example"] and c["exampleGloss"]:
            surface = next((s for lemma, _pos, s in mecab_lemmatize(c["example"])
                            if lemma == c["korean"]), c["korean"])
            sents["entries"].append({
                "vocabId": f"{tag}:{c['korean']}",
                "korean": c["example"],
                "gloss": c["exampleGloss"],
                "targetForm": surface,
                "areaId": "rom_mined",
                "source": c["exampleSource"],
                "glossGenerator": GLOSS_GENERATOR,
                "firstAreaEncountered": c["firstAreaEncountered"],
                "areasReferenced": areas,
            })
            added_s += 1
    save(VOCAB, vocab, vtext)
    save(SENTENCES, sents, stext)
    print(f"added {added_v} vocab, {added_s} sentences")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
