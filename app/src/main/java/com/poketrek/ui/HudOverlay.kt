package com.poketrek.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poketrek.emu.EmulatorRunner
import com.poketrek.emu.MovementGate
import com.poketrek.emu.RomCalibrator
import com.poketrek.emu.RomIdentity
import com.poketrek.emu.RomVariant
import com.poketrek.moneo.MoneoModule
import com.poketrek.step.MAX_RARE_CANDY_COST
import com.poketrek.step.MIN_RARE_CANDY_COST
import com.poketrek.step.MovementBudget
import com.poketrek.step.parseRatioInput
import kotlinx.coroutines.launch

/**
 * Discrete ratio steps the slider snaps through. Index 0 is the hardest
 * (most real steps per tile); the last index is the easiest. The middle
 * stop is realistic 1:1.
 */
private val RATIO_TABLE: List<Pair<Int, Int>> = listOf(
    1 to 8, 1 to 6, 1 to 4, 1 to 3, 1 to 2,
    1 to 1,
    2 to 1, 3 to 1, 4 to 1, 6 to 1, 8 to 1, 12 to 1, 16 to 1,
)

private fun ratioIndexFor(num: Int, den: Int): Int {
    val exact = RATIO_TABLE.indexOfFirst { it.first == num && it.second == den }
    if (exact >= 0) return exact
    // Custom-set ratio not in the preset table — snap to the closest preset
    // by tile-per-step value so the slider doesn't surprise-jump to 1:1.
    val target = num.toDouble() / den.toDouble()
    return RATIO_TABLE.indices.minByOrNull { i ->
        val (n, d) = RATIO_TABLE[i]
        kotlin.math.abs(n.toDouble() / d - target)
    } ?: RATIO_TABLE.indexOfFirst { it.first == 1 && it.second == 1 }
}

private fun ratioMatchesPreset(num: Int, den: Int): Boolean =
    RATIO_TABLE.any { it.first == num && it.second == den }

private fun describeRatio(num: Int, den: Int): String = when {
    num == 1 && den == 1 -> "1 step = 1 tile (realistic)"
    num >= den -> {
        val tiles = num / den
        "1 step = $tiles tile${if (tiles != 1) "s" else ""}"
    }
    else -> {
        val steps = den / num
        "$steps steps = 1 tile"
    }
}

/**
 * Compact, always-visible HUD: just the tile count + a button that opens the
 * settings sheet. Sits in the corner of the framebuffer letterbox so it
 * doesn't overlap gameplay.
 */
