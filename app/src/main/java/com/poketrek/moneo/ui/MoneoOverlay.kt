package com.poketrek.moneo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poketrek.moneo.MoneoModule

/**
 * Full-screen Moneo overlay. Currently has two sub-screens: an area picker
 * and a per-area review screen. No NavHost — a local state enum is enough.
 *
 * [romCrc32Hex] is the loaded ROM's CRC32 in `0xXXXXXXXX` form, forwarded
 * into the correction-report flow so reported sentences carry ROM context
 * without the moneo module having to know about the emulator runner.
 */
@Composable
fun MoneoOverlay(
    module: MoneoModule,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    romCrc32Hex: String? = null,
    /** Open straight into this area's review (e.g. from the area-gate lock chip). */
    initialArea: String? = null,
) {
    var selectedArea by remember(initialArea) { mutableStateOf(initialArea) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xF0111827)),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Header bar.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        "Moneo · 몬어",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    Text(
                        if (selectedArea == null) "Pick an area to study"
                        else module.repository.areas.value.firstOrNull { it.id == selectedArea }
                            ?.let { "${it.koreanLabel} · ${it.englishName}" } ?: "",
                        color = Color(0xFF9CA3AF),
                        fontSize = 11.sp,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selectedArea != null) {
                        Button(
                            onClick = { selectedArea = null },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        ) { Text("Areas", fontSize = 12.sp) }
                    }
                    Button(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF374151)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    ) { Text("Close", fontSize = 12.sp) }
                }
            }

            val area = selectedArea
            if (area == null) {
                AreaPicker(
                    module = module,
                    onPickArea = { id ->
                        selectedArea = id
                        module.prefs.setTargetAreaId(id)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ReviewScreen(
                    module = module,
                    areaId = area,
                    onDone = { selectedArea = null },
                    modifier = Modifier.fillMaxSize(),
                    romCrc32Hex = romCrc32Hex,
                )
            }
        }
    }
}

@Composable
private fun AreaPicker(
    module: MoneoModule,
    onPickArea: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val areas by module.repository.areas.collectAsState()
    val cards by module.repository.cards.collectAsState()

    if (areas.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                "No areas configured. Check assets/moneo/areas.json",
                color = Color(0xFFFBBF24),
                fontSize = 13.sp,
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = modifier.padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(areas, key = { it.id }) { area ->
            val total = module.repository.vocabForArea(area.id).size
            val due = module.repository.dueCountForArea(area.id)
            // Read the cards flow as well so this card recomposes when state changes.
            @Suppress("UNUSED_VARIABLE") val tick = cards
            AreaCard(
                koreanLabel = area.koreanLabel,
                englishName = area.englishName,
                ordinal = area.ordinal,
                total = total,
                due = due,
                onClick = { onPickArea(area.id) },
            )
        }
    }
}

@Composable
private fun AreaCard(
    koreanLabel: String,
    englishName: String,
    ordinal: Int,
    total: Int,
    due: Int,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .background(Color(0xFF1F2937), shape = RoundedCornerShape(10.dp))
            .clickable(enabled = total > 0, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "$ordinal · $englishName",
            color = Color(0xFF9CA3AF),
            fontSize = 10.sp,
        )
        Text(
            koreanLabel,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "$total words",
                color = Color(0xFF9CA3AF),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            if (due > 0) {
                Text(
                    "$due due",
                    color = Color(0xFFFBBF24),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
            } else if (total > 0) {
                Text(
                    "✓ caught up",
                    color = Color(0xFF10B981),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            } else {
                Text(
                    "(empty)",
                    color = Color(0xFF6B7280),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
