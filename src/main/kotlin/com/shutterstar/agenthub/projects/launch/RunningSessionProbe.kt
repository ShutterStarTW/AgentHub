package com.shutterstar.agenthub.projects.launch

import com.shutterstar.agenthub.projects.resolve.ProcessCommandRunner

/**
 * Finds a running agent process that already has a session open, so a session is not opened twice
 * (Codex even stops a second `codex resume` at "This conversation is open in another app"). Matches on
 * command lines, so a `wsl.exe ... codex resume <id>` wrapper is found too; a session started without
 * its id on the command line cannot be seen. The process list is injectable.
 */
object RunningSessionProbe {
    data class Holder(val pid: Long, val commandLine: String)

    private const val TIMEOUT_MILLIS = 8_000L
    private const val PID_SEPARATOR = "|"

    fun find(
        agentId: String,
        sessionId: String,
        processes: (String) -> Sequence<Pair<Long, String?>> = ::systemProcesses,
    ): Holder? {
        val launchWord = NativeResumeCommands.launchWord(agentId) ?: return null
        if (sessionId.isBlank()) return null
        val idToken = Regex("(?<![\\w-])" + Regex.escape(sessionId) + "(?![\\w-])", RegexOption.IGNORE_CASE)
        return processes(sessionId).firstNotNullOfOrNull { (pid, commandLine) ->
            commandLine
                ?.takeIf { line -> line.contains(launchWord, ignoreCase = true) && idToken.containsMatchIn(line) }
                ?.let { Holder(pid, it) }
        }
    }

    /**
     * `ProcessHandle.info().commandLine()` is empty for most processes on Windows, so the command lines
     * come from WMI there. [sessionId] is a validated resume id (letters, digits, `.:_-`), safe to embed.
     */
    private fun systemProcesses(sessionId: String): Sequence<Pair<Long, String?>> {
        val self = ProcessHandle.current().pid()
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            val script = "Get-CimInstance Win32_Process | " +
                "Where-Object { \$_.CommandLine -like '*$sessionId*' -and \$_.CommandLine -notlike '*Get-CimInstance*' } | " +
                "ForEach-Object { '{0}$PID_SEPARATOR{1}' -f \$_.ProcessId, \$_.CommandLine }"
            val output = ProcessCommandRunner.run(
                listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", script),
                TIMEOUT_MILLIS,
            ).orEmpty()
            return output.lineSequence().mapNotNull { line ->
                val pid = line.substringBefore(PID_SEPARATOR).trim().toLongOrNull()?.takeIf { it != self } ?: return@mapNotNull null
                pid to line.substringAfter(PID_SEPARATOR, "")
            }
        }
        return runCatching {
            ProcessHandle.allProcesses()
                .filter { it.pid() != self }
                .map { it.pid() to it.info().commandLine().orElse(null) }
                .toList()
        }.getOrDefault(emptyList()).asSequence()
    }
}