@Composable
fun HudBadge(
    budget: MovementBudget,
    romIdentity: com.poketrek.emu.RomIdentity?,
    hasCalibration: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tiles by budget.budget.collectAsState()
    val gateOn by budget.gateEnabled.collectAsState()
    // Warn whenever no calibration is loaded — that's the actual condition
    // the run loop checks before gating. The variant's static
    // `gatingSupported` flag only says "ships pre-calibrated"; a Korean ROM
    // becomes gateable as soon as the user runs the calibration flow.
    val warn = romIdentity != null && !hasCalibration
    Row(
        modifier = modifier
            .background(Color(0xCC000000), shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column {
            Text(
                text = if (gateOn) "TILES $tiles" else "TILES $tiles (free)",
                color = if (gateOn && tiles == 0) Color(0xFFEF4444) else Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            if (warn) {
                Text(
                    text = "⚠ ${romIdentity!!.variant.displayName} — gating off",
                    color = Color(0xFFFBBF24),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
            }
        }
        Button(
            onClick = onOpenSettings,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF374151)),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Text("☰", color = Color.White, fontSize = 13.sp)
        }
    }
}

/**
 * Floating reminder rendered while a calibration baseline is captured but not
 * yet completed. The settings sheet is dismissed after baseline capture so the
 * player can walk one tile in the overworld; this chip nudges them back.
 *
 * Tapping "Capture" runs the calibration directly. On success the baseline
 * clears (so this chip disappears on its own); on failure the settings sheet
 * is opened so the user can see the error message and retry.
 */
@Composable
fun CalibrationPendingChip(
    onCapture: suspend () -> com.poketrek.emu.RomCalibrator.Result,
    onShowSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var capturing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Row(
        modifier = modifier
            .background(Color(0xCC92400E), shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Walk 1 tile, then capture",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        Button(
            onClick = {
                if (capturing) return@Button
                capturing = true
                scope.launch {
                    try {
                        val result = onCapture()
                        if (result !is com.poketrek.emu.RomCalibrator.Result.Ok) {
                            onShowSettings()
                        }
                    } finally {
                        capturing = false
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111827)),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            if (capturing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            } else {
                Text("Capture", color = Color.White, fontSize = 11.sp)
            }
        }
    }
}

/**
 * Lightweight read-only debug overlay: shows the RAM probe snapshot. Only
 * rendered when the user has enabled the debug HUD in settings. Designed to
 * sit somewhere unobtrusive (top-center letterbox).
 */
@Composable
fun DebugOverlay(
    snapshot: com.poketrek.emu.LeafGreenRam.Snapshot?,
    romIdentity: com.poketrek.emu.RomIdentity?,
    onDebugAddSteps: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color(0xCC000000), shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (romIdentity != null) {
            Text(
                text = "ROM: ${romIdentity.variant.displayName} ${romIdentity.crc32Hex}",
                color = Color(0xFF9CA3AF),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
        if (snapshot != null) {
            Text(
                text = formatSnapshot(snapshot),
                color = Color(0xFF9CA3AF),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
        Button(
            onClick = { onDebugAddSteps(10) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Text("+10 fake steps", color = Color.White, fontSize = 11.sp)
        }
    }
}

/**
 * Modal bottom sheet with all user-tweakable knobs. Hidden by default; opened
 * from the HUD badge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    budget: MovementBudget,
    gate: MovementGate,
    romIdentity: RomIdentity?,
    onDismiss: () -> Unit,
    onPickRom: () -> Unit,
    onSetupKoreanRom: () -> Unit = {},
    koreanSetupState: () -> com.poketrek.emu.KoreanRomPatcher.State =
        { com.poketrek.emu.KoreanRomPatcher.State.Idle },
    getSaveSlots: () -> List<com.poketrek.emu.SaveStateStore.Slot>,
    onSaveSlot: (Int) -> Boolean,
    onLoadSlot: (Int) -> Boolean,
    onBuyRareCandy: (Int) -> EmulatorRunner.BuyResult,
    hasCalibration: Boolean,
    hasPendingBaseline: Boolean,
    calibrationStatus: RomCalibrator.Result?,
    onBeginCalibration: () -> Boolean,
    onFinishCalibration: suspend () -> RomCalibrator.Result,
    onCancelCalibration: () -> Unit,
    onClearCalibrationStatus: () -> Unit,
    moneo: MoneoModule,
    onOpenMoneo: () -> Unit,
    getRomLibrary: () -> List<com.poketrek.emu.RomCache.Slot> = { emptyList() },
    currentRomCrc32: () -> Long? = { null },
    onLoadCachedRom: (Long) -> Boolean = { false },
    onRemoveCachedRom: (Long) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tiles by budget.budget.collectAsState()
    val ratioNum by budget.tilesPerStepNum.collectAsState()
    val ratioDen by budget.tilesPerStepDen.collectAsState()
    val gateOn by budget.gateEnabled.collectAsState()
    val debugOn by budget.debugHudVisible.collectAsState()
    val hapticOn by budget.hapticOnStep.collectAsState()
    val currentIndex = ratioIndexFor(ratioNum, ratioDen)
    var ratioDraft by remember(currentIndex) { mutableStateOf(currentIndex.toFloat()) }
    val draftIndex = ratioDraft.toInt().coerceIn(0, RATIO_TABLE.lastIndex)
    val (draftNum, draftDen) = RATIO_TABLE[draftIndex]

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Moneo settings", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Button(
                    onClick = { onPickRom(); onDismiss() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Text("Add ROM…", fontSize = 12.sp)
                }
            }

            var romLibrary by remember { mutableStateOf(getRomLibrary()) }
            SectionCard(title = "ROMs") {
                RomLibrarySection(
                    slots = romLibrary,
                    currentCrc32 = currentRomCrc32(),
                    onLoad = { crc -> if (onLoadCachedRom(crc)) onDismiss() },
                    onRemove = { crc ->
                        onRemoveCachedRom(crc)
                        romLibrary = getRomLibrary()
                    },
                )
                if (romIdentity != null) {
                    CalibrationSection(
                        hasCalibration = hasCalibration,
                        hasPendingBaseline = hasPendingBaseline,
                        status = calibrationStatus,
                        romIdentity = romIdentity,
                        onBeginCalibration = {
                            if (onBeginCalibration()) onDismiss()
                        },
                        onFinishCalibration = onFinishCalibration,
                        onCancelCalibration = onCancelCalibration,
                        onClearStatus = onClearCalibrationStatus,
                    )
                }
                KoreanRomSetupSection(
                    state = koreanSetupState(),
                    onSetup = onSetupKoreanRom,
                )
            }

            SectionCard(title = "Movement") {
                ToggleRow(
                    label = "Step gate",
                    sublabel = if (gateOn) "Movement requires real steps" else "Free walking",
                    checked = gateOn,
                    onCheckedChange = { gate.setEnabled(it) },
                )

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val isCustom = !ratioMatchesPreset(ratioNum, ratioDen)
                    Text(
                        if (isCustom) "Custom: ${describeRatio(ratioNum, ratioDen)}"
                        else describeRatio(draftNum, draftDen),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    )
                    Slider(
                        value = ratioDraft,
                        onValueChange = { ratioDraft = it },
                        onValueChangeFinished = {
                            val (n, d) = RATIO_TABLE[draftIndex]
                            budget.setRatio(n, d)
                        },
                        valueRange = 0f..(RATIO_TABLE.lastIndex).toFloat(),
                        steps = RATIO_TABLE.size - 2,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "8 steps / tile",
                            color = Color(0xFF6B7280),
                            fontSize = 11.sp,
                        )
                        Text(
                            "16 tiles / step",
                            color = Color(0xFF6B7280),
                            fontSize = 11.sp,
                        )
                    }
                }

                Text(
                    "Current budget: $tiles tiles",
                    color = Color(0xFF6B7280),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )

                Expander(
                    title = "Custom ratio",
                    summary = if (ratioMatchesPreset(ratioNum, ratioDen)) "Use preset"
                    else describeRatio(ratioNum, ratioDen),
                ) {
                    CustomRatioRow(
                        currentNum = ratioNum,
                        currentDen = ratioDen,
                        onApply = { n, d -> budget.setRatio(n, d) },
                    )
                }

                ToggleRow(
                    label = "Vibrate on step credit",
                    sublabel = "Brief pulse when a real step adds to your tile budget",
                    checked = hapticOn,
                    onCheckedChange = { budget.setHapticOnStep(it) },
                )
            }

            MoneoSection(
                moneo = moneo,
                onOpenMoneo = { onOpenMoneo(); onDismiss() },
            )

            SectionCard(title = "Save states") {
                var slotsVersion by remember { mutableStateOf(0) }
                val slots = remember(slotsVersion) { getSaveSlots() }
                slots.forEach { slot ->
                    SlotRow(
                        slot = slot,
                        currentRomCrc = romIdentity?.crc32,
                        onSave = {
                            if (onSaveSlot(slot.index)) slotsVersion++
                        },
                        onLoad = {
                            if (onLoadSlot(slot.index)) onDismiss()
                        },
                    )
                }
            }

            if (romIdentity?.variant == RomVariant.LEAFGREEN_US_REV1) {
                SectionCard(title = "Shop") {
                    ShopSection(
                        budget = budget,
                        onBuyRareCandy = onBuyRareCandy,
                    )
                }
            }

            SectionCard(title = "Developer") {
                ToggleRow(
                    label = "Debug overlay",
                    sublabel = "Shows RAM probe + fake-step button",
                    checked = debugOn,
                    onCheckedChange = { budget.setDebugHudVisible(it) },
                )
                AdvancedSection(
                    onResetSteps = { budget.resetBudgetAndRebaseSteps() },
                )
                UnmatchedDialogSection(moneo)
                RuntimeTextCaptureSection(moneo)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SlotRow(
    slot: com.poketrek.emu.SaveStateStore.Slot,
    currentRomCrc: Long?,
    onSave: () -> Unit,
    onLoad: () -> Unit,
) {
    val mismatch = slot.romCrc32 != null
        && currentRomCrc != null
        && slot.romCrc32 != currentRomCrc
    val romLabel = when {
        slot.isEmpty -> null
        slot.romCrc32 == null -> "Unknown ROM"
        else -> {
            val v = RomIdentity.variantFor(slot.romCrc32)
            "${v.displayName} ${RomIdentity.crc32Hex(slot.romCrc32)}"
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Slot ${slot.index}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(
                if (slot.isEmpty) "Empty" else relativeTime(slot.savedAt!!),
                color = Color(0xFF6B7280),
                fontSize = 11.sp,
            )
            romLabel?.let {
                Text(
                    text = if (mismatch) "$it  ⚠ different ROM" else it,
                    color = if (mismatch) Color(0xFFEF4444) else Color(0xFF6B7280),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
        }
        Button(onClick = onSave, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
            Text("Save", fontSize = 12.sp)
        }
        Button(
            onClick = onLoad,
            enabled = !slot.isEmpty && !mismatch,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            Text("Load", fontSize = 12.sp)
        }
    }
}

private fun relativeTime(epochMs: Long): String {
    val cs = android.text.format.DateUtils.getRelativeTimeSpanString(
        epochMs,
        System.currentTimeMillis(),
        android.text.format.DateUtils.MINUTE_IN_MILLIS,
        android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
    )
    return cs?.toString() ?: "unknown"
}

@Composable
private fun ToggleRow(
    label: String,
    sublabel: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(sublabel, color = Color(0xFF6B7280), fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(),
        )
    }
}

/**
 * Generic 2-or-more-button segmented selector. Selected option gets a
 * filled chip; the rest are outlined-style. Used for the Moneo direction
 * picker and the TTS-language picker.
 */
@Composable
private fun <T> SegmentedRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val colors = if (isSelected) {
                ButtonDefaults.buttonColors(containerColor = Color(0xFF1D4ED8))
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE2E8F0),
                    contentColor = Color(0xFF334155),
                )
            }
            Button(
                onClick = { onSelect(value) },
                colors = colors,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(label, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DirectionPicker(
    current: com.poketrek.moneo.data.FlashcardDirection,
    onPick: (com.poketrek.moneo.data.FlashcardDirection) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Card direction",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        SegmentedRow(
            options = listOf(
                com.poketrek.moneo.data.FlashcardDirection.KO_TO_EN to "한국어 → English",
                com.poketrek.moneo.data.FlashcardDirection.EN_TO_KO to "English → 한국어",
            ),
            selected = current,
            onSelect = onPick,
        )
        val sublabel = when (current) {
            com.poketrek.moneo.data.FlashcardDirection.KO_TO_EN ->
                "Korean shows on the front; tap to reveal the English meaning"
            com.poketrek.moneo.data.FlashcardDirection.EN_TO_KO ->
                "English shows on the front; tap to reveal the Korean translation"
        }
        Text(sublabel, color = Color(0xFF6B7280), fontSize = 12.sp)
    }
}

@Composable
private fun SourceTypeModePicker(
    label: String,
    current: com.poketrek.moneo.data.SourceTypeMode,
    onPick: (com.poketrek.moneo.data.SourceTypeMode) -> Unit,
    separateLabel: String,
    cardCount: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        SegmentedRow(
            options = listOf(
                com.poketrek.moneo.data.SourceTypeMode.OFF to "Off",
                com.poketrek.moneo.data.SourceTypeMode.MERGED to "Merged",
                com.poketrek.moneo.data.SourceTypeMode.SEPARATE to "Separate",
            ),
            selected = current,
            onSelect = onPick,
        )
        val sublabel = when (current) {
            com.poketrek.moneo.data.SourceTypeMode.OFF ->
                "$cardCount cards hidden from every review queue"
            com.poketrek.moneo.data.SourceTypeMode.MERGED ->
                "Mixed into each area's queue alongside dialog vocab"
            com.poketrek.moneo.data.SourceTypeMode.SEPARATE ->
                "Split into a separate area sibling — e.g. $separateLabel"
        }
        Text(sublabel, color = Color(0xFF6B7280), fontSize = 12.sp)
    }
}

@Composable
private fun TtsLanguagePicker(
    override: com.poketrek.moneo.data.TtsLanguage?,
    effective: com.poketrek.moneo.data.TtsLanguage,
    onPick: (com.poketrek.moneo.data.TtsLanguage?) -> Unit,
) {
    // The picker uses a nullable T (null = "Auto / follow direction"). Encode
    // it as four discrete buttons mapping to {null, KOREAN, ENGLISH, OFF}.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Voice language",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        SegmentedRow(
            options = listOf(
                null to "Auto",
                com.poketrek.moneo.data.TtsLanguage.KOREAN to "한국어",
                com.poketrek.moneo.data.TtsLanguage.ENGLISH to "English",
                com.poketrek.moneo.data.TtsLanguage.OFF to "Off",
            ),
            selected = override,
            onSelect = onPick,
        )
        val sublabel = if (override == null) {
            "Auto · matches card direction (currently ${effective.summaryLabel()})"
        } else {
            "Pinned to ${effective.summaryLabel()} regardless of card direction"
        }
        Text(sublabel, color = Color(0xFF6B7280), fontSize = 12.sp)
    }
}

private fun com.poketrek.moneo.data.TtsLanguage.summaryLabel(): String = when (this) {
    com.poketrek.moneo.data.TtsLanguage.KOREAN -> "한국어"
    com.poketrek.moneo.data.TtsLanguage.ENGLISH -> "English"
    com.poketrek.moneo.data.TtsLanguage.OFF -> "Off"
}

/**
 * Tinted, padded container for a settings section. Title sits at the top with
 * an optional trailing action (e.g. a small button). Content stacks below
 * with consistent spacing.
 */
@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailingAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFF1F5F9), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = Color(0xFF334155),
            )
            trailingAction?.invoke()
        }
        content()
    }
}

