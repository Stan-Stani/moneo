"""Centralized ROM offsets + path for the moneo attribution pipeline.

Switch ROMs by editing ROM_PATH and the offsets below. The 2024-02-29 Korean
patch (BPGE-canonical) values are the current defaults.
"""
from __future__ import annotations
import struct  # noqa: F401  (re-exported for callers)
from pathlib import Path

THIS_DIR = Path(__file__).resolve().parent
ROOT = THIS_DIR.parent.parent

# === ROM path ===
ROM_PATH = ROOT / "tools/moneo/rom_swap/leafgreen_J-K_2024.gba"

# Legacy path for the 2010 fan-translation. Kept as documentation; do not use
# from new code.
ROM_PATH_2010 = ROOT / "Pocket Monsters - LeafGreen (Korean).gba"

# === ROM identity (2024-02-29 patch) ===
ROM_CRC32_2024 = 0x4A38A8CB
ROM_MD5_2024 = "4c9e97dab5c8d80c1448dfcace442a2d"

# === GBA addressing ===
GBA_BASE = 0x08000000

# === Table offsets (2024 patch) ===
# All discovered via `tools/moneo/rom_swap/find_offsets_2024.py`.
# See `tools/moneo/rom_swap/OFFSETS_2024.md` for derivation notes.
GMAP_GROUPS = 0x3526F8        # 43 group ptrs, canonical pokefirered order
GITEMS = 0x3DAED4             # 44-byte stride (canonical pokefirered)
GITEMS_STRIDE = 44
GITEMS_DESC_OFF = 20          # description ptr field offset within Item struct
GITEMS_NAME_OFF = 0           # name field
GITEMS_ITEMID_OFF = 14        # itemId u16 field
GITEMS_PRICE_OFF = 16
GITEMS_POCKET_OFF = 26
GITEMS_N_ENTRIES = 375        # gItems[0..374]

GPOKEDEX_ENTRIES = 0x44E2E0   # 36-byte stride (canonical pokefirered)
GPOKEDEX_STRIDE = 36
GPOKEDEX_DESC_OFF = 16
GPOKEDEX_N_ENTRIES = 387

GTRAINERS = 0x23EB3C          # 40-byte stride (canonical pokefirered)
GTRAINERS_STRIDE = 40
GTRAINERS_FLAGS_OFF = 0
GTRAINERS_CLASS_OFF = 1
GTRAINERS_NAME_OFF = 4        # name (12 bytes)
GTRAINERS_PARTYSIZE_OFF = 32
GTRAINERS_PARTY_OFF = 36
GTRAINERS_N_ENTRIES = 743

GTRAINER_CLASS_NAMES = 0x23E5A4  # 13-byte stride (canonical pokefirered)
GTRAINER_CLASS_NAMES_STRIDE = 13
GTRAINER_CLASS_NAMES_N = 107

GWILD_MON_HEADERS = 0x3C9B64  # 20-byte stride
GWILD_STRIDE = 20

# === gWildMonHeaders -> gMapGroups indexing ===
# The 2024 patch keeps pokefirered's canonical group order (43 groups, 425
# maps, Link=0, Dungeons=1, TownsAndRoutes=3). An earlier GMAP_GROUPS value
# pointed two entries into the table, which made it look like Link+Dungeons
# had been dropped and required a -2 correction here. With GMAP_GROUPS fixed,
# gWildMonHeaders.mapGroup maps 1:1 onto walker groups.
KOREAN_GROUP_OFFSET = 0

# === Trainer-dialog region ===
# In the 2010 ROM this was 0x163000-0x166000. The 2024 ROM moved everything;
# scan a wider region. The walker should reach most trainer text via script
# bytecode walking, so the standalone region scan is a fallback.
TRAINER_DIALOG_REGIONS_2024 = [(0x230000, 0x240000)]

# === Helper functions ===
def u32(rom: bytes, off: int) -> int:
    return struct.unpack_from("<I", rom, off)[0]


def u16(rom: bytes, off: int) -> int:
    return struct.unpack_from("<H", rom, off)[0]


def is_rom_ptr(p: int, rom_len: int) -> bool:
    return GBA_BASE <= p < GBA_BASE + rom_len


def get_group_offsets(rom: bytes) -> list[int]:
    """Return the list of group-table offsets (file-relative) for gMapGroups,
    plus a sentinel for the last group's end.

    Reads gMapGroups[] at GMAP_GROUPS, dereferences each pointer to file
    offset, returns [g0_off, g1_off, ..., g(N-1)_off, GMAP_GROUPS] where
    GMAP_GROUPS itself terminates the last group's array.
    """
    offs = []
    for i in range(60):  # canonical FRLG has up to 44; Korean fan-build has 41
        p = u32(rom, GMAP_GROUPS + i * 4)
        if not is_rom_ptr(p, len(rom)):
            break
        offs.append(p - GBA_BASE)
    offs.append(GMAP_GROUPS)
    return offs


def load_rom() -> bytes:
    return ROM_PATH.read_bytes()
