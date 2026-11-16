package io.github.whywood.deepdeep.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechClipTest {
    @Test
    fun blankClipsToEmpty() {
        assertEquals("", clipForSpeech(""))
        assertEquals("", clipForSpeech("   "))
    }

    @Test
    fun shortTextStaysWhole() {
        assertEquals("你好。", clipForSpeech("你好。"))
    }

    @Test
    fun clipsToTwoSentences() {
        assertEquals("一。二。", clipForSpeech("一。二。三。四。"))
    }

    @Test
    fun clipsToCharBudgetWithinOneSentence() {
        val long = "甲".repeat(120) + "。"
        val clipped = clipForSpeech(long, maxSentences = 2, maxChars = 80)
        assertEquals(80, clipped.length)
    }

    @Test
    fun respectsBothLimits() {
        val text = "第一句。" + "第二句。" + "第三句。"
        val clipped = clipForSpeech(text, maxSentences = 2, maxChars = 80)
        assertEquals("第一句。第二句。", clipped)
    }

    @Test
    fun neverExceedsCharBudget() {
        val text = "句号。".repeat(100)
        val clipped = clipForSpeech(text, maxSentences = 2, maxChars = 80)
        assertTrue(clipped.length <= 80)
        assertTrue(clipped.split(SENTENCE_END).filter { it.isNotBlank() }.size <= 2)
    }
}