/**
 * Click-to-expand row used to collapse infrequently-touched controls (custom
 * ratios, area-gate threshold, dev tools). Defaults to collapsed; pass
 * `initiallyExpanded = true` to default-open. Optional [summary] shows under
 * the title only while collapsed, so the resting state still hints at state.
 */
@Composable
private fun Expander(
    title: String,
    initiallyExpanded: Boolean = false,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (expanded) "▾" else "▸",
                fontSize = 12.sp,
                color = Color(0xFF6B7280),
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                if (summary != null && !expanded) {
                    Text(summary, color = Color(0xFF6B7280), fontSize = 11.sp)
                }
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun TtsHelpCard(
    status: com.poketrek.moneo.audio.TtsPlayer.Status,
    onTurnOff: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val (title, body) = when (status) {
        com.poketrek.moneo.audio.TtsPlayer.Status.MISSING_DATA ->
            "Korean voice not installed" to
                "Your TTS engine works, but the Korean (한국어) voice pack hasn't been downloaded yet."
        com.poketrek.moneo.audio.TtsPlayer.Status.UNSUPPORTED ->
            "Default TTS engine doesn't speak Korean" to
                "Switch the preferred engine in TTS settings — Samsung TTS or Google TTS both support Korean."
        com.poketrek.moneo.audio.TtsPlayer.Status.ENGINE_FAILED ->
            "TTS engine couldn't start" to
                "Open TTS settings to pick a preferred engine and check that voice data is installed."
        else -> return  // INITIALIZING / READY shouldn't render this card
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFEF3C7), shape = RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF92400E))
        Text(body, fontSize = 12.sp, color = Color(0xFF92400E))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status == com.poketrek.moneo.audio.TtsPlayer.Status.MISSING_DATA) {
                Button(
                    onClick = {
                        runCatching {
                            val i = android.content.Intent(
                                android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(i)
                        }.onFailure { android.util.Log.w("TtsHelp", "INSTALL_TTS_DATA failed", it) }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) { Text("Install Korean voice", fontSize = 12.sp) }
            }
            Button(
                onClick = {
                    runCatching {
                        val i = android.content.Intent("com.android.settings.TTS_SETTINGS")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(i)
                    }.onFailure { android.util.Log.w("TtsHelp", "TTS_SETTINGS failed", it) }
                },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF92400E),
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) { Text("Open TTS settings", fontSize = 12.sp) }
            TextButton(
                onClick = onTurnOff,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text("Turn off", fontSize = 12.sp, color = Color(0xFF92400E))
            }
        }
    }
}

