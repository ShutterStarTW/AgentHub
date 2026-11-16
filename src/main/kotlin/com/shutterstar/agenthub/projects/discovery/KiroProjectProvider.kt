package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.ScanBudget
import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.projects.model.RawAgentProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

class KiroProjectProvider(
    private val kiroDirectory: Path = defaultKiroDirectory(),
    private val maxScanEntries: Int = MAX_SCAN_ENTRIES,
) : AgentProjectProvider {
    override val agentId: String = AGENT_ID

    private val sessionsRoot = kiroDirectory.resolve("sessions")
    private val sessionsDirectory = sessionsRoot.resolve("cli")

    override fun isAvailable(): Boolean = Files.isDirectory(sessionsRoot, LinkOption.NOFOLLOW_LINKS)

    override fun discover(): List<RawAgentProject> {
        if (!isAvailable()) return emptyList()
        val budget = ScanBudget(maxScanEntries.coerceAtLeast(0))
        val sessions = discoverLegacySessions(budget) + discoverV3Sessions(budget)
        return LocalSessionSupport.deduplicate(sessions)
    }

    private fun discoverLegacySessions(budget: ScanBudget): List<RawAgentProject> {
        if (!Files.isDirectory(sessionsDirectory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return runCatching {
            Files.list(sessionsDirectory).use { files ->
                files
                    .limit(budget.remaining().toLong())
                    .toList()
                    .also { budget.consume(it.size) }
                    .asSequence()
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .filter { it.extension.equals(JSON_EXTENSION, ignoreCase = true) }
                    .sorted()
                    .map { runCatching { parseSession(it) }.getOrNull() }
                    .filter { it != null }
                    .map { it!! }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun discoverV3Sessions(budget: ScanBudget): List<RawAgentProject> =
        runCatching { LocalSessionSupport.listDirectories(sessionsRoot, budget) }.getOrDefault(emptyList())
            .filterNot { it.fileName.toString() == "cli" || it.fileName.toString().startsWith('.') }
            .flatMap { workspace ->
                runCatching { LocalSessionSupport.listDirectories(workspace, budget) }.getOrDefault(emptyList())
                    .filter { it.fileName.toString().startsWith("sess_") }
                    .mapNotNull { directory ->
                        runCatching { parseV3Session(directory.resolve("session.json")) }.getOrNull()
                    }
            }

    private fun parseV3Session(metadata: Path): RawAgentProject? {
        if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)) return null
        val json = LocalSessionSupport.readBoundedText(metadata, MAX_JSON_CHARACTERS) ?: return null
        val fields = MetadataJsonParser.topLevelStringFields(json, V3_STRING_FIELDS)
        if (fields["schemaVersion"] != "1.0.0" ||
            MetadataJsonParser.topLevelLongFields(json, setOf("dataModelVersion"))["dataModelVersion"] != 1L
        ) return null
        val target = MetadataJsonParser.stringAtPath(json, "_meta", "kiro", "executionTarget", "kind")
        if (target != null && target != "local") return null
        val projectPath = listOf("workspacePaths", "rootPaths").firstNotNullOfOrNull { field ->
            MetadataJsonParser.rawTopLevelField(json, field)?.let(MetadataJsonParser::arrayStringElements)
                ?.firstOrNull { it.isNotBlank() }
        }
        val startedAt = LocalSessionSupport.parseTimestamp(fields["createdAt"])
        val updatedAt = LocalSessionSupport.parseTimestamp(fields["lastModifiedAt"])
        val tally = UserMessageTally.scanJsonl(metadata.resolveSibling("messages.jsonl"), V3_PROMPT_MARKERS) { line ->
            if (MetadataJsonParser.topLevelStringFields(line, setOf("role"))["role"] != "user") return@scanJsonl
            val content = MetadataJsonParser.rawTopLevelField(line, "content")
            if (MessageContentExtractor.hasBlockOfType(content, setOf("tool_result"))) return@scanJsonl
            add(MessageContentExtractor.text(content))
        }
        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = projectPath,
            sessionId = fields[ID_FIELD]?.takeIf { it.isNotBlank() } ?: metadata.parent.fileName.toString(),
            startedAt = startedAt,
            updatedAt = LocalSessionSupport.latest(startedAt, updatedAt, LocalSessionSupport.modifiedAt(metadata)),
            sourcePath = metadata.toAbsolutePath().normalize().toString(),
            metadata = tally?.metadata().orEmpty() +
                listOfNotNull(fields[TITLE_FIELD]?.takeIf { it.isNotBlank() }?.let { TITLE_FIELD to it }),
        )
    }

    private fun parseSession(sessionFile: Path): RawAgentProject? {
        val json = LocalSessionSupport.readBoundedText(sessionFile, MAX_JSON_CHARACTERS) ?: return null
        val fields = MetadataJsonParser.topLevelStringFields(json, STRING_FIELDS)
        val longs = MetadataJsonParser.topLevelLongFields(json, TIME_FIELDS)
        val projectPath = fields[CWD_FIELD]?.takeIf { it.isNotBlank() } ?: return null
        val sessionId = fields[SESSION_ID_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[ID_FIELD]?.takeIf { it.isNotBlank() }
            ?: sessionFile.nameWithoutExtension
        val startedAt = LocalSessionSupport.parseTimestamp(fields[CREATED_AT_FIELD])
            ?: LocalSessionSupport.epochTimestamp(longs[CREATED_AT_FIELD])
        val recordedUpdate = LocalSessionSupport.parseTimestamp(fields[UPDATED_AT_FIELD])
            ?: LocalSessionSupport.epochTimestamp(longs[UPDATED_AT_FIELD])
        val title = fields[TITLE_FIELD]?.takeIf { it.isNotBlank() }
        val statistics = SessionStatisticsAccumulator(agentId)
        statistics.recordedBounds(startedAt, recordedUpdate)
        val state = MetadataJsonParser.rawPath(json, "session_state", "rts_model_state")
        state?.let {
            statistics.metric("contextWindow", MetadataJsonParser.objectLongFields(it, "model_info", setOf("context_window_tokens"))["context_window_tokens"])
            MetadataJsonParser.rawTopLevelField(it, "context_usage_percentage")?.toBigDecimalOrNull()
                ?.takeIf { percent -> percent.signum() >= 0 && percent <= java.math.BigDecimal(100) }
                ?.movePointRight(2)?.setScale(0, java.math.RoundingMode.HALF_UP)
                ?.let { percent -> statistics.metric("contextUsageBasisPoints", percent.toLong()) }
        }
        val prompts = userMessages(sessionFile.resolveSibling("${sessionFile.nameWithoutExtension}.$JSONL_EXTENSION"), statistics)
        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = projectPath,
            sessionId = sessionId,
            startedAt = startedAt,
            updatedAt = LocalSessionSupport.latest(startedAt, recordedUpdate, LocalSessionSupport.modifiedAt(sessionFile)),
            sourcePath = sessionFile.toAbsolutePath().normalize().toString(),
            metadata = title?.let { mapOf(TITLE_FIELD to it) }.orEmpty() +
                prompts?.metadata().orEmpty(),
            statistics = statistics.snapshot().filterKeys { it != "activeMillis" },
        )
    }

    /** The transcript next to the session file: `{"kind":"Prompt","data":{"content":[{"kind":"text","data":"..."}]}}`. */
    private fun userMessages(transcript: Path, statistics: SessionStatisticsAccumulator): UserMessageTally? =
        UserMessageTally.scanJsonl(transcript, PROMPT_MARKERS) { line ->
            statistics.record(line)
            if (MetadataJsonParser.topLevelStringFields(line, setOf(KIND_FIELD))[KIND_FIELD] == PROMPT_KIND) {
                val text = MessageContentExtractor.text(MetadataJsonParser.rawPath(line, DATA_FIELD, CONTENT_FIELD), BLOCK_TEXT_FIELDS)
                add(text)
                statistics.userPrompt(text)
            }
        }

    companion object {
        private const val AGENT_ID = "kiro"
        private const val JSON_EXTENSION = "json"
        private const val JSONL_EXTENSION = "jsonl"
        private const val KIND_FIELD = "kind"
        private const val PROMPT_KIND = "Prompt"
        private const val DATA_FIELD = "data"
        private const val CONTENT_FIELD = "content"
        private val BLOCK_TEXT_FIELDS = setOf("data", "text")
        private val PROMPT_MARKERS = listOf("\"kind\"")
        private const val MAX_SCAN_ENTRIES = 20_000
        private const val MAX_JSON_CHARACTERS = 256 * 1024
        private const val SESSION_ID_FIELD = "session_id"
        private const val ID_FIELD = "id"
        private const val CWD_FIELD = "cwd"
        private const val CREATED_AT_FIELD = "created_at"
        private const val UPDATED_AT_FIELD = "updated_at"
        private const val TITLE_FIELD = "title"
        private val STRING_FIELDS = setOf(
            SESSION_ID_FIELD,
            ID_FIELD,
            CWD_FIELD,
            CREATED_AT_FIELD,
            UPDATED_AT_FIELD,
            TITLE_FIELD,
        )
        private val TIME_FIELDS = setOf(CREATED_AT_FIELD, UPDATED_AT_FIELD)
        private val V3_STRING_FIELDS = setOf("schemaVersion", ID_FIELD, TITLE_FIELD, "createdAt", "lastModifiedAt")
        private val V3_PROMPT_MARKERS = listOf("\"role\"")

        private fun defaultKiroDirectory(): Path = EnvHomeDirectorySupport.resolve("KIRO_HOME", ".kiro")
    }
}
