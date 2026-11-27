package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.discovery.MimoHomeSupport
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

/**
 * MiMo Code CLI instructions (source `packages/cli/src/session/instruction.ts`): the first existing global file of
 * `$MIMOCODE_CONFIG_DIR/AGENTS.md`, `<config dir>/AGENTS.md` and the Claude Code `~/.claude/CLAUDE.md`; per project
 * `AGENTS.md` (searched upwards), with `CLAUDE.md` only as a fallback when no `AGENTS.md` exists or that one is shorter
 * than 500 characters. A `CLAUDE.md` that therefore is not applied is still listed, with an info line saying why. (The
 * deprecated `CONTEXT.md` and the `MIMOCODE_DISABLE_CLAUDE_CODE[_PROMPT]` switches are not modelled.)
 */
class MimoInstructionProvider(
    homeDirectory: Path = AgentRuntime.userHome(),
) : InstructionProvider {
    override val agentId: String = AGENT_ID
    private val globalFiles = listOfNotNull(
        EnvHomeDirectorySupport.configuredDirectoryGuarded("MIMOCODE_CONFIG_DIR", homeDirectory)?.resolve(AGENTS_FILE),
        MimoHomeSupport.configDirectory(homeDirectory).resolve(AGENTS_FILE),
    )
    private val globalClaudeFile = homeDirectory.resolve(".claude").resolve(CLAUDE_FILE)

    override fun discoverGlobal(): List<InstructionSource> =
        InstructionFileSupport.globalAgentsOrClaude(globalFiles, globalClaudeFile, agentId)

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> =
        InstructionFileSupport.projectAgentsAndClaude(project, agentId, SHADOWED_NOTE, ::isSubstantial)

    private fun isNonEmptyFile(path: Path): Boolean =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { Files.size(path) > 0L }.getOrDefault(false)

    /** An `AGENTS.md` with at least [FALLBACK_MAX_CHARACTERS] characters (trimmed): enough that MiMo ignores `CLAUDE.md`. */
    private fun isSubstantial(path: Path): Boolean =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            runCatching {
                // A bounded prefix decides it: enough non-blank characters in the first chunk already exceed the threshold.
                Files.newBufferedReader(path).use { reader ->
                    val buffer = CharArray(FALLBACK_READ_CHARACTERS)
                    String(buffer, 0, maxOf(0, reader.read(buffer))).trim().length >= FALLBACK_MAX_CHARACTERS
                }
            }.getOrDefault(false)

    private companion object {
        const val AGENT_ID = "mimo"
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val FALLBACK_MAX_CHARACTERS = 500
        const val FALLBACK_READ_CHARACTERS = 64 * 1024
        const val SHADOWED_NOTE =
            "Not used by MiMo Code: it reads CLAUDE.md only when no AGENTS.md exists in the same directory or that AGENTS.md is shorter than 500 characters."
    }
}
