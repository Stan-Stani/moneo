package com.poketrek

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import android.provider.OpenableColumns
import com.poketrek.emu.CalibrationStore
import com.poketrek.emu.EmulatorRunner
import com.poketrek.emu.KoreanRomPatcher
import com.poketrek.emu.RomCache
import com.poketrek.emu.SaveStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.poketrek.moneo.MoneoModule
import com.poketrek.moneo.gate.MoneoSoftGate
import com.poketrek.step.MovementBudget
import com.poketrek.step.StepCounterService

private const val TAG = "EmulatorActivity"

class EmulatorActivity : ComponentActivity() {

    private lateinit var budget: MovementBudget
    private lateinit var runner: EmulatorRunner
    private lateinit var saveStateStore: SaveStateStore
    private lateinit var moneo: MoneoModule
    private lateinit var moneoGate: MoneoSoftGate
    private lateinit var romCache: RomCache

    // On-device Japanese→Korean ROM construction. Surfaced to the settings
    // UI so the user sees download/patch/verify progress and errors.
    private val koreanSetupState: MutableState<KoreanRomPatcher.State> =
        mutableStateOf(KoreanRomPatcher.State.Idle)

    // Feedback for "Choose ROM" when the file can't be read or loaded.
    private val romPickError: MutableState<String?> = mutableStateOf(null)

    // A picked Japanese 1.0 base, held while the user decides between
    // building the Korean ROM from it and playing it as-is.
    private val pendingJpBase: MutableState<Pair<ByteArray, String?>?> = mutableStateOf(null)

    // Orientation lock + manual flip. The activity is locked (no sensor
    // follow) but we listen to OrientationEventListener purely to detect when
    // the user is *trying* to rotate between landscape and portrait, and
    // surface a one-tap "switch" prompt in that case. Portrait is mainly
    // useful for the Moneo card-review overlay.
    private var portraitLocked = false
    private val showFlipPrompt: MutableState<Boolean> = mutableStateOf(false)
    private var orientListener: OrientationEventListener? = null