private fun formatSnapshot(s: com.poketrek.emu.LeafGreenRam.Snapshot): String {
    fun hex(v: Int, width: Int) = "0x" + v.toUInt().toString(16).padStart(width, '0').uppercase()
    return "X:${s.playerX} Y:${s.playerY} bank:${s.mapBank}/${s.mapId} mov:${s.movingStatus} sb1=${hex(s.saveBlockPtr, 8)}"
}

/**
 * Free-form ratio input: accepts integers, decimals, or fractions and applies
 * via [parseRatioInput]. Sits below the discrete preset slider; the slider
 * snaps to the closest preset when a non-preset value is set here.
 */
@Composable
private fun CustomRatioRow(
    currentNum: Int,
    currentDen: Int,
    onApply: (num: Int, den: Int) -> Unit,
) {
    var draft by remember(currentNum, currentDen) {
        mutableStateOf(if (currentDen == 1) "$currentNum" else "$currentNum/$currentDen")
    }
    val parsed = parseRatioInput(draft)
    val isError = draft.isNotBlank() && parsed == null
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            label = { Text("Custom ratio (e.g. 2.5 or 5/2)", fontSize = 11.sp) },
            singleLine = true,
            isError = isError,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { parsed?.let { (n, d) -> onApply(n, d) } },
            ),
        )
        Button(
            onClick = { parsed?.let { (n, d) -> onApply(n, d) } },
            enabled = parsed != null && parsed != (currentNum to currentDen),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        ) {
            Text("Set", fontSize = 12.sp)
        }
    }
}

/**
 * Collapsible "Advanced / debug" section. Hidden by default to keep the
 * primary settings sheet calm; expand to reveal destructive actions.
 */
