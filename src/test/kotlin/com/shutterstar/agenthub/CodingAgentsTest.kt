package com.shutterstar.agenthub

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CodingAgentsTest {

    @Test
    fun `agent ids are unique lowercase and without whitespace`() {
        CodingAgents.all.forEach {
            assertEquals(it.id, it.id.lowercase().trim(), "id not lowercase/trimmed: ${it.id}")
            assertTrue(it.id.isNotBlank() && !it.id.contains(' '), "invalid id: '${it.id}'")
        }
        val ids = CodingAgents.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "duplicate agent id found")
    }

    @Test
    fun `every agent has a command install hint and website`() {
        CodingAgents.all.forEach {
            assertTrue(it.command.isNotBlank(), "blank command for: ${it.id}")
            assertTrue(it.installHint.isNotBlank(), "blank installHint for: ${it.id}")
            assertTrue(it.url.isNotBlank(), "blank URL for: ${it.id}")
        }
    }

    @Test
    fun `npm update hints reference a package name`() {
        CodingAgents.all
            .filter { "npm" in it.updateHint }
            .forEach {
                val parts = it.updateHint.trim().split(Regex("\\s+"))
                assertTrue(parts.size >= 2, "npm updateHint too short for: ${it.id}")
                assertTrue(parts.last().isNotBlank(), "blank package name in npm updateHint for: ${it.id}")
            }
    }

    @Test
    fun `opencode installs and updates the 2x package and checks its version`() {
        val opencode = CodingAgents.all.single { it.id == "opencode" }
        assertEquals("npm install -g @opencode/cli", opencode.installHint)
        assertEquals("npm update --quiet --no-fund -g @opencode/cli", opencode.updateHint)
        assertEquals("npm uninstall -g @opencode/cli", opencode.uninstallHint)
        assertEquals(VersionSource(VersionSource.Kind.NPM, "@opencode/cli"), opencode.resolvedVersionSource)
        assertTrue("@opencode\\cli\\postinstall.mjs" in opencode.postUpdateHintWindows.orEmpty())
        assertTrue("@opencode\\cli\\postinstall.mjs" in opencode.installHintWindows.orEmpty())
    }
}
