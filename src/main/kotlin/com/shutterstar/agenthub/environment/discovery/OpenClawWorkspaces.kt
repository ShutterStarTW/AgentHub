package com.shutterstar.agenthub.environment.discovery

import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.OsDetector
import com.shutterstar.agenthub.projects.discovery.MetadataJsonParser
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Where an OpenClaw agent works (docs `concepts/agent-workspace`): `agents.entries.<id>.workspace`; without it the sole
 * agent and `main` use `agents.defaults.workspace` (else `OPENCLAW_WORKSPACE_DIR`, else `<state>/workspace`), and the
 * other agents of a multi-agent roster `<agents.defaults.workspace>/<id>` or `<state>/workspace-<id>`.
 * Paths are returned as the agent sees them (a Linux path in WSL mode).
 */
internal class OpenClawWorkspaces(
    private val config: String?,
    private val stateDirectory: Path,
    private val workspaceOverride: String?,
) {
    private val defaults = config?.let { MetadataJsonParser.stringAtPath(it, "agents", "defaults", "workspace") }?.takeIf { it.isNotBlank() }
    private val entryIds = config?.let { MetadataJsonParser.rawPath(it, "agents", "entries") }
        ?.let(MetadataJsonParser::objectEntries)?.map { it.first }.orEmpty()

    /** The agents the configuration lists (a configured agent may not have run yet). */
    fun configuredAgentIds(): List<String> = entryIds

    fun forAgent(agentId: String): String {
        val explicit = config?.let { MetadataJsonParser.stringAtPath(it, "agents", "entries", agentId, "workspace") }?.takeIf { it.isNotBlank() }
        return when {
            explicit != null -> expandHome(explicit)
            agentId == MAIN_AGENT || entryIds.size <= 1 -> defaults?.let(::expandHome) ?: workspaceOverride ?: stateRelative("workspace")
            defaults != null -> expandHome(defaults).trimEnd('/', '\\') + "/" + agentId
            else -> stateRelative("workspace-$agentId")
        }
    }

    private fun stateRelative(name: String): String {
        val path = stateDirectory.resolve(name).toString()
        return AgentRuntime.toLinuxPath(path) ?: path
    }

    private fun expandHome(value: String): String {
        if (value != "~" && !value.startsWith("~/") && !value.startsWith("~\\")) return value
        val rest = value.removePrefix("~").trimStart('/', '\\')
        val linuxHome = AgentRuntime.linuxHome()
        if (linuxHome != null) return if (rest.isEmpty()) linuxHome else "$linuxHome/$rest"
        return AgentRuntime.userHome().resolve(rest).toString()
    }

    private companion object {
        const val MAIN_AGENT = "main"
    }
}

/**
 * The OpenClaw installation below [home]: its state directory, configuration, agents and the workspace of each agent.
 * An OpenClaw "project" is an agent workspace - skills, instruction files and sessions are all keyed by it.
 */
internal class OpenClawLayout(
    home: Path = AgentRuntime.userHome(),
    env: (String) -> String? = System::getenv,
) {
    val stateDirectory: Path = OpenClawHomeSupport.stateDirectory(home, env)
    val configPath: Path = OpenClawHomeSupport.configPath(home, stateDirectory, env)
    private val workspaceOverride: String? = OpenClawHomeSupport.workspaceOverride(home, env)

    fun workspaces(): OpenClawWorkspaces = OpenClawWorkspaces(readConfig(), stateDirectory, workspaceOverride)

    /** The agents with state on disk (`agents/<id>/`), the configured ones and the initial `main` agent every installation has. */
    fun agentIds(workspaces: OpenClawWorkspaces = workspaces()): List<String> {
        val onDisk = runCatching {
            Files.list(stateDirectory.resolve(AGENTS_DIRECTORY)).use { entries ->
                entries.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.map { it.fileName.toString() }.limit(MAX_AGENTS).toList()
            }
        }.getOrDefault(emptyList())
        return (onDisk + workspaces.configuredAgentIds() + MAIN_AGENT).distinct().sorted()
    }

    /** The agents whose workspace is [root]. */
    fun agentsWorkingIn(root: Path): List<String> {
        val workspaces = workspaces()
        val wanted = pathKey(root.toString())
        return agentIds(workspaces).filter { agentId ->
            val host = AgentRuntime.toHostPath(workspaces.forAgent(agentId)) ?: return@filter false
            pathKey(host) == wanted
        }
    }

    private fun pathKey(path: String): String = OsDetector.pathKey(Path.of(path).toAbsolutePath().normalize().toString())

    private fun readConfig(): String? = runCatching {
        if (!Files.isRegularFile(configPath, LinkOption.NOFOLLOW_LINKS)) return null
        Files.newInputStream(configPath).use { String(it.readNBytes(MAX_CONFIG_BYTES), Charsets.UTF_8) }.takeIf { it.isNotBlank() }
    }.getOrNull()

    companion object {
        const val AGENTS_DIRECTORY = "agents"
        private const val MAIN_AGENT = "main"
        private const val MAX_AGENTS = 100L
        private const val MAX_CONFIG_BYTES = 1024 * 1024
    }
}
