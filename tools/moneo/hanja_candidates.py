"""List hanja candidates for deck words not yet in hanja_decisions.json.

For each new (non-species) deck word, looks the word -- or its noun stem,
for 하다/되다/시키다/스럽다/적 forms -- up in kengdic (MPL-2.0/LGPL) and
libhangul's hanja.txt (LGPL), keeps candidates whose characters' Unihan
Korean readings match the syllables (initial-sound-law variants allowed),
and marks one "auto" when its dictionary gloss overlaps ours more than any
other candidate's. Review the printed list, add decisions (hanja or null,
plus pickedBy) to hanja_decisions.json, then run build_hanja.py.

  python3 tools/moneo/hanja_candidates.py <dir with kengdic.tsv, hanja.txt, Unihan_Readings.txt>

Sources: github.com/garfieldnate/kengdic (kengdic.tsv),
github.com/libhangul/libhangul (data/hanja/hanja.txt),
unicode.org/Public/UCD/latest/ucd/Unihan.zip.
"""
import json, glob, re, collections, csv, sys
from pathlib import Path
H = sys.argv[1].rstrip('/') + '/'
ROOT = Path(__file__).resolve().parents[2]
A = str(ROOT / 'app/src/main/assets/moneo') + '/'
# char readings
read=collections.defaultdict(set); defs={}
for line in open(H+'Unihan_Readings.txt',encoding='utf-8'):
    if line.startswith('#') or not line.strip(): continue
    cp,f,v=line.rstrip('\n').split('\t')
    ch=chr(int(cp[2:],16))
    if f=='kHangul':
        for tok in v.split(): read[ch].add(tok.split(':')[0])
    elif f=='kDefinition': defs[ch]=v
def variants(s):
    # 두음법칙 inverse: 이<->리, 여<->려, 요<->료, 유<->류, 야<->랴, 예<->례, 나<->라, 노<->로, 뇌<->뢰, 누<->루, 느<->르, 니<->이, 녀<->여 ...
    base=ord(s)-0xAC00
    if not (0<=base<11172): return {s}
    ini,med,fin=base//588,(base%588)//28,base%28
    out={s}
    def mk(i): return chr(0xAC00+i*588+med*28+fin)
    if ini==11: out|={mk(5),mk(2)}   # ㅇ -> ㄹ, ㄴ
    if ini==2: out|={mk(5)}          # ㄴ -> ㄹ
    if ini==5: out|={mk(2),mk(11)}
    return out
def valid(hangul,hanja):
    if len(hangul)!=len(hanja): return False
    for h,c in zip(hangul,hanja):
        if h==c: continue           # native syllable left as hangul
        if not (read.get(c,set()) & variants(h)): return False
    return any('㐀'<=c<='鿿' or '豈'<=c<='﫿' for c in hanja)
keng=collections.defaultdict(list)
for r in csv.DictReader(open(H+'kengdic.tsv',encoding='utf-8'),delimiter='\t'):
    if r['hanja'] and r['surface']: keng[r['surface'].strip()].append((r['hanja'].strip(), r['gloss'] or ''))
lib=collections.defaultdict(list)
for line in open(H+'hanja.txt',encoding='utf-8'):
    if line.startswith('#') or ':' not in line: continue
    p=line.rstrip('\n').split(':')
    if len(p)>=2 and len(p[0])>1: lib[p[0]].append((p[1], p[2] if len(p)>2 else ''))
STOP=set('a an the to of be in on for and or with as at by from up out it is one someone something'.split())
def toks(s): return {w for w in re.findall(r'[a-z]+',s.lower()) if w not in STOP and len(w)>2}
def stem(k):
    for suf in ('하다','되다','시키다','스럽다','적'):
        if k.endswith(suf) and len(k)>len(suf)+1: return k[:-len(suf)], suf
    return k, ''
decided = json.load(open(ROOT / 'tools/moneo/hanja_decisions.json', encoding='utf-8'))
for f in sorted(glob.glob(A+'seed-vocab-ko-*.json')):
    if 'species' in f or 'etymology' in f: continue
    for e in json.load(open(f, encoding='utf-8'))['entries']:
        k = e['korean']
        if k in decided: continue
        decided[k] = None  # print each word once across decks
        gloss = e['gloss']+' '+' '.join(e.get('senses') or [])
        s, suf = stem(k)
        cands = {}
        for hj, g in keng.get(s, []) + lib.get(s, []):
            if valid(s, hj): cands.setdefault(hj, set()).update(toks(g))
        if not cands: continue
        gt = toks(gloss)
        scored = sorted(((len(gt & t), hj) for hj, t in cands.items()), reverse=True)
        auto = scored[0][1] if scored[0][0] > 0 and (len(scored) == 1 or scored[0][0] > scored[1][0]) else ''
        print(f"{k}\t{gloss[:40]}\t{'*'+auto if auto else ''}\t{' '.join(sorted(cands))}")
