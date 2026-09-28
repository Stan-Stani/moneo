package com.poketrek.moneo.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class DialogReadingTest {

    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    private val text by lazy {
        KoText2024.parse(File("src/main/assets/moneo/ko2024_codepoints.json").readText())
    }
    private val index by lazy {
        DialogIndex.parse(File("src/main/assets/moneo/dialog_index.json").readText())
    }

    /** Bytes at 0x02021D18 on the emulator with the Pallet NPC's box open (followed by an older message). */
    private val palletNpc = hex(
        "37 03 3f c3 3d 60 00 39 0d 3d 3a 3d 72 00 39 1f 3e 0b ab ff fb cd ce bb cc ce 00 3a d7"
    )

    @Test fun decodesLiveMessageBytes() {
        assertEquals("간판은 도움이 되지!", text.decode(palletNpc))
    }

    @Test fun matchesTheLiveMessageToItsLine() {
        val line = index.match(text.decode(palletNpc))!!
        assertEquals(listOf("간판", "도움", "되다"), line.words)
    }

    @Test fun controlCodesAndPageBreaks() {
        // FC 01 xx (text color, 2 bytes after FC), FB page break, FE newline.
        val t = KoText2024(mapOf(0x3701 to '가', 0x3702 to '나'))
        assertEquals("가\n나\nAB!", t.decode(hex("fc 01 02 37 01 fb 37 02 fe bb bc ab ff")))
    }

    @Test fun placeholdersAndParticlesDontBreakTheMatch() {
        val idx = DialogIndex(
            listOf(
                DialogIndex.Line(1, listOf("선장의등을쓰다듬어줬다"), listOf("선장", "쓰다듬다")),
                DialogIndex.Line(2, listOf("선장"), listOf("선장")),
            )
        )
        // "{PLAYER}은(는) 선장의 등을 쓰다듬어 줬다!" rendered for a Korean-named player.
        assertEquals(1, idx.match("민수는 선장의\n등을 쓰다듬어 줬다!")!!.id)
    }

    @Test fun unrelatedTextDoesNotMatch() {
        val idx = DialogIndex(listOf(DialogIndex.Line(1, listOf("포켓몬"), listOf("포켓몬"))))
        assertNull(idx.match("포켓몬 센터에 어서 오세요 무엇을 도와드릴까요"))
        assertNull(idx.match("A"))
    }
}
