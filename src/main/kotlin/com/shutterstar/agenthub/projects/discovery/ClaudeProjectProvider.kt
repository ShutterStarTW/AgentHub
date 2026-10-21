package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.latest
import com.shutterstar.agenthub.projects.model.RawAgentProject
import com.shutterstar.agenthub.projects.model.SessionStatistics
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.logging.Logger
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import com.shutterstar.agenthub.AgentRuntime

class ClaudeProjectProvider(
    homeDirectory: Path = AgentRuntime.userHome(),
    private val maxProjectDirectoryEntries: Int = MAX_PROJECT_DIRECTORY_ENTRIES,
    private val maxSessionEntries: Int = MAX_SESSION_ENTRIES,
) : AgentProjectProvider {
    override val agentId: String = AGENT_ID

    private val claudeDirectory = EnvHomeDirectorySupport.resolveGuarded("CLAUDE_CONFIG_DIR", homeDirectory, CLAUDE_DIRECTORY)
    private val projectsDirectory = claudeDirectory.resolve(PROJECTS_DIRECTORY)
    private val historyFile = claudeDirectory.resolve(HISTORY_FILE)

    override fun isAvailable(): Boolean = Files.isDirectory(projectsDirectory, LinkOption.NOFOLLOW_LINKS) ||
        Files.isRegularFile(historyFile, LinkOption.NOFOLLOW_LINKS)

    override fun discover(): List<RawAgentProject> {
        if (!isAvailable()) return emptyList()

        var skippedSessions = 0
        var remainingSessionEntries = maxSessionEntries.coerceAtLeast(0)
        val sessions = mutableListOf<RawAgentProject>()
        if (Files.isDirectory(projectsDirectory, LinkOption.NOFOLLOW_LINKS)) Files.list(projectsDirectory).use { projectDirectories ->
            projectDirectories
                .limit(maxProjectDirectoryEntries.coerceAtLeast(0).toLong())
                .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted()
                .forEach { projectDirectory ->
                    if (remainingSessionEntries == 0) return@forEach
                    val result = discoverProjectDirectory(projectDirectory, remainingSessionEntries)
                    sessions += result.sessions
                    skippedSessions += result.skippedSessions
                    remainingSessionEntries -= result.scannedEntries
                }
        }
        if (skippedSessions > 0) {
            LOG.fine("[ProjectDiscovery] Claude: skipped $skippedSessions malformed or unreadable sessions")
        }
        sessions += historyOnlySessions(sessions.mapTo(hashSetOf()) { it.sessionId })
        return LocalSessionSupport.deduplicate(sessions)
    }

    /**
     * Claude Code deletes transcripts after `cleanupPeriodDays`, but `history.jsonl` keeps every typed prompt with its
     * session id, project and time. A session without a transcript is still listed from it (prompts and times only:
     * the history has no model, tokens or tool calls, and `claude --resume` needs the transcript).
     */
    private fun historyOnlySessions(knownSessionIds: Set<String>): List<RawAgentProject> {
        if (!Files.isRegularFile(historyFile, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val bySession = linkedMapOf<String, HistorySession>()
        runCatching {
            LocalSessionSupport.scanHeaderLines(historyFile, MAX_HISTORY_LINES, MAX_HISTORY_LINE_CHARACTERS, HISTORY_CHARACTER_BUDGET) { line ->
                val fields = MetadataJsonParser.topLevelStringFields(line, HISTORY_STRING_FIELDS)
                val sessionId = fields[SESSION_ID_FIELD]?.takeIf { it.isNotBlank() && it !in knownSessionIds } ?: return@scanHeaderLines false
                val project = fields[PROJECT_FIELD]?.takeIf { it.isNotBlank() } ?: return@scanHeaderLines false
                val time = MetadataJsonParser.topLevelLongFields(line, setOf(TIMESTAMP_FIELD))[TIMESTAMP_FIELD]
                    ?.takeIf { it > 0 }?.let { runCatching { Instant.ofEpochMilli(it) }.getOrNull() }
                val session = bySession.getOrPut(sessionId) { HistorySession(project) }
                time?.let { if (session.first == null || it < session.first!!) session.first = it }
                time?.let { if (session.last == null || it > session.last!!) session.last = it }
                fields[DISPLAY_FIELD]?.takeUnless { it.isBlank() || it.trimStart().startsWith("/") }?.let { session.tally.add(it) }
                false
            }
        }.onFailure { LOG.fine("[ProjectDiscovery] Claude: the prompt history could not be read") }
        val historyPath = historyFile.toAbsolutePath().normalize().toString()
        return bySession.map { (sessionId, session) ->
            val start = session.first
            val end = session.last
            RawAgentProject(
                agentId = agentId,
                rawProjectPath = session.project,
                sessionId = sessionId,
                startedAt = start,
                updatedAt = latest(start, end),
                sourcePath = historyPath,
                metadata = session.tally.metadata(),
                statistics = SessionStatistics.sanitize(
                    buildMap {
                        start?.let { put("firstEventMillis", it.toEpochMilli().toString()) }
                        end?.let { put("lastEventMillis", it.toEpochMilli().toString()) }
                        if (start != null && end != null) put("elapsedMillis", (end.toEpochMilli() - start.toEpochMilli()).toString())
                    },
                ),
            )
        }
    }

    private class HistorySession(val project: String) {
        var first: Instant? = null
        var last: Instant? = null
        val tally = UserMessageTally()
    }

    private fun discoverProjectDirectory(projectDirectory: Path, entryLimit: Int): ProjectDirectoryScan {
        val sessions = mutableListOf<RawAgentProject>()
        var skippedSessions = 0
        var scannedEntries = 0
        return try {
            Files.list(projectDirectory).use { files ->
                files
                    .limit(entryLimit.coerceAtLeast(0).toLong())
                    .peek { scannedEntries++ }
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .filter { it.extension.equals(JSONL_EXTENSION, ignoreCase = true) }
                    .sorted()
                    .forEach { sessionFile ->
                        val session = runCatching { parseSession(sessionFile) }.getOrNull()
                        if (session == null) {
                            skippedSessions++
                        } else {
                            sessions += session
                        }
                    }
            }
            ProjectDirectoryScan(sessions, skippedSessions, scannedEntries)
        } catch (_: Exception) {
            ProjectDirectoryScan(emptyList(), skippedSessions = 1, scannedEntries = scannedEntries)
        }
    }

    private fun parseSession(sessionFile: Path): RawAgentProject? {
        var sessionId = sessionFile.nameWithoutExtension.takeIf { it.isNotBlank() }
        var projectPath: String? = null
        var startedAt: Instant? = null
        var sawTimestamp = false

        LocalSessionSupport.scanHeaderLines(sessionFile, MAX_HEADER_LINES, MAX_LINE_CHARACTERS) { text ->
            val fields = MetadataJsonParser.topLevelStringFields(text, METADATA_FIELDS)
            fields[SESSION_ID_FIELD]?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            fields[WORKING_DIRECTORY_FIELD]?.takeIf { it.isNotBlank() }?.let { projectPath = it }
            fields[TIMESTAMP_FIELD]?.let { timestamp ->
                sawTimestamp = true
                startedAt = LocalSessionSupport.parseTimestamp(timestamp)
            }
            sessionId != null && projectPath != null && sawTimestamp
        }

        val resolvedSessionId = sessionId ?: return null
        val resolvedProjectPath = projectPath ?: return null
        val fileModifiedAt = runCatching { Files.getLastModifiedTime(sessionFile, LinkOption.NOFOLLOW_LINKS) }
            .getOrNull()
            ?.toInstant()
        val statistics = SessionStatisticsAccumulator(agentId)
        val conversation = conversationMetadata(sessionFile, statistics)
        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = resolvedProjectPath,
            sessionId = resolvedSessionId,
            startedAt = startedAt,
            updatedAt = latest(startedAt, fileModifiedAt),
            sourcePath = sessionFile.toAbsolutePath().normalize().toString(),
            metadata = conversation,
            statistics = statistics.snapshot(),
        )
    }

    /**
     * A full second pass (the header scan above stops within the first few lines): the user's
     * prompts and Claude Code's own `{"type":"summary"}` title, if it wrote one. `type:"user"` lines
     * are far from all typed by the user — tool results, meta/caveat lines, compaction summaries,
     * interrupt markers and background-task notifications are injected by Claude Code itself.
     */
    private fun conversationMetadata(sessionFile: Path, statistics: SessionStatisticsAccumulator): Map<String, String> {
        var summaryTitle: String? = null
        var customTitle: String? = null
        var aiTitle: String? = null
        val tally = UserMessageTally.scanJsonl(sessionFile, SCAN_MARKERS) scan@{ line ->
            statistics.record(line)
            val topLevel = MetadataJsonParser.topLevelStringFields(line, SUMMARY_SCAN_FIELDS)
            when (topLevel[TYPE_FIELD]) {
                SUMMARY_TYPE -> topLevel[SUMMARY_FIELD]?.takeIf { it.isNotBlank() }?.let { summaryTitle = it }
                CUSTOM_TITLE_TYPE -> topLevel[CUSTOM_TITLE_FIELD]?.takeIf { it.isNotBlank() }?.let { customTitle = it }
                AI_TITLE_TYPE -> topLevel[AI_TITLE_FIELD]?.takeIf { it.isNotBlank() }?.let { aiTitle = it }
                USER_TYPE -> {
                    statistics.toolResult(line)
                    if (MetadataJsonParser.topLevelBooleanFields(line, INJECTED_FLAGS).values.any { it }) return@scan
                    if (MetadataJsonParser.stringAtPath(line, ORIGIN_FIELD, KIND_FIELD) in INJECTED_ORIGINS) return@scan
                    val content = MetadataJsonParser.rawPath(line, MESSAGE_FIELD, CONTENT_FIELD) ?: return@scan
                    if (MessageContentExtractor.hasBlockOfType(content, TOOL_RESULT_TYPES)) return@scan
                    val text = MessageContentExtractor.text(content)
                    if (text != null && INJECTED_PREFIXES.any { text.trimStart().startsWith(it) }) return@scan
                    add(text)
                    statistics.userPrompt(text)
                }
            }
        }
        return tally?.metadata().orEmpty() + listOfNotNull((customTitle ?: aiTitle ?: summaryTitle)?.let { TITLE_FIELD to it })
    }

    private data class ProjectDirectoryScan(
        val sessions: List<RawAgentProject>,
        val skippedSessions: Int,
        val scannedEntries: Int,
    )

    companion object {
        private const val AGENT_ID = "claude"
        private const val CLAUDE_DIRECTORY = ".claude"
        private const val PROJECTS_DIRECTORY = "projects"
        private const val HISTORY_FILE = "history.jsonl"
        private const val MAX_HISTORY_LINES = 500_000
        private const val MAX_HISTORY_LINE_CHARACTERS = 256 * 1024
        private const val HISTORY_CHARACTER_BUDGET = 128 * 1024 * 1024
        private const val PROJECT_FIELD = "project"
        private const val DISPLAY_FIELD = "display"
        private val HISTORY_STRING_FIELDS = setOf(SESSION_ID_FIELD, PROJECT_FIELD, DISPLAY_FIELD)
        private const val JSONL_EXTENSION = "jsonl"
        private const val MAX_PROJECT_DIRECTORY_ENTRIES = 20_000
        private const val MAX_SESSION_ENTRIES = 50_000
        private const val MAX_HEADER_LINES = 100
        private const val MAX_LINE_CHARACTERS = 64 * 1024
        private const val SESSION_ID_FIELD = "sessionId"
        private const val WORKING_DIRECTORY_FIELD = "cwd"
        private const val TIMESTAMP_FIELD = "timestamp"
        private val METADATA_FIELDS = setOf(SESSION_ID_FIELD, WORKING_DIRECTORY_FIELD, TIMESTAMP_FIELD)
        private val SCAN_MARKERS = listOf("\"timestamp\"", "\"assistant\"", "\"user\"", "\"summary\"", "\"custom-title\"", "\"ai-title\"")
        private const val TYPE_FIELD = "type"
        private const val SUMMARY_TYPE = "summary"
        private const val CUSTOM_TITLE_TYPE = "custom-title"
        private const val CUSTOM_TITLE_FIELD = "customTitle"
        private const val AI_TITLE_TYPE = "ai-title"
        private const val AI_TITLE_FIELD = "aiTitle"
        private const val SUMMARY_FIELD = "summary"
        private const val USER_TYPE = "user"
        private const val MESSAGE_FIELD = "message"
        private const val CONTENT_FIELD = "content"
        private const val ORIGIN_FIELD = "origin"
        private const val KIND_FIELD = "kind"
        private const val TITLE_FIELD = "title"
        private val SUMMARY_SCAN_FIELDS = setOf(TYPE_FIELD, SUMMARY_FIELD, CUSTOM_TITLE_FIELD, AI_TITLE_FIELD)
        private val INJECTED_FLAGS = setOf("isMeta", "isCompactSummary", "isVisibleInTranscriptOnly", "isSidechain")
        private val INJECTED_ORIGINS = setOf("task-notification")
        private val TOOL_RESULT_TYPES = setOf("tool_result")
        private val INJECTED_PREFIXES = listOf("<local-command", "<system-reminder", "<task-notification", "[Request interrupted")
        private val LOG = Logger.getLogger(ClaudeProjectProvider::class.java.name)
    }
}
