package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.createSqliteDatabase
import com.shutterstar.agenthub.environment.discovery.OpenClawWorkspaces
import com.shutterstar.agenthub.json
import com.shutterstar.agenthub.sql
import com.shutterstar.agenthub.sqliteAvailable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class OpenClawProjectProviderTest {
    @TempDir
    lateinit var tempDirectory: Path

    private fun state(): Path = tempDirectory.resolve("openclaw-state")

    private fun config(): Path = state().resolve("openclaw.json")

    private fun touchAgentDatabase(agentId: String): Path {
        val directory = Files.createDirectories(state().resolve("agents/$agentId/agent"))
        return Files.createFile(directory.resolve("openclaw-agent.sqlite"))
    }

    private fun hex(value: String) = value.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    private fun provider(reader: (Path) -> List<OpenClawSessionRecord>) =
        OpenClawProjectProvider(state(), config(), workspaceOverride = null, databaseReader = reader)

    @Test
    fun `should be unavailable without an agent database and empty when none exists`() {
        val provider = provider { error("must not be read") }

        assertFalse(provider.isAvailable())
        assertTrue(provider.discover().isEmpty())
    }

    @Test
    fun `should list the session keys of an agent in its configured workspace`() {
        val database = touchAgentDatabase("main")
        val workspace = tempDirectory.resolve("claw-workspace").toString()
        Files.writeString(config(), """{"agents":{"defaults":{"workspace":${json(workspace)}},"entries":{"main":{"name":"Main"}}}}""")
        val startedAt = Instant.parse("2026-11-17T10:00:00Z")
        val updatedAt = Instant.parse("2026-11-17T11:00:00Z")
        val provider = provider { selected ->
            assertEquals(database, selected)
            listOf(
                OpenClawSessionRecord("agent:main:main", null, startedAt, updatedAt, userMessageCount = 4, firstMessage = "Hello claw"),
                OpenClawSessionRecord("agent:main:bugfix", "Bugfix", startedAt, updatedAt, archivedAt = updatedAt),
            )
        }

        val sessions = provider.discover().sortedBy { it.sessionId }

        assertEquals(listOf("agent:main:bugfix", "agent:main:main"), sessions.map { it.sessionId })
        assertEquals(setOf("openclaw"), sessions.map { it.agentId }.toSet())
        assertEquals(setOf(workspace), sessions.map { it.rawProjectPath }.toSet())
        assertEquals("Bugfix", sessions[0].metadata["title"])
        assertEquals(updatedAt.toString(), sessions[0].metadata["archivedAt"])
        assertEquals("4", sessions[1].metadata[UserMessageTally.MESSAGE_COUNT_KEY])
        assertEquals("Hello claw", sessions[1].metadata[UserMessageTally.FIRST_MESSAGE_KEY])
        assertEquals(database.toAbsolutePath().normalize().toString(), sessions[1].sourcePath)
    }

    @Test
    fun `should fail only when no database can be read`() {
        touchAgentDatabase("main")
        val broken = touchAgentDatabase("ops")
        val record = OpenClawSessionRecord("agent:main:main", null, Instant.parse("2026-11-17T10:00:00Z"), null)

        val partial = provider { if (it != broken) listOf(record) else throw IOException("locked") }
        assertEquals(listOf("agent:main:main"), partial.discover().map { it.sessionId })

        val failing = provider { throw IOException("locked") }
        assertThrows(IOException::class.java) { failing.discover() }
    }

    @Test
    fun `workspace follows the documented resolution order`() {
        val stateDirectory = state()
        val explicit = tempDirectory.resolve("explicit").toString()
        val base = tempDirectory.resolve("base").toString()
        val roster = """{"agents":{"defaults":{"workspace":${json(base)}},"entries":{"main":{},"ops":{"workspace":${json(explicit)}},"docs":{}}}}"""

        val multi = OpenClawWorkspaces(roster, stateDirectory, null)
        assertEquals(explicit, multi.forAgent("ops"))
        assertEquals("$base/docs", multi.forAgent("docs"))
        assertEquals(base, multi.forAgent("main"))

        val noBase = OpenClawWorkspaces("""{"agents":{"entries":{"main":{},"docs":{}}}}""", stateDirectory, null)
        assertEquals(stateDirectory.resolve("workspace-docs").toString(), noBase.forAgent("docs"))
        assertEquals(stateDirectory.resolve("workspace").toString(), noBase.forAgent("main"))

        val sole = OpenClawWorkspaces("""{"agents":{"entries":{"helper":{}}}}""", stateDirectory, "/override")
        assertEquals("/override", sole.forAgent("helper"))

        assertEquals(stateDirectory.resolve("workspace").toString(), OpenClawWorkspaces(null, stateDirectory, null).forAgent("main"))
        assertEquals(stateDirectory.resolve("workspace").toString(), OpenClawWorkspaces("{not json", stateDirectory, null).forAgent("main"))
    }

    @Test
    fun `reader uses readonly arguments and parses hex encoded rows`() {
        val commands = mutableListOf<List<String>>()
        val sessions = listOf(
            listOf(hex("agent:main:main"), hex("Main\tchat"), "1794918639503", "1794918778244", "").joinToString("\t"),
            "malformed-row",
            listOf(hex("agent:main:old"), "", "invalid", "invalid", "1794918778244").joinToString("\t"),
        ).joinToString("\n")
        val stats = listOf(listOf(hex("agent:main:main"), "2", hex("Hello")).joinToString("\t")).joinToString("\n")
        val reader = OpenClawSqliteReader(commandRunner = { command, _ ->
            commands += command
            if ("FROM session_nodes WHERE" in command.last() && "transcript_events" !in command.last()) sessions else stats
        })

        val records = reader.readSessions(tempDirectory.resolve("openclaw-agent.sqlite"))

        assertEquals(2, records.size)
        assertEquals("agent:main:main", records[0].sessionKey)
        assertEquals("Main\tchat", records[0].title)
        assertEquals(Instant.ofEpochMilli(1794918639503), records[0].startedAt)
        assertNull(records[0].archivedAt)
        assertEquals(2, records[0].userMessageCount)
        assertEquals("Hello", records[0].firstMessage)
        assertEquals(Instant.ofEpochMilli(1794918778244), records[1].archivedAt)
        assertEquals(0, records[1].userMessageCount)
        assertTrue(commands.all { "-readonly" in it })
    }

    @Test
    fun `should read conversations and typed prompts from an isolated SQLite database`() {
        assumeTrue(sqliteAvailable())
        val database = createSqliteDatabase(
            tempDirectory.resolve("db"), "openclaw-agent.sqlite",
            """
            CREATE TABLE session_nodes (session_key TEXT PRIMARY KEY, current_session_id TEXT, display_name TEXT, label TEXT,
                session_started_at INTEGER, created_at INTEGER, last_activity_at INTEGER, last_interaction_at INTEGER,
                updated_at INTEGER, archived_at INTEGER);
            CREATE TABLE transcript_events (session_id TEXT, seq INTEGER, event_json TEXT, message_role TEXT);
            INSERT INTO session_nodes VALUES ('agent:main:main', 'sid-main', NULL, NULL, 1000, 900, 5000, 4000, 3000, NULL);
            INSERT INTO session_nodes VALUES ('agent:main:bugfix', 'sid-bug', '', 'Bugfix', NULL, 1100, NULL, NULL, 2000, 2500);
            INSERT INTO session_nodes VALUES ('cron:job-1', 'sid-cron', NULL, NULL, 1, 1, 1, 1, 1, NULL);
            INSERT INTO transcript_events VALUES ('sid-main', 1, ${sql("""{"type":"message","message":{"role":"user","content":"Hello claw","provenance":{"kind":"external_user"}}}""")}, 'user');
            INSERT INTO transcript_events VALUES ('sid-main', 2, ${sql("""{"type":"message","message":{"role":"user","content":"injected","provenance":{"kind":"inter_session"}}}""")}, 'user');
            INSERT INTO transcript_events VALUES ('sid-main', 3, ${sql("""{"type":"message","message":{"role":"user","content":[{"type":"text","text":"Block prompt"}]}}""")}, 'user');
            INSERT INTO transcript_events VALUES ('sid-main', 4, NULL, 'user');
            INSERT INTO transcript_events VALUES ('sid-main', 5, ${sql("""{"type":"message","message":{"role":"assistant","content":"Hi"}}""")}, 'assistant');
            INSERT INTO transcript_events VALUES ('sid-bug', 1, 'not json', 'user');
            INSERT INTO transcript_events VALUES ('sid-old', 1, ${sql("""{"type":"message","message":{"role":"user","content":"previous window"}}""")}, 'user');
            """,
        )

        val records = OpenClawSqliteReader().readSessions(database).associateBy { it.sessionKey }

        assertEquals(setOf("agent:main:main", "agent:main:bugfix"), records.keys)
        val main = records.getValue("agent:main:main")
        assertNull(main.title)
        assertEquals(Instant.ofEpochMilli(1000), main.startedAt)
        assertEquals(Instant.ofEpochMilli(5000), main.updatedAt)
        assertEquals(3, main.userMessageCount)
        assertEquals("Hello claw", main.firstMessage)
        val bug = records.getValue("agent:main:bugfix")
        assertEquals("Bugfix", bug.title)
        assertEquals(Instant.ofEpochMilli(1100), bug.startedAt)
        assertEquals(Instant.ofEpochMilli(2000), bug.updatedAt)
        assertEquals(Instant.ofEpochMilli(2500), bug.archivedAt)
        assertEquals(1, bug.userMessageCount)
        assertNull(bug.firstMessage)
    }
}
