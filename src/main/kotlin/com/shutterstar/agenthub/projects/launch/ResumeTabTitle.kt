package com.shutterstar.agenthub.projects.launch

/** The session part of a resume terminal tab title: one line, at most [MAX_CHARS] characters including the dots. */
object ResumeTabTitle {
    const val MAX_CHARS = 30
    private const val DOTS = "..."
    private const val SESSION_ID_CHARS = 8
    private val WHITESPACE = Regex("\\s+")

    fun label(name: String?, sessionId: String, maxChars: Int = MAX_CHARS): String {
        val line = name?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return sessionId.take(SESSION_ID_CHARS)
        return if (line.length > maxChars) line.take(maxChars - DOTS.length).trimEnd() + DOTS else line
    }
}
