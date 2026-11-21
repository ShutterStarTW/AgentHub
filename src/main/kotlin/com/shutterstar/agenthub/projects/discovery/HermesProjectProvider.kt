package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.environment.discovery.HermesHomeSupport
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.latest
import com.shutterstar.agenthub.projects.model.RawAgentProject
import com.shutterstar.agenthub.projects.resolve.ProcessCommandRunner
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.util.logging.Logger

data class HermesSessionRecord(
    val id: String,
    val directory: String,
    val title: String?,
    val startedAt: Instant?,
    val updatedAt: Instant?,
    val archived: Boolean = false,
    val userMessageCount: Int? = null,
    val firstMessage: String? = null,
)

/**
 * Hermes Agent sessions: the `sessions` table of `<HERMES_HOME>/state.db` (and of `profiles/<name>/state.db`). Only a
 * session that recorded a working directory (`cwd`, else `git_repo_root`) belongs to a project - gateway chats from
 * messaging platforms have none and are skipped. The database is read-only through the `sqlite3` CLI, never migrated.
 */
class HermesProjectProvider(
    private val homeDirectory: Path = HermesHomeSupport.homeDirectory(AgentRuntime.userHome()),
    private val databaseReader: (Path) -> List<HermesSessionRecord> = HermesSqliteReader()::readSessions,
) : AgentProjectProvider {
    override val agentId: String = AGENT_ID

    override fun isAvailable(): Boolean = databaseFiles().isNotEmpty()

    override fun discover(): List<RawAgentProject> {
        val databases = databaseFiles()
        if (databases.isEmpty()) return emptyList()
        val sessions = mutableListOf<RawAgentProject>()
        var failure: Throwable? = null
        databases.forEach { database ->
            runCatching { databaseReader(database) }
                .onSuccess { records -> sessions += records.map { it.toRawProject(database) } }
                .onFailure { error -> failure = error }
        }
        if (sessions.isEmpty() && failure != null) throw IOException("Hermes session database could not be read", failure)
        if (failure != null) LOG.fine("[ProjectDiscovery] Hermes: a database was skipped")
        return LocalSessionSupport.deduplicate(sessions)
    }

    private fun databaseFiles(): List<Path> {
        val profiles = LocalSessionSupport.listDirectories(homeDirectory.resolve(PROFILES_DIRECTORY), MAX_PROFILES)
        return (listOf(homeDirectory) + profiles)
            .map { it.resolve(DATABASE_NAME) }
            .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
    }

    private fun HermesSessionRecord.toRawProject(database: Path) = RawAgentProject(
        agentId = agentId,
        rawProjectPath = directory,
        sessionId = id,
        startedAt = startedAt,
        updatedAt = latest(startedAt, updatedAt),
        sourcePath = database.toAbsolutePath().normalize().toString(),
        metadata = buildMap {
            title?.let { put("title", it) }
            if (archived) put("archivedAt", (updatedAt ?: startedAt ?: Instant.EPOCH).toString())
            userMessageCount?.let { count ->
                put(UserMessageTally.MESSAGE_COUNT_KEY, count.toString())
                MessageContentExtractor.titleText(firstMessage)?.let { put(UserMessageTally.FIRST_MESSAGE_KEY, it) }
            }
        },
    )

    private companion object {
        const val AGENT_ID = "hermes"
        const val DATABASE_NAME = "state.db"
        const val PROFILES_DIRECTORY = "profiles"
        const val MAX_PROFILES = 50
        val LOG: Logger = Logger.getLogger(HermesProjectProvider::class.java.name)
    }
}

