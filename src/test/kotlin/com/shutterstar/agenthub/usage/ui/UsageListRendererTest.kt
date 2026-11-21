package com.shutterstar.agenthub.usage.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UsageListRendererTest {
    @Test
    fun shouldAnnounceLimitsBeyondTheTwoShownRows() {
        assertEquals("", UsageListRenderer.extraInfoText(0, 0))
        assertEquals("+1 more limit · open for details", UsageListRenderer.extraInfoText(1, 0))
        assertEquals("+3 more limits · open for details", UsageListRenderer.extraInfoText(3, 0))
        assertEquals("1 saved reset · open for details", UsageListRenderer.extraInfoText(0, 1))
        assertEquals("+2 more limits · 3 saved resets · open for details", UsageListRenderer.extraInfoText(2, 3))
    }

    @Test
    fun shouldKeepTheSeverityColorOnTheSelectedRow() {
        UsageSeverity.entries.forEach { severity ->
            assertEquals(UsageProgressBar.fillColor(severity), UsageProgressBar.fillColor(severity, onSelectedRow = true))
        }
    }
}