@Composable
private fun AdvancedSection(
    onResetSteps: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }

    Expander(
        title = "Reset budget & step baseline",
        summary = "Zeros tiles + rebases the step counter",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Zeros tiles, clears carry, and rebases the step counter so future steps count from now.",
                    color = Color(0xFF6B7280),
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { confirmReset = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) {
                Text("Reset", fontSize = 12.sp)
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset budget?") },
            text = {
                Text(
                    "This zeros your tile budget and forgets all previously-walked steps. " +
                        "Settings, save states, and ROM are not affected.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onResetSteps()
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Collapsible "Shop" section. Spends tiles to mint Rare Candies into the
 * bag. Only shown for the calibrated US Rev 1 ROM — the bag-pocket and
 * encryption-key offsets are FRLG-specific and would corrupt other builds.
 */
@Composable
private fun ShopSection(
    budget: MovementBudget,
    onBuyRareCandy: (Int) -> EmulatorRunner.BuyResult,
) {
    val tiles by budget.budget.collectAsState()
    val cost by budget.rareCandyCost.collectAsState()
    var costDraft by remember(cost) { mutableStateOf(cost.toString()) }
    var quantity by remember { mutableStateOf(1) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val parsedCost = costDraft.trim().toIntOrNull()
        ?.takeIf { it in MIN_RARE_CANDY_COST..MAX_RARE_CANDY_COST }
    val totalCost = cost.toLong() * quantity.toLong()
    val canAfford = totalCost <= tiles
    Expander(
        title = "Buy Rare Candies",
        summary = "$cost tiles each",
    ) {
        Text(
            "Spend tiles for in-game items. Writes directly to the bag's items pocket.",
            color = Color(0xFF6B7280),
            fontSize = 11.sp,
        )
        Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = costDraft,
                    onValueChange = { costDraft = it.filter(Char::isDigit).take(6) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Tiles per Rare Candy", fontSize = 11.sp) },
                    singleLine = true,
                    isError = costDraft.isNotBlank() && parsedCost == null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { parsedCost?.let { budget.setRareCandyCost(it) } },
                    ),
                )
                Button(
                    onClick = { parsedCost?.let { budget.setRareCandyCost(it) } },
                    enabled = parsedCost != null && parsedCost != cost,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Text("Set", fontSize = 12.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Rare Candy × $quantity", fontSize = 13.sp)
                    Text(
                        "Cost: $totalCost tiles",
                        color = if (canAfford) Color(0xFF6B7280) else Color(0xFFEF4444),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
                Button(
                    onClick = { if (quantity > 1) quantity-- },
                    enabled = quantity > 1,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) { Text("−", fontSize = 14.sp) }
                Text(
                    "$quantity",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    modifier = Modifier.width(28.dp),
                )
                Button(
                    onClick = { if (quantity < 999) quantity++ },
                    enabled = quantity < 999,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) { Text("+", fontSize = 14.sp) }
            }
            Button(
                onClick = {
                    feedback = when (val r = onBuyRareCandy(quantity)) {
                        EmulatorRunner.BuyResult.Ok ->
                            "Bought $quantity Rare Cand${if (quantity == 1) "y" else "ies"}."
                        EmulatorRunner.BuyResult.NotEnoughTiles -> "Not enough tiles."
                        EmulatorRunner.BuyResult.UnsupportedRom -> "Shop disabled on this ROM."
                        EmulatorRunner.BuyResult.NotInGame ->
                            "Save the game once before buying — bag isn't initialized yet."
                        EmulatorRunner.BuyResult.BagFull -> "Bag is full."
                        is EmulatorRunner.BuyResult.StackWouldOverflow ->
                            "Stack would exceed 999 (have ${r.existing})."
                    }
                },
                enabled = canAfford,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
            ) {
                Text("Buy", fontSize = 13.sp)
            }
            feedback?.let {
                Text(
                    it,
                    color = Color(0xFF6B7280),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
    }
}
/**
 * "Set up Korean ROM" entry in the ROMs section. Lets a user who owns a
 * Japanese LeafGreen 1.0 dump build the 2024 Korean fan-translation ROM
 * on-device: we download the authors' public patch bundle and apply it
 * with the native xdelta bridge. We host no ROM — only the user's base
 * plus the authors' patch.
 */
@Composable
private fun KoreanRomSetupSection(
    state: com.poketrek.emu.KoreanRomPatcher.State,
    onSetup: () -> Unit,
) {
    val running = state as? com.poketrek.emu.KoreanRomPatcher.State.Running

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Korean ROM (2024 patch)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(
            "Own a Japanese LeafGreen 1.0 ROM? Build the 99.6%-complete " +
                "2024 Korean translation on your device. The fan-translation " +
                "patch is downloaded from the authors (명군 외); your ROM never " +
                "leaves the device.",
            fontSize = 11.sp,
            color = Color(0xFF6B7280),
        )
        when {
            running != null -> {
                val label = koreanSetupPhaseLabel(running.phase)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(label, fontSize = 12.sp)
                }
            }
            state is com.poketrek.emu.KoreanRomPatcher.State.Success -> {
                Text(
                    "Korean ROM ready and loaded " +
                        "(${com.poketrek.emu.RomIdentity.crc32Hex(state.crc32)}).",
                    color = Color(0xFF059669),
                    fontSize = 12.sp,
                )
            }
            state is com.poketrek.emu.KoreanRomPatcher.State.Error -> {
                Text("Setup failed.", color = Color(0xFFB91C1C), fontSize = 12.sp)
                Text(state.message, fontSize = 11.sp, color = Color(0xFF6B7280))
                Button(onClick = onSetup) { Text("Try again", fontSize = 12.sp) }
            }
            else -> {
                Button(onClick = onSetup) {
                    Text("Set up Korean ROM…", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun CalibrationSection(
    hasCalibration: Boolean,
    hasPendingBaseline: Boolean,
    status: com.poketrek.emu.RomCalibrator.Result?,
    romIdentity: com.poketrek.emu.RomIdentity,
    onBeginCalibration: () -> Unit,
    onFinishCalibration: suspend () -> com.poketrek.emu.RomCalibrator.Result,
    onCancelCalibration: () -> Unit,
    onClearStatus: () -> Unit,
) {
    var capturing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ROM Calibration", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

        when {
            status is com.poketrek.emu.RomCalibrator.Result.Ok -> {
                Text("Calibration saved.", color = Color(0xFF059669), fontSize = 12.sp)
                Text(
                    "SaveBlock1 ptr: 0x${status.saveBlock1PtrAddr.toUInt().toString(16).uppercase()}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                Button(onClick = onClearStatus) { Text("OK") }
            }
            status != null -> {
                Text("Calibration failed.", color = Color(0xFFB91C1C), fontSize = 12.sp)
                Text(formatCalibrationError(status), fontSize = 11.sp, color = Color(0xFF6B7280))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        onClearStatus()
                        onBeginCalibration()
                    }) { Text("Try again") }
                    TextButton(onClick = onClearStatus) { Text("Dismiss") }
                }
            }
            capturing -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("Scanning EWRAM…", fontSize = 12.sp)
                }
            }
            hasPendingBaseline -> {
                Text(
                    "Baseline taken. Walk exactly one tile in the overworld, then tap Capture.",
                    color = Color(0xFFD97706),
                    fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        capturing = true
                        scope.launch {
                            try {
                                onFinishCalibration()
                            } finally {
                                capturing = false
                            }
                        }
                    }) { Text("Capture") }
                    TextButton(onClick = onCancelCalibration) { Text("Cancel") }
                }
            }
            else -> {
                Text(
                    text = if (hasCalibration)
                        "Calibrated for ${romIdentity.crc32Hex} ✓"
                    else
                        "Not calibrated — step gating disabled for this ROM.",
                    color = if (hasCalibration) Color(0xFF059669) else Color(0xFFD97706),
                    fontSize = 12.sp,
                )
                Text(
                    "Stand still in the overworld, then tap below — the menu closes so you can walk one tile.",
                    color = Color(0xFF6B7280),
                    fontSize = 11.sp,
                )
                Button(onClick = onBeginCalibration) {
                    Text(if (hasCalibration) "Re-calibrate" else "Calibrate")
                }
            }
        }
    }
}

private fun formatCalibrationError(r: com.poketrek.emu.RomCalibrator.Result?): String = when (r) {
    is com.poketrek.emu.RomCalibrator.Result.NoChangedHalfword -> "No coordinate change detected. Did you walk exactly one tile? Make sure you took the baseline while standing still."
    is com.poketrek.emu.RomCalibrator.Result.TooManyChangedHalfwords -> "Too noisy (${r.count} candidates). Try again from a quieter game state — minimize NPCs/animations on screen."
    is com.poketrek.emu.RomCalibrator.Result.NoPointerFound -> "Found a coordinate change but no IWRAM pointer references it. Unexpected — please report."
    is com.poketrek.emu.RomCalibrator.Result.MultiplePointers -> "Ambiguous: ${r.addrs.size} candidate pointers. Try recalibrating after a save+reload."
    com.poketrek.emu.RomCalibrator.Result.ReadFailed -> "Memory read failed (no ROM loaded?)."
    else -> "Unknown failure."
}

/**
 * Small Settings-sheet section for the Moneo (Korean learning) feature.
 * Shows enable toggle, current target area, and an "Open Moneo" button.
 * Anything more advanced lives in the dedicated [com.poketrek.moneo.ui.MoneoOverlay].
 */
@Composable
private fun MoneoSection(
    moneo: MoneoModule,
    onOpenMoneo: () -> Unit,
) {
    val enabled by moneo.prefs.enabled.collectAsState()
    val targetAreaId by moneo.prefs.targetAreaId.collectAsState()
    val areas by moneo.repository.areas.collectAsState()
    val cards by moneo.repository.cards.collectAsState()
    val targetArea = areas.firstOrNull { it.id == targetAreaId }
    @Suppress("UNUSED_VARIABLE") val tick = cards
    val totalDue = moneo.repository.totalDueCount()

    val verbatimSentences by moneo.prefs.verbatimSentences.collectAsState()
    val direction by moneo.prefs.direction.collectAsState()
    val ttsLanguageOverride by moneo.prefs.ttsLanguageOverride.collectAsState()
    val effectiveTtsLanguage by moneo.prefs.effectiveTtsLanguage.collectAsState()
    val ttsStatus by moneo.tts.status.collectAsState()
    val ttsAutoFront by moneo.prefs.ttsAutoPlayFront.collectAsState()
    val ttsAutoReveal by moneo.prefs.ttsAutoPlayReveal.collectAsState()
    val ttsRatePct by moneo.prefs.ttsRatePct.collectAsState()
    val muteInReview by moneo.prefs.muteGameInReview.collectAsState()
    val includeSpecies by moneo.prefs.includeSpecies.collectAsState()
    val includeEtymology by moneo.prefs.includeEtymology.collectAsState()
    val movesMode by moneo.prefs.movesMode.collectAsState()
    val abilitiesMode by moneo.prefs.abilitiesMode.collectAsState()
    val areaGateEnabled by moneo.prefs.areaGateEnabled.collectAsState()
    val areaGateThresholdPct by moneo.prefs.areaGateThresholdPct.collectAsState()
    val visitedAreas by moneo.prefs.visitedAreas.collectAsState()
    val ttsOn = effectiveTtsLanguage != com.poketrek.moneo.data.TtsLanguage.OFF

    val sectionTitle = when (direction) {
        com.poketrek.moneo.data.FlashcardDirection.KO_TO_EN -> "Moneo · 몬어 (learn Korean)"
        com.poketrek.moneo.data.FlashcardDirection.EN_TO_KO -> "Moneo · 영어 학습 (learn English)"
    }
    SectionCard(title = sectionTitle) {
        ToggleRow(
            label = "Moneo enabled",
            sublabel = if (enabled) "Review badge visible during play" else "Hidden",
            checked = enabled,
            onCheckedChange = { moneo.prefs.setEnabled(it) },
        )
        DirectionPicker(
            current = direction,
            onPick = { moneo.prefs.setDirection(it) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (targetArea != null) "Studying: ${targetArea.koreanLabel} · ${targetArea.englishName}"
                    else "No area selected",
                    fontSize = 13.sp,
                )
                Text(
                    "$totalDue card${if (totalDue == 1) "" else "s"} due across all areas",
                    color = Color(0xFF6B7280),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onOpenMoneo,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) { Text("Open", fontSize = 12.sp) }
        }

        ToggleRow(
            label = "Mute game audio during review",
            sublabel = if (muteInReview)
                "Game silenced while the review overlay is open"
            else
                "Game keeps playing under the review overlay",
            checked = muteInReview,
            onCheckedChange = { moneo.prefs.setMuteGameInReview(it) },
        )

        Expander(
            title = "Card content",
            summary = buildList {
                if (verbatimSentences) add("ROM sentences") else add("Study sentences")
                if (includeSpecies) add("species")
                if (includeEtymology) add("etymology")
                if (movesMode != com.poketrek.moneo.data.SourceTypeMode.OFF) add("moves")
                if (abilitiesMode != com.poketrek.moneo.data.SourceTypeMode.OFF) add("abilities")
            }.joinToString(" · "),
        ) {
            ToggleRow(
                label = "Spoilers in examples",
                sublabel = if (verbatimSentences)
                    "Real game lines — may spoil dialog/Pokédex entries"
                else
                    "Plain study sentences only — no spoilers",
                checked = verbatimSentences,
                onCheckedChange = { moneo.prefs.setVerbatimSentences(it) },
            )
            ToggleRow(
                label = "Pokémon name cards",
                sublabel = if (includeSpecies)
                    "246 Gen 1+2 species names included in your deck"
                else
                    "Species names hidden — focus on grammar/vocab only",
                checked = includeSpecies,
                onCheckedChange = { moneo.prefs.setIncludeSpecies(it) },
            )
            ToggleRow(
                label = "Etymology root cards",
                sublabel = if (includeEtymology)
                    "142 Korean roots from Pokémon name puns (e.g. 곰 from 링곰)"
                else
                    "Off — enable to study compound roots tangentially",
                checked = includeEtymology,
                onCheckedChange = { moneo.prefs.setIncludeEtymology(it) },
            )
            SourceTypeModePicker(
                label = "Pokémon moves (기술)",
                current = movesMode,
                onPick = { moneo.prefs.setMovesMode(it) },
                separateLabel = "Route N · 기술",
                cardCount = 349,
            )
            SourceTypeModePicker(
                label = "Pokémon abilities (특성)",
                current = abilitiesMode,
                onPick = { moneo.prefs.setAbilitiesMode(it) },
                separateLabel = "Route N · 특성",
                cardCount = 77,
            )
        }

        // Default-open when there's something the user might want to act on:
        // they've turned TTS on, OR the engine reports a problem worth showing.
        val ttsHasIssue = ttsOn &&
            ttsStatus != com.poketrek.moneo.audio.TtsPlayer.Status.INITIALIZING &&
            ttsStatus != com.poketrek.moneo.audio.TtsPlayer.Status.READY
        Expander(
            title = "Read aloud (TTS)",
            initiallyExpanded = ttsHasIssue,
            summary = when {
                !ttsOn -> "Off"
                ttsHasIssue -> "Needs setup"
                else -> "${effectiveTtsLanguage.summaryLabel()} · ${"%.2f".format(ttsRatePct / 100f)}×"
            },
        ) {
            if (ttsHasIssue) {
                TtsHelpCard(
                    status = ttsStatus,
                    onTurnOff = {
                        moneo.prefs.setTtsLanguage(com.poketrek.moneo.data.TtsLanguage.OFF)
                    },
                )
            }
            TtsLanguagePicker(
                override = ttsLanguageOverride,
                effective = effectiveTtsLanguage,
                onPick = { moneo.prefs.setTtsLanguage(it) },
            )
            if (ttsOn) {
                ToggleRow(
                    label = "Auto-play headword",
                    sublabel = if (ttsAutoFront)
                        "Speak the front-side text automatically when a new card appears"
                    else
                        "Front side stays silent until you tap 🔊",
                    checked = ttsAutoFront,
                    onCheckedChange = { moneo.prefs.setTtsAutoPlayFront(it) },
                )
                ToggleRow(
                    label = "Auto-play example on reveal",
                    sublabel = if (ttsAutoReveal)
                        "Speak the example sentence as soon as you reveal the back"
                    else
                        "Example stays silent until you tap 🔊",
                    checked = ttsAutoReveal,
                    onCheckedChange = { moneo.prefs.setTtsAutoPlayReveal(it) },
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Speech rate: ${"%.2f".format(ttsRatePct / 100f)}×",
                        fontSize = 12.sp,
                        color = Color(0xFF374151),
                    )
                    Slider(
                        value = ttsRatePct.toFloat(),
                        onValueChange = { moneo.prefs.setTtsRatePct(it.toInt()) },
                        valueRange = com.poketrek.moneo.data.MIN_TTS_RATE_PCT.toFloat()..
                            com.poketrek.moneo.data.MAX_TTS_RATE_PCT.toFloat(),
                    )
                }
            }
        }

        val readingHelp by moneo.prefs.readingHelp.collectAsState()
        ToggleRow(
            label = "Reading help in dialogue",
            sublabel = "Korean 2024 ROM: lists the words of the open message box beside the game",
            checked = readingHelp,
            onCheckedChange = { moneo.prefs.setReadingHelp(it) },
        )

        AskFolderExpander(moneo)
        StudyWordsExpander(moneo)

        // Hard area-gate: blocks the player from physically entering a new area
        // until they know enough of the words its text uses.
        Expander(
            title = "Area gate",
            initiallyExpanded = areaGateEnabled,
            summary = if (areaGateEnabled) "Enter at ≥${areaGateThresholdPct}% readable" else "Off",
        ) {
            ToggleRow(
                label = "Block at boundaries",
                sublabel = if (areaGateEnabled)
                    "Blocks DPAD at the edge of a new area until you know enough of the words it uses"
                else
                    "Off — area transitions are unrestricted",
                checked = areaGateEnabled,
                onCheckedChange = { moneo.prefs.setAreaGateEnabled(it) },
            )
            if (areaGateEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Readable text needed: ${areaGateThresholdPct}% (early areas ramp up from ${minOf(com.poketrek.moneo.data.GateThreshold.RAMP_START_PCT, areaGateThresholdPct)}%)",
                        fontSize = 12.sp,
                        color = Color(0xFF374151),
                    )
                    Slider(
                        value = areaGateThresholdPct.toFloat(),
                        onValueChange = {
                            moneo.prefs.setAreaGateThresholdPct(it.toInt())
                        },
                        valueRange = 0f..100f,
                        steps = 9, // 10-percent increments (0, 10, 20, ..., 100)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Areas already visited are never blocked (${visitedAreas.size} so far)",
                        fontSize = 12.sp,
                        color = Color(0xFF374151),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { moneo.prefs.clearVisitedAreas() },
                        enabled = visitedAreas.isNotEmpty(),
                    ) { Text("Forget (new game)") }
                }
            }
        }

        CorrectionEndpointExpander(moneo)
    }
}

/**
 * Settings row for the optional VPS endpoint that receives in-app
 * "Send to server" correction submissions. Collapsed by default; the
 * GitHub-Issue path always works without this configured.
 */
@Composable
private fun CorrectionEndpointExpander(moneo: MoneoModule) {
    val vpsUrl by moneo.prefs.correctionVpsUrl.collectAsState()
    var draft by remember(vpsUrl) { mutableStateOf(vpsUrl ?: "") }
    Expander(
        title = "Corrections",
        summary = if (vpsUrl.isNullOrBlank()) "GitHub Issue only" else "GitHub + server",
    ) {
        Text(
            "Korean speakers can tap ✎ Report on any sentence to suggest a fix.",
            fontSize = 12.sp,
            color = Color(0xFF6B7280),
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Server URL (optional)") },
            placeholder = { Text("https://your-vps.example.com/moneo/corrections") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Uri),
            keyboardActions = KeyboardActions(onDone = { moneo.prefs.setCorrectionVpsUrl(draft) }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { moneo.prefs.setCorrectionVpsUrl(draft) },
                enabled = draft != (vpsUrl ?: ""),
            ) { Text("Save", fontSize = 12.sp) }
            if (!vpsUrl.isNullOrBlank()) {
                TextButton(onClick = {
                    draft = ""
                    moneo.prefs.setCorrectionVpsUrl(null)
                }) { Text("Clear") }
            }
        }
    }
}

/**
 * The words to go over (Again/Hard lately, and newly learned), written to
 * study-words.md in the ask folder whenever the flashcards close;
 * tools/ask_bridge copies it into a Google Doc a Claude project reads.
 */
@Composable
private fun StudyWordsExpander(moneo: MoneoModule) {
    val folder by moneo.prefs.askFolder.collectAsState()
    val status by moneo.studyWordsStatus.collectAsState()
    Expander(
        title = "Study words for Claude",
        summary = if (folder == null) "Needs the ask folder" else status ?: "On",
    ) {
        Text(
            "Writes ${MoneoModule.STUDY_WORDS_FILE} to the ask folder with the words you marked Again/Hard and " +
                "the words you started in the last ${com.poketrek.moneo.data.StudyDigest.WINDOW_DAYS} days, " +
                "whenever you close the flashcards. The Termux watcher copies it into a Google Doc for your Claude project.",
            fontSize = 12.sp,
            color = Color(0xFF6B7280),
        )
        if (folder != null) {
            TextButton(onClick = { moneo.exportStudyWords() }) { Text("Update now", fontSize = 12.sp) }
        }
    }
}

/**
 * Folder shared with the LLM watcher (tools/ask_bridge/moneo-ask.sh). Picked
 * with the system folder picker, so it can be a folder in Termux's home;
 * setting it shows the 💬 button in game.
 */
@Composable
private fun AskFolderExpander(moneo: MoneoModule) {
    val folder by moneo.prefs.askFolder.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { android.util.Log.w("AskFolder", "Could not persist folder permission", it) }
        moneo.prefs.setAskFolder(uri.toString())
    }
    val apiKey by moneo.prefs.askApiKey.collectAsState()
    var keyDraft by remember { mutableStateOf("") }
    val label = folder?.let { android.net.Uri.parse(it).lastPathSegment?.substringAfterLast(':') ?: it }
    Expander(
        title = "Ask an LLM (💬)",
        summary = when {
            apiKey != null -> "Claude API"
            label != null -> label
            else -> "Off"
        },
    ) {
        Text("Claude API key", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(
            "Sends the message box, its words and a screenshot straight to Claude " +
                "(${com.poketrek.moneo.ask.ClaudeAsker.MODEL}) with your own key from " +
                "console.anthropic.com. Usage is billed to your account. The key stays on this phone.",
            fontSize = 12.sp,
            color = Color(0xFF6B7280),
        )
        if (apiKey == null) {
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                label = { Text("sk-ant-…", fontSize = 12.sp) },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { moneo.prefs.setAskApiKey(keyDraft); keyDraft = "" },
                enabled = keyDraft.isNotBlank(),
            ) { Text("Save key", fontSize = 12.sp) }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Key …${apiKey!!.takeLast(4)}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                TextButton(onClick = { moneo.prefs.setAskApiKey(null) }) { Text("Remove key", fontSize = 12.sp) }
            }
        }

        Text("Or: Claude Code in Termux", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(
            "Writes the message box, its words and a screenshot to a folder; " +
                "tools/ask_bridge/moneo-ask.sh (e.g. Claude Code in Termux) writes the answer back. " +
                "In Termux, make ~/moneo-ask and pick it here via the Termux entry in the picker. " +
                "The folder also receives the study-words file. With an API key set, questions use the key.",
            fontSize = 12.sp,
            color = Color(0xFF6B7280),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pick.launch(null) }) {
                Text(if (folder == null) "Choose folder" else "Change folder", fontSize = 12.sp)
            }
            if (folder != null) {
                TextButton(onClick = { moneo.prefs.setAskFolder(null) }) { Text("Turn off", fontSize = 12.sp) }
            }
        }
    }
}

