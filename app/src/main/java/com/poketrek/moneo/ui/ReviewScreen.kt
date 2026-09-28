package com.poketrek.moneo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.poketrek.BuildConfig
import com.poketrek.moneo.MoneoModule
import com.poketrek.moneo.correction.CorrectionDialog
import com.poketrek.moneo.correction.CorrectionReport
import com.poketrek.moneo.correction.CorrectionSubmitter
import com.poketrek.moneo.correction.DEFAULT_GITHUB_REPO_NAME
import com.poketrek.moneo.correction.DEFAULT_GITHUB_REPO_OWNER
import com.poketrek.moneo.correction.GithubIssueSubmitter
import com.poketrek.moneo.correction.VpsSubmitter
import com.poketrek.moneo.data.CardRecord
import com.poketrek.moneo.data.FlashcardDirection
import com.poketrek.moneo.data.SentenceEntry
import com.poketrek.moneo.data.TtsLanguage
import com.poketrek.moneo.data.VocabEntry
import com.poketrek.moneo.srs.CardSnapshot
import com.poketrek.moneo.srs.CardState
import com.poketrek.moneo.srs.Rating
import androidx.compose.ui.platform.LocalContext

/**
 * Per-area flashcard review. Shows one card at a time; reveal then grade.
 * On grade, requests the next due card from the repository. Empty state
 * sends the user back to the area picker.
 */
