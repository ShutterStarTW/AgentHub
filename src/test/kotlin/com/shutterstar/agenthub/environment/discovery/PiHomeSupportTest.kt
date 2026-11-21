package com.shutterstar.agenthub.environment.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class PiHomeSupportTest {
    private val systemHome: Path = Path.of(System.getProperty("user.home"))

    private fun env(vararg values: Pair<String, String>): (String) -> String? = values.toMap()::get

    @Test
    fun `default sessions directory is dot pi agent sessions`() {
        assertEquals(systemHome.resolve(".pi/agent"), PiHomeSupport.agentDirectory(systemHome, env()))
        assertEquals(systemHome.resolve(".pi/agent/sessions"), PiHomeSupport.sessionsDirectory(systemHome, env()))
    }

    @Test
    fun `PI_CODING_AGENT_DIR moves the agent directory and its sessions`() {
        val override = systemHome.resolve("custom-agent")

        assertEquals(override, PiHomeSupport.agentDirectory(systemHome, env("PI_CODING_AGENT_DIR" to override.toString())))
        assertEquals(override.resolve("sessions"), PiHomeSupport.sessionsDirectory(systemHome, env("PI_CODING_AGENT_DIR" to override.toString())))
    }

    @Test
    fun `PI_CODING_AGENT_SESSION_DIR replaces only the sessions directory`() {
        val sessions = systemHome.resolve("elsewhere/sessions")

        assertEquals(
            sessions,
            PiHomeSupport.sessionsDirectory(systemHome, env("PI_CODING_AGENT_SESSION_DIR" to sessions.toString(), "PI_CODING_AGENT_DIR" to "/x")),
        )
        assertEquals(systemHome.resolve(".pi/agent"), PiHomeSupport.agentDirectory(systemHome, env("PI_CODING_AGENT_SESSION_DIR" to sessions.toString())))
    }

    @Test
    fun `environment is ignored when the home directory is not the system home`() {
        val home = systemHome.resolve("isolated-test-home")

        assertEquals(
            home.resolve(".pi/agent/sessions"),
            PiHomeSupport.sessionsDirectory(home, env("PI_CODING_AGENT_DIR" to "/x", "PI_CODING_AGENT_SESSION_DIR" to "/y")),
        )
    }
}
