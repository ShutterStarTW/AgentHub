package com.shutterstar.agenthub.environment

import com.shutterstar.agenthub.environment.config.discovery.PiConfigProvider
import com.shutterstar.agenthub.environment.instructions.discovery.PiInstructionProvider
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.environment.mcp.discovery.PiMcpProvider
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.environment.skills.discovery.PiSkillProvider
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

class PiEnvironmentProvidersTest {
    @TempDir
    lateinit var home: Path

    private fun relative(root: Path, file: String) = root.relativize(Path.of(file)).toString().replace('\\', '/')

    @Test
    fun `should discover global skills in the agent directory only`() {
        writeSkill(home.resolve(".pi/agent/skills/native"), "native")
        writeSkill(home.resolve(".agents/skills/shared"), "shared")
        writeSkill(home.resolve(".claude/skills/claude-user"), "claude-user")

        val records = PiSkillProvider(home).discoverGlobal()

        assertEquals(setOf("native"), records.map { it.name }.toSet())
        assertTrue(records.all { it.agentId == "pi" && it.scope == SkillScope.GLOBAL && !it.shared })
    }

    @Test
    fun `should discover project skills in dot pi including nested packages with the project name`() {
        val root = Files.createDirectories(home.resolve("project"))
        writeSkill(root.resolve(".pi/skills/own"), "own")
        writeSkill(root.resolve("packages/app/.pi/skills/nested"), "nested")
        writeSkill(root.resolve(".agents/skills/shared"), "shared")

        val records = PiSkillProvider(home).discoverProject(project(root))

        assertEquals(setOf("own", "nested"), records.map { it.name }.toSet())
        assertTrue(records.all { it.scope == SkillScope.PROJECT && it.projectName == "project" })
    }

    @Test
    fun `should read mcpServers from the agent directory and the project dot pi without secrets`() {
        writeFile(home.resolve(".pi/agent/mcp.json"), """{"mcpServers":{"files":{"command":"npx","args":["-y","tool"],"env":{"TOKEN":"secret"}}},"autoEnableCodemode":true}""")
        writeFile(home.resolve(".pi/agent/mcp-auth.json"), """{"mcpServers":{"leaked":{"command":"x"}}}""")
        val root = Files.createDirectories(home.resolve("project"))
        writeFile(root.resolve(".pi/mcp.json"), """{"mcpServers":{"remote":{"type":"http","url":"https://user:pass@example.test/mcp?key=secret","headers":{"Authorization":"Bearer ${'$'}{API_TOKEN}"}}}}""")
        writeFile(root.resolve(".mcp.json"), """{"mcpServers":{"other":{"command":"c"}}}""")

        val provider = PiMcpProvider(home)
        val global = provider.discoverGlobal()
        val project = provider.discoverProject(project(root))

        assertEquals(listOf("files"), global.map { it.name })
        assertEquals(setOf("TOKEN"), global.single().environmentVariableNames)
        assertEquals(McpScope.GLOBAL, global.single().scope)
        assertEquals(listOf("remote"), project.map { it.name })
        assertEquals(McpTransport.HTTP, project.single().transport)
        assertEquals(setOf("API_TOKEN"), project.single().environmentVariableNames)
        assertFalse(project.single().url.orEmpty().contains("pass"))
        assertEquals("project", project.single().projectName)
    }

    @Test
    fun `should list context and system prompt files and explain a replaced AGENTS md`() {
        writeFile(home.resolve(".pi/agent/AGENTS.md"), "Global")
        writeFile(home.resolve(".pi/agent/SYSTEM.md"), "System")
        writeFile(home.resolve(".pi/agent/APPEND_SYSTEM.md"), "Append")
        writeFile(home.resolve(".pi/agent/settings.json"), "{}")
        val root = Files.createDirectories(home.resolve("project"))
        writeFile(root.resolve("AGENTS.md"), "Standalone")
        writeFile(root.resolve("AGENTS.override.md"), "Personal")
        writeFile(root.resolve("pkg/CLAUDE.md"), "Nested claude")
        writeFile(root.resolve(".pi/SYSTEM.md"), "Project system")
        writeFile(root.resolve(".claude/AGENTS.md"), "Other tool")

        val provider = PiInstructionProvider(home)
        val global = provider.discoverGlobal()
        val project = provider.discoverProject(project(root))

        assertEquals(setOf("AGENTS.md", "SYSTEM.md", "APPEND_SYSTEM.md"), global.map { Path.of(it.path).fileName.toString() }.toSet())
        assertTrue(global.all { it.scope == InstructionScope.GLOBAL && it.agentIds == setOf("pi") })
        assertEquals(
            setOf("AGENTS.md", "AGENTS.override.md", "pkg/CLAUDE.md", ".pi/SYSTEM.md"),
            project.map { relative(root, it.path) }.toSet(),
        )
        assertNotNull(project.single { it.path.endsWith("AGENTS.md") && !it.path.endsWith("override.md") }.agentNotes["pi"])
        assertNull(project.single { it.path.endsWith("override.md") }.agentNotes["pi"])
        assertEquals(InstructionType.CLAUDE_MD, project.single { it.path.endsWith("CLAUDE.md") }.type)
        assertEquals(InstructionType.AGENT_SPECIFIC, project.single { it.path.endsWith("SYSTEM.md") }.type)
    }

    @Test
    fun `should inventory settings json of the agent directory and the project only`() {
        writeFile(home.resolve(".pi/agent/settings.json"), """{"defaultProvider":"x"}""")
        writeFile(home.resolve(".pi/agent/auth.json"), """{"token":"secret"}""")
        writeFile(home.resolve(".pi/agent/trust.json"), "{}")
        val root = Files.createDirectories(home.resolve("project"))
        writeFile(root.resolve(".pi/settings.json"), "{}")

        val provider = PiConfigProvider(home)

        assertEquals(listOf("settings.json"), provider.discoverGlobal().map { Path.of(it.path).fileName.toString() })
        assertEquals("project", provider.discoverProject(project(root)).single().projectName)
    }
}
