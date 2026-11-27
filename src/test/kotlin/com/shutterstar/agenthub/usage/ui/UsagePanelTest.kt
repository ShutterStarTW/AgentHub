package com.shutterstar.agenthub.usage.ui

import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.LocalUsageBucket
import com.shutterstar.agenthub.usage.model.LocalUsageSummary
import com.shutterstar.agenthub.usage.model.UsageDiscoveryResult
import com.shutterstar.agenthub.usage.model.UsageSourceKind
import com.shutterstar.agenthub.usage.model.UsageStatus
import com.shutterstar.agenthub.usage.model.UsageWindow
import com.shutterstar.agenthub.usage.model.UsageWindowKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import javax.swing.SwingUtilities

class UsagePanelTest {
    private val now = Instant.parse("2026-11-19T12:00:00Z")

    @Test
    fun shouldRetainSelectionAcrossRefreshesAndFilterByQuery() {
        SwingUtilities.invokeAndWait {
            val panel = UsagePanel(
                displayName = { id ->
                    when (id) {
                        "codex" -> "Codex CLI"
                        "claude" -> "Claude Code"
                        else -> id
                    }
                },
                agentIcon = { null },
                now = { now },
            )
            val codex = AgentUsageSnapshot(
                agentId = "codex",
                status = UsageStatus.OK,
                sourceKind = UsageSourceKind.LOCAL_TELEMETRY,
                planLabel = "Pro",
                observedAt = now,
                windows = listOf(UsageWindow("primary", "5-hour window", UsageWindowKind.ROLLING, 50.0, resetsAt = now.plusSeconds(3600))),
            )
            val claude = AgentUsageSnapshot(
                agentId = "claude",
                status = UsageStatus.LOCAL_ONLY,
                sourceKind = UsageSourceKind.LOCAL_SESSIONS,
                localSummary = LocalUsageSummary(
                    last24Hours = LocalUsageBucket(sessions = 2, prompts = 5, totalTokens = 50_000),
                    last30Days = LocalUsageBucket(sessions = 2, prompts = 5, totalTokens = 50_000),
                ),
            )

            panel.setState(listOf(claude, codex), warnings = emptyList(), refreshing = false, refreshedAt = now)
            assertEquals(listOf("codex", "claude"), panel.displayedAgentIds())
            assertEquals("codex", panel.selectedAgentId())

            assertTrue(panel.selectAgent("claude"))
            assertEquals("claude", panel.selectedAgentId())

            // Refresh keeps the selected agent rather than jumping back to row 0.
            panel.setState(listOf(claude, codex), warnings = emptyList(), refreshing = false, refreshedAt = now)
            assertEquals("claude", panel.selectedAgentId())

            panel.setQuery("codex")
            assertEquals(listOf("codex"), panel.displayedAgentIds())
            assertEquals("codex", panel.selectedAgentId())

            panel.setQuery("nonexistent")
            assertEquals(emptyList<String>(), panel.displayedAgentIds())
            assertEquals(null, panel.selectedAgentId())
        }
    }

    @Test
    fun shouldListOnlyAgentsWithLimitsWhenTheFilterIsOnAndReportTheChoice() {
        SwingUtilities.invokeAndWait {
            val toggled = mutableListOf<Boolean>()
            val panel = UsagePanel(agentIcon = { null }, onOnlyWithLimitsToggled = { toggled += it }, now = { now })
            val codex = AgentUsageSnapshot(
                agentId = "codex",
                status = UsageStatus.OK,
                sourceKind = UsageSourceKind.LOCAL_TELEMETRY,
                observedAt = now,
                windows = listOf(UsageWindow("primary", "5-hour window", UsageWindowKind.ROLLING, 50.0, resetsAt = now.plusSeconds(3600))),
            )
            val claude = AgentUsageSnapshot("claude", UsageStatus.LOCAL_ONLY, UsageSourceKind.LOCAL_SESSIONS)
            panel.setState(listOf(claude, codex), warnings = emptyList(), refreshing = false, refreshedAt = now)
            assertEquals(2, panel.displayedAgentIds().size)

            panel.setOnlyWithLimits(true)
            assertEquals(listOf("codex"), panel.displayedAgentIds())

            panel.setOnlyWithLimits(false)
            assertEquals(2, panel.displayedAgentIds().size)
            assertEquals(emptyList<Boolean>(), toggled)
        }
    }

