package com.shutterstar.agenthub.usage.cleanup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class UsageProbeSessionsTest {
    @TempDir
    lateinit var projects: Path

    private val caveat = """{"type":"user","isMeta":true,"message":{"role":"user","content":"<local-command-caveat>x</local-command-caveat>"}}"""
    private val command = """{"type":"user","message":{"role":"user","content":"<command-name>/usage</command-name>"}}"""
    private val stdout = """{"type":"system","subtype":"local_command","content":"<local-command-stdout>Current session: 3% used</local-command-stdout>"}"""
    private val prompt = """{"type":"user","message":{"role":"user","content":"Fix the bug"}}"""
    private val reply = """{"type":"assistant","message":{"role":"assistant","content":"ok"}}"""

    private fun session(directory: String, name: String, vararg lines: String): Path {
        val file = Files.createDirectories(projects.resolve(directory)).resolve(name)
        Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
        return file
    }

    @Test
    fun `finds only sessions holding nothing but the usage command`() {
        val probe = session("a", "probe.jsonl", caveat, command, stdout)
        session("a", "typed.jsonl", command, prompt, stdout)
        session("b", "answered.jsonl", command, reply)
        session("b", "other.jsonl", prompt, reply)
        session("b", "notes.txt", caveat, command)

        assertEquals(listOf(probe), UsageProbeSessions.find(projects))
    }

    @Test
    fun `a large transcript is never a probe`() {
        val file = session("a", "big.jsonl", caveat, command)
        Files.writeString(file, "x".repeat(40 * 1024), java.nio.file.StandardOpenOption.APPEND)

        assertFalse(UsageProbeSessions.isProbe(file))
    }

    @Test
    fun `delete removes probes and leaves everything else`() {
        val probe = session("a", "probe.jsonl", caveat, command)
        val real = session("a", "real.jsonl", command, prompt)

        assertEquals(1, UsageProbeSessions.delete(listOf(probe, real)))

        assertFalse(Files.exists(probe))
        assertTrue(Files.exists(real))
    }

    @Test
    fun `a missing store yields nothing`() {
        assertTrue(UsageProbeSessions.find(projects.resolve("missing")).isEmpty())
    }
}
