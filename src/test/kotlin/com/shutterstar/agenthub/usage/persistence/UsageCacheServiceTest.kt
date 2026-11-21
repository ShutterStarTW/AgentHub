package com.shutterstar.agenthub.usage.persistence

import com.shutterstar.agenthub.storage.AgentHubHome
import com.shutterstar.agenthub.storage.SharedXmlStore
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class UsageCacheServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun shouldRoundTripSnapshotsThroughSharedXmlStoreAndFilterHiddenAgents() {
        val home = AgentHubHome(root = tempDir.resolve("agenthub-home"))
        val store = SharedXmlStore(home, "cache/host/usage.xml", UsageCacheState::class.java, ::UsageCacheState)
        val service = UsageCacheService(store)
        val now = Instant.parse("2026-11-19T12:00:00Z")

        val codex = AgentUsageSnapshot(
            agentId = "codex",
            status = UsageStatus.OK,
            sourceKind = UsageSourceKind.LOCAL_TELEMETRY,
            planLabel = "Pro",
            observedAt = now.minusSeconds(120),
            windows = listOf(
                UsageWindow(
                    id = "primary",
                    label = "5-hour window",
                    kind = UsageWindowKind.ROLLING,
                    usedPercent = 64.5,
                    windowMinutes = 300,
                    resetsAt = now.plusSeconds(3600),
                ),
            ),
            localSummary = LocalUsageSummary(
                last5Hours = LocalUsageBucket(sessions = 2, prompts = 9, totalTokens = 120_000),
                last24Hours = LocalUsageBucket(sessions = 4, prompts = 20, totalTokens = 320_000),
                last7Days = LocalUsageBucket(sessions = 10, prompts = 50, totalTokens = 900_000),
                last30Days = LocalUsageBucket(sessions = 20, prompts = 100, totalTokens = 2_000_000),
                lastActivity = now.minusSeconds(120),
                models = listOf("gpt-5.4"),
            ),
            statusNote = "Observed in the latest Codex session.",
            billingUrl = "https://chatgpt.com/settings/usage?tab=overview",
        )
        val claude = AgentUsageSnapshot(
            agentId = "claude",
            status = UsageStatus.LOCAL_ONLY,
            sourceKind = UsageSourceKind.LOCAL_SESSIONS,
            billingUrl = "https://claude.ai/settings/usage",
        )

        service.record(UsageDiscoveryResult(listOf(codex, claude), refreshedAt = now))

        // Re-open with a fresh store + service to verify real XML serialization/deserialization.
        val reloadedStore = SharedXmlStore(home, "cache/host/usage.xml", UsageCacheState::class.java, ::UsageCacheState)
        val reloaded = UsageCacheService(reloadedStore)

        assertEquals(now, reloaded.lastRefreshedAt())
        val onlyCodex = reloaded.cachedSnapshots { it == "codex" }
        assertEquals(1, onlyCodex.size)
        assertEquals(codex, onlyCodex.single())
    }

    @Test
    fun shouldSanitizeSensitiveOrMalformedValuesBeforePersisting() {
        val home = AgentHubHome(root = tempDir.resolve("agenthub-home"))
        val store = SharedXmlStore(home, "cache/host/usage.xml", UsageCacheState::class.java, ::UsageCacheState)
        val service = UsageCacheService(store)
        val now = Instant.parse("2026-11-19T12:00:00Z")

        val unsafeSnapshot = AgentUsageSnapshot(
            agentId = "codex",
            status = UsageStatus.OK,
            sourceKind = UsageSourceKind.LOCAL_TELEMETRY,
            planLabel = "sk-secret-token=12345;drop",
            windows = listOf(
                UsageWindow(
                    id = "primary",
                    label = "5-hour window",
                    kind = UsageWindowKind.ROLLING,
                    usedPercent = 250.0,
                    usedAmount = -10.0,
                ),
            ),
            localSummary = LocalUsageSummary(
                last5Hours = LocalUsageBucket(sessions = -3, prompts = -9, totalTokens = -100),
                models = listOf("gpt-5.4", "bad\nsecret=token"),
            ),
            billingUrl = "https://user:supersecret@example.com/usage",
        )

        service.record(UsageDiscoveryResult(listOf(unsafeSnapshot), refreshedAt = now))

        val xmlText = Files.readString(tempDir.resolve("agenthub-home/cache/host/usage.xml"))
        assertFalse(xmlText.contains("supersecret"))
        assertFalse(xmlText.contains("sk-secret-token"))

        val decoded = service.cachedSnapshots().single()
        assertNull(decoded.planLabel)
        assertEquals(100.0, decoded.windows.single().usedPercent)
        assertNull(decoded.windows.single().usedAmount)
        assertEquals(0, decoded.localSummary?.last5Hours?.sessions)
        assertEquals(listOf("gpt-5.4"), decoded.localSummary?.models)
        // Credential-bearing URL was stripped; falls back to safe catalog URL on decode.
        assertEquals("https://chatgpt.com/settings/usage?tab=overview", decoded.billingUrl)
    }
}
