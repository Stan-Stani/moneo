```markdown
# Moneo attribution pipeline runbook

End-to-end pipeline that takes the Korean LeafGreen ROM and emits per-card
`firstAreaEncountered` attribution for the existing TOPIK + ROM-mined decks.
All deterministic, all driven by [pokefirered](https://github.com/pret/pokefirered)
struct/opcode knowledge applied to the Korean fan-translation.

The wider RE history (glyph-map labeling, mGBA capture, etc.) is in `README.md`.
This document is the attribution-pipeline runbook only.

## What it produces

For every TOPIK / ROM-mined / live-mined card, a `firstAreaEncountered` field
naming the canonical FRLG area where the player first hears or reads the
vocabulary. Combined with `areasReferenced` (full area list), it lets the deck
be filtered or sorted by story progression.

## Prerequisites

```bash
# Korean LeafGreen ROM (2024-02-29 patch is the current default)
ls tools/moneo/rom_swap/leafgreen_J-K_2024.gba   # CRC32 0x4A38A8CB
# legacy 2010 build still works if you swap rom_config.ROM_PATH:
# ls "Pocket Monsters - LeafGreen (Korean).gba"   # CRC32 0x398C4817

# Python venv with mecab-ko
source .venv-moneo/bin/activate
python3 -c "import mecab_ko"   # required by mine_vocab + lemma index

# Pokefirered reference (read-only, used to extract opcode/struct knowledge)
git clone --depth 1 https://github.com/pret/pokefirered.git ~/.cache/pokefirered
```

## Pipeline (run in order)

Each stage's output feeds the next. Re-run a stage if you change the ROM,
the glyph-map, or upstream artifacts. All commands assume venv is active.

### 1. Extract live-region corpus

```bash
python3 tools/moneo/extend_corpus_live.py
```

- Reads: ROM + `glyph-map.json` + existing `app/src/main/assets/moneo/corpus.ko.json`
- Writes: `tools/moneo/corpus.ko.live.json` (~40K records)
- What: every u32 ROM-pointer in ROM[:0x800000] whose target passes
  `msg_quality` (≥1 hangul, FF terminator within 500 bytes, hangul-heavy
  records may have up to `hangul/4` unmapped glyph bytes). Excludes offsets
  already in the static corpus.
- Note: depends on `glyph-map.json` — relabeling glyphs requires re-running
  this whole pipeline.

### 2. Walk map scripts

```bash
python3 tools/moneo/walk_scripts_v2.py
```

- Reads: ROM + both corpus files + `script_opcodes.py` (auto-generated FRLG
  opcode table)
- Writes: `tools/moneo/map_text_index.json` (per-map text refs)
- What: deterministic FRLG bytecode disassembler. Walks each map's
  `events.objectEvents`/`coordEvents`/`signEvents`/`mapScripts` seed scripts.
  Recognizes `message` (0x67), `braillemessage` (0x78), `loadword + callstd`
  (msgbox pattern), `trainerbattle` (0x5C with subtype layouts), `bufferstring`
  (0x85/0xBF), CALL/GOTO recursion (depth=2), END/RETURN terminators.
  Falls back to scoped 2KB window u32 scan on unknown opcodes.
- Map walker uses gMapGroups (2024 patch: 0x3526F8; 2010 build: 0x316740).
  Group offsets are now derived dynamically from the ROM via
  `rom_config.get_group_offsets()`, so the walker auto-adapts to either
  build. The 2024 patch ships all 425 maps in pokefirered's canonical group
  order (43 groups: Link=0, Dungeons=1, SpecialArea=2, TownsAndRoutes=3, ...).
  An earlier value of 0x352700 started two entries into the table, which
  silently dropped Link + all 123 Dungeons maps and shifted every other group
  number by -2.

If `script_opcodes.py` is missing (or pokefirered updates), regenerate:
```bash
python3 tools/moneo/_gen_opcodes.py   # parses ~/.cache/pokefirered/asm/macros/event.inc
```

### 3. Resolve maps to areas (via warps)

```bash
python3 tools/moneo/resolve_map_areas.py
```

- Reads: `map_text_index.json` + `mapsec_areas.json` + `app/src/main/assets/moneo/areas.json`
- Writes: `tools/moneo/map_area_index.json`
- What: each map's mapsec → area_id (canonical FRLG → user's areas.json).
  For maps whose mapsec is unmapped (e.g. shared "interior" mapsecs across
  many cities), follows `events.warps[].destMap` to find the parent town's
  outdoor map and inherits its area. ~108/146 maps directly mapped; 5 via warp.

### 4. Build lemma → area index

```bash
python3 tools/moneo/build_live_lemma_index.py
```

- Reads: corpus files + `map_area_index.json` + `mapsec_areas.json` + several
  table files (see passes below) + `glyph-map.json`
- Writes: `tools/moneo/lemma_area_index.json`
- What: 7 tokenization passes that union into a `lemma -> {areas: [], rec_ids: []}`
  index. Passes (in order):

  | # | Pass | Source | Tag |
  |---|------|--------|-----|
  | 1 | Live records (map-walker reached) | `map_area_index.json` | per-map area |
  | 2 | Static corpus (Pokédex / items / menus) | `corpus.ko.json` | `rom_mined` |
  | 3 | Trainer-dialog ROM table | scan 0x163000-0x166000 | `trainer_dialog` |
  | 4 | Items × pokemart/giveitem | `item_obtain_index.json` | per-area items |
  | 5 | Pokédex × wild encounters | `pokedex_obtain_index.json` | per-route Pokémon |
  | 6 | Per-NPC trainer classes | `trainer_npc_index.json` | per-map trainers |
  | 7 | Trainer-class names fallback | `trainer_class_names.json` | `trainer_dialog` |

  All passes apply the same lemma quality filter (length 2-5, all hangul,
  no kana-romaji noise, no batchim-less 3+ syllable nouns).

  `first_area` for each lemma = lowest-ordinal area in its set (negative
  ordinals treated as last to keep canonical Kanto progression at the front).

### 5. Generate auxiliary tables (run once after ROM/glyph changes)

These produce intermediate JSONs read by pass 4-6. They depend only on the
ROM + corpus, not on each other.

```bash
# Items: gItems @ 0x3A058C, 40-byte stride, 374 items
# (a) extract description offsets
python3 -c "
import struct, json
rom = open('Pocket Monsters - LeafGreen (Korean).gba','rb').read()
GBA=0x08000000; START=0x3A058C; STRIDE=40
sc = json.load(open('app/src/main/assets/moneo/corpus.ko.json'))
lc = json.load(open('tools/moneo/corpus.ko.live.json'))
all_recs = {r['offset']: r for r in sc['records']}
for r in lc['records']: all_recs.setdefault(r['offset'], r)
items = []
for i in range(500):
    e = START + i*STRIDE
    if e + STRIDE > len(rom): break
    iid = struct.unpack_from('<H', rom, e+14)[0]
    desc_ptr = struct.unpack_from('<I', rom, e+20)[0]
    if (desc_ptr & 0xFE000000) != GBA: break
    desc_off = desc_ptr - GBA
    rec = all_recs.get(desc_off)
    if iid >= 400: break
    items.append({'index': i, 'item_id': iid, 'description_offset': desc_off,
                  'description_rec_id': rec['id'] if rec else None})
