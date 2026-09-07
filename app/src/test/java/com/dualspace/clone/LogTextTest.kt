package com.dualspace.clone

import com.dualspace.clone.util.LogText
import org.junit.Assert.assertEquals
import org.junit.Test

class LogTextTest {
    @Test
    fun `tail keeps only the newest lines`() {
        val lines = (1..10).map { "line $it" }
        assertEquals(listOf("line 8", "line 9", "line 10"), LogText.lastLines(lines, 3))
    }

    @Test
    fun `tail returns everything when asked for more than exists`() {
        val lines = listOf("a", "b")
        assertEquals(lines, LogText.lastLines(lines, 400))
    }

    @Test
    fun `tail of nothing or zero is empty`() {
        assertEquals(emptyList<String>(), LogText.lastLines(emptyList(), 5))
        assertEquals(emptyList<String>(), LogText.lastLines(listOf("a"), 0))
    }

    @Test
    fun `byte counts are readable`() {
        assertEquals("512 B", LogText.humanBytes(512))
        assertEquals("1.0 KB", LogText.humanBytes(1024))
        assertEquals("1.5 MB", LogText.humanBytes(1536 * 1024))
    }
}
