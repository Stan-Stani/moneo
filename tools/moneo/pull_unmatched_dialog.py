#!/usr/bin/env python3
"""Pull the reading helper's missed-dialogue log off a device and sort each miss.

    python3 tools/moneo/pull_unmatched_dialog.py [serial]

Reads files/moneo/unmatched_dialog.json via adb run-as, then checks each
message against the clean corpus and dialog_index.json:

  NOT IN CORPUS   no corpus line explains the message under the app's own
                  matching rules -> decode gap, text the corpus never
                  captured, or a template whose fixed text is too thin
                  ("{STR_VAR_1}의 {STR_VAR_2}!")
  NO DECK WORDS   a corpus line matches but was left out of the index
                  because none of its words are cards -> nothing to show
  INDEXED         the index has a line that should have matched -> matcher bug

Most frequent first, so the fixes that help most come first.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/moneo"
ADB = [str(Path.home() / "Library/Android/sdk/platform-tools/adb")] + (["-s", sys.argv[1]] if len(sys.argv) > 1 else [])
PLACEHOLDER = re.compile(r"\{[A-Z_0-9]+\}|[가-힣]?\([가-힣]\)")


def hangul(s):
    return re.sub(r"[^가-힣]", "", s)


def covered(segs, h):
    """Syllables of h explained by segs appearing in order (0 if any is missing)."""
    at, score = 0, 0
    for seg in segs:
        i = h.find(seg, at)
        if i < 0:
            return 0
        at, score = i + len(seg), score + len(seg)
    return score


def accepts(score, n, template):
    """Same rule as DialogIndex.accepts in the app."""
    if score < 3:
        return score == n
    return score * 5 >= n if template else score * 2 >= n


def main():
    raw = subprocess.run(ADB + ["exec-out", "run-as", "com.poketrek", "cat", "files/moneo/unmatched_dialog.json"],
                         capture_output=True).stdout
    if not raw.strip().startswith(b"{"):  # run-as prints "No such file" to stdout
        print("no unmatched dialogue recorded")
        return
    msgs = json.loads(raw)["messages"]
    corpus = [r for r in json.load(open(ASSETS / "corpus.ko.json", encoding="utf-8"))["records"]
              if not r.get("source") and not r.get("unknown") and "·" not in r["text"]]
    indexed = {l["id"] for l in json.load(open(ASSETS / "dialog_index.json", encoding="utf-8"))["lines"]}
    rows = []
    for m in msgs:
        h = hangul(m["message"])
        hit, best = None, 0
        for r in corpus:
            segs = [hangul(p) for p in PLACEHOLDER.split(r["text"]) if hangul(p)]
            score = covered(segs, h)
            if score > best and accepts(score, len(h), bool(PLACEHOLDER.search(r["text"]))):
                hit, best = r, score
        kind = "NOT IN CORPUS" if hit is None else ("INDEXED" if hit["id"] in indexed else "NO DECK WORDS")
        rows.append((m["count"], kind, m.get("map") or "?", m["message"].replace("\n", " "),
                     hit and hit["text"].replace("\n", " ")))
    for count, kind, where, msg, line in sorted(rows, key=lambda r: -r[0]):
        print(f"x{count:<3} {kind:<14} [{where}] {msg}")
        if line:
            print(f"{'':20}corpus: {line}")


if __name__ == "__main__":
    main()