@Composable
fun ReviewScreen(
    module: MoneoModule,
    areaId: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    romCrc32Hex: String? = null,
) {
    val cards by module.repository.cards.collectAsState()
    val showSentenceGloss by module.prefs.showSentenceGloss.collectAsState()
    val direction by module.prefs.direction.collectAsState()
    val effectiveTtsLanguage by module.prefs.effectiveTtsLanguage.collectAsState()
    val availableLangs by module.tts.availableLanguages.collectAsState()
    val ttsAutoReveal by module.prefs.ttsAutoPlayReveal.collectAsState()
    val ttsAutoFront by module.prefs.ttsAutoPlayFront.collectAsState()
    val ttsRatePct by module.prefs.ttsRatePct.collectAsState()
    // Speaker button shows iff the current effective language is supported by
    // the mounted engine. OFF or unavailable → no button, no auto-play.
    val canSpeak = effectiveTtsLanguage != TtsLanguage.OFF &&
        effectiveTtsLanguage in availableLangs
    LaunchedEffect(ttsRatePct) { module.tts.setRate(ttsRatePct / 100f) }
    var revealed by remember(areaId) { mutableStateOf(false) }
    val verbatim by module.prefs.verbatimSentences.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val correctionVpsUrl by module.prefs.correctionVpsUrl.collectAsState()
    val context = LocalContext.current
    var pendingCorrection by remember { mutableStateOf<CorrectionReport?>(null) }
    val submitters = remember(correctionVpsUrl) {
        val list = mutableListOf<CorrectionSubmitter>(
            GithubIssueSubmitter(
                context = context,
                owner = DEFAULT_GITHUB_REPO_OWNER,
                repo = DEFAULT_GITHUB_REPO_NAME,
            ),
        )
        correctionVpsUrl?.let { list += VpsSubmitter(it) }
        list.toList()
    }

    // Pin the chosen card to the current `cards` snapshot so it only re-derives
    // when grade/suspend actually mutates state. Without this, a LEARNING card
    // whose `dueAt` elapses mid-session would preempt a NEW card on the next
    // recomposition (e.g. when the user taps Reveal), and because `revealed`
    // survives the swap the user sees a different card's definition.
    val nextPair = remember(cards, areaId) { module.repository.nextDueCard(areaId) }

    if (nextPair == null) {
        Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "✓ All caught up",
                    color = Color(0xFF10B981),
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
                Text(
                    "No cards due in this area right now.",
                    color = Color(0xFF9CA3AF),
                    fontSize = 13.sp,
                )
                Button(onClick = onDone) { Text("Pick another area") }
            }
        }
        return
    }

    val (record, vocab) = nextPair
    val sentence = if (revealed) {
        module.repository.sentenceFor(vocab.id, preferAreaId = areaId, verbatim = verbatim)
    } else null

    // Direction-driven sides. KO_TO_EN = original behavior (front is Korean).
    // EN_TO_KO swaps everything so a Korean speaker drills the English token
    // and reveals the Korean it maps to.
    val sides = vocabSidesFor(vocab, direction)
    val sentenceSides = sentence?.let { sentenceSidesFor(it, direction) }
    // Pick which language to read aloud, per side. The card front and
    // sentence-front share a language; the back shares the other. Speaker
    // buttons sit on the side whose language matches effectiveTtsLanguage —
    // there's no point putting a Korean speaker next to English text.
    val frontLang = languageOfFront(direction)
    val backLang = languageOfBack(direction)

    // Auto-play headword when a new card surfaces (front side). Keys on
    // vocab.id so it doesn't re-fire on toggle/recompose for the same card.
    LaunchedEffect(vocab.id, canSpeak, ttsAutoFront, effectiveTtsLanguage) {
        if (canSpeak && ttsAutoFront && !revealed) {
            speakSide(module, sides.front, sides.back, frontLang, backLang, effectiveTtsLanguage)
        }
    }
    // Auto-play example sentence when the back is revealed. Keyed on
    // (vocab, revealed) so flipping back never replays.
    LaunchedEffect(vocab.id, revealed, canSpeak, ttsAutoReveal, effectiveTtsLanguage) {
        if (canSpeak && ttsAutoReveal && revealed) {
            sentenceSides?.let {
                speakSide(module, it.front, it.back, frontLang, backLang, effectiveTtsLanguage)
            }
        }
    }

    val onSuspendCurrent: () -> Unit = {
        // Capture the vocab being suspended so the snackbar text and Undo
        // refer to *this* card, even after the queue advances.
        val suspendedVocab = vocab
        val suspendedSides = vocabSidesFor(suspendedVocab, direction)
        module.repository.setSuspended(suspendedVocab.id, true)
        revealed = false
        snackbarScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Suspended ${suspendedSides.front} / ${suspendedSides.back}",
                actionLabel = "Undo",
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                module.repository.setSuspended(suspendedVocab.id, false)
            }
        }
    }

    val header: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StateChip(record.snapshot)
            // Suspend lives in the header, far from the grade buttons —
            // Suspend is destructive-ish (removes from review) so it must
            // not sit alongside Again/Hard/Good/Easy.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = onSuspendCurrent,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) {
                    Text("Suspend", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                Button(
                    onClick = { module.prefs.setShowSentenceGloss(!showSentenceGloss) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) {
                    val translateLabel = when (direction) {
                        FlashcardDirection.KO_TO_EN -> if (showSentenceGloss) "번역 ✓" else "번역 —"
                        FlashcardDirection.EN_TO_KO -> if (showSentenceGloss) "Trans ✓" else "Trans —"
                    }
                    Text(translateLabel, color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
            }
        }
    }

    val frontHasSpeaker = canSpeak && effectiveTtsLanguage == frontLang
    val backHasSpeaker = canSpeak && effectiveTtsLanguage == backLang

    val onReportSentence: (SentenceEntry) -> Unit = { s ->
        pendingCorrection = CorrectionReport(
            vocabId = vocab.id,
            vocabHeadword = vocab.korean,
            vocabGloss = vocab.gloss,
            areaId = s.areaId ?: areaId,
            currentKorean = s.korean,
            currentGloss = s.gloss,
            source = s.source,
            speaker = s.speaker,
            generator = s.generator ?: s.glossGenerator?.let { "$it (gloss only)" },
            proposedKorean = null,
            proposedGloss = null,
            reason = null,
            appVersion = BuildConfig.VERSION_NAME,
            romCrc32 = romCrc32Hex,
        )
    }
    // Card-level report: usable even when there's no spoiler-free example
    // sentence (mined/etymology lemmas with a corpus gap). Without this the
    // entries that most need flagging would be the only ones you can't flag,
    // since the Report button used to live exclusively inside SentenceCard.
    val onReportCard: () -> Unit = {
        pendingCorrection = CorrectionReport(
            vocabId = vocab.id,
            vocabHeadword = vocab.korean,
            vocabGloss = vocab.gloss,
            areaId = areaId,
            currentKorean = vocab.korean,
            currentGloss = vocab.gloss,
            source = null,
            speaker = null,
            generator = null,
            proposedKorean = null,
            proposedGloss = null,
            reason = null,
            appVersion = BuildConfig.VERSION_NAME,
            romCrc32 = romCrc32Hex,
        )
    }
    val front: @Composable () -> Unit = {
        CardFront(
            text = sides.front,
            partOfSpeech = vocab.partOfSpeech,
            revealed = revealed,
            onTapToReveal = { if (!revealed) revealed = true },
            canSpeak = frontHasSpeaker,
            onSpeak = { module.tts.speak(sides.front, frontLang) },
        )
    }

    val ratings: @Composable () -> Unit = {
        RatingButtons(
            onGrade = { rating ->
                module.repository.grade(vocab.id, rating)
                revealed = false
            },
        )
    }
    val revealButton: @Composable () -> Unit = {
        Button(
            onClick = { revealed = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
        ) {
            Text("Reveal meaning", color = Color.White, fontSize = 14.sp)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Two-column layout when wider than tall (landscape phones, tablets).
        // Keeps the prompt visible on the left while the answer is on the
        // right; rating buttons are pinned to the bottom of the right column
        // so they stay reachable even when the example sentence is long.
        val twoColumn = maxWidth > maxHeight
        if (twoColumn) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    header()
                    front()
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (revealed) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CardBack(
                                text = sides.back,
                                notes = vocab.notes,
                                direction = direction,
                                senses = sensesForBack(vocab, direction),
                            )
                            HanjaCard(module = module, korean = vocab.korean)
                            if (sentenceSides != null) {
                                SentenceCard(
                                    frontText = sentenceSides.front,
                                    backText = sentenceSides.back,
                                    showBack = showSentenceGloss,
                                    generator = sentence?.generator,
                                    glossGenerator = sentence?.glossGenerator,
                                    canSpeak = frontHasSpeaker,
                                    onSpeak = { module.tts.speak(sentenceSides.front, frontLang) },
                                    onReport = sentence?.let { s -> { onReportSentence(s) } },
                                )
                            } else {
                                ReportOnlyCard(onReport = onReportCard)
                            }
                        }
                        ratings()
                    } else {
                        revealButton()
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                header()
                front()
                if (revealed) {
                    CardBack(
                        text = sides.back,
                        notes = vocab.notes,
                        direction = direction,
                        senses = sensesForBack(vocab, direction),
                    )
                    HanjaCard(module = module, korean = vocab.korean)
                    if (sentenceSides != null) {
                        SentenceCard(
                            frontText = sentenceSides.front,
                            backText = sentenceSides.back,
                            showBack = showSentenceGloss,
                            generator = sentence?.generator,
                            glossGenerator = sentence?.glossGenerator,
                            canSpeak = frontHasSpeaker,
                            onSpeak = { module.tts.speak(sentenceSides.front, frontLang) },
                            onReport = sentence?.let { s -> { onReportSentence(s) } },
                        )
                    } else {
                        ReportOnlyCard(onReport = onReportCard)
                    }
                    ratings()
                } else {
                    revealButton()
                }
            }
        }
    }
    pendingCorrection?.let { report ->
        CorrectionDialog(
            initialReport = report,
            submitters = submitters,
            onDismiss = { pendingCorrection = null },
            onSubmit = { editedReport, submitter ->
                pendingCorrection = null
                snackbarScope.launch {
                    val result = submitter.submit(editedReport)
                    val msg = if (result.isSuccess) {
                        "Sent via ${submitter.displayName}. Thank you!"
                    } else {
                        "Failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                    }
                    snackbarHostState.showSnackbar(msg)
                }
            },
        )
    }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}

