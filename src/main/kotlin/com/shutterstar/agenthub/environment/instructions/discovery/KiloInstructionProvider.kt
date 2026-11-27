package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

/**
 * Kilo Code CLI instructions (source: `session/instruction.ts`): the global `AGENTS.md` of
 * `$KILO_CONFIG_DIR` and `~/.config/kilo`, falling back to the global Claude Code file; per directory
 * `AGENTS.md`, with `CLAUDE.md` only as a fallback when no `AGENTS.md` is there. A `CLAUDE.md` that
 * is shadowed by an `AGENTS.md` is still listed, with an info line saying why it is not applied.
 * (The deprecated `CONTEXT.md` and the `KILO_DISABLE_CLAUDE_CODE[_PROMPT]` switches are not modelled.)
 */
class KiloInstructionProvider(
    homeDirectory: Path = AgentRuntime.userHome(),
) : InstructionProvider {
    override val agentId: String = AGENT_ID
    private val globalFiles = listOfNotNull(
        EnvHomeDirectorySupport.configuredDirectoryGuarded("KILO_CONFIG_DIR", homeDirectory)?.resolve(AGENTS_FILE),
        EnvHomeDirectorySupport.resolveXdgGuarded("XDG_CONFIG_HOME", homeDirectory, ".config", "kilo").resolve(AGENTS_FILE),
    )
    private val globalClaudeFile = homeDirectory.resolve(".claude").resolve(CLAUDE_FILE)

    override fun discoverGlobal(): List<InstructionSource> =
        InstructionFileSupport.globalAgentsOrClaude(globalFiles, globalClaudeFile, agentId)

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> =
        InstructionFileSupport.projectAgentsAndClaude(project, agentId, SHADOWED_NOTE, ::isNonEmptyFile)

    private fun isNonEmptyFile(path: Path): Boolean =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { Files.size(path) > 0L }.getOrDefault(false)

    private companion object {
        const val AGENT_ID = "kilo"
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val SHADOWED_NOTE = "Not used by Kilo: it reads CLAUDE.md only when no AGENTS.md exists in the same directory."
    }
}