    @Test
    fun shouldDeliverCachedAndRefreshedStateAndDropClosedCallbacks() {
        val states = mutableListOf<UsageControllerState>()
        val pendingTasks = mutableListOf<Runnable>()
        var recorded: UsageDiscoveryResult? = null
        val cachedSnapshot = AgentUsageSnapshot("claude", UsageStatus.LOCAL_ONLY, UsageSourceKind.LOCAL_SESSIONS)
        val liveSnapshot = AgentUsageSnapshot(
            agentId = "codex",
            status = UsageStatus.OK,
            sourceKind = UsageSourceKind.LOCAL_TELEMETRY,
            windows = listOf(UsageWindow("primary", "5-hour window", UsageWindowKind.ROLLING, 30.0)),
        )

        val controller = UsageController(
            executor = { pendingTasks += it },
            deliver = { it() },
            discover = { UsageDiscoveryResult(listOf(liveSnapshot), refreshedAt = now) },
            cached = { listOf(cachedSnapshot) to now.minusSeconds(300) },
            recordCache = { recorded = it },
            onStateChanged = { states += it },
        )

        controller.loadCached()
        assertEquals(listOf("claude"), states.last().snapshots.map { it.agentId })
        assertFalse(states.last().loading)

        controller.refresh(emptyList())
        assertTrue(states.last().loading)
        pendingTasks.removeAt(0).run()

        assertFalse(states.last().loading)
        assertEquals(listOf("codex"), states.last().snapshots.map { it.agentId })
        assertEquals(listOf(liveSnapshot), recorded?.snapshots)

        // After close, queued background tasks must not publish new state.
        val countBeforeClose = states.size
        controller.refresh(emptyList(), force = true)
        controller.close()
        pendingTasks.forEach(Runnable::run)
        assertEquals(countBeforeClose + 1, states.size)
    }

    @Test
    fun shouldNotCacheASupersededRunOrOneThatFinishedInAnotherPartition() {
        val pendingTasks = mutableListOf<Runnable>()
        val recorded = mutableListOf<UsageDiscoveryResult>()
        var partition = "host"
        val controller = UsageController(
            executor = { pendingTasks += it },
            deliver = { it() },
            discover = { UsageDiscoveryResult(emptyList(), refreshedAt = now, liveEnabled = true) },
            cached = { emptyList<AgentUsageSnapshot>() to null },
            recordCache = { recorded += it },
            isLiveEnabled = { true },
            onStateChanged = {},
            partition = { partition },
        )

        controller.refresh(emptyList(), force = true)
        controller.refresh(emptyList(), force = true)
        pendingTasks.removeAt(0).run()
        assertEquals(0, recorded.size)

        partition = "wsl-Ubuntu"
        pendingTasks.removeAt(0).run()
        assertEquals(0, recorded.size)

        partition = "host"
        controller.refresh(emptyList(), force = true)
        pendingTasks.removeAt(0).run()
        assertEquals(1, recorded.size)
    }

    @Test
    fun shouldClearLocalSummaryOfAnAgentWithoutSessionsDuringTheLiveCooldown() {
        var currentTime = now
        val pendingTasks = mutableListOf<Runnable>()
        val states = mutableListOf<UsageControllerState>()
        val summary = LocalUsageSummary()
        val live = AgentUsageSnapshot(
            agentId = "claude",
            status = UsageStatus.OK,
            sourceKind = UsageSourceKind.LIVE_API,
            windows = listOf(UsageWindow("five_hour", "5-hour window", UsageWindowKind.ROLLING, 20.0)),
            localSummary = summary,
        )
        val controller = UsageController(
            executor = { pendingTasks += it },
            deliver = { it() },
            discover = { UsageDiscoveryResult(listOf(live), refreshedAt = currentTime, liveEnabled = true) },
            cached = { emptyList<AgentUsageSnapshot>() to null },
            recordCache = {},
            isLiveEnabled = { true },
            now = { currentTime },
            onStateChanged = { states += it },
        )
        controller.refresh(emptyList(), force = true)
        pendingTasks.removeAt(0).run()
        assertEquals(summary, states.last().snapshots.single().localSummary)

        currentTime = now.plusSeconds(10)
        controller.refresh(emptyList())

        assertEquals(null, states.last().snapshots.single().localSummary)
    }