/**
 * Messages the in-game reading helper saw but couldn't match, collected
 * during real play. Share sends the list as text (e.g. to yourself) so the
 * misses can be fixed; tools/moneo/pull_unmatched_dialog.py reads the same
 * file over adb.
 */
@Composable
private fun UnmatchedDialogSection(moneo: MoneoModule) {
    val log = moneo.unmatchedDialog
    val n by log.count.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    Expander(
        title = "Reading help: missed dialogue",
        summary = if (n == 0) "None yet" else "$n message${if (n == 1) "" else "s"}",
    ) {
        Text(
            "Messages shown in a box that the reading panel couldn't identify.",
            color = Color(0xFF6B7280),
            fontSize = 11.sp,
        )
        if (n > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, log.report())
                    context.startActivity(android.content.Intent.createChooser(send, "Share missed dialogue"))
                }) { Text("Share", fontSize = 12.sp) }
                TextButton(onClick = { log.clear() }) { Text("Clear", fontSize = 12.sp) }
            }
        }
    }
}

/**
 * Dev-only EWRAM text capture controls (Phase 2 corpus path). Surfaced in
 * the Developer SectionCard, behind an Expander, so it stays out of normal
 * users' way but is one tap away when probing the Korean charmap.
 */
@Composable
private fun RuntimeTextCaptureSection(moneo: MoneoModule) {
    val capture = moneo.ramCapture ?: return
    val captureOn by capture.enabled.collectAsState()
    val runs by capture.runsCaptured.collectAsState()
    Expander(
        title = "Runtime text capture (dev)",
        summary = if (captureOn) "Sampling · $runs run${if (runs == 1) "" else "s"}" else "Off",
    ) {
        ToggleRow(
            label = "Capture runtime text",
            sublabel = if (captureOn)
                "Sampling EWRAM diffs · $runs run${if (runs == 1) "" else "s"} captured"
            else
                "Records candidate Korean strings to filesDir/moneo/capture.bin",
            checked = captureOn,
            onCheckedChange = { capture.setEnabled(it) },
        )
        if (runs > 0) {
            Text(
                "Capture file: ${capture.captureSizeBytes()} bytes",
                color = Color(0xFF6B7280),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
            TextButton(
                onClick = { capture.resetCapture() },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) { Text("Reset capture", fontSize = 11.sp) }
        }
        // Probe button: read gStringVar1-4 synchronously and log to Logcat
        // tag "MoneoProbe". Take a screenshot at the same time to correlate
        // hex bytes → on-screen Hangul characters (charmap derivation).
        var probeResult by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                        val result = capture.probeTextBuffers()
                        val summary = result.entries.joinToString("\n") { (k, v) ->
                            "$k: ${v?.take(60) ?: "(null)"}"
                        }
                        probeResult = summary
                    }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) { Text("Probe gStringVar1-4", fontSize = 11.sp) }
            Text(
                "→ also in Logcat tag MoneoProbe",
                color = Color(0xFF6B7280),
                fontSize = 10.sp,
            )
        }
        // Decode visible Korean text via VramTextReader + KoreanCharmap.
        var decodeResult by remember { mutableStateOf("") }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                        val charmap = com.poketrek.moneo.corpus.KoreanCharmap.get(ctx)
                        val lines = capture.decodeVisibleText(charmap)
                        decodeResult = if (lines.isEmpty()) "(no text)"
                        else lines.joinToString("\n")
                        android.util.Log.i("MoneoProbe", "DecodeKO:\n$decodeResult")
                    }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) { Text("Decode KO", fontSize = 11.sp) }
            Text(
                "(${decodeResult.lines().size} lines)",
                color = Color(0xFF6B7280),
                fontSize = 10.sp,
            )
        }
        if (decodeResult.isNotEmpty()) {
            Text(
                decodeResult,
                color = Color(0xFFFFE0B0),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (probeResult.isNotEmpty()) {
            Text(
                probeResult,
                color = Color(0xFFE0E0E0),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * ROM library: clickable rows for each cached ROM, current one highlighted.
 * Hidden when nothing has been cached yet (first launch).
 */
@Composable
private fun RomLibrarySection(
    slots: List<com.poketrek.emu.RomCache.Slot>,
    currentCrc32: Long?,
    onLoad: (Long) -> Unit,
    onRemove: (Long) -> Unit,
) {
    if (slots.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (slot in slots) {
            val isCurrent = slot.crc32 == currentCrc32
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (isCurrent) Color(0xFF1F3A2E) else Color(0xFF1F2937),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable(enabled = !isCurrent) { onLoad(slot.crc32) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        slot.label,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                    Text(
                        if (isCurrent) "Loaded · ${slot.sizeBytes / 1024} KB"
                        else "${slot.sizeBytes / 1024} KB",
                        color = if (isCurrent) Color(0xFF6EE7B7) else Color(0xFF9CA3AF),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
                if (!isCurrent) {
                    TextButton(
                        onClick = { onRemove(slot.crc32) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("Remove", color = Color(0xFFEF4444), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}