#!/usr/bin/env python3
"""Make an emulator save state Continue anywhere on the map.

    tools/test_states/teleport.py <state.bin> <group> <num> <x> <y> [out.bin|out.sav]

<state.bin> must be an mGBA save state taken at the title screen of a game
that has an in-game save (kr2024/title_with_save.bin). The script edits the
newest save inside the state's SAVEDATA block: it sets
SaveBlock1.continueGameWarp to (group, num, x, y) and the CONTINUE_GAME_WARP
bit of SaveBlock2.specialSaveWarpFlags, then fixes both sector checksums.
That's the path the game itself uses (pokefirered overworld.c
CB2_ContinueSavedGame), so choosing Continue puts the player there.

Map numbers are pokefirered's map_groups order (the 2024 KR patch uses the
same numbering): e.g. Viridian City is 3 1, with warp tiles listed in
data/maps/ViridianCity/map.json -- stand one tile below a door (y+1).

An out path ending in .sav writes just the 128 KB battery save. Install it
with push_save.sh: the app keeps each ROM's in-game save in
files/saves/<crc>.sav and only takes a slot's copy while that file is blank,
so pushing the .sav is the reliable way. Then relaunch, START until the
Continue menu appears, A, and B to skip the recap.
"""
import struct
import sys

SAVEDATA = 0x61030  # EXTDATA_SAVEDATA payload offset in a PokeTrek/mGBA GBA state
SIG = 0x08012025


def checksum(sector: bytes) -> int:
    s = sum(struct.unpack_from("<1020I", sector, 0)) & 0xFFFFFFFF
    return ((s >> 16) + s) & 0xFFFF


def main():
    src, g, n, x, y = sys.argv[1], *map(int, sys.argv[2:6])
    out = sys.argv[6] if len(sys.argv) > 6 else src
    st = bytearray(open(src, "rb").read())
    newest = {}
    for slot in (0, 0xE000):
        for i in range(14):
            o = SAVEDATA + slot + i * 0x1000
            sid, _chk, sig, idx = struct.unpack_from("<HHII", st, o + 0xFF4)
            if sig == SIG and (sid not in newest or idx > newest[sid][1]):
                newest[sid] = (o, idx)
    if 0 not in newest or 1 not in newest:
        sys.exit("no in-game save in this state")
    o0, o1 = newest[0][0], newest[1][0]
    struct.pack_into("<bbbbhh", st, o1 + 0xC, g, n, -1, 0, x, y)  # continueGameWarp
    st[o0 + 9] |= 1                                                # CONTINUE_GAME_WARP
    for o in (o0, o1):
        struct.pack_into("<H", st, o + 0xFF6, checksum(st[o:o + 0x1000]))
    open(out, "wb").write(st[SAVEDATA:SAVEDATA + 0x20000] if out.endswith(".sav") else st)
    print(f"Continue -> map {g}:{n} ({x},{y}) in {out}")


if __name__ == "__main__":
    main()