@Composable
private fun StateChip(snapshot: CardSnapshot) {
    val (label, color) = when (snapshot.state) {
        CardState.NEW -> "NEW" to Color(0xFFFBBF24)
        CardState.LEARNING -> "LEARNING" to Color(0xFFF97316)
        CardState.REVIEW -> "REVIEW · ${"%.1f".format(snapshot.intervalDays)}d" to Color(0xFF10B981)
    }
    Row(
        modifier = Modifier
            .background(Color(0xFF0F172A), shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CardFront(
    text: String,
    partOfSpeech: String,
    revealed: Boolean,
    onTapToReveal: () -> Unit,
    canSpeak: Boolean,
    onSpeak: () -> Unit,
) {
    val verticalPadding = if (revealed) 12.dp else 24.dp
    val frontSize = if (revealed) 28.sp else 36.sp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1F2937), shape = RoundedCornerShape(12.dp))
            .let { if (!revealed) it.clickable { onTapToReveal() } else it }
            .padding(horizontal = 24.dp, vertical = verticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = frontSize)
            if (canSpeak) {
                Text(
                    "🔊",
                    fontSize = 16.sp,
                    modifier = Modifier
                        .background(Color(0xFF334155), shape = RoundedCornerShape(4.dp))
                        .clickable { onSpeak() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(partOfSpeech, color = Color(0xFF6B7280), fontSize = 11.sp)
        if (!revealed) {
            Text(
                "Tap card or Reveal button",
                color = Color(0xFF6B7280),
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun CardBack(
    text: String,
    notes: String?,
    direction: FlashcardDirection,
    senses: List<String> = emptyList(),
) {
    val label = when (direction) {
        FlashcardDirection.KO_TO_EN -> "Meaning"
        FlashcardDirection.EN_TO_KO -> "한국어"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF064E3B), shape = RoundedCornerShape(12.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = Color(0xFF6EE7B7), fontSize = 10.sp)
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        // Secondary senses sit directly below the answer text. Single
        // muted line, dot-separated, so the canonical headword stays
        // dominant and the grade buttons don't get pushed off-screen.
        //
        // In EN_TO_KO mode the answer is Korean but the senses are
        // alternate English meanings of the front word — prefix with
        // `also EN:` so a Korean learner isn't misled into thinking
        // these are extra Korean readings.
        if (senses.isNotEmpty()) {
            val sensesText = when (direction) {
                FlashcardDirection.KO_TO_EN -> senses.joinToString(" · ")
                FlashcardDirection.EN_TO_KO -> "also EN: " + senses.joinToString(" · ")
            }
            Text(
                sensesText,
                color = Color(0xFFA7F3D0),
                fontSize = 11.sp,
            )
        }
        notes?.let {
            Text(it, color = Color(0xFFA7F3D0), fontSize = 12.sp)
        }
    }
}

/**
 * Hanja breakdown for Sino-Korean words: each syllable's character with its
 * meaning (體 body · 育 educate · 館 public building). Tapping a character
 * lists other deck words built on it, ticking the ones already learned, so
 * a new word hooks onto known ones. Renders nothing for native words and
 * loanwords.
 */
@Composable
private fun HanjaCard(module: MoneoModule, korean: String) {
    val parts = remember(korean) { module.hanja.breakdown(korean) } ?: return
    var selected by remember(korean) { mutableStateOf<Char?>(null) }
    val vocab by module.repository.vocab.collectAsState()
    val cards by module.repository.cards.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1F2937), shape = RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("漢字 · tap a character", color = Color(0xFF9CA3AF), fontSize = 10.sp)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            parts.forEach { p ->
                val h = p.hanja
                val isSel = h != null && h == selected
                Column(
                    modifier = Modifier
                        .width(76.dp)
                        .background(
                            if (isSel) Color(0xFF374151) else Color.Transparent,
                            RoundedCornerShape(8.dp),
                        )
                        .then(if (h != null) Modifier.clickable { selected = if (isSel) null else h } else Modifier)
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        (h ?: p.syllable).toString(),
                        color = if (h != null) Color.White else Color(0xFF6B7280),
                        fontSize = 24.sp,
                    )
                    Text(p.syllable.toString(), color = Color(0xFFFCD34D), fontSize = 12.sp)
                    Text(
                        p.meaning ?: "native",
                        color = Color(0xFFD1D5DB),
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val sel = selected
        if (sel != null) {
            val related = remember(sel, korean) { module.hanja.wordsWith(sel, except = korean) }
            val byKorean = remember(vocab) { vocab.values.groupBy { it.korean } }
            val rows = related.mapNotNull { w ->
                val entries = byKorean[w] ?: return@mapNotNull null
                val known = entries.any { e ->
                    cards[e.id]?.let { it.suspended || it.snapshot.state == CardState.REVIEW } == true
                }
                Triple(w, entries.first().gloss, known)
            }.sortedByDescending { it.third }.take(8)
            if (rows.isEmpty()) {
                Text("No other deck words use $sel yet.", color = Color(0xFF9CA3AF), fontSize = 11.sp)
            } else {
                Text("Also uses $sel:", color = Color(0xFF9CA3AF), fontSize = 11.sp)
                rows.forEach { (w, gloss, known) ->
                    Text(
                        (if (known) "✓ " else "   ") + "$w — $gloss",
                        color = if (known) Color(0xFF6EE7B7) else Color(0xFFE5E7EB),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Shown in place of [SentenceCard] when the revealed card has no example
 * sentence to display (e.g. a mined/etymology lemma with no spoiler-free
 * line). Keeps the Report affordance reachable so corpus gaps are exactly
 * the cards the user *can* flag, not the ones they can't.
 */
@Composable
private fun ReportOnlyCard(onReport: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "✎ Report",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .background(Color(0xFF334155), shape = RoundedCornerShape(4.dp))
                .clickable { onReport() }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SentenceCard(
    frontText: String,
    backText: String,
    showBack: Boolean,
    generator: String?,
    glossGenerator: String?,
    canSpeak: Boolean,
    onSpeak: () -> Unit,
    onReport: (() -> Unit)?,
) {
    val isLlm = generator?.startsWith("llm-") == true
    val isLlmGloss = glossGenerator?.startsWith("llm-") == true
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E293B), shape = RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Example", color = Color(0xFF93C5FD), fontSize = 10.sp)
            if (isLlm) {
                Text(
                    "AI",
                    color = Color(0xFFE0E7FF),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Color(0xFF4338CA), shape = RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            if (canSpeak) {
                Text(
                    "🔊",
                    fontSize = 14.sp,
                    modifier = Modifier
                        .background(Color(0xFF334155), shape = RoundedCornerShape(4.dp))
                        .clickable { onSpeak() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (onReport != null) {
                // Slightly more prominent affordance when the line is LLM-generated:
                // those are the ones a native speaker is most likely to need to fix.
                val reportBg = if (isLlm || isLlmGloss) Color(0xFF7C3AED) else Color(0xFF334155)
                Text(
                    "✎ Report",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(reportBg, shape = RoundedCornerShape(4.dp))
                        .clickable { onReport() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(frontText, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 18.sp)
        if (showBack) {
            Text(backText, color = Color(0xFFCBD5E1), fontSize = 13.sp)
            if (isLlmGloss) {
                Text("AI translation of a game line", color = Color(0xFFA5B4FC), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun RatingButtons(onGrade: (Rating) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RatingButton("Again", Color(0xFFB91C1C), Modifier.weight(1f)) { onGrade(Rating.AGAIN) }
        RatingButton("Hard", Color(0xFFB45309), Modifier.weight(1f)) { onGrade(Rating.HARD) }
        RatingButton("Good", Color(0xFF15803D), Modifier.weight(1f)) { onGrade(Rating.GOOD) }
        RatingButton("Easy", Color(0xFF1D4ED8), Modifier.weight(1f)) { onGrade(Rating.EASY) }
    }
}

@Composable
private fun RatingButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

@Suppress("unused")
private fun CardRecord.debugLabel(): String = "$vocabId · ${snapshot.state}"

/** Front/back text for a vocab card, given the active direction. */
private data class TextSides(val front: String, val back: String)

private fun vocabSidesFor(vocab: VocabEntry, direction: FlashcardDirection): TextSides =
    when (direction) {
        FlashcardDirection.KO_TO_EN -> TextSides(vocab.korean, vocab.gloss)
        FlashcardDirection.EN_TO_KO -> TextSides(vocab.gloss, vocab.korean)
    }

/**
 * Senses to show on the back of the card.
 *
 * KO_TO_EN: alternate English meanings of the Korean headword — sit
 *   directly under the English primary gloss.
 * EN_TO_KO: same English alternates surface under the Korean answer,
 *   labelled `also EN:` to make clear they're additional senses of the
 *   front word (the prompt), not extra Korean readings.
 */
private fun sensesForBack(vocab: VocabEntry, direction: FlashcardDirection): List<String> =
    vocab.senses

private fun sentenceSidesFor(s: SentenceEntry, direction: FlashcardDirection): TextSides =
    when (direction) {
        FlashcardDirection.KO_TO_EN -> TextSides(s.korean, s.gloss)
        FlashcardDirection.EN_TO_KO -> TextSides(s.gloss, s.korean)
    }

private fun languageOfFront(direction: FlashcardDirection): TtsLanguage = when (direction) {
    FlashcardDirection.KO_TO_EN -> TtsLanguage.KOREAN
    FlashcardDirection.EN_TO_KO -> TtsLanguage.ENGLISH
}

private fun languageOfBack(direction: FlashcardDirection): TtsLanguage = when (direction) {
    FlashcardDirection.KO_TO_EN -> TtsLanguage.ENGLISH
    FlashcardDirection.EN_TO_KO -> TtsLanguage.KOREAN
}

/**
 * Speak whichever side matches the user's effective TTS language. Used for
 * auto-play hooks: the user pinned a voice, so we play the side that's in
 * that voice. If neither side matches (e.g. an OFF override slipped past
 * the canSpeak guard), this is a no-op.
 */
private fun speakSide(
    module: MoneoModule,
    frontText: String,
    backText: String,
    frontLang: TtsLanguage,
    backLang: TtsLanguage,
    effective: TtsLanguage,
) {
    when (effective) {
        frontLang -> module.tts.speak(frontText, frontLang)
        backLang -> module.tts.speak(backText, backLang)
        else -> {}
    }
}