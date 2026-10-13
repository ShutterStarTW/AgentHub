package com.shutterstar.agenthub

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StatusFilterTest {
    @Test
    fun `all matches every status including undetected`() {
        listOf("✓", "↑", "?", "✗", "").forEach { assertTrue(StatusFilter.ALL.matches(it)) }
    }

    @Test
    fun `installed covers current, outdated and unverified but not missing or unknown`() {
        listOf("✓", "↑", "?").forEach { assertTrue(StatusFilter.INSTALLED.matches(it)) }
        listOf("✗", "").forEach { assertFalse(StatusFilter.INSTALLED.matches(it)) }
    }

    @Test
    fun `not installed and update available are exact`() {
        assertTrue(StatusFilter.NOT_INSTALLED.matches("✗"))
        assertFalse(StatusFilter.NOT_INSTALLED.matches(""))
        assertTrue(StatusFilter.UPDATE_AVAILABLE.matches("↑"))
        assertFalse(StatusFilter.UPDATE_AVAILABLE.matches("✓"))
    }
}
