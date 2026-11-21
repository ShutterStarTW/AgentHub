package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.environment.discovery.OpenClawHomeSupport
import com.shutterstar.agenthub.environment.discovery.OpenClawWorkspaces
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.latest
import com.shutterstar.agenthub.projects.model.RawAgentProject
import com.shutterstar.agenthub.projects.resolve.ProcessCommandRunner
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.util.logging.Logger

data class OpenClawSessionRecord(
    /** `agent:<agentId>:<name>` - the key `openclaw resume` takes. */
    val sessionKey: String,
    val title: String?,
    val startedAt: Instant?,
    val updatedAt: Instant?,
    val archivedAt: Instant? = null,
    val userMessageCount: Int? = null,
    val firstMessage: String? = null,
)

/**
 * OpenClaw sessions: the `session_nodes` rows of each agent's `<state>/agents/<agentId>/agent/openclaw-agent.sqlite`.
 * OpenClaw is a gateway-based assistant: a session has no working directory, the agent's *workspace* plays that role
 * (see [OpenClawWorkspaces]), so every session of an agent lands in that agent's workspace project. A session is
 * identified by its key (stable across daily resets), which is also what `openclaw resume` takes.
 * Only `agent:` keys are listed - cron (`cron:<id>`) and webhook (`hook:<id>`) runs are not conversations.
 */
class OpenClawProjectProvider(
    private val stateDirectory: Path = OpenClawHomeSupport.stateDirectory(AgentRuntime.userHome()),
    private val configPath: Path = OpenClawHomeSupport.configPath(AgentRuntime.userHome(), stateDirectory),
    private val workspaceOverride: String? = OpenClawHomeSupport.workspaceOverride(AgentRuntime.userHome()),
    private val databaseReader: (Path) -> List<OpenClawSessionRecord> = OpenClawSqliteReader()::readSessions,
) : AgentProjectProvider {
    override val agentId: String = AGENT_ID

    override fun isAvailable(): Boolean = agentDatabases().isNotEmpty()

    override fun discover(): List<RawAgentProject> {
        val databases = agentDatabases()
        if (databases.isEmpty()) return emptyList()
        val workspaces = OpenClawWorkspaces(
            // A missing or unreadable config is normal (defaults apply): never a reason to lose the sessions.
            config = runCatching { LocalSessionSupport.readBoundedText(configPath, MAX_CONFIG_CHARACTERS) }.getOrNull(),
            stateDirectory = stateDirectory,
            workspaceOverride = workspaceOverride,
        )
        val sessions = mutableListOf<RawAgentProject>()
        var failure: Throwable? = null
        databases.forEach { (openClawAgentId, database) ->
            runCatching { databaseReader(database) }
                .onSuccess { records ->
                    val workspace = workspaces.forAgent(openClawAgentId)
                    sessions += records.map { it.toRawProject(workspace, database) }
                }
                .onFailure { error -> failure = error }
        }
        if (sessions.isEmpty() && failure != null) throw IOException("OpenClaw session database could not be read", failure)
        if (failure != null) LOG.fine("[ProjectDiscovery] OpenClaw: a database was skipped")
        return LocalSessionSupport.deduplicate(sessions)
    }

    private fun agentDatabases(): List<Pair<String, Path>> =
        LocalSessionSupport.listDirectories(stateDirectory.resolve(AGENTS_DIRECTORY), MAX_AGENTS).mapNotNull { agent ->
            val database = agent.resolve(AGENT_SUBDIRECTORY).resolve(DATABASE_NAME)
            if (Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) agent.fileName.toString() to database else null
        }

    private fun OpenClawSessionRecord.toRawProject(workspace: String, database: Path) = RawAgentProject(
        agentId = agentId,
        rawProjectPath = workspace,
        sessionId = sessionKey,
        startedAt = startedAt,
        updatedAt = latest(startedAt, updatedAt),
        sourcePath = database.toAbsolutePath().normalize().toString(),
        metadata = buildMap {
            title?.let { put("title", it) }
            archivedAt?.let { put("archivedAt", it.toString()) }
            userMessageCount?.let { count ->
                put(UserMessageTally.MESSAGE_COUNT_KEY, count.toString())
                MessageContentExtractor.titleText(firstMessage)?.let { put(UserMessageTally.FIRST_MESSAGE_KEY, it) }
            }
        },
    )

    private companion object {
        const val AGENT_ID = "openclaw"
        const val AGENTS_DIRECTORY = "agents"
        const val AGENT_SUBDIRECTORY = "agent"
        const val DATABASE_NAME = "openclaw-agent.sqlite"
        const val MAX_AGENTS = 100
        const val MAX_CONFIG_CHARACTERS = 1024 * 1024
        val LOG: Logger = Logger.getLogger(OpenClawProjectProvider::class.java.name)
    }
}

