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

    @Test fun shortTextIsKept() = assertEquals("নমস্কার", SpeechText.forSpeaking("  নমস্কার  "))
}
