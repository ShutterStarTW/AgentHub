package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import com.shutterstar.agenthub.AgentRuntime

class OpenCodeInstructionProvider(
    homeDirectory: Path = AgentRuntime.userHome(),
    private val version: () -> OpenCodeVersion? = ::detectVersion,
) : InstructionProvider {
    override val agentId: String = AGENT_ID
    private val globalFile = EnvHomeDirectorySupport.resolveXdgGuarded(
        "XDG_CONFIG_HOME",
        homeDirectory,
        ".config",
        "opencode",
    ).resolve(AGENTS_FILE)
    private val globalClaudeFile = homeDirectory.resolve(".claude").resolve(CLAUDE_FILE)
    private val usesClaudeFallback by lazy { version()?.readsClaudeFallback() ?: true }

    override fun discoverGlobal(): List<InstructionSource> = listOfNotNull(
        InstructionFileSupport.source(
            path = if (isNonEmptyFile(globalFile) || !usesClaudeFallback) globalFile else globalClaudeFile,
            scope = InstructionScope.GLOBAL,
            agentId = agentId,
            type = if (isNonEmptyFile(globalFile) || !usesClaudeFallback) InstructionType.AGENTS_MD else InstructionType.CLAUDE_MD,
        ),
    )

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val projectRoot = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        return InstructionFileSupport.scan(projectRoot) { _, file ->
            val fileName = file.fileName.toString()
            fileName.equals(AGENTS_FILE, ignoreCase = true) ||
                (usesClaudeFallback && fileName.equals(CLAUDE_FILE, ignoreCase = true))
        }.filterNot { file ->
            file.fileName.toString().equals(CLAUDE_FILE, ignoreCase = true) &&
                isNonEmptyFile(file.resolveSibling(AGENTS_FILE))
        }.mapNotNull { file ->
            val type = if (file.fileName.toString().equals(AGENTS_FILE, ignoreCase = true)) {
                InstructionType.AGENTS_MD
            } else {
                InstructionType.CLAUDE_MD
            }
            InstructionFileSupport.source(file, InstructionScope.PROJECT, agentId, type, project.name)
        }
    }

    private fun isNonEmptyFile(path: Path): Boolean =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { Files.size(path) > 0L }.getOrDefault(false)

    private companion object {
        const val AGENT_ID = "opencode"
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"

        fun detectVersion(): OpenCodeVersion? = try {
            val process = ProcessBuilder("opencode", "--version").redirectErrorStream(true).start()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else {
                process.inputStream.bufferedReader().use { reader ->
                    OpenCodeVersion.parse(reader.readText())
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }
}

// A 2.0.0-tól 2.3.0-ig az OpenCode nem olvasta a CLAUDE.md-t, a 2.3.1 visszahozta.
data class OpenCodeVersion(val major: Int, val minor: Int, val patch: Int) {
    fun readsClaudeFallback(): Boolean = major != 2 || minor > 3 || (minor == 3 && patch >= 1)

    companion object {
        private val pattern = Regex("\\b(\\d+)\\.(\\d+)(?:\\.(\\d+))?")

        fun parse(text: String): OpenCodeVersion? = pattern.find(text)?.let { match ->
            val (major, minor, patch) = match.destructured
            OpenCodeVersion(major.toInt(), minor.toInt(), patch.toIntOrNull() ?: 0)
        }
    }
}
