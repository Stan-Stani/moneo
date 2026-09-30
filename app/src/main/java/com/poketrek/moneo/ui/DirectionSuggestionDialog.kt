package com.poketrek.moneo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.poketrek.emu.RomIdentity
import com.poketrek.emu.RomVariant
import com.poketrek.moneo.MoneoModule
import com.poketrek.moneo.data.FlashcardDirection
import java.util.Locale

/**
 * One-time prompt that asks the user whether to flip moneo to the
 * direction implied by their device locale + the loaded ROM. Once the
 * user accepts or declines (or has already toggled direction by hand),
 * [com.poketrek.moneo.data.MoneoPrefs.directionWasManuallySet] becomes
 * true and the dialog never reappears.
 *
 * The two scenarios that trigger a suggestion:
 *  - US Rev 1 ROM + ko-* device locale → suggest [FlashcardDirection.EN_TO_KO]
 *    (a Korean speaker who picked up the English ROM is almost certainly
 *    here to study English, not Korean from English text).
 *  - KR ROM (any variant) + en-* device locale → suggest [FlashcardDirection.KO_TO_EN].
 *    This is already the default, so we only prompt if the user somehow
 *    flipped to EN_TO_KO previously without setting the manual flag — in
 *    practice this branch rarely fires.
 *
 * If the suggestion already matches [com.poketrek.moneo.data.MoneoPrefs.direction],
 * no prompt is shown and the manual flag is silently set so we don't
 * re-evaluate on every launch.
 */
@Composable
fun DirectionSuggestionDialog(
    moneo: MoneoModule,
    romIdentity: RomIdentity?,
) {
    val direction by moneo.prefs.direction.collectAsState()
    val manualSet by moneo.prefs.directionWasManuallySet.collectAsState()
    var pendingSuggestion by remember { mutableStateOf<FlashcardDirection?>(null) }

    LaunchedEffect(romIdentity?.crc32, manualSet) {
        if (manualSet) return@LaunchedEffect
        val variant = romIdentity?.variant ?: return@LaunchedEffect
        val suggested = suggestDirectionFor(variant, Locale.getDefault().language)
        // No suggestion (UNKNOWN ROM, neutral locale) → wait for next ROM
        // load. Suggestion already matches current direction → also wait;
        // the user may swap ROMs into a mismatch later and we'd want to
        // prompt then. We only mark manual once they actually engage with
        // a dialog or toggle direction by hand.
        if (suggested == null || suggested == direction) return@LaunchedEffect
        pendingSuggestion = suggested
    }

    val suggestion = pendingSuggestion ?: return
    val (titleText, bodyText, switchLabel) = when (suggestion) {
        FlashcardDirection.EN_TO_KO -> Triple(
            "Use English-learning mode?",
            "한국인을 위한 영어 학습 모드 — 카드 앞면에 영어, 뒷면에 한국어가 표시됩니다.\n\n(English text on the front of every card; tap to reveal the Korean.)",
            "Switch to English mode",
        )
        FlashcardDirection.KO_TO_EN -> Triple(
            "Use Korean-learning mode?",
            "Cards will show Korean on the front; tap to reveal the English meaning.",
            "Switch to Korean mode",
        )
    }

    AlertDialog(
        onDismissRequest = {
            // Tap outside / back press should not lock in the choice; user
            // will see the dialog again next launch unless they make a real
            // pick. Nothing changes.
            pendingSuggestion = null
        },
        title = { Text(titleText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(bodyText)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                moneo.prefs.setDirection(suggestion) // marks manual + flips
                pendingSuggestion = null
            }) { Text(switchLabel) }
        },
        dismissButton = {
            TextButton(onClick = {
                moneo.prefs.acknowledgeDirectionSuggestion()
                pendingSuggestion = null
            }) { Text("Keep current") }
        },
    )
}

/**
 * Returns the direction to suggest based on the active ROM + the device
 * primary locale. Null = "no suggestion applies, leave the user alone."
 *
 * Pure function so the rule is unit-testable without an Android context.
 * Locale comparison is on the language code (`"ko"` / `"en"`) rather than
 * full locale strings — `ko-KR`, `ko-KP`, and a hypothetical `ko` all
 * count.
 */
fun suggestDirectionFor(variant: RomVariant, language: String): FlashcardDirection? {
    val isKoreanLocale = language.equals("ko", ignoreCase = true)
    val isEnglishLocale = language.equals("en", ignoreCase = true)
    return when (variant) {
        RomVariant.LEAFGREEN_US_REV1 ->
            if (isKoreanLocale) FlashcardDirection.EN_TO_KO else null
        RomVariant.LEAFGREEN_KOREAN, RomVariant.LEAFGREEN_KR_2024 ->
            if (isEnglishLocale) FlashcardDirection.KO_TO_EN else null
        RomVariant.LEAFGREEN_JP_10, RomVariant.UNKNOWN -> null
    }
}

