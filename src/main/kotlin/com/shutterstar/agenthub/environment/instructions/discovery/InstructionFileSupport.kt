package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.ScanBudget
import com.shutterstar.agenthub.environment.discovery.PROJECT_WALK_EXCLUDED_DIRECTORY_NAMES
import com.shutterstar.agenthub.environment.discovery.ProjectScanScope
import com.shutterstar.agenthub.environment.discovery.ProjectWalkStats
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal object InstructionFileSupport {
    private val excludedDirectoryNames = PROJECT_WALK_EXCLUDED_DIRECTORY_NAMES

    fun projectRoot(project: DiscoveredProject): Path? = ProjectPathResolver.resolveExistingRoot(project)

    fun source(
        path: Path,
        scope: InstructionScope,
        agentId: String,
        type: InstructionType,
        projectName: String? = null,
        note: String? = null,
    ): InstructionSource? {
        if (!isNonEmptyRegularFile(path)) return null
        return InstructionSource(
            path = path.toAbsolutePath().normalize().toString(),
            scope = scope,
            agentIds = setOf(agentId),
            type = type,
            projectName = projectName,
            agentNotes = if (note == null) emptyMap() else mapOf(agentId to note),
        )
    }

    /** AGENTS.md-first agents (Kilo, MiMo): the first non-empty global AGENTS.md, else the Claude Code file. */
    fun globalAgentsOrClaude(globalAgentsFiles: List<Path>, claudeFile: Path, agentId: String): List<InstructionSource> {
        val agents = globalAgentsFiles.firstOrNull(::isNonEmptyRegularFile)
        val type = if (agents != null) InstructionType.AGENTS_MD else InstructionType.CLAUDE_MD
        return listOfNotNull(source(agents ?: claudeFile, InstructionScope.GLOBAL, agentId, type))
    }

    /** Every project AGENTS.md and CLAUDE.md; a CLAUDE.md whose sibling AGENTS.md wins ([agentsWins]) is listed with [shadowedNote]. */
    fun projectAgentsAndClaude(
        project: DiscoveredProject,
        agentId: String,
        shadowedNote: String,
        agentsWins: (Path) -> Boolean,
    ): List<InstructionSource> {
        val projectRoot = projectRoot(project) ?: return emptyList()
        return scan(projectRoot) { _, file ->
            val fileName = file.fileName.toString()
            fileName == "AGENTS.md" || fileName == "CLAUDE.md"
        }.mapNotNull { file ->
            val isAgents = file.fileName.toString() == "AGENTS.md"
            val shadowed = !isAgents && agentsWins(file.resolveSibling("AGENTS.md"))
            source(
                path = file,
                scope = InstructionScope.PROJECT,
                agentId = agentId,
                type = if (isAgents) InstructionType.AGENTS_MD else InstructionType.CLAUDE_MD,
                projectName = project.name,
                note = shadowedNote.takeIf { shadowed },
            )
        }
    }

    /**
     * The regular, non-empty files below [root] that [matcher] accepts. With the default [budget] and inside a
     * [ProjectScanScope] the tree is walked once for all providers; a custom budget always walks on its own.
     */
    fun scan(
        root: Path,
        budget: ScanBudget? = null,
        matcher: (root: Path, file: Path) -> Boolean,
    ): List<Path> {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        if (budget == null) {
            ProjectScanScope.inventory(root, ProjectWalkStats.INSTRUCTION, MAXIMUM_DEPTH, MAXIMUM_ENTRIES, countExcluded = false)?.let { inventory ->
                return inventory.entries
                    .filter { !it.isDirectory && it.isRegularFile && it.size > 0L && matcher(root, it.path) }
                    .map { it.path.toAbsolutePath().normalize() }
                    .distinct()
                    .sortedBy { it.toString().lowercase() }
            }
        }
        val walkBudget = budget ?: newBudget()
        val matches = mutableListOf<Path>()
        val budgetBefore = walkBudget.remaining()
        runCatching {
            Files.walkFileTree(
                root,
                emptySet(),
                MAXIMUM_DEPTH,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                        if (directory != root && directory.fileName.toString().lowercase() in excludedDirectoryNames) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return if (!walkBudget.consume()) {
                            FileVisitResult.TERMINATE
                        } else {
                            FileVisitResult.CONTINUE
                        }
                    }

                    // An entry that cannot be inspected (a Linux symlink seen over the WSL share) is skipped, not fatal.
                    override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult = FileVisitResult.CONTINUE

                    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                        if (!walkBudget.consume()) return FileVisitResult.TERMINATE
                        if (attributes.isRegularFile && attributes.size() > 0L && matcher(root, file)) {
                            matches.add(file.toAbsolutePath().normalize())
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
        ProjectWalkStats.record(ProjectWalkStats.INSTRUCTION, budgetBefore - walkBudget.remaining())
        return matches.distinct().sortedBy { it.toString().lowercase() }
    }

    private fun isWithinDirectory(file: Path, directoryName: String, childName: String): Boolean {
        val segments = file.iterator().asSequence().map { it.toString().lowercase() }.toList()
        return segments.windowed(2).any { (first, second) ->
            first == directoryName.lowercase() && second == childName.lowercase()
        }
    }

    fun isWithinDirectory(
        root: Path,
        file: Path,
        directoryName: String,
        childName: String,
    ): Boolean {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedFile = file.toAbsolutePath().normalize()
        if (!normalizedFile.startsWith(normalizedRoot)) return false
        return isWithinDirectory(normalizedRoot.relativize(normalizedFile), directoryName, childName)
    }

    private fun isNonEmptyRegularFile(path: Path): Boolean =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { Files.size(path) > 0L }.getOrDefault(false)

    private const val MAXIMUM_DEPTH = 12
    private const val MAXIMUM_ENTRIES = 20_000

    fun newBudget(): ScanBudget = ScanBudget(MAXIMUM_ENTRIES)
}
