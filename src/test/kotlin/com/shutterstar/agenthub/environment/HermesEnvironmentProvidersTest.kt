package com.shutterstar.agenthub.environment

import com.shutterstar.agenthub.environment.config.discovery.HermesConfigProvider
import com.shutterstar.agenthub.environment.instructions.discovery.HermesInstructionProvider
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.environment.mcp.discovery.HermesMcpProvider
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.environment.skills.discovery.HermesSkillProvider
import com.shutterstar.agenthub.environment.skills.model.SkillScope
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

class HermesEnvironmentProvidersTest {
    @TempDir
    lateinit var home: Path

    private fun relative(root: Path, file: String) = root.relativize(Path.of(file)).toString().replace('\\', '/')

    @Test
    fun `should discover global skills in category folders and skip hub state`() {
        writeSkill(home.resolve(".hermes/skills/devops/deploy"), "deploy")
        writeSkill(home.resolve(".hermes/skills/own"), "own")
        writeSkill(home.resolve(".hermes/skills/.hub/quarantine/bad"), "bad")
        writeSkill(home.resolve(".agents/skills/shared"), "shared")

        val records = HermesSkillProvider(home, windowsRuntime = false).discoverGlobal()

        assertEquals(setOf("deploy", "own"), records.map { it.name }.toSet())
        assertTrue(records.all { it.agentId == "hermes" && it.scope == SkillScope.GLOBAL && !it.shared })
    }

    @Test
    fun `should use the LOCALAPPDATA layout on Windows`() {
        writeSkill(home.resolve("AppData/Local/hermes/skills/win"), "win")

        val records = HermesSkillProvider(home, windowsRuntime = true).discoverGlobal()

        assertEquals(listOf("win"), records.map { it.name })
    }

    @Test
    fun `should discover project skills in dot hermes only`() {
        val root = Files.createDirectories(home.resolve("project"))
        writeSkill(root.resolve(".hermes/skills/own"), "own")
        writeSkill(root.resolve(".agents/skills/shared"), "shared")

        val records = HermesSkillProvider(home, windowsRuntime = false).discoverProject(project(root))

        assertEquals(listOf("own"), records.map { it.name })
        assertEquals("project", records.single().projectName)
    }

    @Test
    fun `should read mcp_servers from config yaml keeping only environment variable names`() {
        writeFile(
            home.resolve(".hermes/config.yaml"),
            """
            model: some-model   # not an mcp setting
            mcp_servers:
              github:
                command: "npx"
                args: ["-y", "@modelcontextprotocol/server-github"]
                env:
                  GITHUB_PERSONAL_ACCESS_TOKEN: "ghp_secret"
              remote:
                url: "https://user:pass@mcp.example.test/mcp?key=secret"
                headers:
                  Authorization: "Bearer ${'$'}{REMOTE_TOKEN}"
              legacy:
                url: https://sse.example.test/sse
                transport: sse
              block-args:
                command: uvx
                args:
                  - mcp-server-fetch
                  - --flag
              off:
                command: echo
                enabled: false
              flow: {command: node, args: [server.js]}
            terminal:
              backend: local
            """.trimIndent(),
        )

        val servers = HermesMcpProvider(home, windowsRuntime = false).discoverGlobal().associateBy { it.name }

        assertEquals(setOf("github", "remote", "legacy", "block-args", "flow"), servers.keys)
        assertEquals(McpTransport.STDIO, servers.getValue("github").transport)
        assertEquals("npx", servers.getValue("github").command)
        assertEquals(listOf("-y", "@modelcontextprotocol/server-github"), servers.getValue("github").args)
        assertEquals(setOf("GITHUB_PERSONAL_ACCESS_TOKEN"), servers.getValue("github").environmentVariableNames)
        assertEquals(McpTransport.HTTP, servers.getValue("remote").transport)
        assertEquals(setOf("REMOTE_TOKEN"), servers.getValue("remote").environmentVariableNames)
        assertFalse(servers.getValue("remote").url.orEmpty().contains("pass"))
        assertEquals(McpTransport.SSE, servers.getValue("legacy").transport)
        assertEquals(listOf("mcp-server-fetch", "--flag"), servers.getValue("block-args").args)
        assertEquals(listOf("server.js"), servers.getValue("flow").args)
        assertTrue(servers.values.all { it.scope == McpScope.GLOBAL })
    }

