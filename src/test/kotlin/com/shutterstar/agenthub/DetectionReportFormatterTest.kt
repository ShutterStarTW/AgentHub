package com.shutterstar.agenthub

import com.shutterstar.agenthub.DetectionReportFormatter.Entry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetectionReportFormatterTest {
    private val rule = "--------------------------------------------------"
    private val ansi = Regex("\u001B\\[[0-9;]*m")

    private val entries = listOf(
        Entry("Claude Code", true),
        Entry("Codex", false),
        Entry("Cursor", null),
    )

    @Test
    fun `prints installed, missing and unchecked agents with a summary`() {
        assertEquals(
            listOf(
                "Detecting installed agents & companion tools",
                rule,
                "  [+] Claude Code  installed",
                "  [x] Codex        not found",
                "  [?] Cursor       could not check",
                rule,
                "  1 installed, 1 not found, 1 could not be checked",
            ),
            DetectionReportFormatter.format(entries).trimEnd('\n').lines(),
        )
    }

    @Test
    fun `an agent whose check could not run is not reported as not found`() {
        val report = DetectionReportFormatter.format(listOf(Entry("Forge", null)))

        assertFalse("not found" in report.lines()[2])
        assertTrue("0 installed, 0 not found, 1 could not be checked" in report)
    }

    @Test
    fun `the colored report is the plain one plus ASCII escape codes`() {
        val colored = DetectionReportFormatter.format(entries, color = true)

        assertEquals(DetectionReportFormatter.format(entries), colored.replace(ansi, ""))
        assertTrue(colored.all { it.code < 128 })
        assertTrue("${Ansi.BRIGHT_GREEN}installed${Ansi.RESET}" in colored)
        assertTrue("${Ansi.DIM}not found${Ansi.RESET}" in colored)
    }

    @Test
    fun `tells that there is nothing to detect`() {
        assertTrue("No agents to detect." in DetectionReportFormatter.format(emptyList()))
    }
}
