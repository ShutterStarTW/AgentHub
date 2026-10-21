package com.shutterstar.agenthub.projects.model

import com.shutterstar.agenthub.AgentRuntime
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

object ProjectPathResolver {
    fun resolveExistingRoot(project: DiscoveredProject): Path? =
        listOfNotNull(project.path, project.gitRoot, project.identity.canonicalPath)
            .distinct()
            .firstNotNullOfOrNull(::toExistingDirectory)

    /**
     * True when the project has recorded locations and none of them is a directory any more. A location that cannot be
     * mapped to this machine (a WSL path while the distro is unreachable) makes the answer unknown, so not "missing".
     */
    fun isMissing(paths: List<String?>): Boolean {
        val candidates = paths.filterNotNull().filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) return false
        return candidates.all { raw ->
            val hostPath = AgentRuntime.toHostPath(raw) ?: return false
            val path = try {
                Path.of(hostPath).toAbsolutePath().normalize()
            } catch (_: InvalidPathException) {
                return false
            }
            !Files.isDirectory(path)
        }
    }

    private fun toExistingDirectory(raw: String): Path? {
        // In WSL mode a project recorded as `/home/me/proj` is opened through the distro share.
        val hostPath = AgentRuntime.toHostPath(raw) ?: return null
        val path = try {
            Path.of(hostPath).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
            return null
        }
        return path.takeIf { Files.isDirectory(it) }
    }
}
