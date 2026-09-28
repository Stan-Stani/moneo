package com.poketrek.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poketrek.moneo.MoneoModule
import com.poketrek.moneo.srs.CardState

/**
 * In-game reading helper: while a message box is open on the 2024 KR ROM,
 * lists the deck words the line uses with their glosses, ticks the ones the
 * player knows, and shows what share of them that is. Tapping a word queues
 * it to study next (★). Sits in the black column right of the game screen.
 */
@Composable
fun ReadingHelperPanel(moneo: MoneoModule, modifier: Modifier = Modifier) {
    val reader = moneo.dialogReader ?: return
    val enabled by moneo.prefs.readingHelp.collectAsState()
    val onScreen by reader.current.collectAsState()
    val line = onScreen?.line
    if (!enabled || line == null) return
    val cards by moneo.repository.cards.collectAsState()
    val studyNext by moneo.repository.studyNext.collectAsState()
    var collapsed by remember { mutableStateOf(false) }

    data class Row(val korean: String, val gloss: String, val id: String?, val known: Boolean)
    val rows = remember(line, cards) {
        line.words.mapNotNull { w ->
            val entries = moneo.repository.visibleEntriesFor(w)
            if (entries.isEmpty()) return@mapNotNull null
            val known = entries.any { e ->
                cards[e.id]?.let { it.suspended || it.snapshot.state == CardState.REVIEW } == true
            }
            val primary = entries.firstOrNull { cards[it.id]?.snapshot?.state != CardState.REVIEW } ?: entries.first()
            Row(w, primary.gloss, primary.id, known)
        }
    }
    if (rows.isEmpty()) return
    val pct = rows.count { it.known } * 100 / rows.size

    Column(
        modifier = modifier
            .width(148.dp)
            .background(Color(0xE6111827), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            "📖 $pct% known" + if (collapsed) " ▸" else " ▾",
            color = if (pct >= 90) Color(0xFF6EE7B7) else Color(0xFFFCD34D),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().clickable { collapsed = !collapsed },
        )
        if (!collapsed) {
            Column(
                modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rows.forEach { r ->
                    val queued = r.id != null && r.id in studyNext
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = r.id != null && !r.known) { r.id?.let { moneo.repository.toggleStudyNext(it) } }
                            .padding(vertical = 2.dp),
                    ) {
                        Text(
                            (if (r.known) "✓ " else if (queued) "★ " else "") + r.korean,
                            color = when {
                                r.known -> Color(0xFF6EE7B7)
                                queued -> Color(0xFFFCD34D)
                                else -> Color.White
                            },
                            fontSize = 13.sp,
                            fontWeight = if (r.known) FontWeight.Normal else FontWeight.SemiBold,
                        )
                        Text(
                            r.gloss,
                            color = Color(0xFF9CA3AF),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Text("tap a word: study next ★", color = Color(0xFF6B7280), fontSize = 9.sp)
        }
    }
}
