package com.shutterstar.agenthub.projects.model

import java.time.Instant

data class AgentSession(
    val id: String,
    val agentId: String,
    val projectPath: String?,
    val startedAt: Instant?,
    val updatedAt: Instant?,
    val sourcePath: String?,
    val title: String? = null,
    val nativeResumeId: String? = null,
    /** How many messages the user typed in this session; null when the transcript could not be read. */
    val messageCount: Int? = null,
    /** The user's first message (one line, truncated). The shortened first message is persisted as a session-name fallback. */
    val firstMessage: String? = null,
    val statistics: Map<String, String> = emptyMap(),
) {
    /** The agent recorded no prompt and the session has no name: shown as "Empty session". */
    val isEmpty: Boolean
        get() = messageCount == 0 && title.isNullOrBlank() && firstMessage.isNullOrBlank()

    /** The session comes from a database (OpenCode family, Antigravity, Codex fallback), not from a transcript file. */
    val sourceIsDatabase: Boolean
        get() = sourcePath?.let { path -> DATABASE_EXTENSIONS.any { path.endsWith(it, ignoreCase = true) } } == true

    /** Claude sessions whose transcript is gone are listed from `history.jsonl` (prompts and times only); `sourcePath` is then that file. */
    val fromPromptHistory: Boolean
        get() = agentId == "claude" && sourcePath?.let { it.substringAfterLast('/').substringAfterLast('\\') == "history.jsonl" } == true

    /** Codex sessions whose rollout file is gone are listed from its state database; `sourcePath` is then that database. */
    val fromFallbackSource: Boolean
        get() = (agentId == "codex" && sourceIsDatabase) || fromPromptHistory

    private companion object {
        val DATABASE_EXTENSIONS = listOf(".sqlite", ".sqlite3", ".db")
    }
}