import pathlib
pathlib.Path('tools/moneo/items_table.json').write_text(json.dumps(
    {'version':1,'table_offset':START,'stride':STRIDE,'n_entries':len(items),'items':items},
    ensure_ascii=False, indent=1) + '\n')
print(f'{len(items)} items')
"
# (b) per-area mapping via map-script pokemart/giveitem walker:
# (in-line script in commit 3b2ddb0; produces item_obtain_index.json)
```

(See commits `3b2ddb0`, `5619b0f`, `a6c1fd5`, `0fe15b9` for the exact code that
produced `item_obtain_index.json`, `pokedex_obtain_index.json`,
`trainer_class_names.json`, `trainer_npc_index.json`. They're all small
one-shot scripts that may eventually be promoted to top-level CLIs.)

### 6. Mine new vocab from live corpus

```bash
python3 tools/moneo/mine_vocab.py \
    --corpus tools/moneo/corpus.ko.live.json \
    --out-vocab tools/moneo/seed-vocab-ko-live-mined.json \
    --out-sents tools/moneo/sentences-ko-live-mined.json \
    --threshold 3
```

- What: tokenizes live records (those that pass kana-romaji noise filters)
  with mecab-ko, finds lemmas appearing ≥3 times, pulls a representative
  example sentence per lemma. Output: 78 cards at threshold=3.
- Lower `--threshold` for broader (noisier) coverage; raise for tighter quality.

### 7. Annotate live-mined cards

```bash
python3 tools/moneo/annotate_live_attribution.py
```

- Reads: live-mined vocab/sentences + `map_text_index.json` + `mapsec_areas.json`
- Writes: `tools/moneo/seed-vocab-ko-live-attributed.json` and matching sentences
- Each live-mined card gets `firstAreaEncountered` from its source rec_id's
  resolved-area mapping. Includes a text-overlap fallback for records whose
  rec_id wasn't reached but whose text is contained in a reached record.

### 8. Apply lemma index to existing decks

```bash
python3 tools/moneo/attribute_existing_decks.py
```

- Reads: `lemma_area_index.json` + the existing TOPIK + ROM-mined decks in
  `app/src/main/assets/moneo/`
- Writes: `tools/moneo/seed-vocab-ko-{topik,mined}-attributed.json` and
  matching sentences with `firstAreaEncountered` and `areasReferenced` per card.

### 9. Extract name tables (moves, abilities, species) from ROM

The 2024 patch encodes gMoveNames, gAbilityNames, and gSpeciesNames as
inline byte arrays of 16-bit BE codepoints into a custom hangul font
(distinct from the F0..F6 page-byte scheme used by dialog text). To
extract them as deck cards:

```bash
# 1. Locate the table offsets (one-shot, results saved into rom_config-ish files)
python3 tools/moneo/rom_swap/find_name_tables.py
# Expected output:
#   gMoveNames    @ 0x2470E0  (stride 13, 355 entries)
#   gAbilityNames @ 0x24FC8C  (stride 13,  78 entries)
#   gSpeciesNames @ 0x245F2C  (stride 11, 412 entries)