class HermesSqliteReader(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val commandRunner: (List<String>, Long) -> String? = ProcessCommandRunner::run,
) {
    fun readSessions(database: Path): List<HermesSessionRecord> {
        val output = query(database, SESSION_QUERY) ?: throw IOException("sqlite3 is unavailable or could not read the database")
        val sessions = output.lineSequence().mapNotNull(::parseRow).toList()
        val statsOutput = query(database, USER_MESSAGE_QUERY)
        val stats = statsOutput?.lineSequence()?.mapNotNull(::parseStatsRow)?.toMap().orEmpty()
        return sessions.map { session ->
            val user = stats[session.id]
            session.copy(userMessageCount = user?.first ?: if (statsOutput == null) null else 0, firstMessage = user?.second)
        }
    }

    private fun query(database: Path, sql: String): String? = SqliteCli.query(database, sql, timeoutMillis, commandRunner)

    private fun parseRow(line: String): HermesSessionRecord? {
        val columns = line.split('\t')
        if (columns.size != SESSION_COLUMN_COUNT) return null
        val id = SqliteCli.decodeHex(columns[0])?.takeIf { it.isNotBlank() } ?: return null
        val directory = SqliteCli.decodeHex(columns[1])?.takeIf { it.isNotBlank() } ?: return null
        return HermesSessionRecord(
            id = id,
            directory = directory,
            title = SqliteCli.decodeHex(columns[2])?.takeIf { it.isNotBlank() },
            startedAt = SqliteCli.epochMillis(columns[3]),
            updatedAt = SqliteCli.epochMillis(columns[4]),
            archived = columns[5].trim() == "1",
        )
    }

    private fun parseStatsRow(line: String): Pair<String, Pair<Int, String?>>? {
        val columns = line.split('\t')
        if (columns.size != STATS_COLUMN_COUNT) return null
        val id = SqliteCli.decodeHex(columns[0])?.takeIf { it.isNotBlank() } ?: return null
        val count = columns[1].trim().toIntOrNull() ?: return null
        return id to (count to SqliteCli.decodeHex(columns[2])?.let(::plainContent)?.takeIf { it.isNotBlank() })
    }

    /** Structured content is stored behind a reserved `\u0000json:` prefix; only the plain-text shapes yield a title. */
    private fun plainContent(content: String): String? =
        if (content.startsWith(JSON_CONTENT_PREFIX)) MessageContentExtractor.text(content.removePrefix(JSON_CONTENT_PREFIX)) else content

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        const val SESSION_COLUMN_COUNT = 6
        const val STATS_COLUMN_COUNT = 3
        const val JSON_CONTENT_PREFIX = "\u0000json:"

        // The same predicate counts and picks the title: a message the user typed, not scaffolding the agent added
        // (`display_kind` hidden / async delegation results), an observed group-chat line, or a compaction summary.
        fun userMessage(alias: String) =
            "$alias.role='user' AND COALESCE($alias.observed,0)=0 AND COALESCE($alias._compressed_summary,0)=0 " +
                "AND COALESCE($alias.display_kind,'') NOT IN ('hidden','async_delegation_complete')"

        const val SESSION_QUERY =
            "SELECT hex(id), hex(COALESCE(NULLIF(cwd,''), git_repo_root)), hex(COALESCE(title,'')), " +
                "CAST(started_at*1000 AS INTEGER), CAST(COALESCE(last_activity_at, ended_at, started_at)*1000 AS INTEGER), " +
                "COALESCE(archived,0) FROM sessions " +
                "WHERE COALESCE(NULLIF(cwd,''), NULLIF(git_repo_root,'')) IS NOT NULL " +
                "ORDER BY started_at DESC LIMIT 20000;"

        // Message text is cut inside SQLite; nothing but the first line of the first prompt leaves the database.
        val USER_MESSAGE_QUERY =
            "SELECT hex(m.session_id), COUNT(*), hex(COALESCE((" +
                "SELECT substr(f.content, 1, 2000) FROM messages f " +
                "WHERE f.session_id=m.session_id AND ${userMessage("f")} ORDER BY f.timestamp, f.id LIMIT 1), '')) " +
                "FROM messages m WHERE ${userMessage("m")} GROUP BY m.session_id;"
    }
}
