package com.shutterstar.agenthub

import com.shutterstar.agenthub.projects.resolve.ProcessCommandRunner
import java.nio.file.Files
import java.nio.file.Path

/** Whether the `sqlite3` command line the SQLite-backed providers use is installed (the real-database tests skip without it). */
internal fun sqliteAvailable(): Boolean = ProcessCommandRunner.run(listOf("sqlite3", "--version"), 5_000) != null

/** Creates a SQLite database file from [sql] with the `sqlite3` CLI; fails the test when the script does not run cleanly. */
internal fun createSqliteDatabase(directory: Path, name: String, sql: String): Path {
    Files.createDirectories(directory)
    val database = directory.resolve(name)
    val script = Files.writeString(directory.resolve("$name.sql"), sql.trimIndent())
    val scriptPath = script.toString().replace('\\', '/').replace("'", "''")
    val output = ProcessCommandRunner.run(listOf("sqlite3", database.toString(), ".read '$scriptPath'"), 10_000)
    check(output != null && output.isBlank()) { "sqlite3 fixture script failed: $output" }
    return database
}

/** [value] as a SQL string literal. */
internal fun sql(value: String): String = "'" + value.replace("'", "''") + "'"
