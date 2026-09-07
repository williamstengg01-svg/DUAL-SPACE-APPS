package com.dualspace.clone

import com.dualspace.clone.util.FailureText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureTextTest {

    @Test
    fun `plain exception keeps class and message`() {
        assertEquals("IllegalStateException: boom", FailureText.describe(IllegalStateException("boom")))
    }

    @Test
    fun `engine null pointer gets a readable name`() {
        val text = FailureText.describe(NullPointerException())
        assertTrue(text, text.startsWith("Engine not ready"))
    }

    @Test
    fun `cause chain is included`() {
        val t = RuntimeException("outer", IllegalArgumentException("inner"))
        assertEquals("RuntimeException: outer ← IllegalArgumentException: inner", FailureText.describe(t))
    }

    @Test
    fun `self referencing cause does not loop forever`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        val text = FailureText.describe(a)
        assertTrue(text, text.contains("RuntimeException: a"))
        assertTrue(text, text.contains("RuntimeException: b"))
    }

    @Test
    fun `very long messages are truncated`() {
        val text = FailureText.describe(RuntimeException("x".repeat(1000)))
        assertTrue(text.length <= 300)
        assertTrue(text.endsWith("…"))
    }
}
