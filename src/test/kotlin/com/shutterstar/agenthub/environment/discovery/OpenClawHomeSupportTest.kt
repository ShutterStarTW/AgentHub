package com.shutterstar.agenthub.environment.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path

class OpenClawHomeSupportTest {
    private val systemHome: Path = Path.of(System.getProperty("user.home"))

    private fun env(vararg values: Pair<String, String>): (String) -> String? = values.toMap()::get

    @Test
    fun `default state directory is dot openclaw`() {
        val state = OpenClawHomeSupport.stateDirectory(systemHome, env())

        assertEquals(systemHome.resolve(".openclaw"), state)
        assertEquals(state.resolve("openclaw.json"), OpenClawHomeSupport.configPath(systemHome, state, env()))
        assertNull(OpenClawHomeSupport.workspaceOverride(systemHome, env()))
    }

    @Test
    fun `OPENCLAW_PROFILE selects dot openclaw dash profile and ignores unsafe or default names`() {
        assertEquals(systemHome.resolve(".openclaw-work"), OpenClawHomeSupport.stateDirectory(systemHome, env("OPENCLAW_PROFILE" to "work")))
        assertEquals(systemHome.resolve(".openclaw"), OpenClawHomeSupport.stateDirectory(systemHome, env("OPENCLAW_PROFILE" to "default")))
        assertEquals(systemHome.resolve(".openclaw"), OpenClawHomeSupport.stateDirectory(systemHome, env("OPENCLAW_PROFILE" to "../evil")))
    }

    @Test
    fun `explicit state config and workspace overrides win`() {
        val state = systemHome.resolve("custom-state")
        val config = systemHome.resolve("custom.json")
        val workspace = systemHome.resolve("ws")

        assertEquals(state, OpenClawHomeSupport.stateDirectory(systemHome, env("OPENCLAW_STATE_DIR" to state.toString(), "OPENCLAW_PROFILE" to "work")))
        assertEquals(config, OpenClawHomeSupport.configPath(systemHome, state, env("OPENCLAW_CONFIG_PATH" to config.toString())))
        assertEquals(workspace.toString(), OpenClawHomeSupport.workspaceOverride(systemHome, env("OPENCLAW_WORKSPACE_DIR" to workspace.toString())))
    }

    @Test
    fun `environment is ignored when the home directory is not the system home`() {
        val home = systemHome.resolve("isolated-test-home")

        assertEquals(home.resolve(".openclaw"), OpenClawHomeSupport.stateDirectory(home, env("OPENCLAW_STATE_DIR" to "/x", "OPENCLAW_PROFILE" to "work")))
        assertNull(OpenClawHomeSupport.workspaceOverride(home, env("OPENCLAW_WORKSPACE_DIR" to "/x")))
    }
}
