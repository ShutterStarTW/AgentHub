package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.environment.discovery.PiHomeSupport
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Pi instructions (docs `configuration.md`, Pi 1.5): the context files `AGENTS.override.md`, `AGENTS.md` and `CLAUDE.md` of
 * the agent directory (`~/.pi/agent`), of the working directory and of its parents - a file applies in its directory and
 * below, and `AGENTS.override.md` replaces `AGENTS.md`/`CLAUDE.md` in the same directory - plus the system-prompt files
 * `SYSTEM.md` (replaces the default prompt) and `APPEND_SYSTEM.md` in the agent directory and in the project's `.pi/`.
 * Which of `AGENTS.md` and `CLAUDE.md` wins when a directory has both is not documented and is not modelled.
 */
class PiInstructionProvider(
    homeDirectory: Path = AgentRuntime.userHome(),
) : InstructionProvider {
    override val agentId: String = AGENT_ID
    private val agentDirectory = PiHomeSupport.agentDirectory(homeDirectory)

    override fun discoverGlobal(): List<InstructionSource> =
        contextFiles(agentDirectory, InstructionScope.GLOBAL, null) + systemFiles(agentDirectory, InstructionScope.GLOBAL, null)

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val projectRoot = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        val context = InstructionFileSupport.scan(projectRoot) { _, file ->
            file.fileName.toString() in CONTEXT_FILES && !file.parent?.fileName?.toString().orEmpty().startsWith(".")
        }.mapNotNull { file ->
            InstructionFileSupport.source(file, InstructionScope.PROJECT, agentId, typeOf(file.fileName.toString()), project.name, overrideNote(file))
        }
        return context + systemFiles(projectRoot.resolve(NATIVE_DIRECTORY), InstructionScope.PROJECT, project.name)
    }

    private fun contextFiles(directory: Path, scope: InstructionScope, projectName: String?): List<InstructionSource> =
        CONTEXT_FILES.mapNotNull { name ->
            val file = directory.resolve(name)
            InstructionFileSupport.source(file, scope, agentId, typeOf(name), projectName, overrideNote(file))
        }

    private fun systemFiles(directory: Path, scope: InstructionScope, projectName: String?): List<InstructionSource> =
        SYSTEM_FILES.mapNotNull { name ->
            InstructionFileSupport.source(directory.resolve(name), scope, agentId, InstructionType.AGENT_SPECIFIC, projectName)
        }

    /** Pi applies `AGENTS.override.md` instead of `AGENTS.md`/`CLAUDE.md` of the same directory. */
    private fun overrideNote(file: Path): String? {
        val name = file.fileName.toString()
        if (name != AGENTS_FILE && name != CLAUDE_FILE) return null
        val override = file.resolveSibling(OVERRIDE_FILE)
        return if (Files.isRegularFile(override, LinkOption.NOFOLLOW_LINKS)) "Not used: $OVERRIDE_FILE in the same directory replaces it." else null
    }

    private fun typeOf(name: String): InstructionType = when (name) {
        AGENTS_FILE -> InstructionType.AGENTS_MD
        CLAUDE_FILE -> InstructionType.CLAUDE_MD
        else -> InstructionType.AGENT_SPECIFIC
    }

    private companion object {
        const val AGENT_ID = "pi"
        const val NATIVE_DIRECTORY = ".pi"
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val OVERRIDE_FILE = "AGENTS.override.md"
        val CONTEXT_FILES = listOf(OVERRIDE_FILE, AGENTS_FILE, CLAUDE_FILE)
        val SYSTEM_FILES = listOf("SYSTEM.md", "APPEND_SYSTEM.md")
    }
}
