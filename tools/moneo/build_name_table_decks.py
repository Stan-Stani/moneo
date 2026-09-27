#!/usr/bin/env python3
"""Build moneo deck cards from the 2024 ROM's gMoveNames, gAbilityNames,
and gSpeciesNames tables.

Outputs:
  - tools/moneo/seed-vocab-ko-rom-names.json   (moves + abilities to merge with mined deck)
  - tools/moneo/seed-vocab-ko-species.json     (separate species deck)
  - tools/moneo/sentences-ko-rom-names.json    (matching sentences)
"""
from __future__ import annotations
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1].parent
HERE = Path(__file__).resolve().parent
ROM_SWAP = HERE / "rom_swap"

# === ROM offsets (verified by find_name_tables.py) ===
GMOVE_NAMES = 0x2470E0
GMOVE_NAMES_STRIDE = 13
GMOVE_NAMES_N = 355

GABILITY_NAMES = 0x24FC8C
GABILITY_NAMES_STRIDE = 13
GABILITY_NAMES_N = 78

GSPECIES_NAMES = 0x245F2C
GSPECIES_NAMES_STRIDE = 11
GSPECIES_NAMES_N = 412

# === TM/HM table from pokefirered (party_menu.h sTMHMMoves[]) ===
# Maps TM01..TM50, HM01..HM08 to the move_id (1-indexed pokefirered MOVE_*).
TM_TO_MOVE_ID = {
    "TM01": 264, "TM02": 337, "TM03": 352, "TM04": 347, "TM05": 46,
    "TM06": 92, "TM07": 258, "TM08": 339, "TM09": 331, "TM10": 237,
    "TM11": 241, "TM12": 269, "TM13": 58, "TM14": 59, "TM15": 63,
    "TM16": 113, "TM17": 182, "TM18": 240, "TM19": 202, "TM20": 219,
    "TM21": 218, "TM22": 76, "TM23": 231, "TM24": 85, "TM25": 87,
    "TM26": 89, "TM27": 216, "TM28": 91, "TM29": 94, "TM30": 247,
    "TM31": 280, "TM32": 104, "TM33": 115, "TM34": 351, "TM35": 53,
    "TM36": 188, "TM37": 201, "TM38": 126, "TM39": 317, "TM40": 332,
    "TM41": 259, "TM42": 263, "TM43": 290, "TM44": 156, "TM45": 213,
    "TM46": 168, "TM47": 211, "TM48": 285, "TM49": 289, "TM50": 315,
    "HM01": 15,  "HM02": 19,  "HM03": 57,  "HM04": 70,  "HM05": 148,
    "HM06": 249, "HM07": 127, "HM08": 291,
}

# In FRLG, the player obtains TMs in particular areas. This is approximate
# from canonical FRLG progression. For finer detail consult bulbapedia.
# Where a TM is obtainable in multiple areas (e.g. Game Corner prizes), pick
# the canonical first-encounter area.
TM_FIRST_AREA = {
    "TM01": "two_island",        # Cape Brink
    "TM02": "victory_road",      # via NPC; we treat as route_23 area
    "TM03": "celadon_city",      # Celadon Dept Store
    "TM04": "saffron_city",      # Mr Psychic
    "TM05": "route_23",          # Route 23
    "TM06": "fuchsia_city",      # Fuchsia City
    "TM07": "seafoam_islands",   # post-game
    "TM08": "two_island",        # Cape Brink reward
    "TM09": "cerulean_city",     # Cerulean
    "TM10": "rock_tunnel",       # Power Plant in canon, Rock Tunnel proxy
    "TM11": "route_12",          # Route 12 (changes)
    "TM12": "lavender_town",     # Pokemon Tower
    "TM13": "celadon_city",      # Celadon Dept Store
    "TM14": "celadon_city",      # Celadon Dept Store
    "TM15": "celadon_city",      # Celadon Dept Store
    "TM16": "celadon_city",      # Celadon Dept Store
    "TM17": "fuchsia_city",
    "TM18": "fuchsia_city",      # Fuchsia City
    "TM19": "celadon_city",
    "TM20": "celadon_city",
    "TM21": "fuchsia_city",
    "TM22": "celadon_city",
    "TM23": "victory_road",
    "TM24": "celadon_city",
    "TM25": "power_plant",
    "TM26": "viridian_city",
    "TM27": "fuchsia_city",
    "TM28": "celadon_city",
    "TM29": "saffron_city",
    "TM30": "pokemon_tower",
    "TM31": "celadon_city",
    "TM32": "celadon_city",
    "TM33": "saffron_city",
    "TM34": "rocket_hideout",
    "TM35": "celadon_city",
    "TM36": "rocket_hideout",
    "TM37": "fuchsia_city",
    "TM38": "cinnabar_island",
    "TM39": "mt_moon",
    "TM40": "fuchsia_city",
    "TM41": "lavender_town",
    "TM42": "lavender_town",
    "TM43": "saffron_city",
    "TM44": "pokemon_mansion",
    "TM45": "rocket_hideout",
    "TM46": "rocket_hideout",
    "TM47": "saffron_city",
    "TM48": "celadon_city",
    "TM49": "rocket_hideout",
    "TM50": "rocket_hideout",
    "HM01": "ss_anne",           # Captain on SS Anne
    "HM02": "fuchsia_city",      # Safari Zone area
    "HM03": "safari_zone",       # Safari Zone
    "HM04": "saffron_city",      # Saffron warden / vermilion
    "HM05": "rock_tunnel",       # Rock Tunnel
    "HM06": "fuchsia_city",      # Fuchsia warden
    "HM07": "cerulean_cave",     # post-game
    "HM08": "seafoam_islands",   # via Sevii
}

