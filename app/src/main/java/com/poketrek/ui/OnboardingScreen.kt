package com.poketrek.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poketrek.emu.KoreanRomPatcher

/** Progress text for a running Korean ROM build; shared with the settings sheet. */
internal fun koreanSetupPhaseLabel(phase: KoreanRomPatcher.Phase): String = when (phase) {
    KoreanRomPatcher.Phase.DOWNLOADING_PATCH -> "Downloading patch…"
    KoreanRomPatcher.Phase.EXTRACTING_PATCH -> "Extracting patch…"
    KoreanRomPatcher.Phase.PATCHING -> "Patching ROM…"
    KoreanRomPatcher.Phase.VERIFYING -> "Verifying…"
}

/**
 * First-run screen, shown whenever no ROM is loaded. Explains the idea in
 * three lines and offers the two ways in: build the Korean ROM from a
 * Japanese 1.0 base (the recommended path), or pick a ROM directly.
 * Landscape puts the two choices side by side; portrait stacks them.
 */
@Composable
fun OnboardingScreen(
    koreanSetupState: KoreanRomPatcher.State,
    romPickError: String?,
    onSetupKoreanRom: () -> Unit,
    onPickRom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight && maxWidth >= 600.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Moneo · 몬어", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Learn to read Korean by playing Pokémon LeafGreen.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Column(
                modifier = Modifier.widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Bullet("🚶", "Walking in real life earns tiles. Each step in the game spends one.")
                Bullet("📚", "The Korean words of each area become flashcards.")
                Bullet("🔓", "Learn enough of the next area's words and it opens up.")
            }

            val koreanCard: @Composable (Modifier) -> Unit = { m ->
                ChoiceCard(
                    title = "Build the Korean ROM",
                    badge = "Recommended",
                    body = "Pick your Japanese LeafGreen 1.0 ROM. Moneo downloads the " +
                        "2024 fan-translation patch (by 명군 외) and builds the Korean " +
                        "game on your phone.",
                    modifier = m,
                ) {
                    KoreanSetupStatus(koreanSetupState, onSetupKoreanRom)
                }
            }
            val directCard: @Composable (Modifier) -> Unit = { m ->
                ChoiceCard(
                    title = "I already have a ROM",
                    body = "The patched Korean ROM (2024), or English LeafGreen (US Rev 1). " +
                        "English plays with the step gate, but has no Korean flashcards.",
                    modifier = m,
                ) {
                    OutlinedButton(
                        onClick = onPickRom,
                        enabled = koreanSetupState !is KoreanRomPatcher.State.Running,
                    ) { Text("Choose ROM…") }
                    if (romPickError != null) {
                        Text(romPickError, color = Color(0xFFB91C1C), fontSize = 12.sp)
                    }
                }
            }
            if (wide) {
                Row(
                    modifier = Modifier.widthIn(max = 820.dp).height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    koreanCard(Modifier.weight(1f).fillMaxHeight())
                    directCard(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(
                    modifier = Modifier.widthIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    koreanCard(Modifier.fillMaxWidth())
                    directCard(Modifier.fillMaxWidth())
                }
            }
            Text(
                "Moneo doesn't include any ROM. Use a copy you legally own.",
                fontSize = 11.sp,
                color = Color(0xFF6B7280),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Bullet(icon: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(icon)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ChoiceCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    actions: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxHeight().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                if (badge != null) {
                    Text(badge, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(body, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            actions()
        }
    }
}

@Composable
private fun KoreanSetupStatus(
    state: KoreanRomPatcher.State,
    onSetup: () -> Unit,
) {
    when (state) {
        is KoreanRomPatcher.State.Running -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(koreanSetupPhaseLabel(state.phase), fontSize = 13.sp)
        }
        is KoreanRomPatcher.State.Error -> {
            Text("Setup failed: ${state.message}", color = Color(0xFFB91C1C), fontSize = 12.sp)
            Button(onClick = onSetup) { Text("Try again") }
        }
        // Success loads the ROM, which replaces this screen.
        else -> Button(onClick = onSetup) { Text("Pick Japanese ROM…") }
    }
}

/**
 * Shown when "Choose ROM" picks the Japanese 1.0 dump: that file is the
 * Korean patch's base, so offer to build the Korean game from it.
 */
@Composable
fun JapaneseBaseDialog(onBuildKorean: () -> Unit, onPlayAsIs: () -> Unit) {
    AlertDialog(
        onDismissRequest = onPlayAsIs,
        title = { Text("Japanese LeafGreen 1.0") },
        text = {
            Text(
                "This is the ROM the Korean translation is built from. Build the " +
                    "Korean game from it now? Moneo downloads the fan-translation " +
                    "patch; your ROM stays on the device.",
            )
        },
        confirmButton = { Button(onClick = onBuildKorean) { Text("Build Korean ROM") } },
        dismissButton = { TextButton(onClick = onPlayAsIs) { Text("Play in Japanese") } },
    )
}
