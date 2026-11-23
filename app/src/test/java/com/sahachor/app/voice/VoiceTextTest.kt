package com.sahachor.app.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceTextTest {
    @Test fun linksAndMarkdownAreRemovedBeforeSpeaking() {
        assertEquals("দেখুন এটা", SpeechText.forSpeaking("**দেখুন** https://example.com/a এটা"))
    }

    @Test fun longTextIsCutAtASentenceEnd() {
        val r = SpeechText.forSpeaking("ক।".repeat(300))
        assertTrue(r.length <= 500)
        assertTrue(r.endsWith("।"))
    }

    @Test fun wholeAnswerIsSpokenInChunksNotCutOff() {
        val text = "এটি একটি বাক্য। ".repeat(300)
        val chunks = SpeechText.chunks(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 1200 && it.endsWith("।") })
        assertEquals(300, chunks.sumOf { c -> c.count { it == '।' } })
        val oneLong = SpeechText.chunks("ক".repeat(3000))
        assertTrue(oneLong.all { it.length <= 1200 }); assertEquals(3000, oneLong.sumOf { it.length })
        assertTrue(SpeechText.chunks("   ").isEmpty())
    }

    @Test fun shortTextIsKept() = assertEquals("নমস্কার", SpeechText.forSpeaking("  নমস্কার  "))
}
