package com.shutterstar.agenthub

import com.shutterstar.agenthub.UpdateReportFormatter.Entry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateReportFormatterTest {
    private val rule = "--------------------------------------------------"

    @Test
    fun `prints one marked line per agent and a three part summary`() {
        val report = UpdateReportFormatter.format(
            listOf(
                Entry("Claude Code", AgentUpdateInfo("claude", UpdateStatus.CURRENT, "2.1.282")),
                Entry("Codex", AgentUpdateInfo("codex", UpdateStatus.OUTDATED, "0.5.0", "0.6.0")),
                Entry("Cursor", AgentUpdateInfo("cursor", UpdateStatus.UNKNOWN, "2026.11.1")),
            ),
        )

        assertEquals(
            listOf(
                "Agents & companion tools - versions & updates",
                rule,
                "  [+] Claude Code  2.1.282",
                "  [^] Codex        0.5.0 -> 0.6.0 available",
                "  [?] Cursor       2026.11.1 (could not verify)",
                rule,
                "  1 up to date, 1 update available, 1 could not be verified",
            ),
            report.trimEnd('\n').lines(),
        )
        assertTrue(report.endsWith("\n"))
    }

    @Test
    fun `an unverifiable agent is never reported as up to date`() {
        val report = UpdateReportFormatter.format(listOf(Entry("Forge", AgentUpdateInfo("forge", UpdateStatus.UNKNOWN))))

        assertTrue("  [?] Forge  version unknown (could not verify)" in report)
        assertTrue("0 up to date, 1 could not be verified" in report)
    }

    @Test
    fun `an outdated agent without a known latest version still says an update is available`() {
        val report = UpdateReportFormatter.format(listOf(Entry("Aider", AgentUpdateInfo("aider", UpdateStatus.OUTDATED, "0.50"))))

        assertTrue("  [^] Aider  0.50 (update available)" in report)
        assertTrue("0 up to date, 1 update available" in report)
    }

    @Test
    fun `the colored report keeps the old script colors and is the plain one plus ASCII escape codes`() {
        val entries = listOf(
            Entry("Claude Code", AgentUpdateInfo("claude", UpdateStatus.CURRENT, "2.1.282")),
            Entry("Codex", AgentUpdateInfo("codex", UpdateStatus.OUTDATED, "0.5.0", "0.6.0")),
            Entry("Cursor", AgentUpdateInfo("cursor", UpdateStatus.UNKNOWN, "2026.11.1")),
        )
        val colored = UpdateReportFormatter.format(entries, color = true)

        assertEquals(UpdateReportFormatter.format(entries), colored.replace(Regex("\u001B\\[[0-9;]*m"), ""))
        assertTrue(colored.all { it.code < 128 })
        assertTrue("${Ansi.BRIGHT_GREEN}2.1.282${Ansi.RESET}" in colored)
        assertTrue("${Ansi.DIM}0.5.0${Ansi.RESET} ${Ansi.BRIGHT_GREEN}-> 0.6.0 available${Ansi.RESET}" in colored)
        assertTrue("${Ansi.YELLOW}2026.11.1 (could not verify)${Ansi.RESET}" in colored)
        assertTrue("${Ansi.YELLOW}1 update available${Ansi.RESET}" in colored)
    }

    @Test
    fun `pluralizes the update count and tells that there is nothing to check`() {
        val two = UpdateReportFormatter.format(
            listOf(
                Entry("A", AgentUpdateInfo("a", UpdateStatus.OUTDATED, "1", "2")),
                Entry("B", AgentUpdateInfo("b", UpdateStatus.OUTDATED, "1", "2")),
            ),
        )
        assertTrue("2 updates available" in two)
        assertTrue("No installed agents to check." in UpdateReportFormatter.format(emptyList()))
    }

    @Test
    fun `caps the name column and stays ASCII`() {
        val longName = "A very long companion tool name that overflows"
        val report = UpdateReportFormatter.format(
            listOf(
                Entry(longName, AgentUpdateInfo("x", UpdateStatus.CURRENT, "1.0")),
                Entry("Short", AgentUpdateInfo("y", UpdateStatus.CURRENT, "2.0")),
            ),
        )

        assertTrue(report.all { it.code < 128 })
        assertTrue("  [+] ${"Short".padEnd(24)}  2.0" in report)
        assertTrue("  [+] $longName  1.0" in report)
    }
}
