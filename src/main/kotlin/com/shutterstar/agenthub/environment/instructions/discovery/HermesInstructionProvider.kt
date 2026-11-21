package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.environment.discovery.HermesHomeSupport
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Hermes Agent instructions (docs `user-guide/features/context-files`): `SOUL.md` in `<HERMES_HOME>` (the agent's global
 * personality, always loaded) and the project context files. Hermes loads **one** project context type per session - the
 * first that exists of `.hermes.md`/`HERMES.md`, `AGENTS.override.md`, `AGENTS.md`, `CLAUDE.md`, `.cursorrules` (with
 * the `.mdc` files of `.cursor/rules`) - so a lower-priority file next to a higher one is listed with a note instead of hidden. Nested
 * `AGENTS.md`/`CLAUDE.md` files are listed too: Hermes discovers them progressively as the session touches those directories.
 * `memories/` (MEMORY.md, USER.md) is agent memory, not instructions, and is not read.
 */
class HermesInstructionProvider(
    homeDirectory: Path = AgentRuntime.userHome(),
    windowsRuntime: Boolean = AgentRuntime.isWindowsRuntime(),
) : InstructionProvider {
    override val agentId: String = AGENT_ID
    private val hermesHome = HermesHomeSupport.homeDirectory(homeDirectory, windowsRuntime)

    override fun discoverGlobal(): List<InstructionSource> = listOfNotNull(
        InstructionFileSupport.source(hermesHome.resolve(SOUL_FILE), InstructionScope.GLOBAL, agentId, InstructionType.AGENT_SPECIFIC),
    )

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val root = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        val rootGroups = GROUPS.map { group -> group.names.filter { Files.isRegularFile(root.resolve(it), LinkOption.NOFOLLOW_LINKS) } }
        val winningGroup = rootGroups.indexOfFirst { it.isNotEmpty() }
        val nested = InstructionFileSupport.scan(root) { scanRoot, file ->
            val relative = scanRoot.relativize(file)
            relative.nameCount > 1 && file.fileName.toString() in NESTED_FILES && relative.none { it.toString().startsWith(".") }
        }
        val rootSources = GROUPS.mapIndexed { index, group ->
            rootGroups[index].mapNotNull { name ->
                val note = if (winningGroup in 0 until index) "Not used: ${group.winnerHint}" else null
                InstructionFileSupport.source(root.resolve(name), InstructionScope.PROJECT, agentId, group.type(name), project.name, note)
            }
        }.flatten()
        val cursorRules = cursorRules(root, project.name, if (winningGroup in 0 until GROUPS.lastIndex) "Not used: ${GROUPS.last().winnerHint}" else null)
        val nestedSources = nested.mapNotNull { file ->
            InstructionFileSupport.source(file, InstructionScope.PROJECT, agentId, typeOf(file.fileName.toString()), project.name)
        }
        return (rootSources + cursorRules + nestedSources).distinctBy { it.path }
    }

    private fun cursorRules(root: Path, projectName: String, note: String?): List<InstructionSource> {
        val directory = root.resolve(".cursor").resolve("rules")
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val files = runCatching {
            Files.list(directory).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".mdc") }.limit(MAX_RULE_FILES).sorted().toList()
            }
        }.getOrDefault(emptyList())
        return files.mapNotNull { InstructionFileSupport.source(it, InstructionScope.PROJECT, agentId, InstructionType.CURSOR_RULE, projectName, note) }
    }

    private fun typeOf(name: String): InstructionType = if (name == CLAUDE_FILE) InstructionType.CLAUDE_MD else InstructionType.AGENTS_MD

    private class Group(val names: List<String>, val winnerHint: String, val type: (String) -> InstructionType)

    private companion object {
        const val AGENT_ID = "hermes"
        const val SOUL_FILE = "SOUL.md"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val MAX_RULE_FILES = 200L
        val NESTED_FILES = setOf("AGENTS.md", CLAUDE_FILE)
        const val HINT = "Hermes loads only the first existing of .hermes.md, AGENTS.override.md, AGENTS.md, CLAUDE.md and .cursorrules in the project."
        val GROUPS = listOf(
            Group(listOf(".hermes.md", "HERMES.md"), HINT) { InstructionType.AGENT_SPECIFIC },
            Group(listOf("AGENTS.override.md"), HINT) { InstructionType.AGENT_SPECIFIC },
            Group(listOf("AGENTS.md"), HINT) { InstructionType.AGENTS_MD },
            Group(listOf(CLAUDE_FILE), HINT) { InstructionType.CLAUDE_MD },
            Group(listOf(".cursorrules"), HINT) { InstructionType.CURSOR_RULE },
        )
    }
}