    @Test
    fun `should return no servers without an mcp_servers mapping or a config file`() {
        assertTrue(HermesMcpProvider(home, windowsRuntime = false).discoverGlobal().isEmpty())
        writeFile(home.resolve(".hermes/config.yaml"), "model: x\nterminal:\n  backend: local\n")
        assertTrue(HermesMcpProvider(home, windowsRuntime = false).discoverGlobal().isEmpty())
        writeFile(home.resolve(".hermes/config.yaml"), "mcp_servers: {}\n")
        assertTrue(HermesMcpProvider(home, windowsRuntime = false).discoverGlobal().isEmpty())
        writeFile(home.resolve(".hermes/config.yaml"), "mcp_servers:\n  broken: [unterminated\n  ok:\n    command: x\n")
        assertEquals(listOf("ok"), HermesMcpProvider(home, windowsRuntime = false).discoverGlobal().map { it.name })
    }

    @Test
    fun `should list SOUL md globally and only the winning project context file without a note`() {
        writeFile(home.resolve(".hermes/SOUL.md"), "Soul")
        writeFile(home.resolve(".hermes/memories/MEMORY.md"), "Memory")
        val root = Files.createDirectories(home.resolve("project"))
        writeFile(root.resolve(".hermes.md"), "Hermes")
        writeFile(root.resolve("AGENTS.md"), "Agents")
        writeFile(root.resolve("CLAUDE.md"), "Claude")
        writeFile(root.resolve(".cursorrules"), "Cursor")
        writeFile(root.resolve(".cursor/rules/style.mdc"), "Rule")
        writeFile(root.resolve("pkg/AGENTS.md"), "Nested")
        writeFile(root.resolve(".claude/AGENTS.md"), "Other tool")

        val provider = HermesInstructionProvider(home, windowsRuntime = false)
        val global = provider.discoverGlobal()
        val project = provider.discoverProject(project(root)).associateBy { relative(root, it.path) }

        assertEquals(listOf("SOUL.md"), global.map { Path.of(it.path).fileName.toString() })
        assertEquals(InstructionScope.GLOBAL, global.single().scope)
        assertEquals(setOf(".hermes.md", "AGENTS.md", "CLAUDE.md", ".cursorrules", ".cursor/rules/style.mdc", "pkg/AGENTS.md"), project.keys)
        assertNull(project.getValue(".hermes.md").agentNotes["hermes"])
        assertNotNull(project.getValue("AGENTS.md").agentNotes["hermes"])
        assertNotNull(project.getValue("CLAUDE.md").agentNotes["hermes"])
        assertNotNull(project.getValue(".cursorrules").agentNotes["hermes"])
        assertNotNull(project.getValue(".cursor/rules/style.mdc").agentNotes["hermes"])
        assertNull(project.getValue("pkg/AGENTS.md").agentNotes["hermes"])
        assertEquals(InstructionType.AGENT_SPECIFIC, project.getValue(".hermes.md").type)
        assertEquals(InstructionType.CURSOR_RULE, project.getValue(".cursorrules").type)
    }

    @Test
    fun `should not mark AGENTS md when it is the first project context file`() {
        val root = Files.createDirectories(home.resolve("project"))
        writeFile(root.resolve("AGENTS.md"), "Agents")

        val sources = HermesInstructionProvider(home, windowsRuntime = false).discoverProject(project(root))

        assertEquals(1, sources.size)
        assertTrue(sources.single().agentNotes.isEmpty())
    }

    @Test
    fun `should inventory config yaml but not env or auth`() {
        writeFile(home.resolve(".hermes/config.yaml"), "model: x\n")
        writeFile(home.resolve(".hermes/.env"), "OPENAI_API_KEY=secret")
        writeFile(home.resolve(".hermes/auth.json"), "{}")

        val names = HermesConfigProvider(home, windowsRuntime = false).discoverGlobal().map { Path.of(it.path).fileName.toString() }

        assertEquals(listOf("config.yaml"), names)
    }
}