class OpenClawSqliteReader(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val commandRunner: (List<String>, Long) -> String? = ProcessCommandRunner::run,
) {
    fun readSessions(database: Path): List<OpenClawSessionRecord> {
        val output = SqliteCli.query(database, SESSION_QUERY, timeoutMillis, commandRunner)
            ?: throw IOException("sqlite3 is unavailable or could not read the database")
        val sessions = output.lineSequence().mapNotNull(::parseRow).toList()
        val statsOutput = SqliteCli.query(database, USER_MESSAGE_QUERY, timeoutMillis, commandRunner)
        val stats = statsOutput?.lineSequence()?.mapNotNull(::parseStatsRow)?.toMap().orEmpty()
        return sessions.map { session ->
            val user = stats[session.sessionKey]
            session.copy(userMessageCount = user?.first ?: if (statsOutput == null) null else 0, firstMessage = user?.second)
        }
    }

    private fun parseRow(line: String): OpenClawSessionRecord? {
        val columns = line.split('\t')
        if (columns.size != SESSION_COLUMN_COUNT) return null
        val key = SqliteCli.decodeHex(columns[0])?.takeIf { it.isNotBlank() } ?: return null
        return OpenClawSessionRecord(
            sessionKey = key,
            title = SqliteCli.decodeHex(columns[1])?.takeIf { it.isNotBlank() },
            startedAt = SqliteCli.epochMillis(columns[2]),
            updatedAt = SqliteCli.epochMillis(columns[3]),
            archivedAt = SqliteCli.epochMillis(columns[4]),
        )
    }

    private fun parseStatsRow(line: String): Pair<String, Pair<Int, String?>>? {
        val columns = line.split('\t')
        if (columns.size != STATS_COLUMN_COUNT) return null
        val key = SqliteCli.decodeHex(columns[0])?.takeIf { it.isNotBlank() } ?: return null
        val count = columns[1].trim().toIntOrNull() ?: return null
        return key to (count to SqliteCli.decodeHex(columns[2])?.takeIf { it.isNotBlank() })
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        const val SESSION_COLUMN_COUNT = 5
        const val STATS_COLUMN_COUNT = 3

        // A message the user typed carries `provenance.kind = external_user`; other kinds (inter-session, cron ...) are
        // injected. Rows whose JSON is stored compressed (`event_zstd`, `event_json` NULL) cannot be inspected and count.
        fun typedByUser(alias: String) =
            "(CASE WHEN $alias.event_json IS NOT NULL AND json_valid($alias.event_json) " +
                "THEN COALESCE(json_extract($alias.event_json,'$.message.provenance.kind'),'external_user') " +
                "ELSE 'external_user' END)='external_user'"

        // The content is a string or an array of blocks; only the first 2000 characters of the first text leave SQLite.
        fun firstText(alias: String) =
            "CASE WHEN json_type($alias.event_json,'$.message.content')='text' " +
                "THEN substr(json_extract($alias.event_json,'$.message.content'),1,2000) " +
                "ELSE COALESCE(substr(json_extract($alias.event_json,'$.message.content[0].text'),1,2000),'') END"

        const val SESSION_QUERY =
            "SELECT hex(session_key), hex(COALESCE(NULLIF(display_name,''), NULLIF(label,''), '')), " +
                "COALESCE(session_started_at, created_at, ''), " +
                "COALESCE(last_activity_at, last_interaction_at, updated_at, created_at, ''), IFNULL(archived_at,'') " +
                "FROM session_nodes WHERE session_key LIKE 'agent:%' " +
                "ORDER BY COALESCE(last_activity_at, last_interaction_at, updated_at, created_at) DESC LIMIT 20000;"

        val USER_MESSAGE_QUERY =
            "SELECT hex(n.session_key), COUNT(e.seq), hex(COALESCE((" +
                "SELECT ${firstText("f")} FROM transcript_events f WHERE f.session_id=n.current_session_id " +
                "AND f.message_role='user' AND f.event_json IS NOT NULL AND json_valid(f.event_json) " +
                "AND ${typedByUser("f")} ORDER BY f.seq LIMIT 1), '')) " +
                "FROM session_nodes n LEFT JOIN transcript_events e ON e.session_id=n.current_session_id " +
                "AND e.message_role='user' AND ${typedByUser("e")} " +
                "WHERE n.session_key LIKE 'agent:%' GROUP BY n.session_key;"
    }
}
