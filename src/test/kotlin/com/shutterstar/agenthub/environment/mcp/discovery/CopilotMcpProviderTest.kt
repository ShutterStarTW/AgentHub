package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.project
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CopilotMcpProviderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should prefer local repository MCP and fall back to github MCP only when absent`() {
        val copilotHome = Files.createDirectories(temporaryDirectory.resolve(".copilot"))
        Files.writeString(
            copilotHome.resolve("mcp-config.json"),
            """{"mcpServers":{"global":{"type":"local","command":"npx","env":{"TOKEN":"secret"}}}}""",
        )
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"))
        Files.writeString(
            projectRoot.resolve(".mcp.json"),
            """{"local":{"command":"node","args":["--api-key=secret"]}}""",
        )
        val github = Files.createDirectories(projectRoot.resolve(".github"))
        Files.writeString(
            github.resolve("mcp.json"),
            """{"mcpServers":{"shared":{"type":"http","url":"https://example.test/mcp?token=secret","headers":{"X-Key":"${'$'}{SHARED_KEY}"}}}}""",
        )

        val provider = CopilotMcpProvider(copilotHome)
        val global = provider.discoverGlobal().single()
        val project = provider.discoverProject(project(projectRoot)).associateBy { it.name }

        assertEquals(McpScope.GLOBAL, global.scope)
        assertEquals(setOf("TOKEN"), global.environmentVariableNames)
        assertEquals(null, global.projectName)
        assertEquals(setOf("local"), project.keys)
        assertEquals(McpTransport.STDIO, project.getValue("local").transport)
        assertFalse(project.getValue("local").args.any { it.contains("secret") })
        assertTrue(project.values.all { it.projectName == "project" })

        Files.writeString(projectRoot.resolve(".mcp.json"), "not JSON")
        assertTrue(provider.discoverProject(project(projectRoot)).isEmpty())

        Files.delete(projectRoot.resolve(".mcp.json"))
        val shared = provider.discoverProject(project(projectRoot)).single()
        assertEquals("shared", shared.name)
        assertEquals(McpTransport.HTTP, shared.transport)
        assertEquals(setOf("SHARED_KEY"), shared.environmentVariableNames)
        assertFalse(shared.url.orEmpty().contains("secret"))
    }
}
