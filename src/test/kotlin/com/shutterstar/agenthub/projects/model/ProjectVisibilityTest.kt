package com.shutterstar.agenthub.projects.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ProjectVisibilityTest {
    private val old = Instant.parse("2026-01-01T00:00:00Z")
    private val recent = Instant.parse("2026-09-01T00:00:00Z")

    @Test
    fun `hidden agents' relations are removed and projects left without agents disappear`() {
        val mixed = project("mixed", agent("claude", old), agent("codex", recent))
        val onlyHidden = project("only-hidden", agent("codex", recent))

        val visible = ProjectVisibility.filter(listOf(mixed, onlyHidden)) { it == "claude" }

        assertEquals(listOf("mixed"), visible.map { it.identity.id })
        assertEquals(listOf("claude"), visible.single().agents.map { it.agentId })
    }

    @Test
    fun `last activity is recomputed from the remaining relations`() {
        val mixed = project("mixed", agent("claude", old), agent("codex", recent))

        val visible = ProjectVisibility.filter(listOf(mixed)) { it == "claude" }.single()

        assertEquals(old, visible.lastActivity)
    }

    @Test
    fun `projects are re-sorted by the recomputed activity`() {
        val a = project("a", agent("claude", old), agent("codex", recent))
        val b = project("b", agent("claude", Instant.parse("2026-05-01T00:00:00Z")))

        val visible = ProjectVisibility.filter(listOf(a, b)) { it == "claude" }

        assertEquals(listOf("b", "a"), visible.map { it.identity.id })
    }

    @Test
    fun `an untouched list is returned as is, including projects that never had an agent relation`() {
        val bare = project("bare")
        val full = project("full", agent("claude", recent))

        val visible = ProjectVisibility.filter(listOf(full, bare)) { true }

        assertEquals(listOf("full", "bare"), visible.map { it.identity.id })
        assertSame(full, visible.first())
    }

    @Test
    fun `nothing is visible when no agent is`() {
        assertTrue(ProjectVisibility.filter(listOf(project("p", agent("claude", recent)))) { false }.isEmpty())
    }

    @Test
    fun `empty sessions are dropped with the agents and projects that only had them`() {
        val empty = AgentSession("e", "claude", "/p", null, old, null, messageCount = 0)
        val named = AgentSession("n", "claude", "/p", null, recent, null, messageCount = 0, title = "Fan app")
        val unknown = AgentSession("u", "claude", "/p", null, recent, null)
        val mixed = project("mixed", AgentProject("claude", "p", 3, recent, listOf(empty, named, unknown)))
        val onlyEmpty = project("only-empty", AgentProject("codex", "p", 1, old, listOf(empty)))
        val untouched = project("untouched", AgentProject("claude", "p", 1, recent, listOf(unknown)))

        val visible = ProjectVisibility.withoutEmptySessions(listOf(onlyEmpty, mixed, untouched))

        assertEquals(setOf("mixed", "untouched"), visible.map { it.identity.id }.toSet())
        val kept = visible.single { it.identity.id == "mixed" }.agents.single()
        assertEquals(listOf("n", "u"), kept.sessions.map { it.id })
        assertEquals(2, kept.sessionCount)
        assertSame(untouched, visible.single { it.identity.id == "untouched" })
    }

    @Test
    fun `a list without empty sessions is returned as is`() {
        val list = listOf(project("p", AgentProject("claude", "p", 1, recent, listOf(AgentSession("u", "claude", "/p", null, recent, null)))))

        assertSame(list, ProjectVisibility.withoutEmptySessions(list))
    }

    private fun agent(id: String, activity: Instant) = AgentProject(id, "p", 1, activity, emptyList())

    private fun project(id: String, vararg agents: AgentProject) = DiscoveredProject(
        identity = ProjectIdentity(id, "K:/Projects/$id", null, null),
        name = id,
        path = "K:/Projects/$id",
        gitRoot = null,
        gitRemote = null,
        currentBranch = null,
        agents = agents.toList(),
        lastActivity = agents.mapNotNull { it.lastActivity }.maxOrNull(),
    )
}
