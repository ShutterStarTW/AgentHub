package com.shutterstar.agenthub.environment.discovery

import com.shutterstar.agenthub.AgentRuntime
import java.nio.file.Path

/**
 * Hermes Agent's home directory (docs: environment-variables, `get_hermes_home()`): `HERMES_HOME` when set, else
 * `%LOCALAPPDATA%\hermes` on Windows and `~/.hermes` on macOS/Linux (and inside a WSL distro). Named profiles keep their
 * own `state.db` below `<home>/profiles/<name>/`.
 *
 * `HERMES_HOME` and `LOCALAPPDATA` are honoured only while [home] is the system home (test isolation and WSL mode,
 * same guard as [EnvHomeDirectorySupport.configuredDirectoryGuarded]). Without `LOCALAPPDATA` the Windows default is
 * `<home>\AppData\Local\hermes`.
 */
internal object HermesHomeSupport {
    fun homeDirectory(
        home: Path,
        windowsRuntime: Boolean = AgentRuntime.isWindowsRuntime(),
        env: (String) -> String? = System::getenv,
    ): Path {
        EnvHomeDirectorySupport.configuredDirectoryGuarded(HOME_VARIABLE, home, env(HOME_VARIABLE))?.let { return it }
        if (!windowsRuntime) return home.resolve(".hermes")
        val localAppData = EnvHomeDirectorySupport.configuredDirectoryGuarded(LOCAL_APP_DATA_VARIABLE, home, env(LOCAL_APP_DATA_VARIABLE))
            ?: home.resolve("AppData").resolve("Local")
        return localAppData.resolve("hermes")
    }

    private const val HOME_VARIABLE = "HERMES_HOME"
    private const val LOCAL_APP_DATA_VARIABLE = "LOCALAPPDATA"
}
