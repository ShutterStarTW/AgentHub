package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.projects.model.AgentProject
import com.shutterstar.agenthub.projects.model.AgentSession
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class LocalSessionUsageAggregatorTest {
    private val now = Instant.parse("2026-11-19T12:00:00Z")

    @Test
    fun shouldAggregateSessionsIntoRollingWindowsAndDeduplicateIds() {
        val recent = session(
            id = "s-recent",
            agentId = "claude",
            updatedAt = now.minus(Duration.ofHours(2)),
            prompts = 4,
            stats = mapOf(
                "inputTokens" to "1000",
                "cachedTokens" to "200",
                "outputTokens" to "500",
                "totalTokens" to "1700",
                "reportedCostTicks" to "5000000000",
                "models" to "claude-opus-4-6, claude-sonnet-4-6",
            ),
        )
        val yesterday = session(
            id = "s-20h",
            agentId = "claude",
            updatedAt = now.minus(Duration.ofHours(20)),
            prompts = 2,
            stats = mapOf(
                "inputTokens" to "400",
                "outputTokens" to "100",
                "partialRecordedCostTicks" to "1000000000",
            ),
        )
        val fiveDaysAgo = session(
            id = "s-5d",
            agentId = "claude",
            updatedAt = now.minus(Duration.ofDays(5)),
            prompts = 6,
            stats = mapOf("totalTokens" to "3000"),
        )
        val twentyDaysAgo = session(
            id = "s-20d",
            agentId = "claude",
            updatedAt = now.minus(Duration.ofDays(20)),
            prompts = 1,
            stats = mapOf("totalTokens" to "900"),
        )
        val fortyDaysAgo = session(
            id = "s-40d",
            agentId = "claude",
            updatedAt = now.minus(Duration.ofDays(40)),
            prompts = 99,
            stats = mapOf("totalTokens" to "999999"),
        )

        val project1 = project("p1", "claude", listOf(recent, yesterday, fiveDaysAgo, twentyDaysAgo, fortyDaysAgo))
        // Duplicate session in another project entry must not be double-counted.
        val project2 = project("p2", "claude", listOf(recent))

        val result = LocalSessionUsageAggregator.aggregate(listOf(project1, project2), now)
        val summary = result.getValue("claude")

        assertTrue(summary.hasAnyActivity)
        assertEquals(now.minus(Duration.ofHours(2)), summary.lastActivity)
        assertEquals(listOf("claude-opus-4-6", "claude-sonnet-4-6"), summary.models)

        assertEquals(1, summary.last5Hours.sessions)
        assertEquals(4L, summary.last5Hours.prompts)
        assertEquals(1700L, summary.last5Hours.totalTokens)
        assertEquals(5_000_000_000L, summary.last5Hours.recordedCostTicks)

        assertEquals(2, summary.last24Hours.sessions)
        assertEquals(6L, summary.last24Hours.prompts)
        assertEquals(2200L, summary.last24Hours.totalTokens)
        assertEquals(6_000_000_000L, summary.last24Hours.recordedCostTicks)

        assertEquals(3, summary.last7Days.sessions)
        assertEquals(5200L, summary.last7Days.totalTokens)

        assertEquals(4, summary.last30Days.sessions)
        assertEquals(6100L, summary.last30Days.totalTokens)
    }

    @Test
    fun shouldTrackCopilotPremiumRequestsAndIgnoreOldSessions() {
        val copilotSession = session(
            id = "cp-1",
            agentId = "copilot",
            updatedAt = now.minus(Duration.ofHours(10)),
            prompts = 5,
            stats = mapOf(
                "premiumRequests" to "12",
                "nanoAiu" to "450000",
                "totalTokens" to "8000",
            ),
        )
        val oldCursorSession = session(
            id = "cur-1",
            agentId = "cursor",
            updatedAt = now.minus(Duration.ofDays(45)),
            prompts = 3,
            stats = emptyMap(),
        )

        val result = LocalSessionUsageAggregator.aggregate(
            listOf(
                project("p1", "copilot", listOf(copilotSession)),
                project("p2", "cursor", listOf(oldCursorSession)),
            ),
            now,
        )

        val copilot = result.getValue("copilot")
        assertEquals(12L, copilot.last24Hours.premiumRequests)
        assertEquals(450_000L, copilot.last24Hours.nanoAiu)

        val cursor = result.getValue("cursor")
        assertFalse(cursor.hasAnyActivity)
        assertEquals(now.minus(Duration.ofDays(45)), cursor.lastActivity)
    }

    private fun project(id: String, agentId: String, sessions: List<AgentSession>): DiscoveredProject =
        DiscoveredProject(
            identity = ProjectIdentity(id, "/work/$id", null, null),
            name = id,
            path = "/work/$id",
            gitRoot = null,
            gitRemote = null,
            currentBranch = null,
            agents = listOf(AgentProject(agentId, id, sessions.size, sessions.mapNotNull { it.updatedAt }.maxOrNull(), sessions)),
            lastActivity = sessions.mapNotNull { it.updatedAt }.maxOrNull(),
        )

    private fun session(
        id: String,
        agentId: String,
        updatedAt: Instant,
        prompts: Int,
        stats: Map<String, String>,
    ): AgentSession = AgentSession(
        id = id,
        agentId = agentId,
        projectPath = "/work/p",
        startedAt = updatedAt.minus(Duration.ofMinutes(15)),
        updatedAt = updatedAt,
        sourcePath = "/tmp/$id.jsonl",
        messageCount = prompts,
        statistics = stats,
    )
}
