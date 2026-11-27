package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.ScanBudget
import com.shutterstar.agenthub.environment.discovery.PROJECT_WALK_EXCLUDED_DIRECTORY_NAMES
import com.shutterstar.agenthub.environment.discovery.ProjectFileInventory
import com.shutterstar.agenthub.environment.discovery.ProjectScanScope
import com.shutterstar.agenthub.environment.discovery.ProjectWalkStats
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal class SkillDirectoryScanner(
    private val fingerprint: SkillFingerprint = SkillFingerprint(),
    private val maximumScanEntries: Int = MAXIMUM_SCAN_ENTRIES,
) {
    fun discover(
        root: Path,
        agentId: String?,
        scope: SkillScope,
        shared: Boolean,
        projectName: String?,
        requireValidMetadata: Boolean = false,
        system: Boolean = false,
        excludeDirectoryNames: Set<String> = emptySet(),
    ): List<SkillSourceRecord> = discover(
        root,
        agentId,
        scope,
        shared,
        projectName,
        requireValidMetadata,
        newBudget(),
        system,
        excludeDirectoryNames,
    )

    fun discover(
        root: Path,
        agentId: String?,
        scope: SkillScope,
        shared: Boolean,
        projectName: String?,
        requireValidMetadata: Boolean,
        budget: ScanBudget,
        system: Boolean = false,
        excludeDirectoryNames: Set<String> = emptySet(),
        allowedRoot: Path? = null,
    ): List<SkillSourceRecord> {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return emptyList()
        }

        val skillDirectories = mutableListOf<Path>()
        runCatching {
            Files.walkFileTree(
                root,
                emptySet(),
                MAXIMUM_SCAN_DEPTH,
                object : SimpleFileVisitor<Path>() {
                    // An entry that cannot be inspected (a Linux symlink seen over the WSL share) is skipped, not fatal.
                    override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult = FileVisitResult.CONTINUE

                    override fun preVisitDirectory(
                        directory: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        if (!budget.consume()) return FileVisitResult.TERMINATE
                        if (allowedRoot != null && !isWithinRoot(directory, allowedRoot)) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (directory != root && directory.fileName.toString() in excludeDirectoryNames) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (directory != root && directory.fileName.toString().startsWith('.')) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (Files.isRegularFile(directory.resolve(SKILL_FILE_NAME), LinkOption.NOFOLLOW_LINKS)) {
                            skillDirectories.add(directory)
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        if (!budget.consume()) return FileVisitResult.TERMINATE
                        if (allowedRoot != null && !isWithinRoot(file, allowedRoot)) return FileVisitResult.CONTINUE
                        if (
                            attributes.isSymbolicLink &&
                            Files.isDirectory(file) &&
                            Files.isRegularFile(file.resolve(SKILL_FILE_NAME), LinkOption.NOFOLLOW_LINKS)
                        ) {
                            skillDirectories.add(file)
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }.getOrElse { return emptyList() }

        return skillDirectories
            .sortedBy { it.toString().lowercase() }
            .mapNotNull { skillDirectory ->
                readSkill(skillDirectory, agentId, scope, shared, projectName, requireValidMetadata, system)
            }
    }

    private fun isWithinRoot(path: Path, root: Path): Boolean =
        runCatching { path.toRealPath().startsWith(root) }.getOrDefault(false)

    fun discoverNestedProjectSkills(
        projectRoot: Path,
        ownerDirectoryName: String,
        agentId: String?,
        shared: Boolean,
        projectName: String,
        requireValidMetadata: Boolean = false,
        budget: ScanBudget = newBudget(),
    ): List<SkillSourceRecord> {
        if (!Files.isDirectory(projectRoot, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val inventory = ProjectScanScope.inventory(
            projectRoot, ProjectWalkStats.SKILL, MAXIMUM_SCAN_DEPTH, INVENTORY_ENTRY_BUDGET,
            countExcluded = true, recordExcluded = true,
        )
        if (inventory != null) {
            replayNestedProjectSkills(
                inventory, projectRoot, ownerDirectoryName, agentId, shared, projectName, requireValidMetadata, budget,
            )?.let { return it }
        }
        val records = mutableListOf<SkillSourceRecord>()
        val budgetBefore = budget.remaining()
        runCatching {
            Files.walkFileTree(
                projectRoot,
                emptySet(),
                MAXIMUM_SCAN_DEPTH,
                object : SimpleFileVisitor<Path>() {
                    // An entry that cannot be inspected (a Linux symlink seen over the WSL share) is skipped, not fatal.
                    override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult = FileVisitResult.CONTINUE

                    override fun preVisitDirectory(
                        directory: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        if (!budget.consume()) return FileVisitResult.TERMINATE
                        if (
                            directory != projectRoot &&
                            directory.fileName.toString().lowercase() in EXCLUDED_PROJECT_DIRECTORIES
                        ) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (
                            directory != projectRoot &&
                            directory.fileName.toString().startsWith('.') &&
                            !directory.fileName.toString().equals(ownerDirectoryName, ignoreCase = true)
                        ) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (
                            directory.fileName?.toString().equals(SKILLS_DIRECTORY, ignoreCase = true) &&
                            directory.parent?.fileName?.toString().equals(ownerDirectoryName, ignoreCase = true)
                        ) {
                            records += discover(
                                root = directory,
                                agentId = agentId,
                                scope = SkillScope.PROJECT,
                                shared = shared,
                                projectName = projectName,
                                requireValidMetadata = requireValidMetadata,
                                budget = budget,
                            )
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult =
                        if (!budget.consume()) {
                            FileVisitResult.TERMINATE
                        } else {
                            FileVisitResult.CONTINUE
                        }
                },
            )
        }.getOrElse { return emptyList() }
        ProjectWalkStats.record(ProjectWalkStats.SKILL, budgetBefore - budget.remaining())

        return records
            .distinctBy { it.path }
            .sortedBy { it.path.lowercase() }
    }

    /**
     * The same traversal as the walk in [discoverNestedProjectSkills], replayed over the shared inventory of the
     * project: the budget is used up entry by entry in the same order, a skipped directory skips its subtree, and the
     * skills directory of the owner is read as before. Null when the inventory was cut short and the replay would run
     * past its end - the caller then walks the tree itself.
     */
    private fun replayNestedProjectSkills(
        inventory: ProjectFileInventory,
        projectRoot: Path,
        ownerDirectoryName: String,
        agentId: String?,
        shared: Boolean,
        projectName: String,
        requireValidMetadata: Boolean,
        budget: ScanBudget,
    ): List<SkillSourceRecord>? {
        val records = mutableListOf<SkillSourceRecord>()
        var skipBelowDepth: Int? = null
        var terminated = false
        for (entry in inventory.entries) {
            val skipping = skipBelowDepth
            if (skipping != null) {
                if (entry.depth > skipping) continue
                skipBelowDepth = null
            }
            if (!budget.consume()) {
                terminated = true
                break
            }
            if (!entry.isDirectory) continue
            val name = entry.path.fileName?.toString()
            val isRoot = entry.path == projectRoot
            when {
                entry.excluded -> skipBelowDepth = entry.depth
                !isRoot && name != null && name.startsWith('.') && !name.equals(ownerDirectoryName, ignoreCase = true) ->
                    skipBelowDepth = entry.depth
                name.equals(SKILLS_DIRECTORY, ignoreCase = true) &&
                    entry.path.parent?.fileName?.toString().equals(ownerDirectoryName, ignoreCase = true) -> {
                    records += discover(
                        root = entry.path,
                        agentId = agentId,
                        scope = SkillScope.PROJECT,
                        shared = shared,
                        projectName = projectName,
                        requireValidMetadata = requireValidMetadata,
                        budget = budget,
                    )
                    skipBelowDepth = entry.depth
                }
            }
        }
        if (!terminated && inventory.truncated) return null
        return records
            .distinctBy { it.path }
            .sortedBy { it.path.lowercase() }
    }

    fun newBudget(): ScanBudget = ScanBudget(maximumScanEntries)

    private fun readSkill(
        skillDirectory: Path,
        agentId: String?,
        scope: SkillScope,
        shared: Boolean,
        projectName: String?,
        requireValidMetadata: Boolean,
        system: Boolean = false,
    ): SkillSourceRecord? {
        val skillFile = skillDirectory.resolve(SKILL_FILE_NAME)
        if (!Files.isRegularFile(skillFile, LinkOption.NOFOLLOW_LINKS)) {
            return null
        }

        val contentFingerprint = fingerprint.calculate(skillDirectory) ?: return null
        val metadata = readMetadata(skillFile)
        val directoryName = skillDirectory.fileName?.toString()?.trim().orEmpty()
        if (requireValidMetadata && !isValidMetadata(metadata, directoryName)) return null
        val skillName = metadata.name ?: directoryName
        if (skillName.isBlank()) {
            return null
        }

        return SkillSourceRecord(
            name = skillName,
            path = skillDirectory.toAbsolutePath().normalize().toString(),
            description = metadata.description,
            agentId = agentId,
            scope = scope,
            shared = shared,
            fingerprint = contentFingerprint,
            displayTitle = metadata.displayTitle,
            projectName = projectName,
            system = system,
        )
    }

    private fun isValidMetadata(metadata: SkillMetadata, directoryName: String): Boolean =
        metadata.name == directoryName &&
            metadata.name.matches(SKILL_NAME_PATTERN) &&
            !metadata.description.isNullOrBlank()

    private fun readMetadata(skillFile: Path): SkillMetadata = runCatching {
        Files.newInputStream(skillFile).use { input ->
            val bytes = input.readNBytes(MAXIMUM_METADATA_BYTES)
            SkillMetadataParser.parse(String(bytes, StandardCharsets.UTF_8))
        }
    }.getOrDefault(SkillMetadata(name = null, description = null))

    private companion object {
        const val SKILL_FILE_NAME = "SKILL.md"
        const val SKILLS_DIRECTORY = "skills"
        const val MAXIMUM_METADATA_BYTES = 128 * 1024
        const val MAXIMUM_SCAN_DEPTH = 12
        const val MAXIMUM_SCAN_ENTRIES = 20_000

        /** The shared project inventory lists more than one owner's walk visits: owners skip other dot directories. */
        const val INVENTORY_ENTRY_BUDGET = 4 * MAXIMUM_SCAN_ENTRIES
        val SKILL_NAME_PATTERN = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        val EXCLUDED_PROJECT_DIRECTORIES = PROJECT_WALK_EXCLUDED_DIRECTORY_NAMES
    }
}
