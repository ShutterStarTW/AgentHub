package com.shutterstar.agenthub.environment.discovery

import java.nio.file.Path

/**
 * OpenClaw's state layout (docs: `concepts/agent-workspace`, `reference/database-schemas/layout`; `openclaw --help`):
 * - state directory `~/.openclaw`; `OPENCLAW_STATE_DIR` replaces it; `OPENCLAW_PROFILE` (the `--profile` flag sets the
 *   same) selects `~/.openclaw-<profile>`;
 * - configuration `<state>/openclaw.json`; `OPENCLAW_CONFIG_PATH` replaces it;
 * - default agent workspace `<state>/workspace`; `OPENCLAW_WORKSPACE_DIR` replaces that default.
 *
 * Environment variables are honoured only while [home] is the system home (test isolation and WSL mode, same guard as
 * [EnvHomeDirectorySupport.configuredDirectoryGuarded]).
 */
internal object OpenClawHomeSupport {
    fun stateDirectory(home: Path, env: (String) -> String? = System::getenv): Path {
        EnvHomeDirectorySupport.configuredDirectoryGuarded(STATE_DIR_VARIABLE, home, env(STATE_DIR_VARIABLE))?.let { return it }
        val profile = EnvHomeDirectorySupport.guardedValue(PROFILE_VARIABLE, home, env(PROFILE_VARIABLE))
            ?.takeIf { PROFILE_NAME.matches(it) && !it.equals("default", ignoreCase = true) }
        return home.resolve(if (profile == null) ".openclaw" else ".openclaw-$profile")
    }

    fun configPath(home: Path, stateDirectory: Path, env: (String) -> String? = System::getenv): Path =
        EnvHomeDirectorySupport.configuredDirectoryGuarded(CONFIG_PATH_VARIABLE, home, env(CONFIG_PATH_VARIABLE))
            ?: stateDirectory.resolve("openclaw.json")

    fun workspaceOverride(home: Path, env: (String) -> String? = System::getenv): String? =
        EnvHomeDirectorySupport.configuredDirectoryGuarded(WORKSPACE_VARIABLE, home, env(WORKSPACE_VARIABLE))?.toString()

    private const val STATE_DIR_VARIABLE = "OPENCLAW_STATE_DIR"
    private const val PROFILE_VARIABLE = "OPENCLAW_PROFILE"
    private const val CONFIG_PATH_VARIABLE = "OPENCLAW_CONFIG_PATH"
    private const val WORKSPACE_VARIABLE = "OPENCLAW_WORKSPACE_DIR"
    private val PROFILE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
}
