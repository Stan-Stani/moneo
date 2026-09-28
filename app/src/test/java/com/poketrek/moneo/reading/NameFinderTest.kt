package com.poketrek.moneo.reading

import org.junit.Assert.assertEquals
import org.junit.Test

class NameFinderTest {
    private val f = NameFinder(listOf("이상해씨", "몸통박치기", "구구", "피죤투", "전광석화", "울음소리", "울"))

    @Test fun battleTemplateMessages() {
        assertEquals(listOf("이상해씨", "몸통박치기"), f.find("이상해씨의 몸통박치기!"))
        assertEquals(listOf("구구", "몸통박치기"), f.find("야생 구구의 몸통박치기!"))
        assertEquals(listOf("이상해씨"), f.find("가랏! 이상해씨!"))
        assertEquals(listOf("구구"), f.find("앗! 야생 구구가 튀어나왔다!"))
    }

    @Test fun nameMustStartTheWord() {
        // 구구 inside 비둘기구구 or 구구단-like words elsewhere mustn't match mid-word.
        assertEquals(emptyList<String>(), f.find("아구구 아파라"))
    }

    @Test fun longestNameWinsAndOneLetterNamesAreIgnored() {
        assertEquals(listOf("구구", "울음소리"), f.find("구구는 울음소리를 썼다"))
    }
}
