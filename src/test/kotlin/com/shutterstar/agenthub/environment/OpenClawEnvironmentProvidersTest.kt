package com.shutterstar.agenthub.environment

import com.shutterstar.agenthub.environment.config.discovery.OpenClawConfigProvider
import com.shutterstar.agenthub.environment.instructions.discovery.OpenClawInstructionProvider
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.environment.mcp.discovery.OpenClawMcpProvider
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.environment.skills.discovery.OpenClawSkillProvider
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.target.OpenClawSkillSyncTarget
import com.shutterstar.agenthub.json
import com.shutterstar.agenthub.project
import com.shutterstar.agenthub.writeFile
import com.shutterstar.agenthub.writeSkill
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OpenClawEnvironmentProvidersTest {
    @TempDir
    lateinit var home: Path

    private fun state(): Path = home.resolve(".openclaw")

    private fun workspace(): Path = Files.createDirectories(state().resolve("workspace"))

    @Test
    fun `should discover managed and workshop skills globally and workspace skills only in the workspace project`() {
        writeSkill(state().resolve("skills/managed"), "managed")
        writeSkill(state().resolve("agents/main/agent/workshop-skills/learned"), "learned")
        writeSkill(home.resolve(".agents/skills/shared"), "shared")
        val workspace = workspace()
        writeSkill(workspace.resolve("skills/own"), "own")
        writeSkill(workspace.resolve(".agents/skills/shared-project"), "shared-project")
        val other = Files.createDirectories(home.resolve("other"))
        writeSkill(other.resolve("skills/not-openclaw"), "not-openclaw")

        val provider = OpenClawSkillProvider(home)

        assertEquals(setOf("managed", "learned"), provider.discoverGlobal().map { it.name }.toSet())
        assertTrue(provider.discoverGlobal().all { it.agentId == "openclaw" && it.scope == SkillScope.GLOBAL })
        val inWorkspace = provider.discoverProject(project(workspace))
        assertEquals(listOf("own"), inWorkspace.map { it.name })
        assertEquals(SkillScope.PROJECT, inWorkspace.single().scope)
        assertTrue(provider.discoverProject(project(other)).isEmpty())
    }

    @Test
    fun `should follow a configured workspace for skills instructions and the sync target`() {
        val configured = Files.createDirectories(home.resolve("claw-ws"))
        writeFile(state().resolve("openclaw.json"), """{"agents":{"entries":{"main":{"workspace":${json(configured.toString())}}}}}""")
        writeSkill(configured.resolve("skills/own"), "own")
        writeFile(configured.resolve("AGENTS.md"), "Rules")

        assertEquals(listOf("own"), OpenClawSkillProvider(home).discoverProject(project(configured)).map { it.name })
        assertEquals(1, OpenClawInstructionProvider(home).discoverProject(project(configured)).size)
        assertEquals(configured.resolve("skills"), OpenClawSkillSyncTarget(home).projectSkillDirectory(project(configured)))
        assertNull(OpenClawSkillSyncTarget(home).projectSkillDirectory(project(workspace())))
        assertEquals(state().resolve("skills"), OpenClawSkillSyncTarget(home).globalSkillDirectory())
        assertFalse(OpenClawSkillSyncTarget(home).supportsLinkedSkills())
    }

    @Test
    fun `should read mcp servers from openclaw json accepting comments and trailing commas`() {
        writeFile(
            state().resolve("openclaw.json"),
            """
            {
              // JSON5 style
              "mcp": {
                "servers": {
                  "files": {"command": "npx", "args": ["-y", "tool",], "env": {"TOKEN": "secret"}},
                  "stream": {"url": "https://user:pass@mcp.example.test/mcp", "transport": "streamable-http", "headers": {"Authorization": "Bearer ${'$'}{MCP_TOKEN}"}},
                  "legacy": {"url": "https://sse.example.test/sse"},
                  "off": {"command": "echo", "enabled": false},
                },
              },
              "agents": {"entries": {"main": {}}},
            }
            """.trimIndent(),
        )

        val servers = OpenClawMcpProvider(home).discoverGlobal().associateBy { it.name }

        assertEquals(setOf("files", "stream", "legacy"), servers.keys)
        assertEquals(McpTransport.STDIO, servers.getValue("files").transport)
        assertEquals(setOf("TOKEN"), servers.getValue("files").environmentVariableNames)
        assertEquals(McpTransport.HTTP, servers.getValue("stream").transport)
        assertEquals(setOf("MCP_TOKEN"), servers.getValue("stream").environmentVariableNames)
        assertFalse(servers.getValue("stream").url.orEmpty().contains("pass"))
        assertEquals(McpTransport.SSE, servers.getValue("legacy").transport)
        assertTrue(servers.values.all { it.scope == McpScope.GLOBAL })
        assertTrue(OpenClawMcpProvider(home).discoverProject(project(workspace())).isEmpty())
    }

    @Test
    fun `should return no servers without a config file or an mcp section`() {
        assertTrue(OpenClawMcpProvider(home).discoverGlobal().isEmpty())
        writeFile(state().resolve("openclaw.json"), """{"agents":{}}""")
        assertTrue(OpenClawMcpProvider(home).discoverGlobal().isEmpty())
    }

    @Test
    fun `should list the bootstrap files of the workspace with notes on conditional ones`() {
        val workspace = workspace()
        listOf("AGENTS.md", "SOUL.md", "USER.md", "IDENTITY.md", "MEMORY.md", "BOOT.md", "BOOTSTRAP.md", "HEARTBEAT.md", "TOOLS.md").forEach {
            writeFile(workspace.resolve(it), it)
        }
        writeFile(workspace.resolve("memory/2026-11-17.md"), "daily")
        val other = Files.createDirectories(home.resolve("other"))
        writeFile(other.resolve("AGENTS.md"), "Not a workspace")

        val provider = OpenClawInstructionProvider(home)
        val sources = provider.discoverProject(project(workspace)).associateBy { Path.of(it.path).fileName.toString() }

        assertEquals(setOf("AGENTS.md", "SOUL.md", "USER.md", "IDENTITY.md", "MEMORY.md", "BOOT.md", "BOOTSTRAP.md"), sources.keys)
        assertEquals(InstructionType.AGENTS_MD, sources.getValue("AGENTS.md").type)
        assertEquals(InstructionType.AGENT_SPECIFIC, sources.getValue("SOUL.md").type)
        assertNull(sources.getValue("SOUL.md").agentNotes["openclaw"])
        assertNotNull(sources.getValue("MEMORY.md").agentNotes["openclaw"])
        assertNotNull(sources.getValue("BOOT.md").agentNotes["openclaw"])
        assertNotNull(sources.getValue("BOOTSTRAP.md").agentNotes["openclaw"])
        assertTrue(provider.discoverGlobal().isEmpty())
        assertTrue(provider.discoverProject(project(other)).isEmpty())
    }

    @Test
    fun `should inventory openclaw json but not its backups`() {
        writeFile(state().resolve("openclaw.json"), "{}")
        writeFile(state().resolve("openclaw.json.bak"), "{}")
        writeFile(state().resolve("openclaw.json.last-good"), "{}")

        val sources = OpenClawConfigProvider(home).discoverGlobal()

        assertEquals(listOf("openclaw.json"), sources.map { Path.of(it.path).fileName.toString() })
    }
}
