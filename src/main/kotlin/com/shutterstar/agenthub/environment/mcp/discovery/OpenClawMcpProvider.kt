package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.environment.discovery.OpenClawLayout
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Path
import java.util.logging.Logger

/**
 * OpenClaw MCP servers (docs `tools/mcp`, `cli/mcp`): the `mcp.servers` map of `openclaw.json` (JSON5; comments and trailing
 * commas are accepted here, other JSON5 syntax makes the file unreadable and is reported). A server has `command` or `url`;
 * `transport` is `stdio`, `streamable-http` or `sse`, and an `url` without `transport` means SSE. mcporter servers
 * (`config/mcporter.json`) are not modelled. There are no project-level MCP files.
 */
class OpenClawMcpProvider(
    homeDirectory: Path = AgentRuntime.userHome(),
) : McpProvider {
    override val agentId: String = AGENT_ID
    private val configPath = OpenClawLayout(homeDirectory).configPath

    override fun discoverGlobal(): List<RawMcpServer> {
        val root = McpConfigRootReader.read(configPath, "OpenClaw", LOG, jsonc = true) ?: return emptyList()
        val servers = (root.fields[MCP_FIELD] as? JsonObject)?.fields?.get(SERVERS_FIELD)
        return JsonMcpServerSupport.parseServers(
            agentId = agentId,
            container = McpServerFields.normalized(servers),
            configPath = configPath,
            scope = McpScope.GLOBAL,
            urlImpliesSse = true,
        )
    }

    override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> = emptyList()

    private companion object {
        const val AGENT_ID = "openclaw"
        const val MCP_FIELD = "mcp"
        const val SERVERS_FIELD = "servers"
        val LOG: Logger = Logger.getLogger(OpenClawMcpProvider::class.java.name)
    }
}
