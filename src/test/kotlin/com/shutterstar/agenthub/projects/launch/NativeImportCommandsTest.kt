package com.shutterstar.agenthub.projects.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeImportCommandsTest {
    private val id = "0b1f7a2e-3c4d-4e5f-8a9b-0c1d2e3f4a5b"

    @Test
    fun `builds the import command for Claude and Codex sessions in Oh My Pi`() {
        assertEquals("omp --from-claude", NativeImportCommands.command("claude", "omp", id))
        assertEquals("omp --from-codex", NativeImportCommands.command("codex", "omp", id))
        assertEquals("omp --from-claude", NativeImportCommands.command("claude", "omp"))
    }

    @Test
    fun `fills the slash command of the OpenCode family with the exact source session id`() {
        listOf("kilo", "mimo", "opencode").forEach { target ->
            assertEquals("$target --prompt \"/resume-claude $id\"", NativeImportCommands.command("claude", target, id))
            assertEquals("$target --prompt \"/resume-codex $id\"", NativeImportCommands.command("codex", target, id))
        }
    }

    @Test
    fun `Kilo needs a safe session id`() {
        assertNull(NativeImportCommands.command("claude", "kilo"))
        assertNull(NativeImportCommands.command("claude", "kilo", " "))
        assertNull(NativeImportCommands.command("claude", "kilo", "x\" ; rm -rf /"))
        assertNull(NativeImportCommands.command("claude", "kilo", "-rf"))
    }

    @Test
    fun `has no import for unsupported pairs`() {
        assertNull(NativeImportCommands.command("cursor", "omp", id))
        assertNull(NativeImportCommands.command("claude", "codex", id))
        assertNull(NativeImportCommands.command("omp", "omp", id))
        assertNull(NativeImportCommands.command("kilo", "kilo", id))
    }

    @Test
    fun `lists the targets that can open a source agent session`() {
        assertEquals(listOf("kilo", "mimo", "omp", "opencode"), NativeImportCommands.targets("claude", id))
        assertEquals(listOf("kilo", "mimo", "omp", "opencode"), NativeImportCommands.targets("codex", id))
        assertEquals(listOf("omp"), NativeImportCommands.targets("claude"))
        assertTrue(NativeImportCommands.targets("omp", id).isEmpty())
        assertTrue(NativeImportCommands.targets("cursor", id).isEmpty())
    }

    @Test
    fun `commands contain no shell chaining or substitution`() {
        listOf("claude", "codex").forEach { source ->
            NativeImportCommands.targets(source, id).forEach { target ->
                val command = NativeImportCommands.command(source, target, id)!!
                assertTrue(listOf("&", ";", "|", "$", "`").none { it in command }, command)
            }
        }
    }
}
