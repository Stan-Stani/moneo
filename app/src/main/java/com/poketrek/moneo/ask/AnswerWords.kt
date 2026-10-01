package com.poketrek.moneo.ask

/**
 * The "[[words: 간판, 도움이 되다]]" line the prompt asks the model to end an
 * answer with: dictionary forms of words worth studying. The panel hides it
 * and offers those words as ★ study-next buttons. Pure, for JVM tests.
 */
object AnswerWords {
    private val TAG = Regex("""\[\[\s*words\s*:([^\]]*)]]""", RegexOption.IGNORE_CASE)
    private const val MAX_WORDS = 8

    data class Split(val text: String, val words: List<String>)

    /** [answer] without its words tag(s), and the words they listed. */
    fun split(answer: String): Split {
        val words = TAG.findAll(answer)
            .flatMap { it.groupValues[1].split(',', '、', '，') }
            .map { it.trim().trim('.', '·', '"', '\'') }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_WORDS)
            .toList()
        return Split(TAG.replace(answer, "").trim(), words)
    }

    /** A streaming answer as it should show: a tag that's still arriving is cut off. */
    fun visible(partial: String): String {
        val open = partial.lastIndexOf("[[")
        val text = if (open >= 0 && partial.indexOf("]]", open) < 0) partial.substring(0, open) else partial
        // The tag's first bracket may arrive on its own.
        return split(text.removeSuffix("[")).text
    }
}
