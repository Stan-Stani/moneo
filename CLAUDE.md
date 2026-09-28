# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Android app that turns the phone's hardware step counter into a movement budget for an embedded Pokémon LeafGreen (mGBA) emulator. Real-world steps credit tiles; the overworld direction-pad is masked when the budget is exhausted. Full design: `~/.claude/plans/i-would-like-to-inherited-papert.md`.

## Build / run / test

```bash
# Build + install debug APK to the connected device/emulator
./gradlew installDebug

# Just compile (much faster than installDebug)
./gradlew assembleDebug

# JVM unit tests (movement-gate logic, ratio math, RomIdentity)
./gradlew test

# Phase 0 / native instrumentation tests — requires app/src/androidTest/assets/leafgreen.gba
./gradlew connectedDebugAndroidTest

# Single instrumentation test class
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.poketrek.emu.Phase0EmulatorEmbedTest
```

`adb` is not on PATH. Use `~/Library/Android/sdk/platform-tools/adb`. App package + launcher: `com.poketrek/.EmulatorActivity`.

## Testing on emulator vs device

There is usually an Android emulator running (`emulator-5554`). It is fine for UI/build smoke tests but **does not expose `Sensor.TYPE_STEP_COUNTER`** — the foreground service registers and immediately logs that the sensor is unavailable. To exercise the budget on emulator, use the in-app **debug overlay** (Settings → "Debug overlay" toggle → "+10 fake steps") or call `MovementBudget.debugAddSteps` directly. Real step-counter behaviour can only be validated on a physical Android 8.0+ device with a pedometer. Verified on a **Samsung Galaxy S20+** (real-walk step-gating + save/restore); other devices not yet exercised.

Iteration loop on emulator (rebuild → reinstall → relaunch → screenshot):

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
./gradlew installDebug && \
  $ADB shell am force-stop com.poketrek && \
  $ADB shell am start -n com.poketrek/.EmulatorActivity && \
  sleep 2 && $ADB exec-out screencap -p > /tmp/poketrek.png
```

Logcat tags worth watching: `EmulatorActivity`, `EmulatorRunner`, `poketrek-jni`, `StepCounterService`, `StepSensor`, `SaveStateStore`.

## Architecture

Three layers, top-down:

1. **Compose UI** (`com.poketrek.ui`) — `EmulatorScreen` overlays a Canvas-drawn framebuffer with floating D-pad, action buttons, settings sheet, and HUD. The framebuffer is letterboxed by `drawFramebuffer`; controls live above it. UI never touches the native core directly.
2. **Kotlin runtime** (`com.poketrek.emu`, `com.poketrek.step`) — `EmulatorRunner` owns a daemon thread that ticks the emulator at 59.7275 Hz (`FRAME_PERIOD_NS = 16_750_419`), calls `MovementGate.process` against a fresh `LeafGreenRam.Snapshot`, sets keys, runs one frame, copies the framebuffer into a Bitmap (via a reusable direct ByteBuffer), and pushes audio into an `AudioTrack`. `MovementBudget` is a process-wide singleton holding the StateFlows the UI/service share; persistence is DataStore Preferences.
3. **Native** (`app/src/main/cpp`) — `jni_bridge.cpp` holds a single `std::unique_ptr<Emulator> g_emulator` (the JNI surface is intentionally singleton). `libmgba.a` is built statically from the vendored submodule at `third_party/mgba`; `libpoketrek.so` is the only `.so` shipped.

### Step-gating data flow (the core feature)

```
Hardware sensor ─► StepSensor ─► MovementBudget.onSensorValue(cumulative)
                                    │ delta + reboot rebase
                                    ▼
                                 creditTiles(num/den, carry)  ◄── pure, unit-tested
                                    │ adds whole tiles, persists carry remainder
                                    ▼
                                 budget StateFlow + creditedTiles SharedFlow
                                    │                  │
                                    │                  └─► StepCounterService haptic pulse
                                    ▼
                                 HUD badge ("TILES n")

EmulatorRunner frame loop ─► LeafGreenRam.read(native)  (deref SaveBlock1 ptr at 0x03005008)
                                    ▼
                             MovementGate.process(rawKeys, snapshot)
                                    │ consume 1 tile if (X|Y changed) ∧ (mapBank/mapId unchanged) ∧ (direction held last frame)
                                    │ mask DPAD bits if gateEnabled ∧ budget == 0
                                    ▼
                             native.setKeys(masked) → mCore->setKeys
