package com.shutterstar.agenthub.environment.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class HermesHomeSupportTest {
    private val systemHome: Path = Path.of(System.getProperty("user.home"))

    private fun env(vararg values: Pair<String, String>): (String) -> String? = values.toMap()::get

    @Test
    fun `default home is dot hermes on Linux and macOS`() {
        assertEquals(systemHome.resolve(".hermes"), HermesHomeSupport.homeDirectory(systemHome, windowsRuntime = false, env = env()))
    }

    @Test
    fun `default home is LOCALAPPDATA hermes on Windows`() {
        val localAppData = systemHome.resolve("AppData/Local")

        assertEquals(
            localAppData.resolve("hermes"),
            HermesHomeSupport.homeDirectory(systemHome, windowsRuntime = true, env = env("LOCALAPPDATA" to localAppData.toString())),
        )
        assertEquals(
            systemHome.resolve("AppData/Local/hermes"),
            HermesHomeSupport.homeDirectory(systemHome, windowsRuntime = true, env = env()),
        )
    }

    @Test
    fun `HERMES_HOME wins on every platform`() {
        val override = systemHome.resolve("custom-hermes")

        assertEquals(override, HermesHomeSupport.homeDirectory(systemHome, windowsRuntime = true, env = env("HERMES_HOME" to override.toString())))
        assertEquals(override, HermesHomeSupport.homeDirectory(systemHome, windowsRuntime = false, env = env("HERMES_HOME" to override.toString())))
    }

    @Test
    fun `environment is ignored when the home directory is not the system home`() {
        val home = systemHome.resolve("isolated-test-home")

        assertEquals(home.resolve(".hermes"), HermesHomeSupport.homeDirectory(home, windowsRuntime = false, env = env("HERMES_HOME" to "/x")))
        assertEquals(
            home.resolve("AppData/Local/hermes"),
            HermesHomeSupport.homeDirectory(home, windowsRuntime = true, env = env("LOCALAPPDATA" to "/x", "HERMES_HOME" to "/y")),
        )
    }
}
