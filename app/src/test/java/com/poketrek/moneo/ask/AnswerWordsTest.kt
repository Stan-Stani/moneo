package com.poketrek.moneo.ask

import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerWordsTest {

    @Test fun splitsTheTagOffTheEnd() {
        val s = AnswerWords.split("간판을 보면 알 수 있어요.\n\n[[words: 간판, 도움이 되다, 간판]]")
        assertEquals("간판을 보면 알 수 있어요.", s.text)
        assertEquals(listOf("간판", "도움이 되다"), s.words)
    }

    @Test fun emptyTagAndNoTag() {
        assertEquals(AnswerWords.Split("좋아요.", emptyList()), AnswerWords.split("좋아요.\n[[words: ]]"))
        assertEquals(AnswerWords.Split("좋아요.", emptyList()), AnswerWords.split("좋아요."))
    }

    @Test fun toleratesSpacingCaseAndOtherCommas() {
        assertEquals(listOf("간판", "되다", "도움"), AnswerWords.split("x [[ Words : 간판、되다，도움. ]]").words)
    }

    @Test fun tagInTheMiddleIsStillRemoved() {
        assertEquals("앞 뒤", AnswerWords.split("앞 [[words: 간판]]뒤").text.replace("  ", " "))
    }

    @Test fun streamingHidesATagThatIsStillArriving() {
        assertEquals("간판이에요.", AnswerWords.visible("간판이에요.\n["))
        assertEquals("간판이에요.", AnswerWords.visible("간판이에요.\n[[wor"))
        assertEquals("간판이에요.", AnswerWords.visible("간판이에요.\n[[words: 간판, 도"))
        assertEquals("간판이에요.", AnswerWords.visible("간판이에요.\n[[words: 간판]]"))
        assertEquals("간판이에요", AnswerWords.visible("간판이에요"))
    }
}
