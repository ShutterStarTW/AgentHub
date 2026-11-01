package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.latest
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.readTailLines
import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.projects.model.RawAgentProject
import com.shutterstar.agenthub.projects.model.SessionStatistics
import com.shutterstar.agenthub.projects.resolve.ProcessCommandRunner
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.util.logging.Logger
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import com.shutterstar.agenthub.SafeFileTree

class CodexProjectProvider(
    private val codexDirectory: Path = defaultCodexDirectory(),
    private val maxScanEntries: Int = MAX_SCAN_ENTRIES,
    private val threadReader: (Path) -> List<CodexThreadRecord> = CodexThreadDatabaseReader()::readThreads,
) : AgentProjectProvider {
    override val agentId: String = AGENT_ID

    private val sessionDirectories = listOf(
        codexDirectory.resolve(SESSIONS_DIRECTORY),
        codexDirectory.resolve(ARCHIVED_SESSIONS_DIRECTORY),
    )

    override fun isAvailable(): Boolean = sessionDirectories.any {
        Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)
    } || threadDatabase() != null

    override fun discover(): List<RawAgentProject> {
        if (!isAvailable()) return emptyList()

        var skippedSessions = 0
        var remainingScanEntries = maxScanEntries.coerceAtLeast(0)
        val sessions = mutableListOf<RawAgentProject>()
        val titles = sessionTitles()
        sessionDirectories
            .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
            .forEach { sessionDirectory ->
                if (remainingScanEntries == 0) return@forEach
                var scannedEntries = 0
                SafeFileTree.walk(sessionDirectory, MAX_SCAN_DEPTH).use { paths ->
                    paths
                        .skip(1)
                        .limit(remainingScanEntries.toLong())
                        .peek { scannedEntries++ }
                        .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                        .filter { it.extension.equals(JSONL_EXTENSION, ignoreCase = true) }
                        .sorted()
                        .forEach { sessionFile ->
                            val session = runCatching { parseSession(sessionFile) }.getOrNull()
                            if (session == null) {
                                skippedSessions++
                            } else {
                                sessions += titles[session.sessionId]?.let { title ->
                                    session.copy(metadata = session.metadata + ("title" to title))
                                } ?: session
                            }
                        }
                }
                remainingScanEntries -= scannedEntries
            }
        if (skippedSessions > 0) {
            LOG.fine("[ProjectDiscovery] Codex: skipped $skippedSessions malformed or unreadable sessions")
        }
        sessions += databaseOnlySessions(sessions.mapTo(hashSetOf()) { it.sessionId })
        return LocalSessionSupport.deduplicate(sessions)
    }

    /**
     * Codex's own thread index outlives the rollout files (they get cleaned up or deleted), so a thread
     * without a rollout is still listed from it. Threads with a rollout come from the file, which is richer.
     */
    private fun databaseOnlySessions(knownSessionIds: Set<String>): List<RawAgentProject> {
        val database = threadDatabase() ?: return emptyList()
        val threads = runCatching { threadReader(database) }.getOrElse {
            LOG.fine("[ProjectDiscovery] Codex: thread database could not be read")
            emptyList()
        }
        val databasePath = database.toAbsolutePath().normalize().toString()
        return threads
            .filter { it.id !in knownSessionIds }
            .map { thread ->
                RawAgentProject(
                    agentId = agentId,
                    rawProjectPath = thread.cwd,
                    sessionId = thread.id,
                    startedAt = thread.createdAt,
                    updatedAt = latest(thread.createdAt, thread.updatedAt),
                    sourcePath = databasePath,
                    metadata = buildMap {
                        thread.title?.let { put("title", it) }
                        MessageContentExtractor.titleText(thread.firstMessage)?.let { put(UserMessageTally.FIRST_MESSAGE_KEY, it) }
                        databasePromptCount(thread)?.let { put(UserMessageTally.MESSAGE_COUNT_KEY, it.toString()) }
                    },
                    statistics = threadStatistics(thread),
                )
            }
    }

    /**
     * The history's prompt count; without history items, a thread that has neither a first message nor a name was
     * closed before any prompt (shown as an empty session, like Qwen's); otherwise unknown.
     */
    private fun databasePromptCount(thread: CodexThreadRecord): Int? = thread.activity?.userMessages
        ?: 0.takeIf { thread.firstMessage.isNullOrBlank() && thread.title == null }

    private fun threadStatistics(thread: CodexThreadRecord): Map<String, String> {
        val values = linkedMapOf<String, String>()
        thread.model?.let { values["models"] = it }
        thread.tokensUsed?.let { values["totalTokens"] = it.toString() }
        thread.activity?.let {
            values["toolCalls"] = it.toolCalls.toString()
            values["toolErrors"] = it.toolErrors.toString()
            values["subagentCalls"] = it.subagentCalls.toString()
        }
        val start = thread.createdAt
        val end = thread.updatedAt
        start?.let { values["firstEventMillis"] = it.toEpochMilli().toString() }
        end?.let { values["lastEventMillis"] = it.toEpochMilli().toString() }
        if (start != null && end != null && end >= start) values["elapsedMillis"] = (end.toEpochMilli() - start.toEpochMilli()).toString()
        return SessionStatistics.sanitize(values)
    }

    /** The newest `state_<n>.sqlite`: Codex bumps the number when the schema changes. */
    private fun threadDatabase(): Path? {
        if (!Files.isDirectory(codexDirectory, LinkOption.NOFOLLOW_LINKS)) return null
        return runCatching {
            Files.list(codexDirectory).use { files ->
                files
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .map { it to STATE_DATABASE.matchEntire(it.fileName.toString())?.groupValues?.get(1)?.toIntOrNull() }
                    .filter { it.second != null }
                    .toList()
                    .maxByOrNull { it.second!! }
                    ?.first
            }
        }.getOrNull()
    }

    /** Codex keeps user-assigned thread names outside the rollout, in an append-only index. */
    private fun sessionTitles(): Map<String, String?> {
        val index = codexDirectory.resolve(SESSION_INDEX_FILE)
        if (!Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) return emptyMap()
        val titles = linkedMapOf<String, String?>()
        runCatching { readTailLines(index, MAX_INDEX_BYTES, MAX_INDEX_LINES) }.getOrDefault(emptyList())
            .forEach { line ->
                val fields = MetadataJsonParser.topLevelStringFields(line, INDEX_FIELDS)
                val id = fields[ID_FIELD]?.takeIf { it.isNotBlank() } ?: return@forEach
                if (id !in titles) {
                    titles[id] = fields[THREAD_NAME_FIELD]
                        ?.replace(Regex("\\s+"), " ")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.take(MAX_TITLE_CHARACTERS)
                }
            }
        return titles
    }

    private fun parseSession(sessionFile: Path): RawAgentProject? {
        var metadata: SessionMetadata? = null
        LocalSessionSupport.scanHeaderLines(sessionFile, MAX_HEADER_LINES, MAX_LINE_CHARACTERS) { text ->
            val topLevel = MetadataJsonParser.topLevelStringFields(text, TOP_LEVEL_FIELDS)
            if (topLevel[TYPE_FIELD] != SESSION_META_TYPE) return@scanHeaderLines false
            val payload = MetadataJsonParser.objectStringFields(text, PAYLOAD_FIELD, PAYLOAD_FIELDS)
            // A policy-allowed projectless session (Codex 0.160+) has no cwd; keep it, with a null project,
            // instead of dropping it - ProjectResolver already groups a null rawProjectPath per session.
            val projectPath = payload[WORKING_DIRECTORY_FIELD]?.takeIf { it.isNotBlank() }
            val sessionId = payload[ID_FIELD]
                ?.takeIf { it.isNotBlank() }
                ?: payload[LEGACY_SESSION_ID_FIELD]?.takeIf { it.isNotBlank() }
                ?: sessionIdFromFileName(sessionFile)
            val startedAt = LocalSessionSupport.parseTimestamp(payload[TIMESTAMP_FIELD] ?: topLevel[TIMESTAMP_FIELD])
            metadata = SessionMetadata(sessionId, projectPath, startedAt)
            true
        }
        val resolved = metadata ?: return null
        val lastEventAt = findLastEventTimestamp(sessionFile)
        val fileModifiedAt = runCatching { Files.getLastModifiedTime(sessionFile, LinkOption.NOFOLLOW_LINKS) }
            .getOrNull()
            ?.toInstant()
        val statistics = SessionStatisticsAccumulator(agentId)
        val messages = userMessages(sessionFile, statistics)
        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = resolved.projectPath,
            sessionId = resolved.sessionId,
            startedAt = resolved.startedAt,
            updatedAt = latest(resolved.startedAt, lastEventAt, fileModifiedAt),
            sourcePath = sessionFile.toAbsolutePath().normalize().toString(),
            metadata = messages?.metadata().orEmpty(),
            statistics = statistics.snapshot(),
        )
    }

    /**
     * The user's prompts, from a full second pass. Current Codex records each one as an
     * `event_msg` → `item_completed` → `UserMessage` (older builds: `event_msg` → `user_message`).
     * The `response_item` `role:"user"` messages duplicate those but also carry injected context
     * (`<environment_context>`, subagent tasks), so they only count for rollouts that have no
     * dedicated user-message events at all.
     */
    private fun userMessages(sessionFile: Path, statistics: SessionStatisticsAccumulator): UserMessageTally? {
        val fallback = UserMessageTally()
        val tally = UserMessageTally.scanJsonl(sessionFile, USER_MARKERS) scan@{ line ->
            statistics.record(line)
            val topLevelType = MetadataJsonParser.topLevelStringFields(line, setOf(TYPE_FIELD))[TYPE_FIELD]
            val payloadType = MetadataJsonParser.stringAtPath(line, PAYLOAD_FIELD, TYPE_FIELD)
            when {
                topLevelType == EVENT_MSG_TYPE && payloadType == ITEM_COMPLETED_TYPE &&
                    MetadataJsonParser.stringAtPath(line, PAYLOAD_FIELD, ITEM_FIELD, TYPE_FIELD) == USER_MESSAGE_ITEM ->
                    {
                        val text = MessageContentExtractor.text(MetadataJsonParser.rawPath(line, PAYLOAD_FIELD, ITEM_FIELD, CONTENT_FIELD))
                        add(text)
                        statistics.userPrompt(text)
                    }
                topLevelType == EVENT_MSG_TYPE && payloadType == LEGACY_USER_MESSAGE_TYPE ->
                    {
                        val text = MetadataJsonParser.stringAtPath(line, PAYLOAD_FIELD, MESSAGE_FIELD)
                        add(text)
                        statistics.userPrompt(text)
                    }
                topLevelType == RESPONSE_ITEM_TYPE && payloadType == MESSAGE_FIELD &&
                    MetadataJsonParser.stringAtPath(line, PAYLOAD_FIELD, ROLE_FIELD) == USER_ROLE -> {
                    val text = MessageContentExtractor.text(MetadataJsonParser.rawPath(line, PAYLOAD_FIELD, CONTENT_FIELD))
                    if (text != null && !text.trimStart().startsWith("<")) fallback.add(text)
                }
            }
        } ?: return null
        return if (tally.count == 0) fallback else tally
    }

    private fun findLastEventTimestamp(sessionFile: Path): Instant? =
        LocalSessionSupport.lastTimestamp(sessionFile, MAX_TAIL_BYTES, MAX_TAIL_LINES) { line ->
            LocalSessionSupport.parseTimestamp(
                MetadataJsonParser.topLevelStringFields(line, setOf(TIMESTAMP_FIELD))[TIMESTAMP_FIELD],
            )
        }

    private fun sessionIdFromFileName(sessionFile: Path): String {
        val name = sessionFile.nameWithoutExtension
        return SESSION_ID_SUFFIX.find(name)?.groupValues?.get(1) ?: name
    }

    private data class SessionMetadata(
        val sessionId: String,
        val projectPath: String?,
        val startedAt: Instant?,
    )

    companion object {
        private const val AGENT_ID = "codex"
        private const val SESSIONS_DIRECTORY = "sessions"
        private const val ARCHIVED_SESSIONS_DIRECTORY = "archived_sessions"
        private val STATE_DATABASE = Regex("state_(\\d+)\\.sqlite")
        private const val SESSION_INDEX_FILE = "session_index.jsonl"
        private const val THREAD_NAME_FIELD = "thread_name"
        private const val MAX_INDEX_BYTES = 8 * 1024 * 1024
        private const val MAX_INDEX_LINES = 50_000
        private const val MAX_TITLE_CHARACTERS = 300
        private val INDEX_FIELDS = setOf(ID_FIELD, THREAD_NAME_FIELD)
        private const val JSONL_EXTENSION = "jsonl"
        private const val MAX_SCAN_DEPTH = 4
        private const val MAX_SCAN_ENTRIES = 50_000
        private const val MAX_HEADER_LINES = 32
        private const val MAX_LINE_CHARACTERS = 256 * 1024
        private const val MAX_TAIL_BYTES = 256 * 1024
        private const val MAX_TAIL_LINES = 200
        private const val TYPE_FIELD = "type"
        private const val SESSION_META_TYPE = "session_meta"
        private const val PAYLOAD_FIELD = "payload"
        private const val ID_FIELD = "id"
        private const val LEGACY_SESSION_ID_FIELD = "session_id"
        private const val WORKING_DIRECTORY_FIELD = "cwd"
        private const val TIMESTAMP_FIELD = "timestamp"
        private val USER_MARKERS = listOf("\"timestamp\"", "\"turn_context\"", "\"token_count\"", "\"function_call\"", "\"custom_tool_call\"", "\"UserMessage\"", "\"user_message\"", "\"user\"")
        private const val EVENT_MSG_TYPE = "event_msg"
        private const val ITEM_COMPLETED_TYPE = "item_completed"
        private const val LEGACY_USER_MESSAGE_TYPE = "user_message"
        private const val USER_MESSAGE_ITEM = "UserMessage"
        private const val RESPONSE_ITEM_TYPE = "response_item"
        private const val ITEM_FIELD = "item"
        private const val MESSAGE_FIELD = "message"
        private const val CONTENT_FIELD = "content"
        private const val ROLE_FIELD = "role"
        private const val USER_ROLE = "user"
        private val TOP_LEVEL_FIELDS = setOf(TYPE_FIELD, TIMESTAMP_FIELD)
        private val PAYLOAD_FIELDS = setOf(
            ID_FIELD,
            LEGACY_SESSION_ID_FIELD,
            WORKING_DIRECTORY_FIELD,
            TIMESTAMP_FIELD,
        )
        private val SESSION_ID_SUFFIX = Regex(
            "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$",
        )
        private val LOG = Logger.getLogger(CodexProjectProvider::class.java.name)

        private fun defaultCodexDirectory(): Path = EnvHomeDirectorySupport.resolve("CODEX_HOME", ".codex")
    }
}

