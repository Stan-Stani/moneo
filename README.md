# Moneo · 몬어

Improve your Korean reading skill by playing Pokemon Leaf Green!

Moneo is a project I started vibe-coding (making with AI) a few months back. I recently picked it back up and added a few more features and bug fixes. Originally my idea was just for a Pokemon game to require a certain amount of real world steps to allow you to keep playing (since I think Pokemon Go's gameplay is absolutely atrocious). That idea morphed into a Korean learner friendly Pokemon game in Korean.

There were some false starts, like starting with an older less complete fan translation ROM hack before finding the newer one referenced below. And reverse engineering the ROM for getting vocabulary and memory locations of things consisted entirely of me throwing ideas at Claude like "use the FireRed decompilation project for reference".

**See it in action:** the [blog post](https://www.seldoncortex.com) walks through every feature with screenshots, or jump straight to the videos: [reading helper + asking Claude](https://www.seldoncortex.com/blog/moneo/askvid.mp4), [area vocab gate](https://www.seldoncortex.com/blog/moneo/gatevid.mp4).

An Android app for learning Korean by reading the 2024 fan-translation of Pokémon LeafGreen. Vocabulary and example sentences are mined from the ROM itself, attributed to the in-game area where they surface, and surfaced as a spaced-repetition deck while you play.

The twist: the emulator is **step-gated**. The phone's hardware step counter feeds a movement budget; every overworld tile you move (via the D-pad, as normal) costs one tile from that budget, and when the budget hits zero the direction-pad is masked until you walk in the real world again. You still play the game — you just can't out-walk yourself. The Korean words you'd be encountering at that point in the story queue up for review. The step-gating layer is called **PokéTrek**; Moneo is the Korean-learning experience that runs on top of it.

## Get the app (for players)

> ⚠️ **Early release.** The whole game plays: step-gating, save states, the Korean flashcards, the area gate, reading help and 💬. It has only been tested on a **Samsung Galaxy S20+**, though, so other phones may have rough edges (step counters especially vary by manufacturer). Download builds from the Releases page below.

You don't need to build anything or know how to code to play.

**What you need:**

- An **Android 8.0+ phone with a hardware step counter** (most phones since ~2016 have one). The step-gating feature only works on a real device — emulators don't report steps.
- Your own **legally-obtained Pokémon LeafGreen ROM**. Moneo never ships a ROM. The Korean learning experience targets the 2024 Korean fan-translation; the English US Rev 1 ROM also runs (but the Korean flashcards stay dormant on it). See [Korean ROM (2024 fan-translation)](#korean-rom-2024-fan-translation) for how to get the Korean ROM.

**Install:**

1. Download the latest APK from the [**Releases page**](https://github.com/Stan-Stani/moneo/releases).
2. On your phone, allow installing from your browser/files app when prompted (Settings → *Install unknown apps*).
3. Open the APK to install, then launch **Moneo**.

**Play:**

1. In the app, go to **Settings → Add ROM** and pick your `.gba` file. Moneo identifies the ROM and turns on the matching features. For Korean, you can instead pick a Japanese LeafGreen 1.0 ROM under **Settings → Set up Korean ROM…** and the app patches it for you ([details](#in-app-setup-recommended)).
2. Play LeafGreen normally with the on-screen D-pad.
3. Every tile you walk in the overworld spends from a **movement budget**. When it runs out, the D-pad locks until you **walk in the real world** — your phone's step counter refills the budget.
4. The Korean words you'd be meeting at that point in the story queue up as a spaced-repetition deck. Review them, then keep playing.

### Optional: ask Claude about the screen (💬)

Stuck on a line? The 💬 button sends the current screen to Claude and shows the answer in a panel beside the game. There are two ways to connect it:

- **Your own API key (simplest):** **Settings → Ask an LLM (💬) → Claude API key**, paste a key from [console.anthropic.com](https://console.anthropic.com). The app calls the Claude API directly; usage is billed to your account and the key stays on the phone.
- **Claude Code in [Termux](https://termux.dev):** no key in the app; described below.

Either way, each question carries a screenshot, the exact text of the open message box (decoded from game memory), the dictionary words in it, and every word you already know from your flashcards. Claude answers in simple Korean built from those known words — ask "in English" if you want English — and a follow-up about the same line keeps the conversation going.

**Termux setup.** The app and Termux talk only through a shared folder, and the app itself needs no API key. In short:

1. In Termux, install Claude Code, `jq` and `inotify-tools`, copy `tools/ask_bridge/moneo-ask.sh` and `moneo-ask-start.sh` onto the phone, and run `moneo-ask-start.sh` (it takes a wake lock; exempt Termux from battery optimisation so it survives a long walk).
2. In the app: **Settings → Ask an LLM (💬) → Choose folder** → pick **Termux** in the picker's side menu → `moneo-ask` → *Use this folder*. The 💬 button appears at the left edge of the game.

**Study words → Claude project.** Whenever you close the flashcards, the app writes `study-words.md` — words you marked *Again*/*Hard* plus words first studied in the last 14 days — into the same folder, and the watcher copies it into a Google Doc ("Moneo study words") through `rclone`. Add that Doc to a claude.ai project's knowledge, and Claude there always knows what you're currently learning.

**Anki too.** A separate companion script, [`anki-korean-sync`](https://github.com/Stan-Stani/anki-korean-sync), does the same for AnkiDroid: it copies the collection off the phone over adb (read-only), and writes the Korean cards you graded *Again*/*Hard* or first studied in the last 14 days into an "Anki Korean study words" Google Doc, which can sit in the same Claude project.

Full setup (proot distros, model choice, the Google Doc sync, testing on a Mac emulator) is in [`tools/ask_bridge/README.md`](tools/ask_bridge/README.md).

Found a wrong or awkward Korean flashcard? Hit the **✎ Report** button on the review screen — it opens a pre-filled GitHub issue.

---

## Building from source (for contributors)

The rest of this README is for people who want to build, modify, or contribute to Moneo. The full design lives at `~/.claude/plans/i-would-like-to-inherited-papert.md`.

### Status

Playable end to end: embedded mGBA, real-walk step-gating, save states, in-app Korean ROM patching, the SRS deck with per-area readiness and the area gate, in-game reading help, and 💬 (Claude API key or Termux). Verified on a Samsung Galaxy S20+; wider device validation is still pending.

CI (`.github/workflows/ci.yml`) runs the JVM unit tests and builds a debug APK on every push and pull request; the APK is attached to each run.

### Prerequisites

- macOS / Linux
- [Android Studio](https://developer.android.com/studio) (Ladybug 2024.2.1 or newer recommended)
- Android NDK `27.2.12479018` (install via Android Studio → SDK Manager → SDK Tools)
- CMake `3.22.1+` (also via SDK Manager)
- A device or emulator running Android 8.0+ (API 26). For step-counter testing, a physical device with a hardware pedometer is required — the Android Emulator does not expose `TYPE_STEP_COUNTER`.

### First-time setup

```bash
# Clone with submodules (mGBA lives under third_party/mgba)
git submodule update --init --recursive

# Drop your legally-obtained LeafGreen ROM here for the emulator instrumentation tests.
# This path is gitignored. The runtime will use a Storage Access Framework picker;
# the test asset is only for `connectedAndroidTest`.
mkdir -p app/src/androidTest/assets
cp /path/to/leafgreen.gba app/src/androidTest/assets/leafgreen.gba
```

Open the project in Android Studio. The first sync will take a while because CMake will configure mGBA.

### Tests

```bash
./gradlew test                        # JVM unit tests (no device, no ROM); what CI runs
./gradlew connectedDebugAndroidTest   # emulator-core tests; need a device and the ROM above
```

`Phase0EmulatorEmbedTest` (named for the project's first milestone) checks the native core:
- `loadsRomAndRunsFrames` — ROM loads, 600 frames execute without crash, framebuffer is non-zero
- `framebufferHashIsDeterministic` — two independent 600-frame runs produce byte-identical framebuffers

The determinism test is the canary for emulator state leaking between instances; if it breaks after a change to native init or teardown, that change is the bug.

### Layout

```
app/
  src/main/cpp/         # JNI bridge + native gate logic
    CMakeLists.txt      # builds libmgba.a (static) + libpoketrek.so (shared)
    jni_bridge.cpp      # loadRom, runFrame, getFramebuffer, ...
    movement_gate.{h,cpp}  # native input filter
  src/main/java/com/poketrek/
    EmulatorActivity.kt    # PokéTrek harness (emulator + step-counter)
    emu/NativeEmulator.kt
    moneo/                 # Moneo: SRS, corpus, review UI, correction reporting
  src/androidTest/java/com/poketrek/emu/
    Phase0EmulatorEmbedTest.kt
tools/moneo/             # Korean text extraction + glyph-map pipeline
tools/ask_bridge/        # 💬 watcher: Claude Code in Termux, study-words → Google Doc
third_party/mgba/        # submodule, pinned tag (see .gitmodules)
```

> **Note on the `com.poketrek` package id**: the Android package is still
> `com.poketrek` for historical reasons (PokéTrek pre-dates Moneo). The
> launcher icon, settings sheet, and notifications all read "Moneo" — the
> package id is just the internal Android identifier and is not
> user-visible. Renaming it would orphan everyone's existing install +
> SRS progress.

## ROM handling

ROM files are never committed to this repository. `*.gba` is in `.gitignore`. The runtime app uses Android's Storage Access Framework so the user picks their own ROM at runtime.

## Korean ROM (2024 fan-translation)

Moneo is built around the **2024 Korean fan-translation** of LeafGreen (CRC32 `0x4A38A8CB`). That ROM is produced by applying an xdelta patch to a Japanese FRLG base — **not** the English one. The fan-translation team built on the Japanese binary because the JP RE community had already done the tile/font work; the filename `leafgreen_J-K_2024.gba` encodes this: **J**apanese base, **K**orean-patched.

The patch and the JP base ROM are both **third-party works**: we don't ship either. You supply your own legally-obtained JP LeafGreen dump; the app (or a desktop script) applies the patch locally.

### In-app setup (recommended)

The app can build the Korean ROM on the phone itself — no computer needed.

1. Acquire a Japanese LeafGreen 1.0 ROM yourself. Moneo does not distribute it.
2. In the app, open **Settings → Korean ROM (2024 patch) → Set up Korean ROM…** and pick the Japanese `.gba`.
3. The app downloads the authors' patch bundle (the same Google Drive link as below), extracts the LeafGreen `.xdelta`, applies it on-device with a bundled xdelta3 decoder, and checks the result is 16 MiB with CRC32 `0x4A38A8CB`. Progress shows as *Downloading patch… → Extracting patch… → Patching ROM… → Verifying…*.
4. On success the Korean ROM is cached and loaded immediately, and Korean flashcard features turn on. If verification fails, the base probably isn't Japanese LeafGreen 1.0 — the error message shows the CRC it got.

Your base ROM never leaves the device; the only network request is the patch download (cached after the first run, and re-fetched once automatically if a cached copy fails to apply).

### Desktop setup (alternative)

If you'd rather patch on a computer and copy the result over:

1. Check your Japanese FRLG 1.0 ROM's MD5 matches `138a71a5be83f3f3d7af3d31916a5fc7` (the patcher will warn you if it doesn't).
2. Fetch the patch zip from the team's [hangulogame.com page](https://www.hangulogame.com/patch/gba/844/) (or [mirror](https://drive.google.com/uc?export=download&id=1PtJ7YplZBdN8Yvb3cw-w9hrt-sT2trPt)) and extract `leafgreen_J-K.xdelta` into `tools/moneo/rom_swap/`. See [`tools/moneo/rom_swap/README.md`](tools/moneo/rom_swap/README.md#whats-here) for a one-liner that pulls the zip and renames the three GBA-series patches.
3. Apply:
   ```bash
   source .venv-moneo/bin/activate   # or your preferred venv
   pip install xdelta3
   python3 tools/moneo/rom_swap/apply_patch.py /path/to/leafgreen_japan.gba
   # → writes tools/moneo/rom_swap/leafgreen_J-K_2024.gba
   ```
4. Inside the app, use **Settings → Add ROM** and pick the patched `.gba`. Moneo's `RomIdentity` will recognize CRC32 `0x4A38A8CB` and enable Korean-specific flashcard features.

Full workflow (offset re-derivation, diagnostic script, what to expect after patching) is documented in [`tools/moneo/rom_swap/README.md`](tools/moneo/rom_swap/README.md).

### Credits

The 2024-02-29 Korean fan-translation patch is the work of:

- **명군** (lead)
- tony
- koi
- 돌아온달토끼

Patch distribution + discussion:

- [hangulogame.com — Pokémon FireRed/LeafGreen Korean Fan Translation, v20240229](https://www.hangulogame.com/patch/gba/844/) — canonical patch page (English/Korean release notes, version history)
- [DCInside Nintendo mini-gallery release thread, post 2515975](https://gall.dcinside.com/mgallery/board/view/?id=game_nintendo&no=2515975) — original release announcement + community Q&A

Moneo's pipeline (corpus mining, glyph map, in-app flashcards) is built on top of their translation work. None of this would exist without their effort. If you find issues with the Korean text in Moneo's flashcards, those are on us — please report them via the **✎ Report** button in the review screen, which opens a [pre-filled GitHub Issue](https://github.com/Stan-Stani/moneo/issues/new?template=korean-correction.yml).

The US Rev 1 English LeafGreen (CRC32 `0xDAFFECEC`) is also supported as a non-localized variant; the step-gating logic (PokéTrek) works against either ROM, but the Korean flashcard surface is dormant on the English ROM.
