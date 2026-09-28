# Test save states

Emulator save states for skipping the new-game intro when testing on an
emulator or device. The `.bin`/`.meta` files are game memory, so they're
gitignored like the other save fixtures; copy them between machines by hand.

Restore with `tools/test_states/push.sh [set] [serial]`, relaunch the app,
then Settings -> Save states -> Load.

Only game state is saved. Moneo card progress, visited areas and settings
live in the app's own storage, so reset those separately (e.g.
`adb shell pm clear com.poketrek` for a clean slate, then re-pick the ROM).

## kr2024 (LeafGreen Korean 2024 patch, CRC 0x4A38A8CB)

| Slot | State |
|---|---|
| 1 | New game (player "A", rival "AAAAAAA"), in the bedroom in Pallet Town. No Pokémon yet. |
| 2 | Bulbasaur Lv6, rival beaten. Standing on Pallet's north exit, one step below Route 1: pressing Up tests the area gate's first boundary. |
| 3 | Slot 2's spot, facing the Pallet NPC with her message box open ("간판은 도움이 되지!"): the reading helper panel shows on load. |

Named states (load with `load_slot.sh <file> <slot>`):

| File | State |
|---|---|
| `title_with_save.bin` | Title screen; the in-game save is in Pallet with Bulbasaur. Base for `teleport.py`. |
| `viridian_pc.bin` | Viridian City, at the Pokémon Center door. |
| `viridian_mart_after_parcel.bin` | Inside the Viridian Mart, Oak's Parcel already received. |

## Teleporting

`teleport.py title_with_save.bin <group> <num> <x> <y> out.bin` makes Continue
drop the player anywhere (map numbers as in pokefirered's
`data/maps/map_groups.json`; see the script's docstring). Then
`load_slot.sh out.bin 3`, load slot 3, press START until the Continue menu,
A, then B to skip the recap. Story flags stay those of the save, so NPCs
behave as early-game (e.g. the Mart won't sell until the Parcel is delivered).

