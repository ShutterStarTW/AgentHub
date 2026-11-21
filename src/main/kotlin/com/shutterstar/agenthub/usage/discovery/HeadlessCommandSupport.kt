package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.AgentDetector
import com.shutterstar.agenthub.usage.model.UsageSourceCatalog
import com.shutterstar.agenthub.usage.model.UsageStatus
import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.UsageSourceKind

/**
 * Shared helpers for providers that run an agent CLI's own non-interactive `/usage` command.
 * Only commands whose output was verified live to be handled locally by the CLI (no model call)
 * belong here; an unverified slash command may be sent to the model and spend tokens.
 */
internal object HeadlessCommandSupport {
    private val ANSI = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")

    /** Runs [command] in the active shell (native or WSL) and keeps the output of a non-zero exit. */
    fun runCommand(command: String, timeoutSeconds: Long): String =
        AgentDetector.shellOutputAnyExit(command, timeoutSeconds = timeoutSeconds)

    fun cleanOutput(raw: String): String = ANSI.replace(raw, "").replace("\r", "")

    /** Splits a JSON array of objects into the raw text of each top-level object. */
    fun splitJsonObjects(array: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in array.indices) {
            val ch = array[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        result += array.substring(start, i + 1)
                        start = -1
                    }
                }
            }
        }
        return result
    }

    fun authRequired(agentId: String, note: String, now: java.time.Instant): AgentUsageSnapshot = AgentUsageSnapshot(
        agentId = agentId,
        status = UsageStatus.AUTH_REQUIRED,
        sourceKind = UsageSourceKind.HEADLESS_COMMAND,
        observedAt = now,
        statusNote = note,
        billingUrl = UsageSourceCatalog.billingUrl(agentId),
    )
}
