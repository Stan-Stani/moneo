#!/usr/bin/env python3
"""Replace placeholder ROM examples with the authentic lines chosen by
select_rom_examples.py and their English translations.

Inputs:
  tools/moneo/rom_example_candidates.json  (select_rom_examples.py)
  tools/moneo/rom_example_glosses.json     {korean line: English}, machine
                                           translated; lines judged unusable
                                           (starts mid-word, residual
                                           mislabeled glyphs) are absent

Each updated sentence keeps its vocabId, gets the new korean / gloss /
targetForm / source ("rom-rec<id>"), drops the stale romanization, and is
tagged glossGenerator="llm-claude-opus-5-5" so the review UI can label the
English as a machine translation of a real game line. Placeholder entries
without a usable line are left as-is; MoneoRepository.sentenceFor() already
skips them in favour of the study sentence.

Usage:
    python3 tools/moneo/apply_rom_examples.py
"""
from __future__ import annotations

import json
from collections import Counter
from pathlib import Path

THIS_DIR = Path(__file__).resolve().parent
ASSETS = THIS_DIR.parents[1] / "app/src/main/assets/moneo"
FILES = ["sentences-ko-mined.json", "sentences-ko-topik.json"]
GLOSS_GENERATOR = "llm-claude-opus-5-5"


def main() -> int:
    cands = {c["vocabId"]: c for c in json.loads((THIS_DIR / "rom_example_candidates.json").read_text())}
    glosses = json.loads((THIS_DIR / "rom_example_glosses.json").read_text())
    for name in FILES:
        path = ASSETS / name
        text = path.read_text()
        data = json.loads(text)
        stats = Counter()
        for e in data["entries"]:
            c = cands.get(e["vocabId"])
            if c is None or not (e.get("source") or "").startswith("rom-rec"):
                continue
            gloss = glosses.get(c["korean"])
            if gloss is None:
                stats["no_usable_line"] += 1
                continue
            e["korean"] = c["korean"]
            e["gloss"] = gloss
            e["targetForm"] = c["surface"]
            e["source"] = f"rom-rec{c['recId']}"
            e["glossGenerator"] = GLOSS_GENERATOR
            e.pop("romanization", None)
            stats["updated"] += 1
        indent = len(text.split("\n", 2)[1]) - len(text.split("\n", 2)[1].lstrip(" "))
        path.write_text(json.dumps(data, ensure_ascii=False, indent=indent)
                        + ("\n" if text.endswith("\n") else ""))
        print(f"{name:28} {dict(stats)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
