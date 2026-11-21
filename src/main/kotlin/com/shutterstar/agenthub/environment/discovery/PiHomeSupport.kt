package com.shutterstar.agenthub.environment.discovery

import java.nio.file.Path

/**
 * Pi's (`pi`, `@earendil-works/pi-coding-agent`) native directories (README "Environment Variables", docs `sessions.md`):
 * - default agent directory `~/.pi/agent`; `PI_CODING_AGENT_DIR` replaces it;
 * - sessions live in `<agent directory>/sessions`; `PI_CODING_AGENT_SESSION_DIR` replaces that directory.
 *
 * The `--session-dir` CLI flag cannot be observed from outside, so a session store chosen that way is not found.
 * Environment variables are honoured only while [home] is the system home (test isolation and WSL mode, same guard as
 * [EnvHomeDirectorySupport.configuredDirectoryGuarded]).
 */
internal object PiHomeSupport {
    fun agentDirectory(home: Path, env: (String) -> String? = System::getenv): Path =
        EnvHomeDirectorySupport.configuredDirectoryGuarded(AGENT_DIR_VARIABLE, home, env(AGENT_DIR_VARIABLE))
            ?: home.resolve(".pi").resolve("agent")

    fun sessionsDirectory(home: Path, env: (String) -> String? = System::getenv): Path =
        EnvHomeDirectorySupport.configuredDirectoryGuarded(SESSION_DIR_VARIABLE, home, env(SESSION_DIR_VARIABLE))
            ?: agentDirectory(home, env).resolve("sessions")

    private const val AGENT_DIR_VARIABLE = "PI_CODING_AGENT_DIR"
    private const val SESSION_DIR_VARIABLE = "PI_CODING_AGENT_SESSION_DIR"
}
