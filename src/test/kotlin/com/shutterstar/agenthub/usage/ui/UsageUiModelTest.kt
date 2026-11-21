package com.shutterstar.agenthub.usage.ui

import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.LocalUsageBucket
import com.shutterstar.agenthub.usage.model.LocalUsageSummary
import com.shutterstar.agenthub.usage.model.UsageResetCredit
import com.shutterstar.agenthub.usage.model.UsageResetCredits
import com.shutterstar.agenthub.usage.model.UsageSourceCatalog
import com.shutterstar.agenthub.usage.model.UsageSourceKind
import com.shutterstar.agenthub.usage.model.UsageStatus
import com.shutterstar.agenthub.usage.model.UsageWindow
import com.shutterstar.agenthub.usage.model.UsageWindowKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class UsageUiModelTest {
    private val now = Instant.parse("2026-11-19T12:00:00Z")
    private val names = mapOf(
        "claude" to "Claude Code",
        "codex" to "Codex CLI",
        "copilot" to "Copilot CLI",
        "cursor" to "Cursor CLI",
    )

    @Test
    fun shouldSortHighestUsageFirstAndSupportNameAndRecentActivitySorts() {
        val codex = AgentUsageSnapshot(
            agentId = "codex",
            status = UsageStatus.OK,
            sourceKind = UsageSourceKind.LOCAL_TELEMETRY,
            observedAt = now.minus(Duration.ofMinutes(30)),
            windows = listOf(UsageWindow("primary", "5-hour window", UsageWindowKind.ROLLING, 82.0, resetsAt = now.plusSeconds(3600))),
        )
        val claude = AgentUsageSnapshot(
            agentId = "claude",
            status = UsageStatus.LOCAL_ONLY,
            sourceKind = UsageSourceKind.LOCAL_SESSIONS,
            localSummary = LocalUsageSummary(
                last7Days = LocalUsageBucket(sessions = 5, prompts = 20, totalTokens = 500_000),
                last30Days = LocalUsageBucket(sessions = 5, prompts = 20, totalTokens = 500_000),
                lastActivity = now.minus(Duration.ofMinutes(5)),
            ),
        )
        val copilot = AgentUsageSnapshot(
            agentId = "copilot",
            status = UsageStatus.LOCAL_ONLY,
            sourceKind = UsageSourceKind.LOCAL_SESSIONS,
            localSummary = LocalUsageSummary(
                last7Days = LocalUsageBucket(sessions = 1, prompts = 2, totalTokens = 10_000),
                last30Days = LocalUsageBucket(sessions = 1, prompts = 2, totalTokens = 10_000),
                lastActivity = now.minus(Duration.ofHours(3)),
            ),
        )
        val cursor = AgentUsageSnapshot(
            agentId = "cursor",
            status = UsageStatus.LOCAL_ONLY,
            sourceKind = UsageSourceKind.LOCAL_SESSIONS,
        )

        val input = listOf(cursor, copilot, claude, codex)

        assertEquals(
            listOf("codex", "claude", "copilot", "cursor"),
            UsageUiModel.filterAndSort(input, "", UsageSortOrder.HIGHEST_USAGE, { names[it] ?: it }, now).map { it.agentId },
        )
        assertEquals(
            listOf("claude", "codex", "copilot", "cursor"),
            UsageUiModel.filterAndSort(input, "", UsageSortOrder.NAME, { names[it] ?: it }, now).map { it.agentId },
        )
        assertEquals(
            listOf("claude", "codex", "copilot", "cursor"),
            UsageUiModel.filterAndSort(input, "", UsageSortOrder.RECENT_ACTIVITY, { names[it] ?: it }, now).map { it.agentId },
        )
        assertEquals(
            listOf("codex"),
            UsageUiModel.filterAndSort(input, "5-hour", UsageSortOrder.HIGHEST_USAGE, { names[it] ?: it }, now).map { it.agentId },
        )
    }

    @Test
    fun shouldClassifySeverityThresholdsAndFormatNumbersCleanly() {
        val criticalWindow = UsageWindow("primary", "5-hour window", UsageWindowKind.ROLLING, 93.0, resetsAt = now.plusSeconds(1200))
        val warningWindow = UsageWindow("primary", "5-hour window", UsageWindowKind.ROLLING, 75.0, resetsAt = now.plusSeconds(1200))
        val normalWindow = UsageWindow("primary", "5-hour window", UsageWindowKind.ROLLING, 25.0, resetsAt = now.plusSeconds(1200))
        val expiredWindow = UsageWindow("primary", "5-hour window", UsageWindowKind.ROLLING, 99.0, resetsAt = now.minusSeconds(60))

        assertEquals(UsageSeverity.CRITICAL, UsageUiModel.windowSeverity(criticalWindow, UsageStatus.OK, now))
        assertEquals(UsageSeverity.WARNING, UsageUiModel.windowSeverity(warningWindow, UsageStatus.OK, now))
        assertEquals(UsageSeverity.NORMAL, UsageUiModel.windowSeverity(normalWindow, UsageStatus.OK, now))
        assertEquals(UsageSeverity.STALE, UsageUiModel.windowSeverity(expiredWindow, UsageStatus.OK, now))

        assertEquals("<1%", UsageUiModel.formatPercent(0.2))
        assertEquals("78%", UsageUiModel.formatPercent(78.4))
        assertEquals("1.5M", UsageUiModel.formatTokens(1_500_000))
        assertEquals("640K", UsageUiModel.formatTokens(640_000))
        assertEquals("$1.25", UsageUiModel.formatCost(12_500_000_000L))
        assertEquals("<$0.01", UsageUiModel.formatCost(1_000_000L))
        assertEquals("in 1h 12m", UsageUiModel.formatResetCountdown(now.plusSeconds(72 * 60), now))
        assertEquals("in 2d 3h", UsageUiModel.formatResetCountdown(now.plus(Duration.ofHours(51)), now))
        assertEquals("reset", UsageUiModel.formatResetCountdown(now.minusSeconds(5), now))

        val summary = UsageUiModel.statusSummary(
            allSnapshots = listOf(
                AgentUsageSnapshot("codex", UsageStatus.OK, UsageSourceKind.LOCAL_TELEMETRY, windows = listOf(warningWindow)),
                AgentUsageSnapshot("claude", UsageStatus.LOCAL_ONLY, UsageSourceKind.LOCAL_SESSIONS),
            ),
            visibleCount = 2,
            filtered = false,
            refreshing = false,
            refreshedAt = now,
            now = now,
        )
        assertTrue(summary.contains("2 agents"))
        assertTrue(summary.contains("1 near limit"))
        assertTrue(summary.contains("Local mode"))
    }

    private fun paceWindow(used: Double, totalMinutes: Int?, remainingMinutes: Long?) = UsageWindow(
        id = "w", label = "w", kind = UsageWindowKind.ROLLING, usedPercent = used,
        windowMinutes = totalMinutes, resetsAt = remainingMinutes?.let { now.plusSeconds(it * 60) },
    )

    @Test
    fun shouldProjectEvenPaceAndTimeToLimit() {
        val onTrack = paceWindow(35.4, 30 * 24 * 60, 13L * 24 * 60)
        assertEquals("on track, ~62% by reset", UsageUiModel.paceLine(onTrack, now))
        assertEquals(56.7, UsageUiModel.pace(onTrack, now)!!.elapsedPercent, 0.1)

        val ahead = paceWindow(50.0, 300, 240)
        assertEquals("ahead of pace, ~1h to limit", UsageUiModel.paceLine(ahead, now))

        assertEquals("ahead of pace, ~0m to limit", UsageUiModel.paceLine(paceWindow(100.0, 43_200, 20_000), now))
        assertEquals("on track, ~0% by reset", UsageUiModel.paceLine(paceWindow(0.0, 10_080, 5_000), now))
    }

    @Test
    fun shouldSkipPaceWithoutLengthResetOrEnoughElapsedTime() {
        assertEquals(null, UsageUiModel.paceLine(paceWindow(10.0, null, 100), now))
        assertEquals(null, UsageUiModel.paceLine(paceWindow(10.0, 300, null), now))
        assertEquals(null, UsageUiModel.paceLine(paceWindow(10.0, 300, -5), now))
        assertEquals(null, UsageUiModel.paceLine(paceWindow(10.0, 300, 299), now))
        assertEquals(null, UsageUiModel.paceLine(paceWindow(10.0, 300, 400), now))
    }

    @Test
    fun shouldDescribeSavedResets() {
        assertEquals(null, UsageUiModel.resetCreditsHeadline(UsageResetCredits(0)))
        assertEquals("1 saved reset available", UsageUiModel.resetCreditsHeadline(UsageResetCredits(1)))
        assertEquals("3 saved resets available", UsageUiModel.resetCreditsHeadline(UsageResetCredits(3)))

        val soon = UsageResetCredit("One free rate limit reset", now.plus(Duration.ofDays(10).plusHours(7)))
        val line = UsageUiModel.resetCreditLine(soon, now)
        assertTrue(line.startsWith("One free rate limit reset — expires "))
        assertTrue(line.endsWith("(in 10d 7h)"))
        assertTrue(UsageUiModel.resetCreditLine(soon.copy(requiresLimit = true), now).endsWith("· usable once a limit is reached"))
        assertTrue(UsageUiModel.resetCreditLine(soon.copy(requiresLimit = false), now).endsWith("· usable now"))
        assertEquals("Saved reset", UsageUiModel.resetCreditLine(UsageResetCredit(null), now))
    }

    @Test
    fun shouldMeasureElapsedValidityOfSavedResets() {
        val credit = UsageResetCredit("r", expiresAt = now.plus(Duration.ofDays(20)), grantedAt = now.minus(Duration.ofDays(10)))
        assertEquals(33.33, UsageUiModel.resetCreditElapsedPercent(credit, now)!!, 0.01)
        assertEquals(null, UsageUiModel.resetCreditElapsedPercent(credit.copy(grantedAt = null), now))
        assertEquals(null, UsageUiModel.resetCreditElapsedPercent(credit.copy(expiresAt = null), now))
        assertEquals(100.0, UsageUiModel.resetCreditElapsedPercent(credit.copy(expiresAt = now.minusSeconds(1)), now)!!, 0.01)
    }

    @Test
    fun shouldWarnWhenASavedResetIsAboutToExpire() {
        fun severity(remaining: Duration) =
            UsageUiModel.resetCreditSeverity(UsageResetCredit("r", expiresAt = now.plus(remaining)), now)
        assertEquals(UsageSeverity.NEUTRAL, severity(Duration.ofDays(10)))
        assertEquals(UsageSeverity.WARNING, severity(Duration.ofDays(2)))
        assertEquals(UsageSeverity.CRITICAL, severity(Duration.ofHours(5)))
        assertEquals(UsageSeverity.NEUTRAL, UsageUiModel.resetCreditSeverity(UsageResetCredit("r"), now))
    }
    @Test
    fun shouldHideAgentsWithoutARealLimitButKeepSignInAndRateLimited() {
        fun snapshot(id: String, status: UsageStatus, windows: List<UsageWindow> = emptyList()) =
            AgentUsageSnapshot(id, status, UsageSourceKind.LOCAL_SESSIONS, windows = windows)
        val capped = snapshot("codex", UsageStatus.OK, listOf(UsageWindow("w", "5 hours", UsageWindowKind.ROLLING, 10.0)))
        val unlimited = snapshot("claude", UsageStatus.OK, listOf(UsageWindow("w", "Unlimited", UsageWindowKind.CUSTOM, null, unlimited = true)))
        val localOnly = snapshot("copilot", UsageStatus.LOCAL_ONLY)
        val signIn = snapshot("cursor", UsageStatus.AUTH_REQUIRED)
        val limited = snapshot("grok", UsageStatus.RATE_LIMITED)
        val all = listOf(capped, unlimited, localOnly, signIn, limited)

        val shown = UsageUiModel.filterAndSort(
            snapshots = all,
            query = "",
            sortOrder = UsageSortOrder.NAME,
            displayName = { names[it] ?: it },
            now = now,
            onlyWithLimits = true,
        ).map { it.agentId }

        assertEquals(setOf("codex", "cursor", "grok"), shown.toSet())
        assertEquals(
            5,
            UsageUiModel.filterAndSort(all, "", UsageSortOrder.NAME, { names[it] ?: it }, now).size,
        )
    }
    @Test
    fun shouldOfferOnlyRealDashboardsOrUsageGuides() {
        assertEquals("https://claude.ai/settings/usage", UsageSourceCatalog.billingUrl("claude"))
        assertEquals("Open Usage Dashboard", UsageSourceCatalog.linkLabel(UsageSourceCatalog.billingUrl("claude")))
        assertEquals("Open Usage Guide", UsageSourceCatalog.linkLabel(UsageSourceCatalog.billingUrl("kiro")))
        assertEquals("Open Usage Guide", UsageSourceCatalog.linkLabel(UsageSourceCatalog.billingUrl("hermes")))
        listOf("grok", "cline", "opencode", "qwen", "pi").forEach { assertEquals(null, UsageSourceCatalog.billingUrl(it)) }
        assertTrue(UsageSourceCatalog.isKnownUsageUrl("https://platform.openai.com/usage"))
        assertTrue(!UsageSourceCatalog.isKnownUsageUrl("https://openclaw.ai"))
    }
}
