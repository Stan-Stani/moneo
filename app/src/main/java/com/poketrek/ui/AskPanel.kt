package com.poketrek.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poketrek.moneo.MoneoModule

/** Canned questions for the quick buttons, phrased for the watcher's prompt. */
private const val ASK_SIMPLER = "이 대사를 더 쉬운 한국어로 설명해 줘."
private const val ASK_ENGLISH = "Translate this line and explain the hard parts in English."

/** Opens [AskPanel]; shown once an ask folder is set up. Lights up when a reply is waiting unread. */
@Composable
fun AskChip(moneo: MoneoModule, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val folder by moneo.prefs.askFolder.collectAsState()
    if (folder == null) return
    val exchanges by moneo.ask.exchanges.collectAsState()
    val pending = exchanges.any { it.pending }
    Text(
        if (pending) "💬…" else "💬",
        fontSize = 18.sp,
        modifier = modifier
            .background(if (open) Color(0xCC2D5FB3) else Color(0x99111827), RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/**
 * Ask an LLM about the screen (see [com.poketrek.moneo.ask.AskBridge]): quick
 * buttons for the usual questions, a text box for anything else, and this
 * session's answers above them. Sits over the top of the game so the message
 * box at the bottom stays readable.
 */
@Composable
fun AskPanel(
    moneo: MoneoModule,
    onAsk: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val exchanges by moneo.ask.exchanges.collectAsState()
    val onScreen = moneo.dialogReader?.current?.collectAsState()?.value
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(exchanges) { if (exchanges.isNotEmpty()) listState.animateScrollToItem(exchanges.size - 1) }

    Column(
        modifier = modifier
            .background(Color(0xF0111827), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                onScreen?.message?.replace('\n', ' ') ?: "No message box open — the screenshot is sent",
                color = Color(0xFF9CA3AF),
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (exchanges.isNotEmpty()) {
                Text("clear", color = Color(0xFF9CA3AF), fontSize = 12.sp,
                    modifier = Modifier.clickable { moneo.ask.clear() }.padding(horizontal = 8.dp))
            }
            Text("✕", color = Color.White, fontSize = 16.sp,
                modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 6.dp))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(exchanges, key = { it.id }) { e ->
                Column {
                    Text("› ${e.question}", color = Color(0xFF93C5FD), fontSize = 12.sp)
                    when {
                        e.reply != null -> Text(e.reply, color = Color.White, fontSize = 14.sp)
                        e.error != null -> Text("⚠ ${e.error}", color = Color(0xFFFCA5A5), fontSize = 12.sp)
                        else -> Text("thinking…", color = Color(0xFF6B7280), fontSize = 12.sp)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            QuickAsk("쉽게", onClick = { onAsk(ASK_SIMPLER) })
            QuickAsk("English", onClick = { onAsk(ASK_ENGLISH) })
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Ask anything…", fontSize = 12.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (draft.isNotBlank()) { onAsk(draft.trim()); draft = "" }
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color.White,
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun QuickAsk(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(Color(0xFF2D5FB3), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}