# Build move_id -> area
MOVE_ID_TO_AREA: dict[int, str] = {}
for tm, mid in TM_TO_MOVE_ID.items():
    area = TM_FIRST_AREA.get(tm)
    if area:
        # Validate against areas.json - if area not in list, drop to rom_mined
        MOVE_ID_TO_AREA[mid] = area


def read_codepoints(rom: bytes, off: int, max_len: int) -> list[int]:
    out = []
    i = 0
    while i < max_len:
        b = rom[off + i]
        if b == 0xFF:
            break
        if i + 1 >= max_len:
            break
        cp = (b << 8) | rom[off + i + 1]
        out.append(cp)
        i += 2
    return out


# === Revised Romanization (RR) — simple jamo decomposition ===
# Reference: https://en.wikipedia.org/wiki/Revised_Romanization_of_Korean
INITIAL_RR = ["g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss",
              "", "j", "jj", "ch", "k", "t", "p", "h"]
MEDIAL_RR = ["a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa",
             "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i"]
FINAL_RR = ["", "k", "kk", "ks", "n", "nj", "nh", "t", "l", "lk", "lm",
            "lb", "ls", "lt", "lp", "lh", "m", "p", "ps", "t", "tt",
            "ng", "j", "ch", "k", "t", "p", "h"]


def romanize_syllable(syl: str) -> str:
    cp = ord(syl) - 0xAC00
    if not 0 <= cp < 11172:
        return syl  # not Hangul syllable
    initial = cp // (21 * 28)
    medial = (cp % (21 * 28)) // 28
    final = cp % 28
    return INITIAL_RR[initial] + MEDIAL_RR[medial] + FINAL_RR[final]


def romanize(text: str) -> str:
    return "".join(romanize_syllable(c) for c in text)


def is_clean_korean(text: str) -> bool:
    """Returns True if every char is a Hangul syllable AND text is non-empty
    AND has at least 2 characters."""
    if len(text) < 2:
        return False
    if "<" in text:
        return False
    for c in text:
        if not (0xAC00 <= ord(c) <= 0xD7A3):
            return False
    return True