# 2. Fetch canonical Korean names from PokeAPI (CSV mirror) for triangulation
curl -s -o tools/moneo/rom_swap/pokemon_species_names.csv \
    https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/pokemon_species_names.csv
curl -s -o tools/moneo/rom_swap/move_names.csv \
    https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/move_names.csv
curl -s -o tools/moneo/rom_swap/ability_names.csv \
    https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/ability_names.csv
python3 tools/moneo/rom_swap/parse_korean_names.py
# Writes korean_{species,move,ability}_names.json

# 3. Triangulate codepoint -> hangul mapping from the joined data
python3 tools/moneo/rom_swap/build_codepoint_map.py
python3 tools/moneo/rom_swap/iterative_resolve.py
# Writes codepoint_map.json (~533 codepoints resolved)

# 4. Decode all entries and inspect for unresolved codepoints
python3 tools/moneo/rom_swap/decode_with_map.py
# Writes decoded_names.json

# 5. Build deck cards (TM/HM-based move attribution applied)
python3 tools/moneo/build_name_table_decks.py
# Writes seed-vocab-ko-rom-names.json, seed-vocab-ko-species.json,
# and matching sentences.

# 6. Merge into shipped decks
python3 tools/moneo/merge_into_shipped_decks.py
# Updates app/src/main/assets/moneo/seed-vocab-ko-mined.json (+424 cards),
# matching sentences (+426 cards), copies species deck to assets dir.
```

After step 6, MoneoModule.kt loads the species deck automatically
(seed-vocab-ko-species.json + sentences-ko-species.json).

Total deck size grows from ~908 to 1740 cards.

## End state

`tools/moneo/*-attributed.json` (4 files: vocab+sentences for both topik and mined,
plus the 2 live-mined files from step 7). Each card carries:

- `firstAreaEncountered`: e.g. `"pewter_city"`, `"trainer_dialog"`, `"rom_mined"`, or `null`
- `areasReferenced`: full set of areas where the lemma appears
- `liveRecIds`: sample rec_ids the lemma was found in (for debugging)

The app does not yet read these fields — see "Shipping" below.

## Shipping the data

The attributed JSONs are deliberately staged under `tools/moneo/` rather than
overwriting the live `app/src/main/assets/moneo/` deck files. To ship:

```bash
cp tools/moneo/seed-vocab-ko-topik-attributed.json    app/src/main/assets/moneo/seed-vocab-ko-topik.json
cp tools/moneo/seed-vocab-ko-mined-attributed.json    app/src/main/assets/moneo/seed-vocab-ko-mined.json
cp tools/moneo/sentences-ko-topik-attributed.json     app/src/main/assets/moneo/sentences-ko-topik.json
cp tools/moneo/sentences-ko-mined-attributed.json     app/src/main/assets/moneo/sentences-ko-mined.json
./gradlew installDebug
```

The added fields are extra — existing app code that reads `korean`/`gloss`/`areaId`
won't break. UI consumption of `firstAreaEncountered` (e.g. a per-card area
badge) is a separate UI commit.

## Re-deriving from scratch

If you regenerate the Korean ROM, change the glyph map, or pull pokefirered
updates, run all stages in order:

```bash
source .venv-moneo/bin/activate
python3 tools/moneo/_gen_opcodes.py
python3 tools/moneo/extend_corpus_live.py
python3 tools/moneo/walk_scripts_v2.py
python3 tools/moneo/resolve_map_areas.py
# (regenerate items_table.json, item_obtain_index.json, pokedex_table.json,
#  pokedex_obtain_index.json, trainer_class_names.json, trainer_npc_index.json
#  -- one-shot scripts in commits 3b2ddb0, 5619b0f, a6c1fd5, 0fe15b9)
python3 tools/moneo/build_live_lemma_index.py
python3 tools/moneo/mine_vocab.py --corpus tools/moneo/corpus.ko.live.json \
    --out-vocab tools/moneo/seed-vocab-ko-live-mined.json \
    --out-sents tools/moneo/sentences-ko-live-mined.json --threshold 3
python3 tools/moneo/annotate_live_attribution.py
python3 tools/moneo/attribute_existing_decks.py
```

## Known limitations

- **Move and ability descriptions are kana-romaji** (untranslated by the fan
  build). gMoveDescriptionPointers @ 0x21A2BC and gAbilityDescriptionPointers @
  0x1AA8C0 are findable but their text is unreadable Japanese-as-Korean-glyphs.
  Skipped from attribution.
- **gWildMonHeaders and gMapGroups share pokefirered's canonical group
  indexing** (`KOREAN_GROUP_OFFSET = 0`). The old `-2` translation only existed
  to compensate for the wrong gMapGroups offset.
- **Pallet-heavy distribution**: partly real (most common Korean grammar
  surfaces in Pallet's Mom/Oak/rival dialog), but was inflated by the old
  gMapGroups offset, which filed Trainer Tower / Navel Rock text under Pallet.
- **317 still-unattributed cards** are mostly TOPIK vocab whose lemma doesn't
  tokenize to anything in any reachable game text — likely game-irrelevant
  TOPIK words like 공항 (airport), 회사 (company).
- **12 mapsecs still null** in `mapsec_areas.json` (0xB3, 0xB5, 0xB9, 0xBA,
  0xBB, 0xBC..0xC3). These are shared-building mapsecs whose specific town
  affiliation needs eyeballing of `build_mapsec_dialog.py --identify` output.
- **Glyph-map gaps** (~219 distinct unmapped glyphs in attributed records).
  Each one blocks a few text records from cleanly tokenizing. `label_glyphs.py`
  is the interactive tool to fill them in.

## Key ROM offsets (Korean LeafGreen)

| Symbol | Offset | Notes |
|--------|--------|-------|
**2024-02-29 patch (current):** see `tools/moneo/rom_config.py` for the
authoritative constants. Verified by `tools/moneo/rom_swap/find_offsets_2024.py`.

| Symbol | Offset | Notes |
|--------|--------|-------|
| gMapGroups | 0x3526F8 | 43 groups, 425 maps (canonical order) |
| gItems | 0x3DAED4 | 44-byte stride (canonical), 375 items |
| gPokedexEntries | 0x44E2E0 | 36-byte stride (canonical), 387 entries |
| gWildMonHeaders | 0x3C9B64 | 20-byte stride, 132 entries; canonical groups |
| gTrainers | 0x23EB3C | 40-byte stride (canonical), 743 trainers |
| gTrainerClassNames | 0x23E5A4 | 13-byte stride (canonical), 107 class names |
| Trainer dialog table | 0x230000-0x240000 | dense pointer region |

**2010 fan translation (legacy):**

| Symbol | Offset | Notes |
|--------|--------|-------|
| gMapGroups | 0x316740 | 41 groups, 146 maps |
| gItems | 0x3A058C | 40-byte stride, 374 items |
| gPokedexEntries | 0x40E254 | 28-byte stride, 387 entries |
| gWildMonHeaders | 0x390E04 | 20-byte stride, 76 entries; group offset -2 |
| gTrainers | 0x1FE1B4 | 32-byte stride (Korean compacted), 504 trainers |
| gTrainerClassNames | 0x1FDB18 | 11-byte stride, 117 class names |
| Trainer dialog table | 0x163000-0x166000 | dense pointer region |
| Trainer dialog table | 0x163000-0x166000 | flat region of trainer/sign-dialog ptrs |

These are derived from the canonical pokefirered struct definitions, applied
to the Korean fan-build via signature/stride search. If pokefirered struct
sizes change in a future commit, regenerate via the search scripts in
commits `3b2ddb0`, `5619b0f`, `a6c1fd5`, `0fe15b9`.

## Pokefirered reference paths

```
~/.cache/pokefirered/asm/macros/event.inc        -> all script opcodes
~/.cache/pokefirered/data/event_scripts.s        -> gStdScripts
~/.cache/pokefirered/data/specials.inc           -> gSpecials
~/.cache/pokefirered/include/item.h              -> Item struct
~/.cache/pokefirered/include/pokedex.h           -> PokedexEntry struct
~/.cache/pokefirered/include/wild_encounter.h    -> WildPokemonHeader struct
~/.cache/pokefirered/include/battle.h            -> Trainer struct (for canonical layout)
~/.cache/pokefirered/data/maps/map_groups.json   -> 43-group canonical map ordering
~/.cache/pokefirered/src/data/wild_encounters.json -> per-map encounter tables
```

## Final attribution snapshot (commit `0fe15b9`)

Combined deck: 956 cards total, 639 attributed (67%).

| Area | Cards | |
|------|-------|---|
| pallet_town | 266 | most common Korean grammar surfaces here first |
| rom_mined | 146 | static-only Pokédex/items not tied to specific area |
| viridian_city | 89 | first Mart inventory |
| pewter_city | 39 | Mt. Moon area, Bug Catchers |
| cerulean_city | 14 | |
| trainer_dialog | 13 | shared trainer-encounter phrases |
| route_1 | 11 | |
| route_2 | 10 | |
| fuchsia_city | 9 | |
| route_3 | 5 | |
| celadon_city | 5 | |
| route_4..route_25 + others | ~32 | long tail |
| **unattributed** | 317 | TOPIK vocab not tokenizing in any ROM text |
```
## Re-applying attribution without a full rebuild

The shipped decks carry hand-applied glosses, audits and sentence fixes on top
of the generated attribution, so re-running `attribute_existing_decks.py` +
copying is destructive, and that script can only ever *add* areas. After
changing anything that affects `lemma_area_index.json` or
`pokedex_obtain_index.json`, apply just the difference instead:

```bash
git show HEAD:tools/moneo/lemma_area_index.json    > /tmp/old_index.json
git show HEAD:tools/moneo/pokedex_obtain_index.json > /tmp/old_obtain.json
# ...re-run walk_scripts_v2 / resolve_map_areas / build_obtain_indexes /
#    build_live_lemma_index...
python3 tools/moneo/reattribute_from_index_delta.py \
    --old-index /tmp/old_index.json --old-obtain /tmp/old_obtain.json
```

It only rewrites `areasReferenced` / `firstAreaEncountered`.

## Korean text encoding (2024 patch)

Dialog is **variable-width**: Gen 3's 1-byte charset for space (00),
digits, punctuation, FC/FD control codes, FE newline and FF end, plus
2-byte hangul with lead byte 0x37-0x41 (`rom_swap/codepoint_map.json`).
Strings start at odd addresses about half the time. `ko_text.decode()`
is the decoder; `redecode_corpus_2024.py` applies it to
`corpus.ko.2024.json` and the app's `corpus.ko.json` without changing
record ids, and appends pointer-target strings the old fixed-width scan
missed. Josa codes after name buffers (0x41EF 은(는), 0x41F0 을(를),
0x41F1 이(가), 0x41F2 (이), 0x41F4 (으)) are rendered in dictionary
notation; 0x41EE is still unidentified.

Name tables (gMoveNames etc.) really are fixed-width 16-bit and are
unaffected.

### Verifying codepoint labels against the font

`rom_swap/codepoint_map.json` labels can be checked against the glyphs the
game actually draws. Atlas 1 at ROM `0xF18800`, index
`((hi - 0x35) << 8) | lo`, 64 bytes per slot; hangul use the top-left 8x13
pixels, raw 2bpp value 1 = ink, 2 = drop shadow (see
`rom_swap/FONT_HUNT.md` for the tile layout).

```bash
.venv-moneo/bin/python tools/moneo/rom_swap/glyph_sheet.py --all      # every label
.venv-moneo/bin/python tools/moneo/rom_swap/glyph_sheet.py --suspects # out of KS X 1001 order
.venv-moneo/bin/python tools/moneo/rom_swap/glyph_sheet.py --codes 3B7E
```

The font follows KS X 1001 order between spare out-of-order slots, so a
second, independent check is to predict each slot from its neighbours'
KS X indices; every label was reviewed against both (2026-09-27). The glyph
is ground truth. Context from dialog is only a hint: before ko_text.py, the
corpus included non-text records whose "contexts" looked plausible (0x3839,
really 낟, appeared to read "포켓몬[도]망간다" but never occurs in real
dialog; 0x3863 is 넥 as in "포켓몬스넥", not 터).

Particle codes 0x41ED..0x41F4 are not glyphs: the engine at `0x0872BA00`
indexes a (after-consonant, after-vowel) halfword table at `0x0872BC0C` by
a batchim flag saved from the previous syllable (see `ko_text.PARTICLES`).
