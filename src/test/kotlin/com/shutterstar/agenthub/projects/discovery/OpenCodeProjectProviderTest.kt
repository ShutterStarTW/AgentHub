package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.json
import com.shutterstar.agenthub.projects.resolve.ProcessCommandRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

class OpenCodeProjectProviderTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `discovers sessions from read only database records`() {
        val database = createDatabase("opencode.db")
        val projectPath = tempDirectory.resolve("work/database-project")
        val createdAt = Instant.parse("2026-08-20T10:00:00Z")
        val updatedAt = Instant.parse("2026-08-20T12:00:00Z")
        val provider = OpenCodeProjectProvider(dataDirectory()) { selectedDatabase ->
            assertEquals(database, selectedDatabase)
            listOf(OpenCodeSessionRecord("ses_one", projectPath.toString(), "Database session", createdAt, updatedAt))
        }

        val session = provider.discover().single()

        assertEquals("opencode", session.agentId)
        assertEquals("ses_one", session.sessionId)
        assertEquals(projectPath.toString(), session.rawProjectPath)
        assertEquals(createdAt, session.startedAt)
        assertEquals(updatedAt, session.updatedAt)
        assertEquals("Database session", session.metadata["title"])
        assertEquals(database.toAbsolutePath().normalize().toString(), session.sourcePath)
    }

    @Test
    fun `SQLite reader uses readonly arguments and parses hex encoded metadata`() {
        val commands = mutableListOf<List<String>>()
        val projectPath = "K:\\Work\tProject"
        val output = listOf(
            listOf(
                hex("ses_valid"),
                hex(projectPath),
                hex("A title with\ttab"),
                "1787216400000",
                "1787220000000",
            ).joinToString("\t"),
            "malformed-row",
            listOf(hex("ses_invalid_time"), hex(projectPath), "", "invalid", "invalid").joinToString("\t"),
        ).joinToString("\n")
        val reader = OpenCodeSqliteReader { command, _ ->
            commands += command
            output
        }

        val sessions = reader.readSessions(tempDirectory.resolve("database with spaces.db"))

        assertEquals(2, sessions.size)
        assertEquals(projectPath, sessions.first().directory)
        assertEquals("A title with\ttab", sessions.first().title)
        assertEquals(Instant.ofEpochMilli(1787216400000), sessions.first().createdAt)
        assertEquals(null, sessions.last().updatedAt)
        assertEquals(3, commands.size, "session, user message and native usage queries")
        assertTrue(commands.all { "-readonly" in it })
        assertTrue(commands.all { command -> command.any { it.endsWith("database with spaces.db") } })
        assertTrue(commands.first().last().startsWith("SELECT hex(id)"))
        assertTrue(commands.first().last().contains("LIMIT 20000"))
    }

    @Test
    fun `should read V2 sessions and retain archive state without hiding archived runs`() {
        val database = createDatabase("opencode.db")
        val commands = mutableListOf<List<String>>()
        val archived = Instant.parse("2026-11-06T12:00:00Z")
        val reader = OpenCodeSqliteReader { command, _ ->
            commands += command
            when {
                "FROM session_v2" in command.last() -> listOf(
                    listOf(hex("ses_archived"), hex(tempDirectory.toString()), hex("Archived run"), "1", "2", archived.toEpochMilli().toString()).joinToString("\t"),
                    listOf(hex("ses_active"), hex(tempDirectory.toString()), hex("Active run"), "1", "2", "").joinToString("\t"),
                ).joinToString("\n")
                "COUNT(*)" in command.last() -> "${hex("ses_archived")}\t2\t${hex("First prompt")}"
                else -> ""
            }
        }

        val records = reader.readSessions(database)
        val sessions = OpenCodeProjectProvider(dataDirectory(), databaseReader = { records }).discover()

        assertEquals(2, sessions.size)
        assertEquals(archived, records.first().archivedAt)
        assertEquals(archived.toString(), sessions.first { it.sessionId == "ses_archived" }.metadata["archivedAt"])
        assertEquals(2, records.first().userMessageCount)
        assertEquals(0, records.last().userMessageCount)
        assertEquals("First prompt", records.first().firstMessage)
        assertTrue(commands.all { "-readonly" in it })
        assertFalse(commands.any { "FROM session " in it.last() })
    }

    @Test
    fun `should read V2 archive prompt and usage fields from an isolated SQLite database`() {
        assumeTrue(ProcessCommandRunner.run(listOf("sqlite3", "--version"), 5_000) != null)
        val database = tempDirectory.resolve("v2-fixture.db")
        val directory = tempDirectory.toString().replace("'", "''")
        val sql = """
            CREATE TABLE session_v2 (id TEXT, directory TEXT, title TEXT, time_created INTEGER, time_updated INTEGER, time_archived INTEGER);
            CREATE TABLE session_message (id TEXT, session_id TEXT, type TEXT, seq INTEGER, time_created INTEGER, data TEXT);
            INSERT INTO session_v2 VALUES ('ses_archived', '$directory', 'Archived fixture', 1000, 2000, 3000);
            INSERT INTO session_message VALUES ('u1', 'ses_archived', 'user', 1, 1000, '{"text":"First user prompt"}');
            INSERT INTO session_message VALUES ('s1', 'ses_archived', 'synthetic', 2, 1100, '{"text":"Injected context"}');
            INSERT INTO session_message VALUES ('u2', 'ses_archived', 'user', 3, 1200, '{"text":"Second user prompt"}');
            INSERT INTO session_message VALUES ('a1', 'ses_archived', 'assistant', 4, 1500,
                '{"model":{"modelID":"fixture-model"},"tokens":{"input":10,"output":5,"reasoning":2,"cache":{"read":3,"write":1}},"cost":0.25}');
        """.trimIndent()
        val script = tempDirectory.resolve("fixture.sql")
        Files.writeString(script, sql)
        val scriptPath = script.toString().replace('\\', '/').replace("'", "''")
        assertEquals("", ProcessCommandRunner.run(listOf("sqlite3", database.toString(), ".read '$scriptPath'"), 5_000)?.trim())

        val record = OpenCodeSqliteReader().readSessions(database).single()

        assertEquals(Instant.ofEpochMilli(3000), record.archivedAt)
        assertEquals(2, record.userMessageCount)
        assertEquals("First user prompt", record.firstMessage)
        assertEquals("10", record.statistics["inputTokens"])
        assertEquals("7", record.statistics["outputTokens"])
        assertEquals("1", record.statistics["modelCalls"])
        assertEquals("2500000000", record.statistics["reportedCostTicks"])
        assertFalse(record.statistics.toString().contains("Injected context"))
    }

    @Test
    fun `should fall back to old SQLite schema and leave forks on their verified schema`() {
        val database = createDatabase("old.db")
        for (agent in listOf("opencode", "kilo", "mimo")) {
            val commands = mutableListOf<String>()
            val reader = OpenCodeSqliteReader(agent) { command, _ ->
                commands += command.last()
                when {
                    "session_v2" in command.last() -> null
                    command.last().startsWith("SELECT hex(id)") -> listOf(hex("old"), hex(tempDirectory.toString()), hex("Old"), "1", "2").joinToString("\t")
                    else -> ""
                }
            }

            val session = reader.readSessions(database).single()

            assertEquals("old", session.id)
            assertEquals(null, session.archivedAt)
            assertEquals(agent == "opencode", commands.any { "session_v2" in it })
        }
    }

    @Test
    fun `legacy JSON storage supplies project timestamps and title`() {
        val projectPath = tempDirectory.resolve("work/legacy-project")
        val createdAt = Instant.parse("2026-08-21T09:00:00Z")
        val updatedAt = Instant.parse("2026-08-21T11:00:00Z")
        writeLegacySession(
            "project-key",
            "ses_legacy.json",
            legacyJson("ses_legacy", projectPath.toString(), "Legacy title", createdAt, updatedAt),
            modifiedAt = Instant.parse("2026-08-21T10:00:00Z"),
        )

        val session = OpenCodeProjectProvider(dataDirectory()).discover().single()

        assertEquals("ses_legacy", session.sessionId)
        assertEquals(projectPath.toString(), session.rawProjectPath)
        assertEquals(createdAt, session.startedAt)
        assertEquals(updatedAt, session.updatedAt)
        assertEquals("Legacy title", session.metadata["title"])
    }

    @Test
    fun `discovers sessions from the pre SQLite storage directory`() {
        val projectPath = tempDirectory.resolve("work/pre-sqlite-project")
        val directory = Files.createDirectories(dataDirectory().resolve("storage/session/project-key"))
        Files.writeString(
            directory.resolve("ses_pre_sqlite.json"),
            legacyJson("ses_pre_sqlite", projectPath.toString(), "Pre-SQLite", null, null),
        )

        val session = OpenCodeProjectProvider(dataDirectory()).discover().single()

        assertEquals("ses_pre_sqlite", session.sessionId)
        assertEquals(projectPath.toString(), session.rawProjectPath)
        assertEquals("Pre-SQLite", session.metadata["title"])
    }

    @Test
    fun `legacy storage counts user messages and uses first real text part as title`() {
        val projectPath = tempDirectory.resolve("work/legacy-messages")
        writeLegacySession(
            "project-key",
            "ses_messages.json",
            legacyJson("ses_messages", projectPath.toString(), "Generated title", null, null),
        )
        val messageDirectory = Files.createDirectories(dataDirectory().resolve("storage/message/ses_messages"))
        val partRoot = Files.createDirectories(dataDirectory().resolve("storage/part"))
        Files.writeString(messageDirectory.resolve("msg_001.json"), """{"id":"msg_001","sessionID":"ses_messages","role":"assistant"}""")
        Files.writeString(messageDirectory.resolve("msg_002.json"), """{"id":"msg_002","sessionID":"ses_messages","role":"user"}""")
        Files.writeString(messageDirectory.resolve("msg_003.json"), """{"id":"msg_003","sessionID":"ses_messages","role":"user"}""")
        Files.writeString(messageDirectory.resolve("msg_004.json"), """{"id":"msg_004","sessionID":"other","role":"user"}""")
        val firstParts = Files.createDirectories(partRoot.resolve("msg_002"))
        Files.writeString(firstParts.resolve("prt_001.json"), """{"messageID":"msg_002","type":"text","synthetic":true,"text":"injected"}""")
        Files.writeString(firstParts.resolve("prt_002.json"), """{"messageID":"msg_002","type":"text","text":"  fix the login bug  "}""")
        val secondParts = Files.createDirectories(partRoot.resolve("msg_003"))
        Files.writeString(secondParts.resolve("prt_001.json"), """{"messageID":"msg_003","type":"text","text":"and add a test"}""")

        val metadata = OpenCodeProjectProvider(dataDirectory()).discover().single().metadata

        assertEquals("2", metadata["messageCount"])
        assertEquals("fix the login bug", metadata["firstMessage"])
        assertEquals("Generated title", metadata["title"])
        assertFalse(metadata.values.any { "and add a test" in it })
    }

    @Test
    fun `malformed and missing legacy fields do not abort discovery`() {
        val projectPath = tempDirectory.resolve("work/valid")
        writeLegacySession("key", "broken.json", "{unfinished")
        writeLegacySession("key", "missing-directory.json", """{"id":"missing"}""")
        writeLegacySession(
            "key",
            "fallback-id.json",
            legacyJson(null, projectPath.toString(), null, null, null),
        )

        val sessions = OpenCodeProjectProvider(dataDirectory()).discover()

        assertEquals(1, sessions.size)
        assertEquals("fallback-id", sessions.single().sessionId)
        assertEquals(projectPath.toString(), sessions.single().rawProjectPath)
    }

    @Test
    fun `a large realistic legacy session store is walked on the real filesystem within budget`() {
        val projectCount = 60
        val sessionsPerProject = 50
        repeat(projectCount) { projectIndex ->
            repeat(sessionsPerProject) { sessionIndex ->
                writeLegacySession(
                    "project-$projectIndex",
                    "session-$sessionIndex.json",
                    legacyJson(
                        "p$projectIndex-s$sessionIndex",
                        tempDirectory.resolve("work/project-$projectIndex").toString(),
                        null,
                        null,
                        null,
                    ),
                )
            }
        }

        val sessions = assertTimeout(
            Duration.ofSeconds(45),
            ThrowingSupplier { OpenCodeProjectProvider(dataDirectory()).discover() },
        )

        assertEquals(projectCount * sessionsPerProject, sessions.size)
        assertEquals(projectCount, sessions.mapTo(mutableSetOf()) { it.rawProjectPath }.size)
    }

    @Test
    fun `legacy session scanning is bounded`() {
        val directory = Files.createDirectories(dataDirectory().resolve("project"))
        repeat(3) { index ->
            Files.writeString(
                directory.resolve("$index.json"),
                legacyJson(
                    "session-$index",
                    tempDirectory.resolve("work/$index").toString(),
                    null,
                    null,
                    null,
                ),
            )
        }

        val sessions = OpenCodeProjectProvider(dataDirectory(), maxLegacyScanEntries = 2).discover()

        assertEquals(2, sessions.size)
    }

    @Test
    fun `unknown nested legacy fields do not replace top level metadata`() {
        val projectPath = tempDirectory.resolve("work/real")
        val json = """{"future":{"directory":"wrong","id":"nested"},"id":"real",""" +
            """"directory":${json(projectPath.toString())},"title":"Real",""" +
            """"time":{"created":1787216400000,"updated":1787220000000,"future":{"updated":1}}}"""
        writeLegacySession("key", "unknown.json", json)

        val session = OpenCodeProjectProvider(dataDirectory()).discover().single()

        assertEquals("real", session.sessionId)
        assertEquals(projectPath.toString(), session.rawProjectPath)
        assertEquals("Real", session.metadata["title"])
    }

    @Test
    fun `duplicate records across databases and legacy storage keep newest activity`() {
        createDatabase("opencode.db")
        createDatabase("opencode-preview.db")
        val oldProject = tempDirectory.resolve("work/old")
        val newProject = tempDirectory.resolve("work/new")
        writeLegacySession(
            "key",
            "duplicate.json",
            legacyJson(
                "duplicate",
                oldProject.toString(),
                null,
                Instant.parse("2026-08-20T09:00:00Z"),
                Instant.parse("2026-08-20T10:00:00Z"),
            ),
            modifiedAt = Instant.parse("2026-08-20T10:00:00Z"),
        )
        val provider = OpenCodeProjectProvider(dataDirectory()) { database ->
            if (database.fileName.toString().contains("preview")) {
                listOf(
                    OpenCodeSessionRecord(
                        "duplicate",
                        newProject.toString(),
                        null,
                        Instant.parse("2026-08-22T09:00:00Z"),
                        Instant.parse("2026-08-22T10:00:00Z"),
                    ),
                )
            } else {
                emptyList()
            }
        }

        val session = provider.discover().single()

        assertEquals(newProject.toString(), session.rawProjectPath)
        assertEquals(Instant.parse("2026-08-22T10:00:00Z"), session.updatedAt)
    }

    @Test
    fun `database failure is surfaced for partial discovery warning`() {
        createDatabase("opencode.db")
        val provider = OpenCodeProjectProvider(dataDirectory()) { throw IOException("fixture failure") }

        assertThrows(IOException::class.java) { provider.discover() }
        val result = ProjectDiscoveryService(providers = listOf(provider)).discover()
        assertTrue(result.projects.isEmpty())
        assertEquals("opencode", result.warnings.single().agentId)
    }

    private fun dataDirectory(): Path = tempDirectory.resolve("opencode-data")

    private fun createDatabase(name: String): Path {
        Files.createDirectories(dataDirectory())
        return Files.createFile(dataDirectory().resolve(name))
    }

    private fun writeLegacySession(
        projectKey: String,
        fileName: String,
        contents: String,
        modifiedAt: Instant = Instant.parse("2026-08-20T10:00:00Z"),
    ): Path {
        val directory = Files.createDirectories(
            dataDirectory().resolve("project/$projectKey/storage/session/$projectKey"),
        )
        val file = directory.resolve(fileName)
        Files.writeString(file, contents)
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt))
        return file
    }

    private fun legacyJson(
        id: String?,
        directory: String,
        title: String?,
        createdAt: Instant?,
        updatedAt: Instant?,
    ): String {
        val idField = id?.let { "\"id\":${json(it)}," }.orEmpty()
        val titleField = title?.let { ",\"title\":${json(it)}" }.orEmpty()
        val created = createdAt?.toEpochMilli()?.toString() ?: "null"
        val updated = updatedAt?.toEpochMilli()?.toString() ?: "null"
        return "{$idField\"directory\":${json(directory)}$titleField," +
            "\"time\":{\"created\":$created,\"updated\":$updated},\"unknown\":true}"
    }

    private fun hex(value: String): String = value.toByteArray(Charsets.UTF_8)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}
