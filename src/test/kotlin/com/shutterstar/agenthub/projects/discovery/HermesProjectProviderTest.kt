package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.createSqliteDatabase
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

class HermesProjectProviderTest {
    @TempDir
    lateinit var tempDirectory: Path

    private fun hermesHome(): Path = tempDirectory.resolve("hermes")

    private fun touchDatabase(directory: Path): Path {
        Files.createDirectories(directory)
        return Files.createFile(directory.resolve("state.db"))
    }

    private fun hex(value: String) = value.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    @Test
    fun `should be unavailable without a database and empty when none exists`() {
        val provider = HermesProjectProvider(hermesHome()) { error("must not be read") }

        assertFalse(provider.isAvailable())
        assertTrue(provider.discover().isEmpty())
    }

    @Test
    fun `should map the records of the root and profile databases`() {
        val root = touchDatabase(hermesHome())
        val profile = touchDatabase(hermesHome().resolve("profiles/work"))
        val project = tempDirectory.resolve("work/app").toString()
        val startedAt = Instant.parse("2026-11-17T10:00:00Z")
        val updatedAt = Instant.parse("2026-11-17T11:00:00Z")
        val provider = HermesProjectProvider(hermesHome()) { database ->
            when (database) {
                root -> listOf(HermesSessionRecord("20261117_100000_ab12cd", project, "Root session", startedAt, updatedAt, userMessageCount = 2, firstMessage = "Fix the build"))
                profile -> listOf(HermesSessionRecord("20261117_110000_ef34ab", project, null, startedAt, updatedAt, archived = true))
                else -> error("unexpected database $database")
            }
        }

        val sessions = provider.discover().sortedBy { it.sessionId }

        assertEquals(listOf("hermes", "hermes"), sessions.map { it.agentId })
        assertEquals(project, sessions[0].rawProjectPath)
        assertEquals("Root session", sessions[0].metadata["title"])
        assertEquals("2", sessions[0].metadata[UserMessageTally.MESSAGE_COUNT_KEY])
        assertEquals("Fix the build", sessions[0].metadata[UserMessageTally.FIRST_MESSAGE_KEY])
        assertEquals(startedAt, sessions[0].startedAt)
        assertEquals(updatedAt, sessions[0].updatedAt)
        assertEquals(root.toAbsolutePath().normalize().toString(), sessions[0].sourcePath)
        assertEquals(updatedAt.toString(), sessions[1].metadata["archivedAt"])
        assertNull(sessions[1].metadata[UserMessageTally.MESSAGE_COUNT_KEY])
    }

    @Test
    fun `should keep the sessions of a readable database when another one fails and fail when none can be read`() {
        val root = touchDatabase(hermesHome())
        touchDatabase(hermesHome().resolve("profiles/broken"))
        val record = HermesSessionRecord("one", "/work/app", null, Instant.parse("2026-11-17T10:00:00Z"), null)

        val partial = HermesProjectProvider(hermesHome()) { if (it == root) listOf(record) else throw IOException("locked") }
        assertEquals(listOf("one"), partial.discover().map { it.sessionId })

        val failing = HermesProjectProvider(hermesHome()) { throw IOException("locked") }
        assertThrows(IOException::class.java) { failing.discover() }
    }

    @Test
    fun `reader uses readonly arguments and parses hex encoded rows`() {
        val commands = mutableListOf<List<String>>()
        val project = "K:\\Work\tProject"
        val sessions = listOf(
            listOf(hex("20261117_100000_ab12cd"), hex(project), hex("Title\twith tab"), "1794918639503", "1794918778244", "1").joinToString("\t"),
            "malformed-row",
            listOf(hex("20261117_110000_ef34ab"), hex(project), "", "invalid", "invalid", "0").joinToString("\t"),
        ).joinToString("\n")
        val stats = listOf(
            listOf(hex("20261117_100000_ab12cd"), "3", hex("\u0000json:\"Structured prompt\"")).joinToString("\t"),
        ).joinToString("\n")
        val reader = HermesSqliteReader(commandRunner = { command, _ ->
            commands += command
            if ("FROM sessions" in command.last()) sessions else stats
        })

        val records = reader.readSessions(tempDirectory.resolve("state.db"))

        assertEquals(2, records.size)
        assertEquals(project, records[0].directory)
        assertEquals("Title\twith tab", records[0].title)
        assertEquals(Instant.ofEpochMilli(1794918639503), records[0].startedAt)
        assertTrue(records[0].archived)
        assertEquals(3, records[0].userMessageCount)
        assertEquals("Structured prompt", records[0].firstMessage)
        assertNull(records[1].startedAt)
        assertEquals(0, records[1].userMessageCount)
        assertTrue(commands.all { "-readonly" in it })
    }

    @Test
    fun `should read sessions and typed prompts from an isolated SQLite database`() {
        assumeTrue(sqliteAvailable())
        val project = tempDirectory.resolve("work/app").toString()
        val database = createSqliteDatabase(
            hermesHome(), "state.db",
            """
            CREATE TABLE sessions (id TEXT PRIMARY KEY, cwd TEXT, git_repo_root TEXT, title TEXT, started_at REAL NOT NULL,
                last_activity_at REAL, ended_at REAL, archived INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT,
                timestamp REAL NOT NULL, observed INTEGER DEFAULT 0, _compressed_summary INTEGER NOT NULL DEFAULT 0, display_kind TEXT);
            INSERT INTO sessions VALUES ('s_cwd', ${sql(project)}, NULL, 'Build fix', 1794918639.5, 1794918778.25, NULL, 0);
            INSERT INTO sessions VALUES ('s_repo', '', ${sql("$project-repo")}, NULL, 1794918000.0, NULL, 1794918100.0, 1);
            INSERT INTO sessions VALUES ('s_chat', NULL, NULL, 'Telegram chat', 1794918000.0, NULL, NULL, 0);
            INSERT INTO messages (session_id, role, content, timestamp) VALUES ('s_cwd', 'user', 'First prompt', 1);
            INSERT INTO messages (session_id, role, content, timestamp) VALUES ('s_cwd', 'assistant', 'Answer', 2);
            INSERT INTO messages (session_id, role, content, timestamp, display_kind) VALUES ('s_cwd', 'user', 'scaffolding', 3, 'hidden');
            INSERT INTO messages (session_id, role, content, timestamp, observed) VALUES ('s_cwd', 'user', 'group chat line', 4, 1);
            INSERT INTO messages (session_id, role, content, timestamp, _compressed_summary) VALUES ('s_cwd', 'user', 'summary', 5, 1);
            INSERT INTO messages (session_id, role, content, timestamp) VALUES ('s_cwd', 'user', 'Second prompt', 6);
            """,
        )

        val records = HermesSqliteReader().readSessions(database).associateBy { it.id }

        assertEquals(setOf("s_cwd", "s_repo"), records.keys)
        val withCwd = records.getValue("s_cwd")
        assertEquals(project, withCwd.directory)
        assertEquals("Build fix", withCwd.title)
        assertEquals(Instant.ofEpochMilli(1794918639500), withCwd.startedAt)
        assertEquals(Instant.ofEpochMilli(1794918778250), withCwd.updatedAt)
        assertEquals(2, withCwd.userMessageCount)
        assertEquals("First prompt", withCwd.firstMessage)
        val withRepo = records.getValue("s_repo")
        assertEquals("$project-repo", withRepo.directory)
        assertEquals(Instant.ofEpochMilli(1794918100000), withRepo.updatedAt)
        assertTrue(withRepo.archived)
        assertEquals(0, withRepo.userMessageCount)
    }
}
