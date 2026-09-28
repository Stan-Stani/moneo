"""Decoder for dialog text in the 2024 Korean LeafGreen patch.

The patch keeps Gen 3's 1-byte charset for everything that isn't hangul
(space = 00, digits A1-AA, punctuation, FC/FD control codes, FE newline,
FF end-of-string) and adds 2-byte hangul syllables whose lead byte is
0x37..0x41. Text is therefore variable-width: a space or punctuation
mark is one byte, a syllable is two.

scan_rom_2024.read_message() stepped two bytes at a time, so after the
first 1-byte character it read pairs straddling two characters and
emitted "·" until it happened to realign. About half of every sentence
was lost that way (e.g. "쓰레기통에··· 나무열매가 ···있었는데···" for
"쓰레기통에 이상한 나무열매가 / 버려져 있었는데 뭐지?").
"""
from __future__ import annotations

import json
from pathlib import Path

CODEPOINT_MAP = Path(__file__).resolve().parent / "rom_swap/codepoint_map.json"

HANGUL_LEAD_LO, HANGUL_LEAD_HI = 0x37, 0x41

EOS = 0xFF
NEWLINE = 0xFE
PLACEHOLDER = 0xFD      # FD xx: player / rival / string buffer
EXT_CTRL = 0xFC         # FC code args...: colors, pauses, sounds
PROMPT_CLEAR = 0xFB
PROMPT_SCROLL = 0xFA
EXTRA_SYMBOL = 0xF9     # F9 xx: extra glyph (arrows, etc.)

# pokefirered string_util.c GetExtCtrlCodeLength: bytes after the FC,
# including the code byte itself.
EXT_CTRL_LENGTHS = [1, 2, 2, 2, 4, 2, 2, 1, 2, 1, 1, 3, 2, 2, 2, 1, 3, 2, 2, 2, 2, 1, 1, 1, 1]

PLACEHOLDERS = {0x01: "{PLAYER}", 0x02: "{STR_VAR_1}", 0x03: "{STR_VAR_2}",
                0x04: "{STR_VAR_3}", 0x06: "{RIVAL}"}

# Josa codes the patch emits after a name buffer ({PLAYER}, {STR_VAR_1}...),
# whose final consonant isn't known until runtime. The text engine
# (0x0872BA00) picks the glyph from a (after-consonant, after-vowel) table at
# 0x0872BC0C using a batchim flag saved from the previous syllable; rendered
# here in the usual dictionary notation.
PARTICLES = {0x41ED: "아(야)", 0x41EE: "과(와)", 0x41EF: "은(는)", 0x41F0: "을(를)",
             0x41F1: "이(가)", 0x41F2: "(이)", 0x41F3: "(이)", 0x41F4: "(으)"}

# Gen 3 1-byte charset (pokefirered charmap.txt), the printable subset
# Korean dialog actually uses.
ONE_BYTE = {0x00: " ", 0xAB: "!", 0xAC: "?", 0xAD: ".", 0xAE: "-", 0xAF: "·",
            0xB0: "…", 0xB1: "“", 0xB2: "”", 0xB3: "‘", 0xB4: "’", 0xB5: "♂",
            0xB6: "♀", 0xB7: "¥", 0xB8: ",", 0xB9: "×", 0xBA: "/", 0xF0: ":",
            0x5B: "%", 0x5C: "(", 0x5D: ")", 0x85: "<", 0x86: ">"}
ONE_BYTE.update({0xA1 + d: str(d) for d in range(10)})
ONE_BYTE.update({0xBB + i: chr(ord("A") + i) for i in range(26)})
ONE_BYTE.update({0xD5 + i: chr(ord("a") + i) for i in range(26)})


def load_codepoint_map(path: Path = CODEPOINT_MAP) -> dict[int, str]:
    raw = json.loads(path.read_text())
    cmap = {int(k, 16): v for k, v in raw.items() if not k.endswith("00")}
    cmap.update(PARTICLES)
    return cmap


def decode(rom: bytes, off: int, cmap: dict[int, str], max_len: int = 1200):
    """Decode the string at `off`. Returns (text, end_off, n_unknown), where
    end_off is the offset of the terminating 0xFF (None if not found within
    max_len) and n_unknown counts bytes/syllables with no mapping."""
    out: list[str] = []
    unknown = 0
    i, end = off, min(len(rom), off + max_len)
    while i < end:
        b = rom[i]
        if b == EOS:
            return "".join(out), i, unknown
        if HANGUL_LEAD_LO <= b <= HANGUL_LEAD_HI and i + 1 < end:
            cp = (b << 8) | rom[i + 1]
            ch = cmap.get(cp)
            if ch is None:
                out.append(f"[{cp:04X}]")
                unknown += 1
            else:
                out.append(ch)
            i += 2
        elif b in (NEWLINE, PROMPT_SCROLL, PROMPT_CLEAR):
            out.append("\n")
            i += 1
        elif b == PLACEHOLDER:
            out.append(PLACEHOLDERS.get(rom[i + 1], "{VAR}"))
            i += 2
        elif b == EXT_CTRL:
            code = rom[i + 1]
            i += 1 + (EXT_CTRL_LENGTHS[code] if code < len(EXT_CTRL_LENGTHS) else 1)
        elif b == EXTRA_SYMBOL:
            i += 2
        elif b in ONE_BYTE:
            out.append(ONE_BYTE[b])
            i += 1
        else:
            out.append(f"<{b:02X}>")
            unknown += 1
            i += 1
    return "".join(out), None, unknown