    @Test
    fun shouldRespectLiveCooldownForAutomaticRefreshesButAllowForcedRefresh() {
        var currentTime = now
        var discoverCalls = 0
        val pendingTasks = mutableListOf<Runnable>()
        val liveSnapshot = AgentUsageSnapshot(
            agentId = "claude",
            status = UsageStatus.OK,
            sourceKind = UsageSourceKind.LIVE_API,
            windows = listOf(UsageWindow("five_hour", "5-hour window", UsageWindowKind.ROLLING, 20.0)),
        )
        val controller = UsageController(
            executor = { pendingTasks += it },
            deliver = { it() },
            discover = {
                discoverCalls++
                UsageDiscoveryResult(listOf(liveSnapshot), refreshedAt = currentTime, liveEnabled = true)
            },
            cached = { emptyList<AgentUsageSnapshot>() to null },
            recordCache = {},
            isLiveEnabled = { true },
            liveCooldownMillis = 60_000L,
            now = { currentTime },
            onStateChanged = {},
        )

        controller.refresh(emptyList(), force = false)
        pendingTasks.removeAt(0).run()
        assertEquals(1, discoverCalls)

        // 10 seconds later: automatic refresh skips provider network call.
        currentTime = now.plusSeconds(10)
        controller.refresh(emptyList(), force = false)
        assertEquals(0, pendingTasks.size)
        assertEquals(1, discoverCalls)

        // Forced refresh (manual Refresh button) bypasses cooldown.
        controller.refresh(emptyList(), force = true)
        pendingTasks.removeAt(0).run()
        assertEquals(2, discoverCalls)
    }

    @Test
    fun shouldDeliverFastResultFirstThenReplaceOnlySlowAgents() {
        val states = mutableListOf<UsageControllerState>()
        val pendingTasks = mutableListOf<Runnable>()
        val codex = AgentUsageSnapshot("codex", UsageStatus.LOCAL_ONLY, UsageSourceKind.LOCAL_SESSIONS)
        val openClawLocal = AgentUsageSnapshot("openclaw", UsageStatus.LOCAL_ONLY, UsageSourceKind.LOCAL_SESSIONS)
        val openClawLive = AgentUsageSnapshot(
            agentId = "openclaw",
            status = UsageStatus.OK,
            sourceKind = UsageSourceKind.HEADLESS_COMMAND,
            windows = listOf(UsageWindow("5h", "5h", UsageWindowKind.ROLLING, 9.0)),
        )
        var recorded: UsageDiscoveryResult? = null
        val controller = UsageController(
            executor = { pendingTasks += it },
            deliver = { it() },
            discover = { UsageDiscoveryResult(listOf(codex, openClawLocal), refreshedAt = now) },
            cached = { emptyList<AgentUsageSnapshot>() to null },
            recordCache = { recorded = it },
            onStateChanged = { states += it },
            hasSlowProviders = { true },
            discoverSlow = { fast -> fast.copy(snapshots = listOf(fast.snapshots[0], openClawLive)) },
        )

        controller.refresh(emptyList())
        pendingTasks.removeAt(0).run()

        val last = states.last()
        assertEquals(UsageStatus.LOCAL_ONLY, states.first { !it.loading && it.snapshots.isNotEmpty() }.snapshots[1].status)
        assertEquals(listOf(UsageStatus.LOCAL_ONLY, UsageStatus.OK), last.snapshots.map { it.status })
        assertTrue(last.snapshots[0] === codex)
        assertEquals(listOf(openClawLive), recorded?.snapshots?.filter { it.agentId == "openclaw" })
        assertFalse(last.loading)
    }
}
