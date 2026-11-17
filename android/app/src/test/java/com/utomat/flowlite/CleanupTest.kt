package com.utomat.flowlite

import com.utomat.flowlite.Cleanup.Entry
import com.utomat.flowlite.Cleanup.Style
import org.junit.Assert.assertEquals
import org.junit.Test

class CleanupTest {
    @Test fun removesFillers() {
        assertEquals("Hello, world.", Cleanup.clean("Um, hello, uh, world."))
        assertEquals("I think.", Cleanup.clean("I think, uh."))
        assertEquals("Hummus and umbrella.", Cleanup.clean("Hummus and umbrella."))
    }

    @Test fun keepsFillersWhenDisabled() {
        assertEquals("Um, hello.", Cleanup.clean("Um, hello.", removeFillers = false))
    }

    @Test fun scratchThatDropsThePreviousClause() {
        assertEquals("Send it Monday. Wednesday works.",
            Cleanup.clean("Send it Monday. Actually Tuesday, scratch that, Wednesday works."))
        assertEquals("Wednesday.", Cleanup.clean("Tuesday. Scratch that. Wednesday."))
        assertEquals("", Cleanup.clean("Never mind scratch that"))
    }

    @Test fun spokenPunctuation() {
        assertEquals("Hello, how are you?", Cleanup.clean("hello comma how are you question mark"))
        assertEquals("Hi.\nThanks!", Cleanup.clean("hi period new line thanks exclamation mark"))
        assertEquals("Dear Sam,\n\nSee you.", Cleanup.clean("Dear Sam, comma new paragraph see you full stop"))
    }

    @Test fun capitalisation() {
        assertEquals("Hello there. How are you? Fine.", Cleanup.clean("hello there. how are you? fine."))
        assertEquals("Pi is 3.14 today.", Cleanup.clean("pi is 3.14 today."))
        assertEquals("I think I'm late.", Cleanup.clean("i think i'm late.", language = "en"))
        assertEquals("Vedo i ragazzi.", Cleanup.clean("vedo i ragazzi.", language = "it"))
        assertEquals("Καλημέρα. Τι κάνεις;", Cleanup.clean("καλημέρα. τι κάνεις;"))
    }

    @Test fun dictionary() {
        val dict = listOf(Entry("kubernetes", null), Entry("Kubernetes", null), Entry("gonna", "going to"), Entry("iPhone", null))
        assertEquals("I am going to deploy Kubernetes on my iPhone.",
            Cleanup.clean("I am gonna deploy kubernetes on my iphone.", dict.drop(1)))
        assertEquals("Kubernetesx stays.", Cleanup.applyDictionary("Kubernetesx stays.", dict.drop(1)))
    }

    @Test fun perAppStyle() {
        assertEquals(Style.CASUAL, Cleanup.styleFor("com.whatsapp"))
        assertEquals(Style.FORMAL, Cleanup.styleFor("com.google.android.gm"))
        assertEquals(Style.DEFAULT, Cleanup.styleFor("com.example"))
        assertEquals("See you soon", Cleanup.clean("see you soon.", style = Style.CASUAL))
        assertEquals("See you soon?", Cleanup.clean("see you soon?", style = Style.CASUAL))
        assertEquals("See you soon.", Cleanup.clean("see you soon", style = Style.FORMAL))
        assertEquals("see you soon", Cleanup.clean("see you soon.", style = Style.VERY_CASUAL))
        assertEquals("I agree", Cleanup.clean("I agree.", style = Style.VERY_CASUAL))
        assertEquals("NASA called", Cleanup.clean("NASA called.", style = Style.VERY_CASUAL))
    }

    @Test fun untrustedReplacementsAreBoundedAndCleaned() {
        val entries = listOf(
            Cleanup.Entry("deploy", "ok\u001b[31m\r\nline two"),
            Cleanup.Entry("hello", "x".repeat(Cleanup.MAX_REPLACEMENT + 1)),
        )
        assertEquals("ok[31m\nline two hello", Cleanup.applyDictionary("deploy hello", entries))
    }

    @Test fun replacementsDoNotCascade() {
        val entries = listOf(Cleanup.Entry("alpha", "beta beta"), Cleanup.Entry("beta", "gamma"))
        assertEquals("beta beta and gamma", Cleanup.applyDictionary("alpha and beta", entries))
    }
}
