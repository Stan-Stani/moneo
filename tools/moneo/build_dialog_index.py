"""Build the assets the in-game reading helper uses on the 2024 KR ROM.

dialog_index.json -- one entry per distinct clean dialog line in
corpus.ko.json: the hangul segments the app matches the live message box
against, and the deck words (VocabEntry.korean) the line uses, in order.
Segments are the line's hangul with everything else dropped, split wherever
the line has a name placeholder ({PLAYER}, {RIVAL}, ...) or runtime particle
notation (은(는)), since those render differently per save.

ko2024_codepoints.json -- the 2024 patch's 2-byte hangul codepoint map
(rom_swap/codepoint_map.json + ko_text.PARTICLES), so the app can decode
text straight from RAM.

Run with .venv-moneo/bin/python (needs mecab). Re-running is a no-op.
"""
import glob
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/moneo"))
from ko_text import PARTICLES, load_codepoint_map  # noqa: E402
from mine_vocab import mecab_lemmatize  # noqa: E402

ASSETS = ROOT / "app/src/main/assets/moneo"
PLACEHOLDER = re.compile(r"\{[A-Z_0-9]+\}|[가-힣]?\([가-힣]\)")
NON_HANGUL = re.compile(r"[^가-힣]")


def segments(text):
    segs = []
    for part in PLACEHOLDER.split(text):
        h = NON_HANGUL.sub("", part)
        if h:
            segs.append(h)
    return segs


def main():
    deck = set()
    for f in glob.glob(str(ASSETS / "seed-vocab-ko-*.json")):
        deck.update(e["korean"] for e in json.load(open(f, encoding="utf-8"))["entries"])
    nouns = sorted((w for w in deck if len(w) >= 3 and not w.endswith("다")), key=len, reverse=True)
    recs = json.load(open(ASSETS / "corpus.ko.json", encoding="utf-8"))["records"]
    seen = {}
    for r in recs:
        t = r["text"]
        if r.get("source") or r.get("unknown") or "·" in t or t in seen:
            continue
        segs = segments(t)
        if sum(map(len, segs)) < 2:
            continue
        flat = PLACEHOLDER.sub(" ", t).replace("\n", " ")
        # mecab splits compounds the deck teaches whole (몬스터볼 -> 몬스터 +
        # 보다), so noun compounds found verbatim win and swallow the pieces.
        compounds = [w for w in nouns if w in flat]
        words = []
        try:
            for lemma, _pos, surface in mecab_lemmatize(flat):
                for c in compounds:
                    if surface in c and c not in words:
                        words.append(c)
                if any(surface in c for c in compounds):
                    continue
                if lemma in deck and lemma not in words:
                    words.append(lemma)
        except Exception:
            continue
        if words:
            seen[t] = {"id": r["id"], "segs": segs, "words": words}
            if PLACEHOLDER.search(t):
                seen[t]["tmpl"] = 1  # names/items filled in at runtime
    lines = sorted(seen.values(), key=lambda x: x["id"])
    with open(ASSETS / "dialog_index.json", "w", encoding="utf-8") as f:
        json.dump({"version": 1, "notes": "Built by tools/moneo/build_dialog_index.py.",
                   "lines": lines}, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")
    cmap = {f"{k:04X}": v for k, v in sorted(load_codepoint_map().items())}
    with open(ASSETS / "ko2024_codepoints.json", "w", encoding="utf-8") as f:
        json.dump({"version": 1, "notes": "2024 KR patch 2-byte hangul codepoints (lead 0x37-0x41). "
                   "Built by tools/moneo/build_dialog_index.py.", "codepoints": cmap},
                  f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"dialog_index: {len(lines)} lines; codepoints: {len(cmap)}")


if __name__ == "__main__":
    main()
