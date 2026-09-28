"""Build app/src/main/assets/moneo/hanja.json from hanja_decisions.json.

hanja_decisions.json maps each non-species deck word (VocabEntry.korean) to
its hanja spelling, aligned syllable-for-syllable with the Korean (native
syllables stay hangul, e.g. 도망가다 -> 逃亡가다), or null for native words
and loanwords. Candidates came from kengdic (MPL-2.0/LGPL) and libhangul's
hanja.txt (LGPL); every pick was checked against the character's Unihan
Korean reading (allowing initial-sound-law variants, e.g. 력/역).
`pickedBy` is "dict" when the dictionary's gloss match was accepted as is,
or the LLM that chose between candidates / rejected them.

The asset adds a short English meaning per character from Unihan
kDefinition (Unicode License). Unihan.zip is downloaded to ~/.cache on first
run. Re-running with no changes is a no-op.
"""
import io
import json
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DECISIONS = ROOT / "tools/moneo/hanja_decisions.json"
OUT = ROOT / "app/src/main/assets/moneo/hanja.json"
CACHE = Path.home() / ".cache/poketrek/Unihan.zip"
UNIHAN_URL = "https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip"


def is_hanja(c):
    return "㐀" <= c <= "鿿" or "豈" <= c <= "﫿"


def short_def(d):
    # "public building; hall" -> first two senses, trimmed.
    parts = [p.strip() for p in d.split(";") if p.strip()]
    s = "; ".join(parts[:2])
    return s if len(s) <= 40 else parts[0][:40]


def main():
    if not CACHE.exists():
        CACHE.parent.mkdir(parents=True, exist_ok=True)
        CACHE.write_bytes(urllib.request.urlopen(UNIHAN_URL).read())
    with zipfile.ZipFile(CACHE) as z:
        readings = z.read("Unihan_Readings.txt").decode("utf-8")
    defs = {}
    for line in io.StringIO(readings):
        if line.startswith("#") or "\tkDefinition\t" not in line:
            continue
        cp, _, v = line.rstrip("\n").split("\t")
        defs[chr(int(cp[2:], 16))] = v

    decisions = json.load(open(DECISIONS, encoding="utf-8"))
    words = {k: v for k, v in sorted(decisions.items()) if v}
    chars = sorted({c for v in words.values() for c in v["hanja"] if is_hanja(c)})
    doc = {
        "version": 1,
        "notes": "Hanja for Sino-Korean deck words (syllable-aligned) and a short "
                 "English meaning per character. Word hanja from kengdic "
                 "(MPL-2.0/LGPL) and libhangul (LGPL), checked against Unihan "
                 "readings; character meanings from Unihan kDefinition. Built by "
                 "tools/moneo/build_hanja.py.",
        "words": words,
        "chars": {c: short_def(defs.get(c, "")) for c in chars},
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"wrote {OUT.relative_to(ROOT)}: {len(words)} words, {len(chars)} chars")


if __name__ == "__main__":
    main()