data class CodexThreadRecord(
    val id: String,
    /** Null for a policy-allowed projectless session (Codex 0.160+); kept, not dropped. */
    val cwd: String?,
    val title: String?,
    val firstMessage: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val model: String? = null,
    val tokensUsed: Long? = null,
    val activity: CodexThreadActivity? = null,
)

/** Item counts of one thread from the `thread_history_<n>.sqlite` projection of the rollout. */
data class CodexThreadActivity(
    val userMessages: Int = 0,
    val toolCalls: Int = 0,
    val toolErrors: Int = 0,
    val subagentCalls: Int = 0,
)

class CodexThreadDatabaseReader(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val commandRunner: (List<String>, Long) -> String? = ProcessCommandRunner::run,
    private val historyDatabase: (Path) -> Path? = ::siblingHistoryDatabase,
) {
    /** Null-safe across schema versions: the full query falls back to one with only the long-standing columns. */
    fun readThreads(database: Path): List<CodexThreadRecord> {
        val output = query(database, THREAD_QUERY) ?: query(database, BASIC_THREAD_QUERY)
            ?: throw java.io.IOException("sqlite3 is unavailable or could not read the database")
        val activity = runCatching { historyDatabase(database)?.let(::readActivity) }.getOrNull().orEmpty()
        return output.lineSequence().mapNotNull(::parseRow).map { it.copy(activity = activity[it.id]) }.toList()
    }

    private fun readActivity(history: Path): Map<String, CodexThreadActivity> {
        val output = query(history, ACTIVITY_QUERY) ?: return emptyMap()
        val counts = linkedMapOf<String, CodexThreadActivity>()
        output.lineSequence().forEach { line ->
            val columns = line.split('\t')
            if (columns.size != ACTIVITY_COLUMN_COUNT) return@forEach
            val id = decodeHex(columns[0])?.takeIf { it.isNotBlank() } ?: return@forEach
            val total = columns[2].trim().toIntOrNull() ?: return@forEach
            val failed = columns[3].trim().toIntOrNull() ?: 0
            val current = counts[id] ?: CodexThreadActivity()
            counts[id] = when (columns[1]) {
                USER_MESSAGE_ITEM -> current.copy(userMessages = current.userMessages + total)
                SUBAGENT_ITEM -> current.copy(subagentCalls = current.subagentCalls + total)
                else -> current.copy(toolCalls = current.toolCalls + total, toolErrors = current.toolErrors + failed)
            }
        }
        return counts
    }

    private fun query(database: Path, sql: String): String? = commandRunner(
        listOf("sqlite3", "-readonly", "-batch", "-noheader", "-separator", "\t", database.toAbsolutePath().normalize().toString(), sql),
        timeoutMillis,
    )

    private fun parseRow(line: String): CodexThreadRecord? {
        val columns = line.split('\t')
        if (columns.size != COLUMN_COUNT) return null
        val id = decodeHex(columns[0])?.takeIf { it.isNotBlank() } ?: return null
        val cwd = decodeHex(columns[1])?.removePrefix(EXTENDED_PATH_PREFIX)?.takeIf { it.isNotBlank() }
        return CodexThreadRecord(
            id = id,
            cwd = cwd,
            title = decodeHex(columns[2])?.trim()?.takeIf { it.isNotEmpty() },
            firstMessage = decodeHex(columns[3])?.takeIf { it.isNotBlank() },
            createdAt = epochSeconds(columns[4]),
            updatedAt = epochSeconds(columns[5]),
            model = decodeHex(columns[6])?.trim()?.takeIf { it.isNotEmpty() },
            tokensUsed = columns[7].trim().toLongOrNull()?.takeIf { it > 0 },
        )
    }

    private fun decodeHex(value: String): String? {
        if (value.length % 2 != 0) return null
        val bytes = ByteArray(value.length / 2)
        for (index in bytes.indices) {
            bytes[index] = (value.substring(index * 2, index * 2 + 2).toIntOrNull(16) ?: return null).toByte()
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun epochSeconds(value: String): Instant? = value.trim().toLongOrNull()?.let {
        runCatching { Instant.ofEpochSecond(it) }.getOrNull()
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        private const val ACTIVITY_COLUMN_COUNT = 4
        private const val USER_MESSAGE_ITEM = "userMessage"
        private const val SUBAGENT_ITEM = "collabAgentToolCall"
        private val HISTORY_DATABASE = Regex("thread_history_(\\d+)\\.sqlite")
        private const val ACTIVITY_QUERY =
            "SELECT hex(thread_id), item_type, count(*), " +
                "sum(CASE WHEN json_extract(item_json, '$.status') = 'failed' THEN 1 ELSE 0 END) " +
                "FROM thread_items WHERE item_type IN ('userMessage', 'commandExecution', 'fileChange', 'mcpToolCall', " +
                "'webSearch', 'collabAgentToolCall') GROUP BY thread_id, item_type;"

        /** The newest `thread_history_<n>.sqlite` next to the state database. */
        fun siblingHistoryDatabase(state: Path): Path? = state.toAbsolutePath().parent?.let { directory ->
            Files.list(directory).use { files ->
                files.map { it to HISTORY_DATABASE.matchEntire(it.fileName.toString())?.groupValues?.get(1)?.toIntOrNull() }
                    .filter { it.second != null }
                    .toList()
                    .maxByOrNull { it.second!! }
                    ?.first
            }
        }

        private const val COLUMN_COUNT = 8
        private const val EXTENDED_PATH_PREFIX = "\\\\?\\"

        // No `WHERE cwd <> ''`: a policy-allowed projectless session (Codex 0.160+) has an empty cwd and is
        // kept, not dropped - parseRow() turns the blank into a null rawProjectPath instead.
        private const val THREAD_QUERY =
            "SELECT hex(id), hex(cwd), hex(coalesce(nullif(name, ''), title)), hex(first_user_message), created_at, updated_at, hex(model), tokens_used " +
                "FROM threads ORDER BY updated_at DESC LIMIT 20000;"
        private const val BASIC_THREAD_QUERY =
            "SELECT hex(id), hex(cwd), hex(title), hex(''), created_at, updated_at, hex(''), 0 " +
                "FROM threads ORDER BY updated_at DESC LIMIT 20000;"
    }
}
