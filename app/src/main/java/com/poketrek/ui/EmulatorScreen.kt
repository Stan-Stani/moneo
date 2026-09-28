package com.poketrek.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.poketrek.emu.EmulatorRunner
import com.poketrek.moneo.MoneoModule
import com.poketrek.moneo.gate.MoneoSoftGate
import com.poketrek.moneo.ui.DirectionSuggestionDialog
import com.poketrek.moneo.ui.MoneoHud
import com.poketrek.moneo.ui.MoneoOverlay
import com.poketrek.step.MovementBudget

/**
 * Game-audio gain applied while Korean TTS is speaking (≈ −12 dB). Quiet
 * enough to make the synthesized speech easily intelligible, loud enough
 * to keep the player oriented in the game world.
 */
private const val TTS_DUCK_VOLUME = 0.25f

/**
 * Full-screen emulator: framebuffer letterboxed across the entire window,
 * with semi-transparent controls floating on top. The HUD is just a tiny
 * badge + a settings sheet so it doesn't cover the game.
 */
@Composable
fun EmulatorScreen(
    runner: EmulatorRunner,
    budget: MovementBudget,
    moneo: MoneoModule,
    moneoGate: MoneoSoftGate,
    onDebugAddSteps: (Int) -> Unit,
    onPickRom: () -> Unit,
    onSetupKoreanRom: () -> Unit = {},
    koreanSetupState: () -> com.poketrek.emu.KoreanRomPatcher.State =
        { com.poketrek.emu.KoreanRomPatcher.State.Idle },
    getSaveSlots: () -> List<com.poketrek.emu.SaveStateStore.Slot>,
    onSaveSlot: (Int) -> Boolean,
    onLoadSlot: (Int) -> Boolean,
    getRomLibrary: () -> List<com.poketrek.emu.RomCache.Slot> = { emptyList() },
    currentRomCrc32: () -> Long? = { null },
    onLoadCachedRom: (Long) -> Boolean = { false },
    onRemoveCachedRom: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tick by runner.frameTick
    val ramSnapshot by runner.ramSnapshot
    val romIdentity by runner.romIdentity
    val debugOn by budget.debugHudVisible.collectAsState()
    val pendingBaseline by runner.calibrationBaseline
    val calibrationStatus by runner.calibrationStatus
    val hasCalibration by runner.hasCalibration
    val controlState = rememberControlState()
    var settingsOpen by remember { mutableStateOf(false) }
    var moneoOpen by remember { mutableStateOf(false) }
    /** Area to open Moneo on (set by the area-gate lock chip); null opens the picker. */
    var moneoStudyArea by remember { mutableStateOf<String?>(null) }

    // Game-audio ducking. The Korean TTS engine and the GBA AudioTrack share
    // the speaker; when both play simultaneously the synthesized speech gets
    // buried in chiptune. This effect computes a single target gain from the
    // three relevant signals and applies it to the runner.
    val ttsSpeaking by moneo.tts.isSpeaking.collectAsState()
    val muteInReview by moneo.prefs.muteGameInReview.collectAsState()
    LaunchedEffect(ttsSpeaking, moneoOpen, muteInReview) {
        val volume = when {
            moneoOpen && muteInReview -> 0f
            ttsSpeaking -> TTS_DUCK_VOLUME
            else -> 1f
        }
        runner.setMasterVolume(volume)
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val t = tick
            drawFramebuffer(runner)
        }

        HudBadge(
            budget = budget,
            romIdentity = romIdentity,
            hasCalibration = hasCalibration,
            onOpenSettings = { settingsOpen = true },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        )

        MoneoHud(
            moneo = moneo,
            gate = moneoGate,
            onOpenMoneo = { moneoOpen = true },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 8.dp, top = 56.dp),
        )

        AreaGateLockChip(
            gate = runner.gate,
            moneo = moneo,
            onStudy = { areaId ->
                moneo.prefs.setTargetAreaId(areaId)
                moneoStudyArea = areaId
                moneoOpen = true
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 8.dp, top = 96.dp),
        )

        ReadingHelperPanel(
            moneo = moneo,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 52.dp, end = 6.dp),
        )

        if (debugOn) {
            DebugOverlay(
                snapshot = ramSnapshot,
                romIdentity = romIdentity,
                onDebugAddSteps = onDebugAddSteps,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
            )
        }

        StartSelectChips(
            state = controlState,
            onKeysChanged = runner::setKeys,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        )

        DPadCluster(
            state = controlState,
            onKeysChanged = runner::setKeys,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 16.dp),
        )

        ActionCluster(
            state = controlState,
            onKeysChanged = runner::setKeys,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp),
        )

        if (pendingBaseline != null && !settingsOpen) {
            CalibrationPendingChip(
                onCapture = { runner.finishCalibration() },
                onShowSettings = { settingsOpen = true },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
            )
        }

        if (settingsOpen) {
            SettingsSheet(
                budget = budget,
                gate = runner.gate,
                romIdentity = romIdentity,
                onDismiss = { settingsOpen = false },
                onPickRom = onPickRom,
                onSetupKoreanRom = onSetupKoreanRom,
                koreanSetupState = koreanSetupState,
                getSaveSlots = getSaveSlots,
                onSaveSlot = onSaveSlot,
                onLoadSlot = onLoadSlot,
                onBuyRareCandy = runner::buyRareCandy,
                hasCalibration = hasCalibration,
                hasPendingBaseline = pendingBaseline != null,
                calibrationStatus = calibrationStatus,
                onBeginCalibration = runner::beginCalibration,
                onFinishCalibration = runner::finishCalibration,
                onCancelCalibration = runner::cancelCalibration,
                onClearCalibrationStatus = runner::clearCalibrationStatus,
                moneo = moneo,
                onOpenMoneo = { moneoOpen = true },
                getRomLibrary = getRomLibrary,
                currentRomCrc32 = currentRomCrc32,
                onLoadCachedRom = onLoadCachedRom,
                onRemoveCachedRom = onRemoveCachedRom,
            )
        }

        if (moneoOpen) {
            MoneoOverlay(
                module = moneo,
                onClose = { moneoOpen = false; moneoStudyArea = null },
                modifier = Modifier.fillMaxSize(),
                romCrc32Hex = romIdentity?.crc32Hex,
                initialArea = moneoStudyArea,
            )
        }

        DirectionSuggestionDialog(moneo = moneo, romIdentity = romIdentity)
    }
}

private fun DrawScope.drawFramebuffer(runner: EmulatorRunner) {
    val bmp = runner.bitmap
    val srcW = bmp.width
    val srcH = bmp.height
    val canvasW = size.width.toInt()
    val canvasH = size.height.toInt()
    val scale = minOf(canvasW.toFloat() / srcW, canvasH.toFloat() / srcH)
    val drawW = (srcW * scale).toInt()
    val drawH = (srcH * scale).toInt()
    val offsetX = (canvasW - drawW) / 2
    val offsetY = (canvasH - drawH) / 2
    val snapshot = synchronized(bmp) { bmp.asImageBitmap() }
    drawImage(
        image = snapshot,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(srcW, srcH),
        dstOffset = IntOffset(offsetX, offsetY),
        dstSize = IntSize(drawW, drawH),
        filterQuality = FilterQuality.None,
    )
}
