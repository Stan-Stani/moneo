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

    @Test fun itemPickupTemplateMatches() {
        // Message box after the Viridian Mart clerk hands over Oak's Parcel.
        val line = index.match("A 전해줄물건 중요한 물건\n포켓에 넣었다!")!!
        assertEquals(listOf("넣다"), line.words)
    }

    @Test fun unrelatedTextDoesNotMatch() {
        val idx = DialogIndex(listOf(DialogIndex.Line(1, listOf("포켓몬"), listOf("포켓몬"))))
        assertNull(idx.match("포켓몬 센터에 어서 오세요 무엇을 도와드릴까요"))
        assertNull(idx.match("A"))
        // Battle HP box: species name containing a 2-syllable line.
        val short = DialogIndex(listOf(DialogIndex.Line(1, listOf("이상"), listOf("이상"))))
        assertNull(short.match("이상해씨♂"))
        assertEquals(1, short.match("이상!")!!.id)
    }

    /** Battle text buffer at 0x02022960.. on the emulator: zero padding, then 이상해씨{은(는)}\n무엇을 할까? FF, then a stale tail. */
    private val battle = hex(
        "00 00 00 00 3d 72 3b a1 40 43 3c 97 41 ef fe 3a 8c 3c d2 3d 61 00 40 3d 37 ac ac ff ff c2 ab fb ff 6c 00 3b a1"
    )

    @Test fun findsBattleMessageStartFromAnyCursor() {
        val msgAt = 4
        assertEquals(msgAt, DialogReader.messageStart(battle, msgAt))      // cursor at the start
        assertEquals(msgAt, DialogReader.messageStart(battle, msgAt + 9))  // mid-print
        assertEquals(msgAt, DialogReader.messageStart(battle, 27))         // finished: one past FF
        assertEquals("이상해씨\n무엇을 할까?", text.decode(battle, msgAt))
    }

    private fun rows(vararg entries: Pair<Int, Int>): ByteArray {
        val b = ByteArray(6 * 64)
        for ((i, v) in entries) { b[i * 2] = (v and 0xFF).toByte(); b[i * 2 + 1] = (v shr 8).toByte() }
        return b
    }

    @Test fun messageBoxDetectedFromBg0Rows() {
        // Closed: tile 0 everywhere, palette bits don't count.
        assertEquals(false, DialogReader.boxRowsHaveTiles(rows(0 to 0xF000, 100 to 0xF000)))
        // Open (field and battle): frame tiles like 0xF200 in the box rows.
        assertEquals(true, DialogReader.boxRowsHaveTiles(rows(0 to 0xF200)))
        assertEquals(true, DialogReader.boxRowsHaveTiles(rows(5 * 32 + 29 to 0x0001)))
        // Columns 30-31 are off-screen.
        assertEquals(false, DialogReader.boxRowsHaveTiles(rows(30 to 0xF200, 31 to 0xF200)))
    }
}