```

The "direction held last frame" guard is what keeps cutscenes, ledge hops, ice tiles, and warps from burning the budget. Same-map check filters door warps. **Do not loosen these conditions without tests** — `MovementGateTest` covers the matrix.

### ROM identity & gating

`RomIdentity.of(bytes)` CRC32s the loaded ROM and maps it to a `RomVariant`. `gatingSupported = true` only means "ships pre-calibrated", which is just `LEAFGREEN_US_REV1` (CRC `0xDAFFECEC`). Other ROMs (e.g. the Korean 2024 patch, `LEAFGREEN_KR_2024`) become gateable through runtime calibration: `RomCalibrator` diffs EWRAM across a one-tile step to find the SaveBlock1 pointer in IWRAM, and `CalibrationStore` persists the result per ROM. Until a calibration exists, `EmulatorRunner.runLoop` bypasses the gate (`rawKeys` passes straight through), because gating against wrong RAM addresses would burn the budget, and the HUD shows a yellow warning. `areaGateSupported` (moneo's area gate) is separate and true for both US Rev 1 and KR 2024.

## JNI / native gotchas

- `mCoreInitConfig(core, nullptr)` **must** run before `core->init()`. Skipping it crashes inside the game-DB lookup with an uninitialized hash table.
- ROM bytes are copied into `Emulator::romCopy` because mGBA may keep the pointer past `loadROM` and JNI byte[] lifetime is unreliable. Mirrors what `platform/libretro/libretro.c` does.
- Every JNI entry that touches the core grabs `g_emulator->mutex` so RAM probes / audio polls don't race the frame thread.
- `setKeys` is the only JNI call deliberately *not* locked — it's a single atomic store inside mGBA and the Kotlin side already serialises via `AtomicInteger keys`.
- Linker uses `-Wl,-z,max-page-size=16384` for Android 15+ 16KB-page devices; don't drop it.
- The Phase 0 determinism test (`framebufferHashIsDeterministic`) is the canary for any emulator-state leakage between instances. If it starts failing after a change to native init/teardown, that's the bug.

## ROM handling

ROMs are gitignored (`*.gba`, `*.sav`, `*.savestate`). At runtime the user picks via Storage Access Framework (`OpenDocument`) and the URI permission is persisted. The two `.gba` files at the repo root are dev convenience copies, not committed. For `connectedAndroidTest`, drop `app/src/androidTest/assets/leafgreen.gba` manually.

The moneo gloss pipeline reads two ROMs at the repo root:
- `Pokemon - LeafGreen Version (USA, Europe) (Rev 1).gba` — canonical EN
  (CRC `0xDAFFECEC`). Consumed by `tools/moneo/scan_rom_en.py` and
  `tools/moneo/build_name_table_decks_en.py`. Offsets live in
  `tools/moneo/rom_config_en.py`; verify with `find_offsets_en.py`.
- `tools/moneo/rom_swap/leafgreen_J-K_2024.gba` — Korean 2024 patch
  (CRC `0x4A38A8CB`). The KR scanner is `scan_rom_2024.py`.

After running the EN extractor, `tools/moneo/restructure_glosses.py`
pairs ROM-anchored Korean cards with the canonical EN headword and
splits semicolon-delimited senses into a `senses[]` field. The rebuild
is idempotent — re-running with no changes leaves the assets identical.

## Conventions worth knowing

- Java 17 / Kotlin 2.1 / Compose BOM 2026.03 / AGP 9.1 / NDK `27.2.12479018` / minSdk 26 / targetSdk 35. ABIs: `arm64-v8a`, `x86_64`.
- Versions live in `gradle/libs.versions.toml` — add new deps there, not as ad-hoc strings in `app/build.gradle.kts`.
- Compose state for cross-thread reads from the emulator loop uses plain `mutableIntStateOf`/`mutableStateOf` (not StateFlow); StateFlow is reserved for `MovementBudget` because it's shared with the foreground service.
- The `MovementGateBudget` interface exists so `MovementGate` is testable on the JVM without an Android `Context`. Don't make `MovementGate` depend on `MovementBudget` directly.
- The mGBA submodule at `third_party/mgba` is pinned; do not bump it casually — the JNI bridge depends on the current `mCore` C ABI.

## Workflow

**Commit regularly.** After each logical chunk of work that builds and passes tests, make a focused commit before moving on — don't accumulate large multi-feature diffs. Good commit boundaries: one bug fix, one small feature, one config tweak, one set of related test changes. If the work-in-progress doesn't yet build, finish it before committing rather than committing broken code. Treat `git status` showing many unrelated modified files as a smell to address.
