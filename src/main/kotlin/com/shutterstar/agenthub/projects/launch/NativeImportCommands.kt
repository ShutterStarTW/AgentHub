package com.shutterstar.agenthub.projects.launch

/**
 * Cross-agent open of a discovered session through the *target* agent's own importer — AgentHub
 * never writes to the target's session store, it only starts the agent's documented command.
 *
 * - Oh My Pi (`--help`, 2026-10-23): `omp --from-claude` / `omp --from-codex` ("Import a Claude Code /
 *   Codex session into OMP"). The flags take no session id: OMP opens its own picker.
 * - Kilo Code, MiMo Code and OpenCode (tried by hand 2026-10-24; the same OpenCode-family TUI): the
 *   `/resume-claude <uuid>` and `/resume-codex <uuid>` slash commands import that exact session (only in
 *   an empty session). They are TUI commands, so the line starts a fresh agent with the command
 *   pre-filled through `--prompt` (listed in each `--help`).
 *
 * Only single-command lines are listed, so a line works the same in cmd, Windows PowerShell and bash.
 * `mimo session import-claude` (imports every Claude session) and Codex's `/import` need command
 * chaining or a live TUI check first (see ai-docs/milestones/agenthub-milestone-06-cross-agent-handoff.md, 4a).
 *
 * Pure Kotlin (no IntelliJ imports) so it can be unit tested standalone.
 */
object NativeImportCommands {
    /** Same shape as the resume ids in [NativeResumeCommands]: it is pasted into a shell line. */
    private val safeId = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

    /** target agent id → (source agent id → builder of the full command from the source session id). */
    private val commands: Map<String, Map<String, (String?) -> String?>> = mapOf(
        "kilo" to mapOf(
            "claude" to { id -> promptCommand("kilo", "/resume-claude", id) },
            "codex" to { id -> promptCommand("kilo", "/resume-codex", id) },
        ),
        "mimo" to mapOf(
            "claude" to { id -> promptCommand("mimo", "/resume-claude", id) },
            "codex" to { id -> promptCommand("mimo", "/resume-codex", id) },
        ),
        "omp" to mapOf(
            "claude" to { _ -> "omp --from-claude" },
            "codex" to { _ -> "omp --from-codex" },
        ),
        "opencode" to mapOf(
            "claude" to { id -> promptCommand("opencode", "/resume-claude", id) },
            "codex" to { id -> promptCommand("opencode", "/resume-codex", id) },
        ),
    )

    private fun promptCommand(executable: String, slashCommand: String, id: String?): String? {
        val safe = id?.trim()?.takeIf(safeId::matches) ?: return null
        return "$executable --prompt \"$slashCommand $safe\""
    }

    /** The command that opens a [sourceAgentId] session in [targetAgentId], or null when the target has no importer for it. */
    fun command(sourceAgentId: String, targetAgentId: String, nativeResumeId: String? = null): String? =
        if (sourceAgentId == targetAgentId) null else commands[targetAgentId]?.get(sourceAgentId)?.invoke(nativeResumeId)

    /** Agents (ids, alphabetical) that can natively open this session of [sourceAgentId]. */
    fun targets(sourceAgentId: String, nativeResumeId: String? = null): List<String> =
        commands.keys.filter { command(sourceAgentId, it, nativeResumeId) != null }.sorted()

    /** What the target does after it starts, shown as the menu tooltip. */
    fun note(targetAgentId: String): String = when (targetAgentId) {
        "kilo", "mimo", "opencode" ->
            "The agent starts with the import command filled in: press Enter to import this session. AgentHub does not write to its session store."
        "omp" -> "Oh My Pi opens its own picker: choose the session there. AgentHub does not write to Oh My Pi's session store."
        else -> "The target agent imports the session itself."
    }
}
