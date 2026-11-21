package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.environment.discovery.PiHomeSupport
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import java.util.logging.Logger

/**
 * Pi MCP servers (docs `mcp.md`, Pi 1.5; older versions have no MCP): the `mcpServers` map of `<agent dir>/mcp.json`
 * (`~/.pi/agent/mcp.json`) and of the project's `.pi/mcp.json`, which Pi reads only after project trust and in which a
 * same-named entry replaces the user one. `pi mcp add` writes these files. OAuth tokens (`mcp-auth.json`) are never read.
 */
class PiMcpProvider(
    homeDirectory: Path = AgentRuntime.userHome(),
) : McpProvider {
    override val agentId: String = AGENT_ID
    private val agentDirectory = PiHomeSupport.agentDirectory(homeDirectory)

    override fun discoverGlobal(): List<RawMcpServer> = discoverConfig(agentDirectory.resolve(CONFIG_FILE), McpScope.GLOBAL)

    override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> {
        val root = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return discoverConfig(root.resolve(PROJECT_DIRECTORY).resolve(CONFIG_FILE), McpScope.PROJECT, project.name)
    }

    private fun discoverConfig(configPath: Path, scope: McpScope, projectName: String? = null): List<RawMcpServer> {
        val root = McpConfigRootReader.read(configPath, "Pi", LOG) ?: return emptyList()
        return JsonMcpServerSupport.parseServers(
            agentId = agentId,
            container = root.fields[MCP_SERVERS_FIELD],
            configPath = configPath,
            scope = scope,
            projectName = projectName,
        )
    }

    private companion object {
        const val AGENT_ID = "pi"
        const val PROJECT_DIRECTORY = ".pi"
        const val CONFIG_FILE = "mcp.json"
        const val MCP_SERVERS_FIELD = "mcpServers"
        val LOG: Logger = Logger.getLogger(PiMcpProvider::class.java.name)
    }
}
