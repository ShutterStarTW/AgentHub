package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class KiroProjectProviderTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `should discover V3 metadata alongside legacy sessions and count V3 user messages`() {
        val project = tempDirectory.resolve("work/v3-project")
        writeSession("legacy.json", """{"session_id":"legacy","cwd":${json(project.toString())}}""")
        val metadata = writeV3Session("sess_v3", """
            {"schemaVersion":"1.0.0","dataModelVersion":1,"id":"sess_v3","title":"V3 fixture",
             "workspacePaths":[${json(project.toString())}],"rootPaths":["/ignored-root"],
             "createdAt":"2026-11-01T09:00:00Z","lastModifiedAt":"2026-11-01T11:00:00Z",
             "_meta":{"kiro":{"executionTarget":{"kind":"local"},"secret":"not-for-index"}}}
        """.trimIndent())
        Files.writeString(metadata.resolveSibling("messages.jsonl"), """
            {"role":"user","content":"First V3 prompt"}
            {"role":"assistant","content":"Assistant fixture"}
            {"role":"user","content":[{"type":"tool_result","text":"Tool output"}]}
            {"role":"user","content":[{"type":"text","text":"Second V3 prompt"}]}
        """.trimIndent())

        val sessions = KiroProjectProvider(kiroDirectory()).discover().associateBy { it.sessionId }
        val found = sessions.getValue("sess_v3")

        assertEquals(setOf("legacy", "sess_v3"), sessions.keys)
        assertEquals(project.toString(), found.rawProjectPath)
        assertEquals("V3 fixture", found.metadata["title"])
        assertEquals("2", found.metadata[UserMessageTally.MESSAGE_COUNT_KEY])
        assertEquals("First V3 prompt", found.metadata[UserMessageTally.FIRST_MESSAGE_KEY])
        assertEquals(Instant.parse("2026-11-01T09:00:00Z"), found.startedAt)
        assertEquals(Instant.parse("2026-11-01T11:00:00Z"), found.updatedAt)
        assertEquals(metadata.toAbsolutePath().normalize().toString(), found.sourcePath)
        assertEquals(false, found.toString().contains("not-for-index"))
        assertNull(found.statistics["totalTokens"])
    }

    @Test
    fun `should keep V3 sessions without a workspace and use root paths as a fallback`() {
        val fallback = tempDirectory.resolve("work/fallback").toString()
        writeV3Session("sess_fallback", """{"schemaVersion":"1.0.0","dataModelVersion":1,"workspacePaths":[],"rootPaths":[${json(fallback)}]}""")
        writeV3Session("sess_unresolved", """{"schemaVersion":"1.0.0","dataModelVersion":1,"workspacePaths":[],"rootPaths":[]}""")

        val sessions = KiroProjectProvider(kiroDirectory()).discover().associateBy { it.sessionId }

        assertEquals(fallback, sessions.getValue("sess_fallback").rawProjectPath)
        assertNull(sessions.getValue("sess_unresolved").rawProjectPath)
        assertNull(sessions.getValue("sess_unresolved").metadata[UserMessageTally.MESSAGE_COUNT_KEY])
    }

    @Test
    fun `should skip unknown V3 schemas remote targets and nested subagent directories`() {
        val path = json(tempDirectory.toString())
        val valid = """{"schemaVersion":"1.0.0","dataModelVersion":1,"workspacePaths":[$path]}"""
        writeV3Session("sess_valid", valid)
        writeV3Session("sess_future", valid.replace("1.0.0", "2.0.0"))
        writeV3Session("sess_future_model", valid.replace("\"dataModelVersion\":1", "\"dataModelVersion\":2"))
        writeV3Session("sess_remote", valid.dropLast(1) + """, "_meta":{"kiro":{"executionTarget":{"kind":"ssh"}}}}""")
        writeV3Session("sess_broken", "{unfinished")
        val nested = Files.createDirectories(kiroDirectory().resolve("sessions/workspace/sess_valid/subagents/sess_child"))
        Files.writeString(nested.resolve("session.json"), valid)

        assertEquals(listOf("sess_valid"), KiroProjectProvider(kiroDirectory()).discover().map { it.sessionId })
    }

    @Test
    fun `should share one scan budget between legacy and V3 stores`() {
        writeSession("legacy.json", """{"session_id":"legacy","cwd":${json(tempDirectory.toString())}}""")
        writeV3Session("sess_v3", """{"schemaVersion":"1.0.0","dataModelVersion":1,"workspacePaths":[]}""")

        assertEquals(listOf("legacy"), KiroProjectProvider(kiroDirectory(), maxScanEntries = 3).discover().map { it.sessionId })
        assertEquals(emptyList<Any>(), KiroProjectProvider(kiroDirectory(), maxScanEntries = 0).discover())
    }

    private fun writeV3Session(id: String, contents: String): Path {
        val directory = Files.createDirectories(kiroDirectory().resolve("sessions/workspace/$id"))
        return Files.writeString(directory.resolve("session.json"), contents).also {
            Files.setLastModifiedTime(it, FileTime.from(Instant.parse("2026-11-01T10:00:00Z")))
        }
    }

    @Test
    fun `should show reported context utilization without estimating token usage or active time`() {
        writeSession("context.json", """{"session_id":"context","cwd":${json(tempDirectory.toString())},"created_at":"2026-08-25T09:00:00Z","updated_at":"2026-08-25T11:00:00Z","session_state":{"rts_model_state":{"model_info":{"context_window_tokens":200000},"context_usage_percentage":7.2627997}}}""")
        val statistics = KiroProjectProvider(kiroDirectory()).discover().single().statistics
        assertEquals("200000", statistics["contextWindow"])
        assertEquals("726", statistics["contextUsageBasisPoints"])
        assertEquals("7200000", statistics["elapsedMillis"])
        assertNull(statistics["totalTokens"])
        assertNull(statistics["activeMillis"])
    }

    @Test
    fun `discovers Kiro metadata without reading transcript files`() {
        val project = tempDirectory.resolve("work/kiro-project")
        val metadata = writeSession(
            "kiro-one.json",
            """{
                "session_id":"kiro-one",
                "cwd":${json(project.toString())},
                "created_at":"2026-08-25T09:00:00Z",
                "updated_at":"2026-08-25T11:00:00Z",
                "title":"Kiro title",
                "session_state":{"secret":"ignored"}
            }""".trimIndent(),
            Instant.parse("2026-08-25T10:00:00Z"),
        )
        Files.writeString(sessionsDirectory().resolve("kiro-one.jsonl"), "sensitive transcript fixture")

        val session = KiroProjectProvider(kiroDirectory()).discover().single()

        assertEquals("kiro", session.agentId)
        assertEquals("kiro-one", session.sessionId)
        assertEquals(project.toString(), session.rawProjectPath)
        assertEquals(Instant.parse("2026-08-25T09:00:00Z"), session.startedAt)
        assertEquals(Instant.parse("2026-08-25T11:00:00Z"), session.updatedAt)
        assertEquals("Kiro title", session.metadata["title"])
        assertEquals(metadata.toAbsolutePath().normalize().toString(), session.sourcePath)
    }

    @Test
    fun `malformed missing and future fields do not abort discovery`() {
        val project = tempDirectory.resolve("work/valid")
        writeSession("broken.json", "{unfinished")
        writeSession("missing-cwd.json", """{"session_id":"missing"}""")
        writeSession(
            "fallback-id.json",
            """{"cwd":${json(project.toString())},"future":{"cwd":"wrong"},"title":"Valid"}""",
        )

        val session = KiroProjectProvider(kiroDirectory()).discover().single()

        assertEquals("fallback-id", session.sessionId)
        assertEquals(project.toString(), session.rawProjectPath)
        assertEquals("Valid", session.metadata["title"])
    }

    @Test
    fun `numeric timestamps are supported and invalid values fall back to file time`() {
        val firstProject = tempDirectory.resolve("work/numeric")
        val secondProject = tempDirectory.resolve("work/invalid")
        writeSession(
            "numeric.json",
            """{"session_id":"numeric","cwd":${json(firstProject.toString())},"created_at":1787652000000,"updated_at":1787655600000}""",
            Instant.parse("2026-08-25T09:00:00Z"),
        )
        val fallbackTime = Instant.parse("2026-08-26T13:00:00Z")
        writeSession(
            "invalid.json",
            """{"session_id":"invalid","cwd":${json(secondProject.toString())},"created_at":"bad","updated_at":"bad"}""",
            fallbackTime,
        )

        val sessions = KiroProjectProvider(kiroDirectory()).discover().associateBy { it.sessionId }

        assertEquals(Instant.ofEpochMilli(1787652000000), sessions.getValue("numeric").startedAt)
        assertEquals(Instant.ofEpochMilli(1787655600000), sessions.getValue("numeric").updatedAt)
        assertNull(sessions.getValue("invalid").startedAt)
        assertEquals(fallbackTime, sessions.getValue("invalid").updatedAt)
    }

    private fun kiroDirectory(): Path = tempDirectory.resolve(".kiro")

    private fun sessionsDirectory(): Path = kiroDirectory().resolve("sessions/cli")

    private fun writeSession(fileName: String, contents: String, modifiedAt: Instant = Instant.parse("2026-08-25T10:00:00Z")): Path {
        Files.createDirectories(sessionsDirectory())
        val file = sessionsDirectory().resolve(fileName)
        Files.writeString(file, contents)
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt))
        return file
    }
}
