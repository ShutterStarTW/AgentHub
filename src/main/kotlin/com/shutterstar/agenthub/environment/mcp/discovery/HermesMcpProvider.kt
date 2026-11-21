package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.environment.discovery.HermesHomeSupport
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

/**
 * Hermes Agent MCP servers (docs `user-guide/features/mcp`): the `mcp_servers` mapping of `<HERMES_HOME>/config.yaml`
 * (stdio: `command`/`args`/`env`; HTTP: `url`/`headers`, `transport: sse` for legacy SSE; `enabled: false` servers are
 * skipped). The docs name no project-level MCP file. `.env`, `auth.json` and the OAuth tokens in `mcp-tokens/` are never read.
 */
class HermesMcpProvider(
    homeDirectory: Path = AgentRuntime.userHome(),
    windowsRuntime: Boolean = AgentRuntime.isWindowsRuntime(),
) : McpProvider {
    override val agentId: String = AGENT_ID
    private val configPath = HermesHomeSupport.homeDirectory(homeDirectory, windowsRuntime).resolve(CONFIG_FILE)

    override fun discoverGlobal(): List<RawMcpServer> {
        val content = McpConfigFileReader.read(configPath)
        if (content == null) {
            if (Files.exists(configPath)) LOG.warning("[McpDiscovery] Hermes Agent: unreadable or oversized config: $configPath")
            return emptyList()
        }
        val servers = HermesMcpConfigParser.parse(content, configPath.toAbsolutePath().normalize(), McpScope.GLOBAL, agentId)
        if (servers == null) LOG.warning("[McpDiscovery] Hermes Agent: malformed config: $configPath")
        return servers.orEmpty()
    }

    override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> = emptyList()

    private companion object {
        const val AGENT_ID = "hermes"
        const val CONFIG_FILE = "config.yaml"
        val LOG: Logger = Logger.getLogger(HermesMcpProvider::class.java.name)
    }
}
