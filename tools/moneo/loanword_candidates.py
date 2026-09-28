"""List loanword candidates among deck words not yet in loanword_decisions.json.

Maps each word's hangul to a consonant skeleton (ㅅ→s, ㅌ→t, ㄹ→l ...) and
compares it with the skeletons of its English gloss words (city → s t);
scores >= 0.66 are printed for hand review. Native words whose consonants
happen to line up (하얗다 "white", 읽다 "read") score high too, so every
candidate needs a human (or reviewed LLM) decision in
loanword_decisions.json: {"part": the borrowed part of the word, "source":
original word, "lang": source language, "pickedBy": ...}. Hybrids mark only
their borrowed part (불꽃펀치 -> 펀치 ← punch). Then run build_loanwords.py.

    python3 tools/moneo/loanword_candidates.py
"""
import json,glob,re,difflib
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
A = str(ROOT / 'app/src/main/assets/moneo') + '/'
hanja = json.load(open(ROOT / 'tools/moneo/hanja_decisions.json', encoding='utf-8'))
decided = json.load(open(ROOT / 'tools/moneo/loanword_decisions.json', encoding='utf-8'))
INI=['k','k','s','t','t','l','m','p','p','s','s','','c','c','c','k','t','p','h']   # ㄱㄲㄴ... mapped to classes
INI=['k','k','n','t','t','l','m','p','p','s','s','','c','c','c','k','t','p','h']
FIN=['','k','k','k','n','n','n','t','l','l','l','l','l','l','l','l','m','p','p','s','s','ng','c','c','k','t','p','h']
def kskel(w):
    out=''
    for ch in w:
        o=ord(ch)-0xAC00
        if not 0<=o<11172: continue
        i,f=o//588,o%28
        out+=INI[i]+FIN[f]
    return out.replace('ng','n')
EN={'b':'p','p':'p','f':'p','v':'p','d':'t','t':'t','th':'t','g':'k','k':'k','c':'k','q':'k','x':'ks','s':'s','z':'c','j':'c','ch':'c','sh':'s','l':'l','r':'l','m':'m','n':'n','h':'h'}
def eskel(w):
    w=w.lower(); w=re.sub(r'ph','f',w); w=re.sub(r'ck','k',w); w=re.sub(r'c(?=[eiy])','s',w); w=re.sub(r'g(?=[ei])','c',w)
    out='';i=0
    while i<len(w):
        two=w[i:i+2]
        if two in ('th','ch','sh'): out+=EN[two]; i+=2; continue
        out+=EN.get(w[i],''); i+=1
    return re.sub(r'(.)\1+',r'\1',out)
rows=[]
seen=set()
for f in sorted(glob.glob(A+'seed-vocab-ko-*.json')):
    if 'species' in f or 'etymology' in f: continue
    for e in json.load(open(f))['entries']:
        k=e['korean']
        if k in seen or k in decided or (hanja.get(k) or {}).get('hanja'): continue
        seen.add(k)
        g=e['gloss']+' '+' '.join(e.get('senses') or [])
        ks=re.sub(r'(.)\1+',r'\1',kskel(k.removesuffix('하다')))
        if len(ks)<2: continue
        cands=re.findall(r"[A-Za-zé]+(?: [A-Za-zé]+)?",g)+re.findall(r"[A-Za-zé]+",g)
        best=max(((difflib.SequenceMatcher(None,ks,eskel(c.replace(' ',''))).ratio(),c) for c in cands),default=(0,''))
        if best[0]>=0.66: rows.append((round(best[0],2),k,best[1],e['gloss'][:40],e.get('primarySourceType')))
rows.sort(reverse=True)
print(len(rows))
for r in rows: print('\t'.join(map(str,r)))
