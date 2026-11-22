package com.shutterstar.agenthub.usage.cleanup

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Claude Code sessions that hold nothing but the `/usage` command, left behind by older AgentHub versions
 * (`claude -p "/usage"` wrote a transcript on every refresh). Pure JDK so it can be tested without the IDE.
 */
internal object UsageProbeSessions {
    private const val MAX_FILE_BYTES = 32L * 1024
    private const val MAX_PROJECT_DIRECTORIES = 20_000
    private const val MAX_FILES = 50_000
    private const val USAGE_COMMAND = "<command-name>/usage</command-name>"
    private val ASSISTANT_LINE = Regex(""""type"\s*:\s*"assistant"""")
    private val USER_LINE = Regex(""""type"\s*:\s*"user"""")

    /** The transcripts under [projectsDirectory] (`~/.claude/projects`) that are provably only a `/usage` probe. */
    fun find(projectsDirectory: Path): List<Path> {
        if (!Files.isDirectory(projectsDirectory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val found = mutableListOf<Path>()
        var scanned = 0
        try {
            Files.list(projectsDirectory).use { directories ->
                directories.limit(MAX_PROJECT_DIRECTORIES.toLong()).forEach { directory ->
                    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return@forEach
                    Files.list(directory).use { files ->
                        files.forEach { file ->
                            if (scanned++ >= MAX_FILES) return@forEach
                            if (isProbe(file)) found.add(file)
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // Unreadable store: report what was found so far, never fail the startup.
        }
        return found
    }

    /** A small `.jsonl` whose only user lines are the local-command caveat and `/usage`, with no assistant reply. */
    fun isProbe(file: Path): Boolean {
        if (!file.fileName.toString().endsWith(".jsonl", ignoreCase = true)) return false
        return try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE_BYTES) return false
            val lines = Files.readAllLines(file)
            lines.any { USAGE_COMMAND in it } &&
                lines.none { ASSISTANT_LINE.containsMatchIn(it) } &&
                lines.filter { USER_LINE.containsMatchIn(it) }
                    .all { "local-command-caveat" in it || USAGE_COMMAND in it }
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    /** Deletes the files that are still probes at deletion time; returns how many were removed. */
    fun delete(files: List<Path>): Int = files.count { file ->
        try {
            isProbe(file) && Files.deleteIfExists(file)
        } catch (_: IOException) {
            false
        }
    }
}
