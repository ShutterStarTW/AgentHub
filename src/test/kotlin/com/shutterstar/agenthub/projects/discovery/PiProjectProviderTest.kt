package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PiProjectProviderTest {
    @TempDir
    lateinit var home: Path

    private fun sessionsDirectory(): Path = home.resolve("agent/sessions")

    private fun provider() = PiProjectProvider(sessionsDirectory())

    private fun header(id: String, cwd: String) =
        """{"type":"session","version":3,"id":${json(id)},"timestamp":"2026-11-17T12:15:37.691Z","cwd":${json(cwd)}}"""

    private fun message(role: String, text: String, asBlocks: Boolean = true): String {
        val content = if (asBlocks) """[{"type":"text","text":${json(text)}}]""" else json(text)
        return """{"type":"message","id":"m","parentId":"p","timestamp":"2026-11-17T12:15:38.000Z","message":{"role":${json(role)},"content":$content,"timestamp":1}}"""
    }

    private fun sessionInfo(name: String) =
        """{"type":"session_info","id":"i","parentId":"p","timestamp":"2026-11-17T12:16:00.000Z","name":${json(name)}}"""

    private fun write(bucket: String, name: String, vararg lines: String): Path {
        val directory = Files.createDirectories(sessionsDirectory().resolve(bucket))
        return Files.writeString(directory.resolve(name), lines.joinToString("\n", postfix = "\n"))
    }

    @Test
    fun `should read the working directory and typed prompts from a session`() {
        val project = home.resolve("work/app").toString()
        write(
            "--work-app--", "2026-11-17T12-15-37-691Z_abc.jsonl",
            header("abc-123", project),
            """{"type":"model_change","id":"a","parentId":null,"timestamp":"2026-11-17T12:15:37.700Z","provider":"p","modelId":"m"}""",
            """{"type":"message","id":"s","parentId":"a","timestamp":"2026-11-17T12:15:37.800Z","message":{"role":"system","content":"","sections":{"rules":"\"role\":\"user\""}}}""",
            message("user", "Fix the failing build"),
            message("assistant", "On it"),
            message("user", "Also add a test", asBlocks = false),
            message("custom", "extension injected prompt"),
            """{"type":"custom_message","id":"c","parentId":"p","timestamp":"2026-11-17T12:15:39.000Z","customType":"x","content":"injected \"role\":\"user\"","display":true}""",
        )

        val session = provider().discover().single()

        assertEquals("pi", session.agentId)
        assertEquals("abc-123", session.sessionId)
        assertEquals(project, session.rawProjectPath)
        assertEquals("2", session.metadata[UserMessageTally.MESSAGE_COUNT_KEY])
        assertEquals("Fix the failing build", session.metadata[UserMessageTally.FIRST_MESSAGE_KEY])
        assertNull(session.metadata["title"])
    }

    @Test
    fun `should use the latest session_info name as the title`() {
        val project = home.resolve("work/named").toString()
        write(
            "--work-named--", "a.jsonl",
            header("named-1", project), message("user", "Hello"), sessionInfo("First name"), message("user", "More"), sessionInfo("Refactor auth module"),
        )

        val session = provider().discover().single()

        assertEquals("Refactor auth module", session.metadata["title"])
        assertEquals("2", session.metadata[UserMessageTally.MESSAGE_COUNT_KEY])
    }

    @Test
    fun `should skip files without a valid header and survive malformed lines`() {
        val project = home.resolve("work/mixed").toString()
        write("--work-mixed--", "good.jsonl", header("good-1", project), "{not json", message("user", "Hi"))
        write("--work-mixed--", "no-header.jsonl", message("user", "No header"))
        write("--work-mixed--", "no-cwd.jsonl", """{"type":"session","id":"no-cwd-1","timestamp":"2026-11-17T12:00:00Z"}""")
        write("--work-mixed--", "notes.txt", "ignored")
        Files.createDirectories(sessionsDirectory().resolve("--work-mixed--/good"))
        Files.writeString(sessionsDirectory().resolve("--work-mixed--/good/child.jsonl"), header("child-1", project) + "\n")

        assertEquals(listOf("good-1"), provider().discover().map { it.sessionId })
    }

    @Test
    fun `should be unavailable without a sessions directory and empty when it has no sessions`() {
        assertFalse(provider().isAvailable())
        assertTrue(provider().discover().isEmpty())

        Files.createDirectories(sessionsDirectory())

        assertTrue(provider().isAvailable())
        assertTrue(provider().discover().isEmpty())
    }

    @Test
    fun `should keep a session without prompts with a zero count`() {
        write("--work-empty--", "e.jsonl", header("empty-1", home.resolve("work/empty").toString()))

        val session = provider().discover().single()

        assertEquals("0", session.metadata[UserMessageTally.MESSAGE_COUNT_KEY])
        assertNull(session.metadata[UserMessageTally.FIRST_MESSAGE_KEY])
    }
}
