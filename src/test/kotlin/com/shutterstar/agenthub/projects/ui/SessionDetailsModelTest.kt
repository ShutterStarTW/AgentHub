package com.shutterstar.agenthub.projects.ui

import com.shutterstar.agenthub.projects.model.AgentSession
import com.shutterstar.agenthub.projects.model.SessionStatistics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionDetailsModelTest {
    @Test
    fun `should hide all zero statistics and empty metadata in details and summary`() {
        val zeros = SessionStatistics.labels.keys.filter { it != "models" }.associateWith { "0" }
        val session = AgentSession("s", "claude", " ", null, null, "", title = "", messageCount = 0,
            statistics = zeros + ("models" to " "))
        assertEquals(listOf("Session: s"), SessionDetailsModel.lines(session) { it.toString() })
        assertNull(SessionDetailsModel.summary(session))
        assertEquals(zeros["totalTokens"], session.statistics["totalTokens"], "display filtering preserves measured zeros in the index")
    }

    @Test
    fun `should retain positive statistics while hiding zero values beside them`() {
        val session = AgentSession("s", "cline", null, null, null, null, messageCount = 2,
            statistics = mapOf("models" to "gpt-test", "totalTokens" to "123", "toolErrors" to "0",
                "reportedCostTicks" to "0", "contextUsageBasisPoints" to "726", "activeMillis" to "0"))
        val lines = SessionDetailsModel.lines(session) { it.toString() }
        assertTrue("Prompts: 2" in lines)
        assertTrue("Total tokens: 123" in lines)
        assertTrue("Context utilization: 7.26%" in lines)
        assertFalse(lines.any { it.startsWith("Failed tool calls:") || it.startsWith("Recorded cost") || it.startsWith("Active time") })
        assertEquals("gpt-test · 123 tokens", SessionDetailsModel.summary(session))
    }

    @Test
    fun `should show native measurements and distinguish Codex cached input`() {
        val session = AgentSession("s", "codex", "/project", null, null, "/rollout", title = "A title", messageCount = 4,
            statistics = mapOf("models" to "gpt-test", "inputTokens" to "120", "activeMillis" to "125000"))
        val lines = SessionDetailsModel.lines(session) { it.toString() }
        assertTrue("Input tokens (includes cache): 120" in lines)
        assertTrue("Prompts: 4" in lines)
        assertTrue(lines.any { it.endsWith("2m 5s") })
        assertFalse(lines.any { it.contains("Cost") || it.contains("Total tokens") })
        assertEquals("gpt-test · 2m 5s active", SessionDetailsModel.summary(session))
    }

    @Test
    fun `should only retain allowlisted numeric metrics and safe model identifiers`() {
        assertEquals(mapOf("totalTokens" to "123", "models" to "claude-test, gpt-test"), SessionStatistics.sanitize(mapOf(
            "totalTokens" to "123", "models" to "claude-test, gpt-test", "api_key" to "secret",
            "toolCalls" to "-2", "inputTokens" to "secret", "elapsedMillis" to "NaN",
        )))
    }

    @Test
    fun `should mark Codex sessions listed from the state database`() {
        val fallback = AgentSession("s", "codex", "/project", null, null, "/home/.codex/state_5.sqlite")
        val rollout = AgentSession("s", "codex", "/project", null, null, "/home/.codex/sessions/rollout.jsonl")
        val otherAgent = AgentSession("s", "opencode", "/project", null, null, "/data/opencode.sqlite")

        assertTrue(fallback.fromFallbackSource)
        assertFalse(rollout.fromFallbackSource)
        assertFalse(otherAgent.fromFallbackSource)
        assertTrue(SessionDetailsModel.lines(fallback) { it.toString() }.contains("Database: /home/.codex/state_5.sqlite (rollout file missing)"))
        assertTrue(SessionDetailsModel.lines(rollout) { it.toString() }.any { it.startsWith("Transcript:") })
        assertTrue(otherAgent.sourceIsDatabase)
        assertFalse(rollout.sourceIsDatabase)
        assertTrue(SessionDetailsModel.lines(otherAgent) { it.toString() }.contains("Database: /data/opencode.sqlite"))
    }

    @Test
    fun `should mark Claude sessions listed from the prompt history and block their resume`() {
        val history = AgentSession("s", "claude", "/project", null, null, "C:\\Users\\me\\.claude\\history.jsonl")
        val transcript = AgentSession("s", "claude", "/project", null, null, "C:\\Users\\me\\.claude\\projects\\p\\s.jsonl")

        assertTrue(history.fromPromptHistory)
        assertFalse(transcript.fromPromptHistory)
        assertTrue(SessionDetailsModel.lines(history) { it.toString() }.any { it.startsWith("History: ") && it.endsWith("(transcript file missing)") })
        assertTrue(SessionDetailsModel.lines(transcript) { it.toString() }.any { it.startsWith("Transcript: ") })
    }
}
