package com.blesniffer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    private val sep = DIGIT_SEPARATOR

    @Test
    fun `digits group in threes from the right`() {
        assertEquals("0", groupDigits(0))
        assertEquals("7", groupDigits(7))
        assertEquals("42", groupDigits(42))
        assertEquals("999", groupDigits(999))
        assertEquals("1${sep}000", groupDigits(1_000))
        assertEquals("12${sep}345", groupDigits(12_345))
        assertEquals("123${sep}456", groupDigits(123_456))
        assertEquals("1${sep}234${sep}567", groupDigits(1_234_567))
        assertEquals("12${sep}345${sep}678", groupDigits(12_345_678))
    }

    @Test
    fun `separators are thin spaces that still allow a wrap between groups`() {
        // U+2009 is a space character, so it remains a break opportunity; a
        // narrow *no-break* space would defeat the wrapping this relies on.
        assertEquals(' ', DIGIT_SEPARATOR)
        assertEquals(2, groupDigits(1_234_567).count { it == DIGIT_SEPARATOR })
        assertEquals(0, groupDigits(1_234_567).count { it == ' ' })
        assertEquals(listOf("1", "234", "567"), groupDigits(1_234_567).split(DIGIT_SEPARATOR))
    }

    @Test
    fun `ages read in the coarsest unit that stays precise`() {
        assertEquals("now", formatAge(-5))
        assertEquals("250 ms ago", formatAge(250))
        assertEquals("1.5 s ago", formatAge(1_500))
        assertEquals("2 min ago", formatAge(125_000))
        assertEquals("3 h ago", formatAge(3 * 3_600_000 + 60_000))
    }
}
