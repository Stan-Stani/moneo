package com.poketrek.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poketrek.emu.AreaGateDecision
import com.poketrek.emu.MovementGate
import com.poketrek.moneo.MoneoModule
import kotlin.math.roundToInt

/**
 * Floating chip that appears when MovementGate's MoneoAreaGate has recently
 * blocked a press. Shows the locked destination area's Korean label plus a
 * compact 'currentMaturity%/threshold%' progress.
 *
 * Latches via [MovementGate.persistentAreaGateDecision]: stays visible
 * across the bounce-back animation and remains up afterwards until the
 * player walks off the post-bounce tile under their own input.
 */
@Composable
fun AreaGateLockChip(
    gate: MovementGate,
    moneo: MoneoModule,
    /** Tapping the chip studies the blocked area; receives its area id. */
    onStudy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val decision by gate.persistentAreaGateDecision.collectAsState()
    if (!decision.shouldBlock) return
    val areas by moneo.repository.areas.collectAsState()
    val area = areas.firstOrNull { it.id == decision.destArea }
    val label = area?.koreanLabel ?: decision.destArea ?: "area"
    val curPct = (decision.maturityFraction * 100f).roundToInt().coerceIn(0, 100)
    val thresholdPct = (decision.thresholdFraction * 100f).roundToInt().coerceIn(0, 100)
    Row(
        modifier = modifier
            .background(Color(0xCCB91C1C), shape = RoundedCornerShape(10.dp))
            .clickable(enabled = decision.destArea != null) { decision.destArea?.let(onStudy) }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("🔒", fontSize = 12.sp)
        Text(
            "$label ($curPct%/$thresholdPct%)",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
        Text(
            "Study ›",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
    }
}
