"""Build app/src/main/assets/moneo/loanwords.json from loanword_decisions.json.

null entries are candidates reviewed and rejected (native words that
happen to sound like their gloss). Each entry says which part of a deck word (VocabEntry.korean) is borrowed
and from what: 시티 <- English "city"; hybrids mark only the borrowed part
(불꽃펀치: 펀치 <- "punch"). Candidates come from loanword_candidates.py;
decisions were made by hand/LLM review and carry pickedBy. Re-running is a
no-op.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "tools/moneo/loanword_decisions.json"
OUT = ROOT / "app/src/main/assets/moneo/loanwords.json"


def main():
    words = {k: v for k, v in json.load(open(SRC, encoding="utf-8")).items() if v}  # null = rejected
    for k, v in words.items():
        assert v["part"] in k, k
    doc = {"version": 1,
           "notes": "Loanwords in the deck: borrowed part and source word. Built by "
                    "tools/moneo/build_loanwords.py.",
           "words": words}
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"wrote {OUT.relative_to(ROOT)}: {len(words)} words")


if __name__ == "__main__":
    main()
