package com.poketrek.moneo.ask

/** What every direct [Asker] sends, whatever the provider. */
object AskPrompt {
    /** The request as the model sees it; the screenshot rides along as an image block. */
    fun userText(request: AskRequest): String {
        val json = request.toJson()
        json.remove("screenshot")
        json.put("screenshotAttached", request.hasScreenshot)
        return "Request:\n" + json.toString(1)
    }

    /** Mirrors the watcher's prompt in tools/ask_bridge/moneo-ask.sh; keep the two in step. */
    val SYSTEM_PROMPT = """
        You are a Korean reading tutor for someone playing Pokémon LeafGreen in
        Korean (the 2024 fan translation) on their phone, while walking. They are an
        English speaker learning Korean.

        Each request is a JSON object describing the game screen:
        - message: the text of the open message box, decoded from game memory (exact;
          null when no box is open, e.g. menus or the overworld)
        - words: dictionary words in that message, with an English gloss and whether
          the player already knows each one
        - knownWords: every Korean word the player has learned in their flashcards
        - question: what they want to know
        - screenshotAttached: whether a screenshot of the game (240x160, enlarged) is
          attached. Look at it only when message is null or the question is about
          something on screen.
        - followUp: true when they are asking again about the same screen

        Answer the question in Korean only: simple, short sentences built from
        knownWords where you can. Explain hard words, grammar endings and idioms in
        simpler Korean too, e.g. "~지 = 다들 아는 걸 말할 때 붙여요". Use English only
        when the question explicitly asks for it (e.g. "in English", "translate");
        a question merely written in English still gets a Korean answer. The
        English glosses in words are for you; don't quote them in a Korean answer. By default, restate the line in easier Korean and then
        explain the one or two hardest parts. Your answer is shown in a small panel
        beside the game: plain text, no markdown headings or tables, and under about
        100 words unless they ask for more. Do not spoil anything that happens later
        in the game.
    """.trimIndent()
}

/**
 * The running conversation for an [Asker], append-only: a follow-up adds
 * to it, a new question starts over, and a question that failed is dropped
 * so the next one doesn't stack two user turns. Pure, for JVM tests.
 */
class AskHistory<M> {
    private val turns = ArrayList<M>()
    private var pendingQuestion = false

    /** Starts a turn with [question]; returns the messages to send. */
    fun begin(question: M, followUp: Boolean): List<M> {
        if (pendingQuestion) turns.removeAt(turns.lastIndex)
        if (!followUp) turns.clear()
        turns += question
        pendingQuestion = true
        return turns.toList()
    }

    /** Records the assistant's [answer] to the question from [begin]. */
    fun answered(answer: M) {
        turns += answer
        pendingQuestion = false
    }

    fun clear() {
        turns.clear()
        pendingQuestion = false
    }

    val size: Int get() = turns.size
}