    private val requestActivityRecognition = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            StepCounterService.start(applicationContext)
        } else {
            Log.w(TAG, "ACTIVITY_RECOGNITION denied — step counter unavailable")
        }
    }

    private val requestPostNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result ignored — service still runs without it on Android 13+ */ }

    private val pickRom = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not persist URI permission", e)
        }
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            romPickError.value = "Couldn't read the selected file."
            return@registerForActivityResult
        }
        romPickError.value = null
        // The Japanese 1.0 dump is the Korean patch's base. Someone who picks
        // it almost certainly wants the Korean game, so ask before loading it.
        if (com.poketrek.emu.RomIdentity.of(bytes).variant ==
            com.poketrek.emu.RomVariant.LEAFGREEN_JP_10) {
            pendingJpBase.value = bytes to displayNameFor(uri)
            return@registerForActivityResult
        }
        loadPickedRom(bytes, displayNameFor(uri))
    }

    // Step 1 of Korean setup: user picks their own Japanese LeafGreen 1.0
    // base. We then download the authors' patch bundle, apply it via the
    // native xdelta bridge, verify the CRC, cache + load. No ROM is ever
    // shipped or hosted by us — only the user's base + the authors' patch.
    private val pickJpBaseForKorean = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val baseBytes = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (baseBytes == null) {
            koreanSetupState.value =
                KoreanRomPatcher.State.Error("Couldn't read the selected file.")
            return@registerForActivityResult
        }
        buildKoreanRom(baseBytes)
    }

    private fun loadPickedRom(bytes: ByteArray, displayName: String?) {
        if (!com.poketrek.emu.RomIdentity.looksLikeGbaRom(bytes) || !runner.loadRom(bytes)) {
            romPickError.value = "That file didn't load as a Game Boy Advance ROM."
            return
        }
        val identity = runner.romIdentity.value ?: return
        val label = displayName
            ?: identity.variant.displayName.takeIf { it.isNotBlank() }
            ?: "ROM ${identity.crc32Hex}"
        romCache.put(bytes, identity.crc32, label)
    }

    private fun buildKoreanRom(baseBytes: ByteArray) {
        val picked = com.poketrek.emu.RomIdentity.of(baseBytes)
        if (picked.variant == com.poketrek.emu.RomVariant.LEAFGREEN_KR_2024) {
            // Already the Korean ROM: nothing to patch.
            loadPickedRom(baseBytes, KoreanRomPatcher.ROM_LABEL)
            koreanSetupState.value = KoreanRomPatcher.State.Success(picked.crc32)
            return
        }
        KoreanRomPatcher.baseProblem(picked.variant)?.let {
            koreanSetupState.value = KoreanRomPatcher.State.Error(it)
            return
        }
        koreanSetupState.value =
            KoreanRomPatcher.State.Running(KoreanRomPatcher.Phase.DOWNLOADING_PATCH)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                KoreanRomPatcher.produce(
                    baseBytes = baseBytes,
                    cacheDir = applicationContext.cacheDir,
                    applyXdelta = runner::applyXdelta,
                    onPhase = { phase ->
                        koreanSetupState.value = KoreanRomPatcher.State.Running(phase)
                    },
                )
            }
            result.onSuccess { korean ->
                val identity = com.poketrek.emu.RomIdentity.of(korean)
                if (runner.loadRom(korean)) {
                    romCache.put(korean, identity.crc32, KoreanRomPatcher.ROM_LABEL)
                    koreanSetupState.value = KoreanRomPatcher.State.Success(identity.crc32)
                } else {
                    koreanSetupState.value =
                        KoreanRomPatcher.State.Error("Patched ROM failed to load.")
                }
            }.onFailure {
                Log.w(TAG, "Korean ROM setup failed", it)
                koreanSetupState.value = KoreanRomPatcher.State.Error(
                    it.message ?: "Korean ROM setup failed.",
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        budget = MovementBudget.get(applicationContext)
        runner = EmulatorRunner(
            budget,
            CalibrationStore(applicationContext),
            saveDir = java.io.File(filesDir, "saves"),
        )
        saveStateStore = SaveStateStore(applicationContext)
        moneo = MoneoModule.get(applicationContext)
        moneo.bindCapture { addr, length -> runner.busReadBytes(addr, length) }
        moneo.bindDialogReader(
            reader = { addr, length -> runner.busReadBytes(addr, length) },
            isSupported = { runner.romIdentity.value?.variant == com.poketrek.emu.RomVariant.LEAFGREEN_KR_2024 },
            location = { runner.ramSnapshot.value?.let { "${it.mapBank}:${it.mapId}" } },
        )
        moneoGate = MoneoSoftGate(moneo.repository, moneo.prefs)

        // Wire the hard area-gate. boundary_tiles.json is keyed by
        // pokefirered's canonical map_groups numbering, which both the US
        // Rev 1 ROM and the 2024 KR patch use for SaveBlock1.location.
        runCatching {
            val boundaries = com.poketrek.moneo.data.MapBoundaryLookup
                .loadFromAssets(applicationContext)
            val areaGate = com.poketrek.moneo.MoneoAreaGateImpl.create(
                boundaries,
                moneo.prefs,
                moneo.repository,
                lemmaCounts = moneo.lemmaCounts,
                isRomSupported = { runner.romIdentity.value?.variant?.areaGateSupported == true },
                mapAreas = com.poketrek.moneo.data.MapAreaLookup.loadFromAssets(applicationContext),
                romKey = { runner.romIdentity.value?.crc32Hex },
            )
            runner.setMoneoAreaGate(areaGate)
        }.onFailure {
            android.util.Log.w(
                "EmulatorActivity",
                "MoneoAreaGate not wired (asset missing or parse failed): ${it.message}"
            )
        }
        romCache = RomCache(applicationContext)

        // Manual orientation: locked to landscape (manifest), no sensor follow.
        // A floating "flip" prompt only appears when OrientationEventListener
        // sees the phone being held in the opposite landscape — see init below.
        applyPersistedOrientation()
        installOrientationFlipDetector()

        // If a previously-picked ROM is cached in private storage, load it
        // so the user doesn't have to re-pick from Downloads on every launch.
        loadCachedRomIfPresent()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPostNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            StepCounterService.start(applicationContext)
        } else {
            requestActivityRecognition.launch(android.Manifest.permission.ACTIVITY_RECOGNITION)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        runner = runner,
                        budget = budget,
                        moneo = moneo,
                        moneoGate = moneoGate,
                        onPickRom = { pickRom.launch(arrayOf("application/octet-stream", "*/*")) },
                        onSetupKoreanRom = {
                            koreanSetupState.value = KoreanRomPatcher.State.Idle
                            pickJpBaseForKorean.launch(arrayOf("application/octet-stream", "*/*"))
                        },
                        koreanSetupState = { koreanSetupState.value },
                        romPickError = romPickError.value,
                        onDismissRomPickError = { romPickError.value = null },
                        pendingJpBase = pendingJpBase.value != null,
                        onBuildKoreanFromPicked = {
                            pendingJpBase.value?.let { buildKoreanRom(it.first) }
                            pendingJpBase.value = null
                        },
                        onPlayPickedAsIs = {
                            pendingJpBase.value?.let { loadPickedRom(it.first, it.second) }
                            pendingJpBase.value = null
                        },
                        onDebugAddSteps = budget::debugAddSteps,
                        getSaveSlots = saveStateStore::slots,
                        onSaveSlot = { slot ->
                            runner.saveState()?.let {
                                saveStateStore.save(slot, it, runner.romIdentity.value?.crc32)
                            } ?: false
                        },
                        onLoadSlot = { slot ->
                            saveStateStore.load(slot)?.let { runner.loadState(it) } ?: false
                        },
                        showFlipPrompt = showFlipPrompt.value,
                        flipTargetIsPortrait = !portraitLocked,
                        onFlipOrientation = ::flipOrientation,
                        getRomLibrary = romCache::list,
                        currentRomCrc32 = { runner.romIdentity.value?.crc32 },
                        onLoadCachedRom = ::loadCachedRom,
                        onRemoveCachedRom = romCache::remove,
                    )
                }
            }
            DisposableEffect(Unit) {
                onDispose { runner.stop() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        runner.resume()
        orientListener?.enable()
    }

    override fun onStop() {
        runner.pause()
        orientListener?.disable()
        showFlipPrompt.value = false
        super.onStop()
    }

    override fun onDestroy() {
        runner.stop()
        super.onDestroy()
    }

    private fun uiPrefs() = getSharedPreferences("ui", MODE_PRIVATE)

    private fun loadCachedRomIfPresent() {
        val recent = romCache.mostRecent() ?: return
        loadCachedRom(recent.crc32)
    }

    private fun loadCachedRom(crc32: Long): Boolean {
        val bytes = romCache.load(crc32) ?: run {
            romCache.remove(crc32)
            return false
        }
        return if (runner.loadRom(bytes)) {
            true
        } else {
            Log.w(TAG, "Cached ROM ${"0x" + crc32.toString(16)} failed to load; removing")
            romCache.remove(crc32)
            false
        }
    }

    private fun displayNameFor(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.removeSuffix(".gba")
    } catch (e: Exception) {
        Log.w(TAG, "Failed to query display name", e)
        null
    }

    private fun applyPersistedOrientation() {
        portraitLocked = uiPrefs().getBoolean("orient_portrait", false)
        requestedOrientation = if (portraitLocked) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun installOrientationFlipDetector() {
        // OrientationEventListener emits 0..359 with 0° = device's natural
        // orientation (portrait on phones), ~90°/270° = landscape. Show the
        // prompt only when the device is being held perpendicular to the
        // current lock — i.e. landscape is locked but device is portrait, or
        // vice versa.
        orientListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val portraitHeld = degrees <= 30 || degrees >= 330 ||
                    (degrees in 150..210)
                val landscapeHeld = (degrees in 60..120) || (degrees in 240..300)
                val perpendicular = if (portraitLocked) landscapeHeld else portraitHeld
                if (showFlipPrompt.value != perpendicular) {
                    showFlipPrompt.value = perpendicular
                }
            }
        }.also { if (it.canDetectOrientation()) it.enable() else it.disable() }
    }

    private fun flipOrientation() {
        portraitLocked = !portraitLocked
        uiPrefs().edit().putBoolean("orient_portrait", portraitLocked).apply()
        requestedOrientation = if (portraitLocked) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        showFlipPrompt.value = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowCompat.getInsetsController(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun AppRoot(
    runner: EmulatorRunner,
    budget: MovementBudget,
    moneo: MoneoModule,
    moneoGate: MoneoSoftGate,
    onPickRom: () -> Unit,
    onSetupKoreanRom: () -> Unit,
    koreanSetupState: () -> KoreanRomPatcher.State,
    romPickError: String?,
    onDismissRomPickError: () -> Unit,
    pendingJpBase: Boolean,
    onBuildKoreanFromPicked: () -> Unit,
    onPlayPickedAsIs: () -> Unit,
    onDebugAddSteps: (Int) -> Unit,
    getSaveSlots: () -> List<com.poketrek.emu.SaveStateStore.Slot>,
    onSaveSlot: (Int) -> Boolean,
    onLoadSlot: (Int) -> Boolean,
    showFlipPrompt: Boolean,
    flipTargetIsPortrait: Boolean,
    onFlipOrientation: () -> Unit,
    getRomLibrary: () -> List<com.poketrek.emu.RomCache.Slot>,
    currentRomCrc32: () -> Long?,
    onLoadCachedRom: (Long) -> Boolean,
    onRemoveCachedRom: (Long) -> Unit,
) {
    val romLoaded by runner.romLoaded
    Box(modifier = Modifier.fillMaxSize()) {
        if (!romLoaded) {
            com.poketrek.ui.OnboardingScreen(
                koreanSetupState = koreanSetupState(),
                romPickError = romPickError,
                onSetupKoreanRom = onSetupKoreanRom,
                onPickRom = onPickRom,
            )
        } else {
            com.poketrek.ui.EmulatorScreen(
                runner = runner,
                budget = budget,
                moneo = moneo,
                moneoGate = moneoGate,
                onDebugAddSteps = onDebugAddSteps,
                onPickRom = onPickRom,
                onSetupKoreanRom = onSetupKoreanRom,
                koreanSetupState = koreanSetupState,
                getSaveSlots = getSaveSlots,
                onSaveSlot = onSaveSlot,
                onLoadSlot = onLoadSlot,
                getRomLibrary = getRomLibrary,
                currentRomCrc32 = currentRomCrc32,
                onLoadCachedRom = onLoadCachedRom,
                onRemoveCachedRom = onRemoveCachedRom,
                romPickError = romPickError,
                onDismissRomPickError = onDismissRomPickError,
                modifier = Modifier.fillMaxSize().padding(8.dp),
            )
        }
        if (pendingJpBase) {
            com.poketrek.ui.JapaneseBaseDialog(
                onBuildKorean = onBuildKoreanFromPicked,
                onPlayAsIs = onPlayPickedAsIs,
            )
        }
        if (showFlipPrompt) {
            // In landscape the top bar is wide enough for a centered prompt.
            // In portrait it would sit on the settings button, so it goes in
            // the empty band between the game (vertically centered) and the
            // controls at the bottom.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val portrait = maxHeight > maxWidth
                Button(
                    onClick = onFlipOrientation,
                    modifier = if (portrait) {
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = maxHeight * 0.26f)
                    } else {
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                    },
                ) {
                    Text(if (flipTargetIsPortrait) "Switch to portrait" else "Switch to landscape")
                }
            }
        }
    }
}
