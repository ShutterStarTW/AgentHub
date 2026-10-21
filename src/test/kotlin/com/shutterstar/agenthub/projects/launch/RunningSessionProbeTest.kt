package com.shutterstar.agenthub.projects.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RunningSessionProbeTest {
    private val id = "019fdc1f-5757-7221-910a-817c045b8ade"

    private fun find(agentId: String, vararg processes: Pair<Long, String?>, sessionId: String = id) =
        RunningSessionProbe.find(agentId, sessionId) { processes.asSequence() }

    @Test
    fun `finds a running resume of the session`() {
        val holder = find(
            "codex",
            1L to "C:\\Windows\\explorer.exe",
            2L to null,
            3L to "C:\\npm\\codex.exe resume --no-daemon --no-alt-screen $id",
        )

        assertEquals(3L, holder?.pid)
    }

    @Test
    fun `finds the agent behind a wsl wrapper`() {
        assertEquals(7L, find("codex", 7L to "wsl.exe -d Ubuntu --exec bash -lic 'codex resume --no-alt-screen $id'")?.pid)
    }

    @Test
    fun `works for every agent with a resume command`() {
        assertEquals(1L, find("claude", 1L to "claude.exe --resume $id")?.pid)
        assertEquals(2L, find("cursor", 2L to "cursor-agent --resume $id")?.pid)
        assertEquals(3L, find("junie", 3L to "junie --resume --session-id=$id")?.pid)
        assertEquals(4L, find("opencode", 4L to "opencode --session $id")?.pid)
    }

    @Test
    fun `ignores other sessions, other programs and partial ids`() {
        assertNull(
            find(
                "codex",
                1L to "codex.exe resume 019fdc1f-5757-7221-910a-817c045b8adf",
                2L to "codex.exe resume ${id}0",
                3L to "notepad.exe $id",
                4L to "claude.exe --resume $id",
            ),
        )
    }

    @Test
    fun `agents without a resume command and blank ids are not checked`() {
        assertNull(find("unknown-agent", 1L to "unknown-agent $id"))
        assertNull(find("codex", 1L to "codex resume  ", sessionId = " "))
    }
}
