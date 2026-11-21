package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.projects.model.AgentProject
import com.shutterstar.agenthub.projects.model.AgentSession
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.UsageResetCredit
import com.shutterstar.agenthub.usage.model.UsageResetCredits
import com.shutterstar.agenthub.usage.model.UsageSourceKind
import com.shutterstar.agenthub.usage.model.UsageStatus
import com.shutterstar.agenthub.usage.model.UsageWindow
import com.shutterstar.agenthub.usage.model.UsageWindowKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class UsageDiscoveryServiceTest {
    private val now = Instant.parse("2026-11-19T12:00:00Z")

    @Test
    fun shouldSkipHiddenAgentsAndIsolateProviderFailures() {
        val hiddenProviderRan = AtomicBoolean(false)
        val hiddenProvider = object : AgentUsageProvider {
            override val agentId = "cursor"
            override val sourceKind = UsageSourceKind.LOCAL_TELEMETRY
            override fun isAvailable(): Boolean {
                hiddenProviderRan.set(true)
                return true
            }
            override fun discover(now: Instant): AgentUsageSnapshot? {
                hiddenProviderRan.set(true)
                return null
            }
        }
        val failingProvider = object : AgentUsageProvider {
            override val agentId = "claude"
            override val sourceKind = UsageSourceKind.LOCAL_TELEMETRY
            override fun isAvailable() = true
            override fun discover(now: Instant): AgentUsageSnapshot? = throw IllegalStateException("corrupt local file")
        }
        val workingProvider = object : AgentUsageProvider {
            override val agentId = "codex"
            override val sourceKind = UsageSourceKind.LOCAL_TELEMETRY
            override fun isAvailable() = true
            override fun discover(now: Instant): AgentUsageSnapshot = AgentUsageSnapshot(
                agentId = "codex",
                status = UsageStatus.OK,
                sourceKind = UsageSourceKind.LOCAL_TELEMETRY,
                planLabel = "Plus",
                observedAt = now.minusSeconds(60),
                windows = listOf(UsageWindow("primary", "5-hour window", UsageWindowKind.ROLLING, 42.0)),
            )
        }

        val visibleIds = setOf("claude", "codex", "copilot")
        val service = UsageDiscoveryService(
            providers = listOf(hiddenProvider, failingProvider, workingProvider),
            isAgentVisible = { it in visibleIds },
            candidateAgentIds = { setOf("claude", "codex", "copilot", "cursor") },
            now = { now },
        )

        val claudeSession = AgentSession(
            id = "s1",
            agentId = "claude",
            projectPath = "/work/app",
            startedAt = now.minusSeconds(1200),
            updatedAt = now.minusSeconds(600),
            sourcePath = "/tmp/s1.jsonl",
            messageCount = 3,
            statistics = mapOf("totalTokens" to "5000"),
        )
        val project = DiscoveredProject(
            identity = ProjectIdentity("p1", "/work/app", null, null),
            name = "app",
            path = "/work/app",
            gitRoot = null,
            gitRemote = null,
            currentBranch = null,
            agents = listOf(AgentProject("claude", "p1", 1, claudeSession.updatedAt, listOf(claudeSession))),
            lastActivity = claudeSession.updatedAt,
        )

        val result = service.discover(listOf(project))

        assertFalse(hiddenProviderRan.get(), "Hidden/uninstalled provider must never execute")
        assertEquals(listOf("claude", "codex", "copilot"), result.snapshots.map { it.agentId })
        assertEquals(1, result.warnings.size)
        assertEquals("claude", result.warnings.single().agentId)
        assertTrue(result.warnings.single().message.contains("corrupt local file"))

        val codex = result.snapshots.single { it.agentId == "codex" }
        assertEquals(UsageStatus.OK, codex.status)
        assertEquals(42.0, codex.windows.single().usedPercent)

        // Claude failed telemetry, but still retains its local session activity cleanly.
        val claude = result.snapshots.single { it.agentId == "claude" }
        assertEquals(UsageStatus.LOCAL_ONLY, claude.status)
        assertEquals(5000L, claude.localSummary?.last5Hours?.totalTokens)

        val copilot = result.snapshots.single { it.agentId == "copilot" }
        assertEquals(UsageStatus.LOCAL_ONLY, copilot.status)
    }

    private fun stubProvider(
        kind: UsageSourceKind,
        snapshot: AgentUsageSnapshot?,
        network: Boolean = false,
    ) = object : AgentUsageProvider {
        override val agentId = "codex"
        override val sourceKind = kind
        override val requiresNetwork = network
        override fun isAvailable() = true
        override fun discover(now: Instant): AgentUsageSnapshot? = snapshot
    }

    @Test
    fun shouldFallBackToLocalWindowsWhenLiveProviderHasNoWindows() {
        val live = stubProvider(
            UsageSourceKind.LIVE_API,
            AgentUsageSnapshot("codex", UsageStatus.RATE_LIMITED, UsageSourceKind.LIVE_API, observedAt = now),
            network = true,
        )
        val local = stubProvider(
            UsageSourceKind.LOCAL_TELEMETRY,
            AgentUsageSnapshot(
                "codex", UsageStatus.OK, UsageSourceKind.LOCAL_TELEMETRY,
                windows = listOf(UsageWindow("primary", "5-hour window", UsageWindowKind.ROLLING, 12.0)),
            ),
        )

        val result = UsageDiscoveryService(
            providers = listOf(local, live),
            liveQueriesEnabled = { true },
            candidateAgentIds = { setOf("codex") },
            now = { now },
        ).discover(emptyList())

        val codex = result.snapshots.single()
        assertEquals(UsageStatus.OK, codex.status)
        assertEquals(12.0, codex.windows.single().usedPercent)
    }

    @Test
    fun shouldKeepWindowlessLiveSnapshotWhenNoLocalFallbackExists() {
        val live = stubProvider(
            UsageSourceKind.LIVE_API,
            AgentUsageSnapshot("codex", UsageStatus.AUTH_REQUIRED, UsageSourceKind.LIVE_API, statusNote = "Sign in"),
            network = true,
        )

        val result = UsageDiscoveryService(
            providers = listOf(live),
            liveQueriesEnabled = { true },
            candidateAgentIds = { setOf("codex") },
            now = { now },
        ).discover(emptyList())

        assertEquals(UsageStatus.AUTH_REQUIRED, result.snapshots.single().status)
    }

    @Test
    fun shouldAttachSavedResetsOnlyWithLiveQuotasAndIgnoreFailures() {
        val withWindows = AgentUsageSnapshot(
            "claude", UsageStatus.OK, UsageSourceKind.LOCAL_TELEMETRY,
            windows = listOf(UsageWindow("five_hour", "5-hour window", UsageWindowKind.ROLLING, 20.0)),
        )
        val provider = object : AgentUsageProvider {
            override val agentId = "claude"
            override val sourceKind = UsageSourceKind.LOCAL_TELEMETRY
            override fun isAvailable() = true
            override fun discover(now: Instant): AgentUsageSnapshot = withWindows
        }
        val resets = UsageResetCredits(1, listOf(UsageResetCredit("One reset", expiresAt = now.plusSeconds(60))))
        fun enricher(result: () -> UsageResetCredits?) = object : AgentResetCreditProvider {
            override val agentId = "claude"
            override val requiresNetwork = true
            override fun discover(now: Instant): UsageResetCredits? = result()
        }
        fun discover(live: Boolean, enricher: AgentResetCreditProvider) = UsageDiscoveryService(
            providers = listOf(provider),
            liveQueriesEnabled = { live },
            candidateAgentIds = { setOf("claude") },
            now = { now },
            resetCreditProviders = listOf(enricher),
        ).discover(emptyList()).snapshots.single { it.agentId == "claude" }

        assertEquals(resets, discover(true, enricher { resets }).resetCredits)
        assertEquals(null, discover(false, enricher { resets }).resetCredits)
        assertEquals(null, discover(true, enricher { throw IllegalStateException("network down") }).resetCredits)
        assertEquals(UsageStatus.OK, discover(true, enricher { null }).status)
    }

    @Test
    fun shouldRunSlowProvidersOnlyInTheSlowPassAndKeepLocalSummary() {
        val slowRuns = java.util.concurrent.atomic.AtomicInteger()
        val slowProvider = object : AgentUsageProvider {
            override val agentId = "openclaw"
            override val sourceKind = UsageSourceKind.HEADLESS_COMMAND
            override val requiresNetwork = true
            override val slow = true
            override fun isAvailable() = true
            override fun discover(now: Instant): AgentUsageSnapshot {
                slowRuns.incrementAndGet()
                return AgentUsageSnapshot(
                    "openclaw", UsageStatus.OK, UsageSourceKind.HEADLESS_COMMAND,
                    windows = listOf(UsageWindow("5h", "5h", UsageWindowKind.ROLLING, 9.0)),
                )
            }
        }
        val fastProvider = stubProvider(
            UsageSourceKind.LOCAL_TELEMETRY,
            AgentUsageSnapshot(
                "codex", UsageStatus.OK, UsageSourceKind.LOCAL_TELEMETRY,
                windows = listOf(UsageWindow("primary", "5-hour window", UsageWindowKind.ROLLING, 12.0)),
            ),
        )
        var live = false
        val service = UsageDiscoveryService(
            providers = listOf(slowProvider, fastProvider),
            liveQueriesEnabled = { live },
            candidateAgentIds = { setOf("codex", "openclaw") },
            now = { now },
        )

        assertFalse(service.hasSlowProviders())
        live = true
        assertTrue(service.hasSlowProviders())

        val fast = service.discover(emptyList())
        assertEquals(0, slowRuns.get())
        assertEquals(UsageStatus.LOCAL_ONLY, fast.snapshots.single { it.agentId == "openclaw" }.status)

        val merged = service.discoverSlow(fast)
        assertEquals(1, slowRuns.get())
        assertEquals(UsageStatus.OK, merged.snapshots.single { it.agentId == "openclaw" }.status)
        assertTrue(merged.snapshots.single { it.agentId == "codex" } === fast.snapshots.single { it.agentId == "codex" })

        live = false
        assertTrue(service.discoverSlow(fast) === fast)
    }

    @Test
    fun shouldReportSlowProviderTimeoutWithoutTouchingTheFastResult() {
        val stuck = object : AgentUsageProvider {
            override val agentId = "openclaw"
            override val sourceKind = UsageSourceKind.HEADLESS_COMMAND
            override val slow = true
            override fun isAvailable() = true
            override fun discover(now: Instant): AgentUsageSnapshot? {
                Thread.sleep(5_000)
                return null
            }
        }
        val service = UsageDiscoveryService(
            providers = listOf(stuck),
            candidateAgentIds = { setOf("openclaw") },
            slowProviderTimeoutMillis = 100,
            now = { now },
        )
        val fast = service.discover(emptyList())

        val merged = service.discoverSlow(fast)

        assertEquals(fast.snapshots, merged.snapshots)
        assertEquals("Usage query timed out", merged.warnings.single().message)
    }
    private fun failingLiveProvider(agentId: String) = object : AgentUsageProvider {
        override val agentId = agentId
        override val sourceKind = UsageSourceKind.HEADLESS_COMMAND
        override val requiresNetwork = true
        override fun isAvailable() = true
        override fun discover(now: Instant): AgentUsageSnapshot? = null
    }

    private fun previousLive(
        windows: List<UsageWindow>,
        observedAt: Instant = now.minusSeconds(3600),
        status: UsageStatus = UsageStatus.OK,
        sourceKind: UsageSourceKind = UsageSourceKind.HEADLESS_COMMAND,
    ) = AgentUsageSnapshot("claude", status, sourceKind, planLabel = "Max", observedAt = observedAt, windows = windows)

    private fun lastGoodService(previous: AgentUsageSnapshot, live: Boolean = true) = UsageDiscoveryService(
        providers = listOf(failingLiveProvider("claude")),
        liveQueriesEnabled = { live },
        candidateAgentIds = { setOf("claude") },
        now = { now },
        previousSnapshots = { listOf(previous) },
    )

    @Test
    fun shouldKeepLastLiveReadingAsStaleUntilItsWindowResets() {
        val future = UsageWindow("five_hour", "5 hours", UsageWindowKind.ROLLING, 40.0, resetsAt = now.plusSeconds(600))
        val passed = UsageWindow("seven_day", "7 days", UsageWindowKind.WEEKLY, 20.0, resetsAt = now.minusSeconds(60))

        val snapshot = lastGoodService(previousLive(listOf(future, passed))).discover(emptyList()).snapshots.single()

        assertEquals(UsageStatus.STALE, snapshot.status)
        assertEquals(listOf(future), snapshot.windows)
        assertEquals(now.minusSeconds(3600), snapshot.observedAt)
        assertEquals(UsageSourceKind.HEADLESS_COMMAND, snapshot.sourceKind)
        assertTrue(snapshot.statusNote.orEmpty().contains("Last live reading"))
    }

    @Test
    fun shouldDropAnUndatedWindowAfterItsTimeToLive() {
        val undated = UsageWindow("credits", "Credits", UsageWindowKind.CREDITS, 10.0)

        val fresh = lastGoodService(previousLive(listOf(undated), observedAt = now.minusSeconds(3600))).discover(emptyList()).snapshots.single()
        val old = lastGoodService(previousLive(listOf(undated), observedAt = now.minusSeconds(7 * 3600))).discover(emptyList()).snapshots.single()

        assertEquals(UsageStatus.STALE, fresh.status)
        assertEquals(UsageStatus.LOCAL_ONLY, old.status)
    }

    @Test
    fun shouldNotCarryOverWithoutLiveQuotasOrFromNonLiveOrNonWindowSnapshots() {
        val window = UsageWindow("five_hour", "5 hours", UsageWindowKind.ROLLING, 40.0, resetsAt = now.plusSeconds(600))

        assertEquals(UsageStatus.LOCAL_ONLY, lastGoodService(previousLive(listOf(window)), live = false).discover(emptyList()).snapshots.single().status)
        assertEquals(
            UsageStatus.LOCAL_ONLY,
            lastGoodService(previousLive(listOf(window), sourceKind = UsageSourceKind.LOCAL_TELEMETRY)).discover(emptyList()).snapshots.single().status,
        )
        assertEquals(
            UsageStatus.LOCAL_ONLY,
            lastGoodService(previousLive(emptyList(), status = UsageStatus.AUTH_REQUIRED)).discover(emptyList()).snapshots.single().status,
        )
    }

    @Test
    fun shouldDropSavedResetsAndKeepTheOriginalReadingTimeWhenCarriedAgain() {
        val window = UsageWindow("five_hour", "5 hours", UsageWindowKind.ROLLING, 40.0, resetsAt = now.plusSeconds(600))
        val previous = previousLive(listOf(window)).copy(resetCredits = UsageResetCredits(2))

        val first = lastGoodService(previous).discover(emptyList()).snapshots.single()
        val second = lastGoodService(first).discover(emptyList()).snapshots.single()

        assertEquals(null, first.resetCredits)
        assertEquals(previous.observedAt, second.observedAt)
        assertEquals(UsageStatus.STALE, second.status)
    }
}