def main():
    from rom_config import ROM_PATH
    rom = ROM_PATH.read_bytes()
    cp_map = {int(k, 16): v for k, v in
              json.loads((ROM_SWAP / "codepoint_map.json").read_text()).items()}

    species_ko = {int(k): v for k, v in
                  json.loads((ROM_SWAP / "korean_species_names.json").read_text()).items()}
    moves_ko = {int(k): v for k, v in
                json.loads((ROM_SWAP / "korean_move_names.json").read_text()).items()}
    abilities_ko = {int(k): v for k, v in
                    json.loads((ROM_SWAP / "korean_ability_names.json").read_text()).items()}

    # Pokedex obtain index for species attribution
    pokedex_obtain = json.loads((HERE / "pokedex_obtain_index.json").read_text())
    pokedex_table = json.loads((HERE / "pokedex_table.json").read_text())
    # rec_id -> species_index
    rec_to_species = {e["description_rec_id"]: e["species_index"]
                      for e in pokedex_table["entries"]}
    # species_index -> first_area (lowest-priority area mapping)
    AREA_PRIORITY = ["pallet_town", "route_1", "viridian_city", "route_2",
                     "viridian_forest", "pewter_city", "route_3", "mt_moon",
                     "route_4", "cerulean_city", "route_24", "route_25",
                     "route_5", "route_6", "saffron_city", "route_7",
                     "route_8", "lavender_town", "pokemon_tower", "route_10",
                     "rock_tunnel", "route_11", "vermilion_city", "ss_anne",
                     "digletts_cave", "route_12", "route_13", "route_14",
                     "route_15", "fuchsia_city", "safari_zone", "route_16",
                     "route_17", "route_18", "route_19", "route_20",
                     "route_21", "cinnabar_island", "pokemon_mansion",
                     "celadon_city", "rocket_hideout", "silph_co", "route_9",
                     "power_plant", "cerulean_cave", "seafoam_islands",
                     "route_22", "route_23", "indigo_plateau",
                     "kanto_pokemon_league"]
    AREA_RANK = {a: i for i, a in enumerate(AREA_PRIORITY)}

    species_idx_to_areas: dict[int, list[str]] = {}
    for area, rec_ids in pokedex_obtain["area_to_pokedex_rec_ids"].items():
        for rid in rec_ids:
            sp = rec_to_species.get(rid)
            if sp is None:
                continue
            species_idx_to_areas.setdefault(sp, []).append(area)

    def species_first_area(species_idx: int) -> tuple[str, list[str]]:
        """Returns (firstArea, allAreas). species_idx is the species ID, which
        is both the gSpeciesNames index and pokedex_table's species_index
        (0 = SPECIES_NONE, 1 = BULBASAUR)."""
        areas = species_idx_to_areas.get(species_idx, [])
        if not areas:
            return ("rom_mined", [])
        # Find lowest-rank
        ranked = sorted(set(areas), key=lambda a: AREA_RANK.get(a, 999))
        return (ranked[0], list(set(areas)))

    # Starter overrides (Bulbasaur=1, Charmander=4, Squirtle=7) and full
    # evolution chains -> pallet_town
    STARTER_FAMILIES = {1, 2, 3, 4, 5, 6, 7, 8, 9}
    # Eevee + evolutions: Eevee=133, Vaporeon=134, Jolteon=135, Flareon=136
    EEVEE_FAMILY = {133, 134, 135, 136}
    # Other "given" pokemon: Lapras=131 (given in Silph Co), Hitmonlee/Chan=106/107 (Saffron)
    GIVEN_OVERRIDES = {
        131: "silph_co",
        106: "saffron_city",
        107: "saffron_city",
        # Old amber -> Aerodactyl=142
        142: "pewter_city",
        # Magikarp from Old Man -> Magikarp=129, Gyarados=130
        # (Old Man in Route 4 in canonical, but accessible from cerulean)
        # default keeps wild encounter areas
        # Snorlax=143 sleeps on routes 12 and 16
        143: "route_12",
        # Articuno=144, Zapdos=145, Moltres=146
        144: "seafoam_islands",
        145: "power_plant",
        146: "victory_road" if "victory_road" in AREA_RANK else "route_23",
        # Mewtwo=150
        150: "cerulean_cave",
        # Mew=151 not obtainable normally
        151: "rom_mined",
    }

    # === Build move cards ===
    ko_to_en_moves = {}
    for mid, data in moves_ko.items():
        k = data.get("ko")
        if k and "en" in data:
            ko_to_en_moves[k] = data["en"]
    move_cards = []
    move_sentences = []
    for i in range(GMOVE_NAMES_N):
        cps = read_codepoints(rom, GMOVE_NAMES + i * GMOVE_NAMES_STRIDE,
                              GMOVE_NAMES_STRIDE)
        text = "".join(cp_map.get(cp, f"<{cp:04X}>") for cp in cps)
        if not is_clean_korean(text):
            continue
        if i == 0:
            continue
        en = ko_to_en_moves.get(text) or moves_ko.get(i, {}).get("en", "")
        en_clean = en.replace("-", " ").title() if en else "(unknown)"
        first_area = MOVE_ID_TO_AREA.get(i, "rom_mined")
        # Validate area exists (fall back to rom_mined)
        if first_area not in AREA_RANK and first_area != "rom_mined":
            first_area = "rom_mined"
        # Frequency = byte-occurrence count in ROM
        encoded = b""
        for cp in cps:
            encoded += bytes([cp >> 8, cp & 0xFF])
        freq = rom.count(encoded) if encoded else 1
        card = {
            "korean": text,
            "romanization": romanize(text),
            "gloss": f"{en_clean} (move)" if en_clean else "(move)",
            "partOfSpeech": "noun",
            "areaId": "rom_mined",
            "frequency": freq,
            "firstAreaEncountered": first_area,
            "areasReferenced": [first_area] if first_area != "rom_mined" else [],
            "sourceTypes": ["pokemon_move"],
            "primarySourceType": "pokemon_move",
            "source": f"gMoveNames[{i}]",
        }
        move_cards.append(card)
        move_sentences.append({
            "vocabId": f"rom-mine-v3:{text}",
            "korean": f"{text}을(를) 사용했다.",
            "romanization": f"{romanize(text)}eul/leul sayonghaessda.",
            "gloss": f"Used {en_clean}.",
            "targetForm": text,
            "areaId": first_area,
            "sourceTypes": ["pokemon_move"],
            "primarySourceType": "pokemon_move",
            "source": f"gMoveNames[{i}]",
        })

    # === Build ability cards ===
    ko_to_en_abilities = {}
    for aid, data in abilities_ko.items():
        k = data.get("ko")
        if k and "en" in data:
            ko_to_en_abilities[k] = data["en"]
    ability_cards = []
    ability_sentences = []
    for i in range(GABILITY_NAMES_N):
        cps = read_codepoints(rom, GABILITY_NAMES + i * GABILITY_NAMES_STRIDE,
                              GABILITY_NAMES_STRIDE)
        text = "".join(cp_map.get(cp, f"<{cp:04X}>") for cp in cps)
        if not is_clean_korean(text):
            continue
        if i == 0:
            continue
        en = ko_to_en_abilities.get(text) or abilities_ko.get(i, {}).get("en", "")
        en_clean = en.replace("-", " ").title() if en else "(unknown)"
        encoded = b""
        for cp in cps:
            encoded += bytes([cp >> 8, cp & 0xFF])
        freq = rom.count(encoded) if encoded else 1
        card = {
            "korean": text,
            "romanization": romanize(text),
            "gloss": f"{en_clean} (ability)" if en_clean else "(ability)",
            "partOfSpeech": "noun",
            "areaId": "rom_mined",
            "frequency": freq,
            "firstAreaEncountered": "rom_mined",
            "areasReferenced": [],
            "sourceTypes": ["pokemon_ability"],
            "primarySourceType": "pokemon_ability",
            "source": f"gAbilityNames[{i}]",
        }
        ability_cards.append(card)
        ability_sentences.append({
            "vocabId": f"rom-mine-v3:{text}",
            "korean": f"{text}의 효과로 적이 약해졌다.",
            "romanization": f"{romanize(text)}ui hyogwaro jeogi yakhaejyeossda.",
            "gloss": f"The opponent was weakened by {en_clean}.",
            "targetForm": text,
            "areaId": "rom_mined",
            "sourceTypes": ["pokemon_ability"],
            "primarySourceType": "pokemon_ability",
            "source": f"gAbilityNames[{i}]",
        })

    # === Build species cards ===
    # Build reverse lookup ko -> en so glosses are accurate even when the
    # patch's species ordering doesn't match PokeAPI numerical id (Hoenn).
    ko_to_en_species = {}
    for sid, data in species_ko.items():
        k = data.get("ko")
        if k and "en" in data:
            ko_to_en_species[k] = data["en"]
    species_cards = []
    species_sentences = []
    for i in range(GSPECIES_NAMES_N):
        cps = read_codepoints(rom, GSPECIES_NAMES + i * GSPECIES_NAMES_STRIDE,
                              GSPECIES_NAMES_STRIDE)
        text = "".join(cp_map.get(cp, f"<{cp:04X}>") for cp in cps)
        if not is_clean_korean(text):
            continue
        if i == 0:
            continue
        # Prefer reverse-lookup gloss (matches the Korean name regardless of
        # ROM index)
        en = ko_to_en_species.get(text) or species_ko.get(i, {}).get("en", "")
        # Override for starters/given
        if i in STARTER_FAMILIES:
            first_area = "pallet_town"
            ref_areas = ["pallet_town"]
        elif i in GIVEN_OVERRIDES:
            first_area = GIVEN_OVERRIDES[i]
            if first_area not in AREA_RANK and first_area != "rom_mined":
                first_area = "rom_mined"
            ref_areas = [first_area] if first_area != "rom_mined" else []
        else:
            # pokedex_table species_index is the species ID itself: entry 0
            # is the SPECIES_NONE placeholder ("새로이 ... 포켓몬 조사") and
            # entry 1 is Bulbasaur's dex text, same as gSpeciesNames[i].
            pdx_idx = i
            first_area, ref_areas = species_first_area(pdx_idx)
        # Validate
        if first_area not in AREA_RANK and first_area != "rom_mined":
            first_area = "rom_mined"
            ref_areas = []
        encoded = b""
        for cp in cps:
            encoded += bytes([cp >> 8, cp & 0xFF])
        freq = rom.count(encoded) if encoded else 1
        card = {
            "korean": text,
            "romanization": romanize(text),
            "gloss": f"{en} (Pokemon)" if en else "(Pokemon)",
            "partOfSpeech": "noun",
            "areaId": "rom_mined",
            "frequency": freq,
            "firstAreaEncountered": first_area,
            "areasReferenced": ref_areas,
            "sourceTypes": ["pokemon_species"],
            "primarySourceType": "pokemon_species",
            "source": f"gSpeciesNames[{i}]",
        }
        species_cards.append(card)
        species_sentences.append({
            "vocabId": f"rom-species-2024:{text}",
            "korean": f"{text}이(가) 나타났다!",
            "romanization": f"{romanize(text)}i/ga natanassda!",
            "gloss": f"A wild {en} appeared!" if en else f"A wild Pokemon appeared!",
            "targetForm": text,
            "areaId": first_area,
            "sourceTypes": ["pokemon_species"],
            "primarySourceType": "pokemon_species",
            "source": f"gSpeciesNames[{i}]",
        })

    # === Write outputs ===
    # Combined moves+abilities card pack
    rom_names_out = HERE / "seed-vocab-ko-rom-names.json"
    rom_names_sents = HERE / "sentences-ko-rom-names.json"
    species_out = HERE / "seed-vocab-ko-species.json"
    species_sents = HERE / "sentences-ko-species.json"

    rom_names_out.write_text(json.dumps({
        "version": 1,
        "sourceTag": "rom-names-2024",
        "notes": [
            "Korean move + ability names extracted from gMoveNames @ 0x2470E0 "
            "and gAbilityNames @ 0x24FC8C in the 2024 patched ROM. Each entry "
            "is a sequence of 16-bit BE codepoints into a custom hangul font "
            "table. The codepoint -> hangul map was triangulated against "
            "PokeAPI canonical Korean names.",
            f"Move cards: {len(move_cards)}, Ability cards: {len(ability_cards)}.",
            "Move firstAreaEncountered is set from the canonical FRLG TM/HM "
            "acquisition area when applicable, else rom_mined.",
            "Ability firstAreaEncountered defaults to rom_mined.",
        ],
        "entries": move_cards + ability_cards,
    }, ensure_ascii=False, indent=1))
    rom_names_sents.write_text(json.dumps({
        "version": 1,
        "sourceTag": "rom-names-2024",
        "notes": ["Auto-generated example sentences for move + ability cards."],
        "entries": move_sentences + ability_sentences,
    }, ensure_ascii=False, indent=1))

    species_out.write_text(json.dumps({
        "version": 1,
        "sourceTag": "rom-species-2024",
        "notes": [
            "Korean species names extracted from gSpeciesNames @ 0x245F2C in "
            "the 2024 patched ROM. Each card represents one Pokemon species.",
            f"Species cards: {len(species_cards)}.",
            "firstAreaEncountered set from wild-encounter pokedex_obtain_index "
            "where available; starters -> pallet_town; legendaries / given "
            "Pokemon use canonical FRLG areas.",
            "This deck is a separate study mode (proper-noun corpus) and is "
            "not currently loaded by the app.",
        ],
        "entries": species_cards,
    }, ensure_ascii=False, indent=1))
    species_sents.write_text(json.dumps({
        "version": 1,
        "sourceTag": "rom-species-2024",
        "notes": ["Auto-generated example sentences for species cards."],
        "entries": species_sentences,
    }, ensure_ascii=False, indent=1))

    print(f"  moves: {len(move_cards)} cards -> {rom_names_out.name}")
    print(f"  abilities: {len(ability_cards)} cards -> {rom_names_out.name}")
    print(f"  species: {len(species_cards)} cards -> {species_out.name}")
    # Coverage
    nfa_moves = sum(1 for c in move_cards if c["firstAreaEncountered"] not in (None, "rom_mined"))
    nfa_abil = sum(1 for c in ability_cards if c["firstAreaEncountered"] not in (None, "rom_mined"))
    nfa_spec = sum(1 for c in species_cards if c["firstAreaEncountered"] not in (None, "rom_mined"))
    print(f"\n  area-attributed: moves={nfa_moves}/{len(move_cards)}, "
          f"abilities={nfa_abil}/{len(ability_cards)}, "
          f"species={nfa_spec}/{len(species_cards)}")
    null_count = sum(1 for c in move_cards + ability_cards + species_cards
                     if c["firstAreaEncountered"] is None)
    print(f"  null firstAreaEncountered: {null_count} (must be 0)")


if __name__ == "__main__":
    main()
