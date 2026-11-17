package com.utomat.flowlite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RewriteTest {
    @Test fun plainDictationUsesPrompt() {
        val (system, user) = Rewrite.messages("Be brief.", "hello there", null)
        assertEquals("Be brief.", system)
        assertEquals("hello there", user)
    }

    @Test fun blankPromptFallsBackToDefault() {
        assertEquals(Rewrite.DEFAULT_PROMPT, Rewrite.messages("  ", "x", "").first)
    }

    @Test fun selectionTurnsDictationIntoInstruction() {
        val (_, user) = Rewrite.messages("Be brief.", "make this formal", "hey wanna meet")
        assertTrue(user.startsWith("Instruction: make this formal"))
        assertTrue(user.endsWith("hey wanna meet"))
    }

    @Test fun endpointAcceptsBaseOrFullUrl() {
        val want = "https://api.openai.com/v1/chat/completions"
        assertEquals(want, Rewrite.endpoint("https://api.openai.com/v1/"))
        assertEquals(want, Rewrite.endpoint("https://api.openai.com/v1/audio/transcriptions"))
        assertEquals(want, Rewrite.endpoint(want))
    }
}
