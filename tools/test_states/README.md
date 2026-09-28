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
