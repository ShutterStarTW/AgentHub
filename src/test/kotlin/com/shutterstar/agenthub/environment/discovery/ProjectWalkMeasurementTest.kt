package com.shutterstar.agenthub.environment.discovery

import com.shutterstar.agenthub.environment.config.discovery.ConfigDiscoveryService
import com.shutterstar.agenthub.environment.instructions.discovery.InstructionDiscoveryService
import com.shutterstar.agenthub.environment.skills.discovery.SkillDirectoryScanner
import com.shutterstar.agenthub.environment.skills.discovery.SkillDiscoveryService
import com.shutterstar.agenthub.project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** How often one project-scoped discovery walks the same monorepo (see PROJECT_FILE_INVENTORY_PLAN.md). */
class ProjectWalkMeasurementTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `without a scope every provider walks the monorepo on its own`() {
        val project = project(monorepo(temporaryDirectory.resolve("mono")), "claude", "codex", "cursor", "kilo", "mimo")
        ProjectWalkStats.reset()

        InstructionDiscoveryService().discoverProjectRecordsWithWarnings(project)
        ConfigDiscoveryService().discoverProjectRecordsWithWarnings(project)
        SkillDiscoveryService().discoverProjectRecordsWithWarnings(project)

        val stats = ProjectWalkStats.snapshot()
        stats.toSortedMap().forEach { (family, count) -> println("WALKSTATS $family walks=${count.walks} entries=${count.entries}") }
        assertTrue((stats[ProjectWalkStats.INSTRUCTION]?.walks ?: 0) > 1)
        assertTrue((stats[ProjectWalkStats.CONFIG]?.walks ?: 0) > 1)
    }

    @Test
    fun `inside a scope the instruction and config providers share one walk each and find the same files`() {
        val root = monorepo(temporaryDirectory.resolve("mono"))
        writeSkill(root.resolve(".claude/skills/alpha"))
        val project = project(root, "claude", "codex", "cursor", "kilo", "mimo")
        val instructions = InstructionDiscoveryService()
        val configs = ConfigDiscoveryService()
        val skills = SkillDiscoveryService()
        val expectedInstructions = instructions.discoverProjectRecordsWithWarnings(project)
        val expectedConfigs = configs.discoverProjectRecordsWithWarnings(project)
        val expectedSkills = skills.discoverProjectRecordsWithWarnings(project)
        ProjectWalkStats.reset()

        val scoped = ProjectScanScope.run(root) {
            Triple(
                instructions.discoverProjectRecordsWithWarnings(project),
                configs.discoverProjectRecordsWithWarnings(project),
                skills.discoverProjectRecordsWithWarnings(project),
            )
        }

        val stats = ProjectWalkStats.snapshot()
        stats.toSortedMap().forEach { (family, count) -> println("WALKSTATS scoped $family walks=${count.walks} entries=${count.entries}") }
        assertEquals(1L, stats[ProjectWalkStats.INSTRUCTION]?.walks)
        assertEquals(1L, stats[ProjectWalkStats.CONFIG]?.walks)
        assertEquals(1L, stats[ProjectWalkStats.SKILL]?.walks)
        assertEquals(expectedInstructions, scoped.first)
        assertEquals(expectedConfigs, scoped.second)
        assertEquals(expectedSkills, scoped.third)
        assertTrue(scoped.first.first.size > 5)
        assertTrue(scoped.second.first.isNotEmpty())
        assertTrue(scoped.third.first.isNotEmpty())
    }

    @Test
    fun `the skill replay matches the own walk for every budget, skipping dot directories and excluded ones`() {
        val root = monorepo(temporaryDirectory.resolve("mono"))
        writeSkill(root.resolve(".claude/skills/alpha"))
        writeSkill(root.resolve("packages/pkg-3/.claude/skills/beta"))
        writeSkill(root.resolve(".other/.claude/skills/gamma"))
        writeSkill(root.resolve("node_modules/dep/.claude/skills/delta"))
        writeSkill(root.resolve("packages/pkg-29/docs/.claude/skills/epsilon"))

        listOf(3, 8, 40, 200, 700, 5_000).forEach { budgetSize ->
            val scanner = SkillDirectoryScanner(maximumScanEntries = budgetSize)
            fun scan() = scanner.discoverNestedProjectSkills(root, ".claude", "claude", false, "mono")
            val own = scan()
            val replayed = ProjectScanScope.run(root) { scan() }
            assertEquals(own.map { it.path to it.name }, replayed.map { it.path to it.name }, "budget $budgetSize")
        }
        val full = SkillDirectoryScanner().discoverNestedProjectSkills(root, ".claude", "claude", false, "mono")
        assertEquals(listOf("alpha", "beta", "epsilon"), full.map { it.name }.sorted())
    }

    @Test
    fun `a file created between two scoped discoveries is seen by the second one`() {
        val root = monorepo(temporaryDirectory.resolve("mono"))
        val project = project(root, "claude")
        val instructions = InstructionDiscoveryService()
        val before = ProjectScanScope.run(root) { instructions.discoverProjectRecordsWithWarnings(project) }.first.map { it.path }.toSet()

        Files.writeString(root.resolve("packages/pkg-1/AGENTS.md"), "new")
        val after = ProjectScanScope.run(root) { instructions.discoverProjectRecordsWithWarnings(project) }.first.map { it.path }.toSet()

        assertEquals(1, (after - before).size)
    }

    @Test
    fun `a walk interrupted while building the inventory is not shared`() {
        val root = monorepo(temporaryDirectory.resolve("mono"))
        val project = project(root, "claude")
        val instructions = InstructionDiscoveryService()
        val expected = instructions.discoverProjectRecordsWithWarnings(project)

        Thread.currentThread().interrupt()
        val scoped = try {
            ProjectScanScope.run(root) { instructions.discoverProjectRecordsWithWarnings(project) }
        } finally {
            Thread.interrupted()
        }

        assertEquals(expected, scoped)
    }

    private fun writeSkill(directory: Path) {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("SKILL.md"), "---\nname: ${directory.fileName}\ndescription: test\n---\nBody\n")
    }

    private fun monorepo(root: Path): Path {
        Files.createDirectories(root)
        Files.writeString(root.resolve("AGENTS.md"), "root instructions")
        Files.writeString(root.resolve("CLAUDE.md"), "root claude")
        Files.createDirectories(root.resolve(".claude")).resolve("settings.json").also { Files.writeString(it, "{}") }
        repeat(30) { packageIndex ->
            val packageDirectory = Files.createDirectories(root.resolve("packages/pkg-$packageIndex"))
            if (packageIndex % 5 == 0) Files.writeString(packageDirectory.resolve("AGENTS.md"), "package $packageIndex")
            listOf("src/main", "src/test", "docs").forEach { part ->
                val directory = Files.createDirectories(packageDirectory.resolve(part))
                repeat(10) { fileIndex -> Files.writeString(directory.resolve("File$fileIndex.kt"), "x") }
            }
        }
        val dependencies = Files.createDirectories(root.resolve("node_modules/dep"))
        repeat(200) { Files.writeString(dependencies.resolve("file$it.js"), "x") }
        return root
    }
}
