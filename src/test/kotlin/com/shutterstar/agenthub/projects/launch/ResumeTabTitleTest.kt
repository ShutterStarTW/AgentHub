package com.shutterstar.agenthub.projects.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ResumeTabTitleTest {
    @Test
    fun `collapses a multi-line name into one line`() {
        assertEquals("- elemezd az index.html-t - egységesítsd", ResumeTabTitle.label("- elemezd az index.html-t\r\n- egységesítsd", "id", maxChars = 60))
    }

    @Test
    fun `cuts a long name and ends it with an ellipsis`() {
        val label = ResumeTabTitle.label("x".repeat(60) + " tail", "id")

        assertEquals("x".repeat(ResumeTabTitle.MAX_CHARS - 3) + "...", label)
        assertEquals(ResumeTabTitle.MAX_CHARS, label.length)
        assertEquals(30, ResumeTabTitle.MAX_CHARS)
        assertEquals("x".repeat(30), ResumeTabTitle.label("x".repeat(30), "id"))
        assertEquals("short", ResumeTabTitle.label("  short  ", "id"))
        assertEquals("ab...", ResumeTabTitle.label("ab cd ef gh", "id", maxChars = 5))
    }

    @Test
    fun `falls back to the short session id for a blank name`() {
        assertEquals("019fdc1f", ResumeTabTitle.label(" \n ", "019fdc1f-5757-7221"))
        assertEquals("019fdc1f", ResumeTabTitle.label(null, "019fdc1f-5757-7221"))
    }
}
