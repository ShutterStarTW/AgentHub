package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.usage.model.UsageSourceKind
import com.shutterstar.agenthub.usage.model.UsageStatus
import com.shutterstar.agenthub.usage.model.UsageWindowKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class CodexLocalRateLimitProviderTest {
    @TempDir
    lateinit var tempDir: Path

    private val now = Instant.parse("2026-11-19T12:00:00Z")

    @Test
    fun shouldParsePrimarySecondaryAndCreditsFromLatestRollout() {
        val codexDir = tempDir.resolve(".codex")
        val olderFile = writeRollout(
            codexDir,
            "sessions/2026/11/18/rollout-old.jsonl",
            listOf(
                tokenCountLine(
                    timestamp = "2026-11-18T10:00:00Z",
                    planType = "plus",
                    primaryPercent = 12.0,
                    primaryMinutes = 300,
                    primaryResetsAt = now.minusSeconds(3600).epochSecond,
                    secondaryPercent = 10.0,
                    secondaryMinutes = 10080,
                    secondaryResetsAt = now.plusSeconds(86400).epochSecond,
                ),
            ),
            modifiedAt = Instant.parse("2026-11-18T10:00:00Z"),
        )
        assertNotNull(olderFile)

        writeRollout(
            codexDir,
            "sessions/2026/11/19/rollout-new.jsonl",
            listOf(
                """{"timestamp":"2026-11-19T11:30:00Z","type":"event_msg","payload":{"type":"token_count","rate_limits":null}}""",
                tokenCountLine(
                    timestamp = "2026-11-19T11:45:00Z",
                    planType = "pro",
                    primaryPercent = 78.4,
                    primaryMinutes = 300,
                    primaryResetsAt = now.plusSeconds(4320).epochSecond,
                    secondaryPercent = 31.0,
                    secondaryMinutes = 10080,
                    secondaryResetsAt = now.plusSeconds(345600).epochSecond,
                    creditsJson = """{"has_credits":true,"unlimited":false,"balance":"14.50"}""",
                ),
                // Subsequent exec-mode event with null rate_limits must not erase the earlier valid one.
                """{"timestamp":"2026-11-19T11:50:00Z","type":"event_msg","payload":{"type":"token_count","rate_limits":null}}""",
            ),
            modifiedAt = Instant.parse("2026-11-19T11:50:00Z"),
        )

        val snapshot = CodexLocalRateLimitProvider(codexDir).discover(now)

        assertNotNull(snapshot)
        assertEquals("codex", snapshot!!.agentId)
        assertEquals(UsageStatus.OK, snapshot.status)
        assertEquals(UsageSourceKind.LOCAL_TELEMETRY, snapshot.sourceKind)
        assertEquals("Pro", snapshot.planLabel)
        assertEquals(Instant.parse("2026-11-19T11:45:00Z"), snapshot.observedAt)
        assertEquals(3, snapshot.windows.size)

        val primary = snapshot.windows[0]
        assertEquals("primary", primary.id)
        assertEquals("5-hour window", primary.label)
        assertEquals(UsageWindowKind.ROLLING, primary.kind)
        assertEquals(78.4, primary.usedPercent)
        assertEquals(300, primary.windowMinutes)
        assertEquals(now.plusSeconds(4320), primary.resetsAt)

        val secondary = snapshot.windows[1]
        assertEquals("secondary", secondary.id)
        assertEquals("Weekly limit", secondary.label)
        assertEquals(UsageWindowKind.WEEKLY, secondary.kind)
        assertEquals(31.0, secondary.usedPercent)

        val credits = snapshot.windows[2]
        assertEquals("credits", credits.id)
        assertEquals(UsageWindowKind.CREDITS, credits.kind)
        assertEquals(14.5, credits.limitAmount)
        assertEquals("credits", credits.unit)
    }

    @Test
    fun shouldMarkSnapshotStaleWhenAllRecordedWindowsHaveAlreadyReset() {
        val codexDir = tempDir.resolve(".codex")
        writeRollout(
            codexDir,
            "sessions/2026/11/10/rollout-stale.jsonl",
            listOf(
                tokenCountLine(
                    timestamp = "2026-11-10T10:00:00Z",
                    planType = "plus",
                    primaryPercent = 95.0,
                    primaryMinutes = 300,
                    primaryResetsAt = Instant.parse("2026-11-10T15:00:00Z").epochSecond,
                    secondaryPercent = 80.0,
                    secondaryMinutes = 10080,
                    secondaryResetsAt = Instant.parse("2026-11-17T10:00:00Z").epochSecond,
                ),
            ),
            modifiedAt = Instant.parse("2026-11-10T10:00:00Z"),
        )

        val snapshot = CodexLocalRateLimitProvider(codexDir).discover(now)

        assertNotNull(snapshot)
        assertEquals(UsageStatus.STALE, snapshot!!.status)
        assertTrue(snapshot.windows.all { it.isResetPassed(now) })
    }

    @Test
    fun shouldReturnNullWhenRolloutsContainNoRateLimitsOrMalformedJson() {
        val codexDir = tempDir.resolve(".codex")
        writeRollout(
            codexDir,
            "sessions/2026/11/19/rollout-empty.jsonl",
            listOf(
                "not valid json at all",
                """{"timestamp":"2026-11-19T11:00:00Z","type":"event_msg","payload":{"type":"token_count","rate_limits":null}}""",
            ),
            modifiedAt = now,
        )

        assertNull(CodexLocalRateLimitProvider(codexDir).discover(now))
    }

    private fun writeRollout(codexDir: Path, relativePath: String, lines: List<String>, modifiedAt: Instant): Path {
        val file = codexDir.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.write(file, lines)
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt))
        return file
    }

    private fun tokenCountLine(
        timestamp: String,
        planType: String,
        primaryPercent: Double,
        primaryMinutes: Int,
        primaryResetsAt: Long,
        secondaryPercent: Double,
        secondaryMinutes: Int,
        secondaryResetsAt: Long,
        creditsJson: String? = null,
    ): String {
        val creditsPart = creditsJson?.let { ""","credits":$it""" }.orEmpty()
        return """{"timestamp":"$timestamp","type":"event_msg","payload":{"type":"token_count","rate_limits":{"limit_id":"codex","plan_type":"$planType","primary":{"used_percent":$primaryPercent,"window_minutes":$primaryMinutes,"resets_at":$primaryResetsAt},"secondary":{"used_percent":$secondaryPercent,"window_minutes":$secondaryMinutes,"resets_at":$secondaryResetsAt}$creditsPart}}}"""
    }
}
