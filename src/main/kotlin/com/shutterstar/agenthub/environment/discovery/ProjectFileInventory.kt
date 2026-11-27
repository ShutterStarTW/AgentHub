package com.shutterstar.agenthub.environment.discovery

import com.shutterstar.agenthub.ScanBudget
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

internal class InventoryEntry(
    val path: Path,
    val isDirectory: Boolean,
    val isRegularFile: Boolean,
    val size: Long,
    /** 0 for the walked root. */
    val depth: Int = 0,
    /** A directory of [PROJECT_WALK_EXCLUDED_DIRECTORY_NAMES]: listed (it used up a budget entry) but not entered. */
    val excluded: Boolean = false,
)

/**
 * One bounded walk of a project tree, in visit order. Built once per discovery by [ProjectScanScope] and filtered
 * by every provider that would otherwise walk the same root again.
 */
internal class ProjectFileInventory private constructor(
    val entries: List<InventoryEntry>,
    /** The walk stopped because the entry budget ran out, so entries after the last one are unknown. */
    val truncated: Boolean,
) {
    companion object {
        /**
         * Walks [root] exactly like the providers' own walkers did: the [PROJECT_WALK_EXCLUDED_DIRECTORY_NAMES] are
         * skipped, the walk stops after [entryBudget] directories and files, and an entry that cannot be inspected is
         * skipped. [countExcluded] says whether a skipped excluded directory still uses up one budget entry (the
         * config walker did, the instruction walker did not); with [recordExcluded] such a directory is also kept in
         * the inventory, flagged as excluded. Returns null when the walk was interrupted or failed,
         * so the caller falls back to its own walk.
         */
        fun walk(
            root: Path,
            family: String,
            maxDepth: Int,
            entryBudget: Int,
            countExcluded: Boolean,
            recordExcluded: Boolean = false,
        ): ProjectFileInventory? {
            val budget = ScanBudget(entryBudget)
            val entries = ArrayList<InventoryEntry>()
            val rootNames = root.nameCount
            var interrupted = false
            var truncated = false
            val completed = runCatching {
                Files.walkFileTree(
                    root,
                    emptySet(),
                    maxDepth,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                            if (Thread.currentThread().isInterrupted) {
                                interrupted = true
                                return FileVisitResult.TERMINATE
                            }
                            val excluded = directory != root &&
                                directory.fileName.toString().lowercase(Locale.ROOT) in PROJECT_WALK_EXCLUDED_DIRECTORY_NAMES
                            if (excluded && !countExcluded) return FileVisitResult.SKIP_SUBTREE
                            if (!budget.consume()) {
                                truncated = true
                                return FileVisitResult.TERMINATE
                            }
                            val depth = directory.nameCount - rootNames
                            if (excluded) {
                                if (recordExcluded) entries += InventoryEntry(directory, true, false, 0L, depth, excluded = true)
                                return FileVisitResult.SKIP_SUBTREE
                            }
                            entries += InventoryEntry(directory, isDirectory = true, isRegularFile = false, size = 0L, depth = depth)
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                            if (Thread.currentThread().isInterrupted) {
                                interrupted = true
                                return FileVisitResult.TERMINATE
                            }
                            if (!budget.consume()) {
                                truncated = true
                                return FileVisitResult.TERMINATE
                            }
                            entries += InventoryEntry(
                                file,
                                isDirectory = false,
                                isRegularFile = attributes.isRegularFile,
                                size = attributes.size(),
                                depth = file.nameCount - rootNames,
                            )
                            return FileVisitResult.CONTINUE
                        }

                        // An entry that cannot be inspected (a Linux symlink seen over the WSL share) is skipped, not fatal.
                        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                    },
                )
            }.isSuccess
            if (!completed || interrupted) return null
            ProjectWalkStats.record(family, entryBudget - budget.remaining())
            return ProjectFileInventory(entries, truncated)
        }
    }
}

/**
 * The inventories of one project discovery. The providers of the Skill / MCP / Instruction / Config services run
 * one after another on the discovering thread, so a thread-bound scope reaches all of them without changing their
 * interfaces. Outside a scope (tests, direct provider calls) [inventory] is null and every caller walks on its own;
 * nothing outlives the discovery, so a file created between two discoveries is always seen.
 */
internal object ProjectScanScope {
    private data class Key(
        val root: Path,
        val family: String,
        val maxDepth: Int,
        val entryBudget: Int,
        val countExcluded: Boolean,
        val recordExcluded: Boolean,
    )

    private class Scope(val root: Path) {
        val inventories = HashMap<Key, ProjectFileInventory?>()
    }

    private val current = ThreadLocal<Scope?>()

    fun <T> run(root: Path?, block: () -> T): T {
        if (root == null) return block()
        val previous = current.get()
        current.set(Scope(root.toAbsolutePath().normalize()))
        try {
            return block()
        } finally {
            current.set(previous)
        }
    }

    /** The shared inventory of the scoped project root, or null: no scope, another root, or a failed or interrupted walk. */
    fun inventory(
        root: Path,
        family: String,
        maxDepth: Int,
        entryBudget: Int,
        countExcluded: Boolean,
        recordExcluded: Boolean = false,
    ): ProjectFileInventory? {
        val scope = current.get() ?: return null
        if (root.toAbsolutePath().normalize() != scope.root) return null
        val key = Key(root, family, maxDepth, entryBudget, countExcluded, recordExcluded)
        if (key in scope.inventories) return scope.inventories[key]
        val built = ProjectFileInventory.walk(root, family, maxDepth, entryBudget, countExcluded, recordExcluded)
        // A failed walk is remembered too: the callers fall back to their own walk, which then fails the same way once each.
        scope.inventories[key] = built
        return built
    }
}
