package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.projects.resolve.ProcessCommandRunner
import java.nio.file.Path
import java.time.Instant

/**
 * Read-only access to an agent's SQLite database through the `sqlite3` command line (the JVM has no bundled driver):
 * `-readonly`, tab-separated rows, free text always `hex()`-encoded in the query so tabs and newlines in titles and
 * prompts cannot break the row format. Null from [query] means sqlite3 is missing, timed out or the query failed.
 */
internal object SqliteCli {
    fun query(
        database: Path,
        sql: String,
        timeoutMillis: Long,
        commandRunner: (List<String>, Long) -> String? = ProcessCommandRunner::run,
    ): String? = commandRunner(
        listOf("sqlite3", "-readonly", "-batch", "-noheader", "-separator", "\t", database.toAbsolutePath().normalize().toString(), sql),
        timeoutMillis,
    )

    fun decodeHex(value: String): String? {
        if (value.length % 2 != 0) return null
        val bytes = ByteArray(value.length / 2)
        for (index in bytes.indices) {
            val byte = value.substring(index * 2, index * 2 + 2).toIntOrNull(16) ?: return null
            bytes[index] = byte.toByte()
        }
        return bytes.toString(Charsets.UTF_8)
    }

    fun epochMillis(value: String): Instant? = value.trim().toLongOrNull()?.let { runCatching { Instant.ofEpochMilli(it) }.getOrNull() }
}
