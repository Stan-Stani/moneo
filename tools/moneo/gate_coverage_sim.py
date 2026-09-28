"""Simulate a text-coverage area gate.

For each area in story order, count content-word tokens (mecab lemmas) in its
reachable dialog, then assume a learner who, before entering, learns the most
frequent unknown deck words until the area is readable at threshold T
(coverage = known deck-word tokens / deck-word tokens). Prints new words per
gate for flat thresholds and a 60%->90% ramp, next to the current
"80% of first-seen cards" requirement. Run with .venv-moneo/bin/python.
"""
import json, sys, glob, collections
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools/moneo'))
from mine_vocab import mecab_lemmatize, is_hangul

A = ROOT/'app/src/main/assets/moneo'
recs = {}
for p in [ROOT/'tools/moneo/corpus.ko.live.json', A/'corpus.ko.json']:
    for r in json.load(open(p))['records']: recs.setdefault(r['id'], r)
areas = json.load(open(ROOT/'tools/moneo/map_area_index.json'))['resolved_areas']

deck = {}  # lemma -> firstArea
for f in glob.glob(str(A/'seed-vocab-ko-*.json')):
    for e in json.load(open(f))['entries']:
        deck.setdefault(e['korean'], e.get('firstAreaEncountered') or e.get('areaId'))

STORY = """pallet_town route_1 viridian_city route_2 viridian_forest pewter_city route_3 mt_moon route_4
cerulean_city route_24 route_25 route_5 route_6 vermilion_city ss_anne route_11 digletts_cave route_9
route_10 rock_tunnel lavender_town route_8 route_7 celadon_city rocket_hideout pokemon_tower route_12
route_13 route_14 route_15 fuchsia_city safari_zone route_16 route_17 route_18 saffron_city silph_co
route_19 route_20 seafoam_islands cinnabar_island pokemon_mansion route_21 route_22 route_23
indigo_plateau kanto_pokemon_league power_plant cerulean_cave""".split()

tok_cache = {}
counts = {}
for a in STORY:
    c = collections.Counter()
    for rid in areas.get(a, {}).get('recIds', []):
        r = recs.get(rid)
        if not r or not r.get('text'): continue
        if rid not in tok_cache:
            try: tok_cache[rid] = [l for l,_,_ in mecab_lemmatize(r['text']) if all(is_hangul(ch) for ch in l)]
            except Exception: tok_cache[rid] = []
        c.update(tok_cache[rid])
    counts[a] = c

def cov(c, known):
    tot = sum(n for l,n in c.items() if l in deck)
    return (sum(n for l,n in c.items() if l in deck and l in known) / tot) if tot else 1.0

print(f"{'area':22} {'tokens':>6} {'inDeck':>6} {'types':>5}")
for a in STORY[:12]:
    c=counts[a]; t=sum(c.values()); d=sum(n for l,n in c.items() if l in deck)
    print(f"{a:22} {t:6} {d/t if t else 0:6.0%} {len(c):5}")

for T in (0.6, 0.8, 0.9, 0.95):
    known=set(); row=[]; cum=0
    for a in STORY:
        c=counts[a]; need=0
        if cov(c,known) < T:
            for l,n in sorted(((l,n) for l,n in c.items() if l in deck and l not in known), key=lambda x:-x[1]):
                known.add(l); need+=1
                if cov(c,known) >= T: break
        cum+=need; row.append((a,need,cum))
    print(f"\n== threshold {T:.0%}: new words per gate (cumulative) ==")
    print("  ".join(f"{a}:{n}({cu})" for a,n,cu in row))

# ramp
print("\n== ramp 60%->90% over first 10 areas, then 90% ==")
known=set(); out=[]; cum=0
for i,a in enumerate(STORY):
    T = 0.6 + 0.3*min(i,9)/9
    c=counts[a]; need=0
    if cov(c,known) < T:
        for l,n in sorted(((l,n) for l,n in c.items() if l in deck and l not in known), key=lambda x:-x[1]):
            known.add(l); need+=1
            if cov(c,known) >= T: break
    cum+=need; out.append(f"{a}:{T:.0%}:{need}({cum})")
print("  ".join(out))
# what the current scheme asks (80% of first-seen cards)
fs=collections.Counter(deck.values())
print("\n== current (80% of first-seen cards) ==")
print("  ".join(f"{a}:{int(fs[a]*.8+.99)}" for a in STORY))
