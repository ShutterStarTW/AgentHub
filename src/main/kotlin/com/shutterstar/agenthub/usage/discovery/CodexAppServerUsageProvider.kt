package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.AgentDetector
import com.shutterstar.agenthub.pluginVersion
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport
import com.shutterstar.agenthub.projects.discovery.MetadataJsonParser
import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.UsageSourceCatalog
import com.shutterstar.agenthub.usage.model.UsageResetCredit
import com.shutterstar.agenthub.usage.model.UsageResetCredits
import com.shutterstar.agenthub.usage.model.UsageSourceKind
import com.shutterstar.agenthub.usage.model.UsageStatus
import com.shutterstar.agenthub.usage.model.UsageWindow
import com.shutterstar.agenthub.usage.model.UsageWindowKind
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Quota windows from Codex's own app-server (`codex app-server`, stdio JSON-RPC, method
 * `account/rateLimits/read`; verified live: no model call). The Codex CLI uses its own sign-in, so
 * AgentHub reads no token and sends no request itself.
 *
 * A response without windows (not signed in with ChatGPT, API-key mode, unreadable reply) yields
 * `null` or an auth-required snapshot, so the OAuth-API and local rollout providers can still run.
 */
internal class CodexAppServerUsageProvider(
    private val fetchRateLimitsResponse: () -> String? = ::defaultFetchRateLimitsResponse,
) : AgentUsageProvider {
    override val agentId: String = AGENT_ID
    override val sourceKind: UsageSourceKind = UsageSourceKind.HEADLESS_COMMAND
    override val requiresNetwork: Boolean = true

    override fun isAvailable(): Boolean = true

    override fun discover(now: Instant): AgentUsageSnapshot? {
        val response = fetchRateLimitsResponse()?.takeIf { it.isNotBlank() } ?: return null
        return parseResponse(response, now)
    }

    /** [response] is the JSON-RPC response object of the `account/rateLimits/read` request. */
    internal fun parseResponse(response: String, now: Instant): AgentUsageSnapshot? {
        val error = MetadataJsonParser.rawTopLevelField(response, "error")?.trim()
        if (error != null && error.startsWith("{")) {
            val message = MetadataJsonParser.topLevelStringFields(error, setOf("message"))["message"].orEmpty()
            if (AUTH_ERROR.containsMatchIn(message)) {
                return HeadlessCommandSupport.authRequired(AGENT_ID, "Sign in with ChatGPT in Codex CLI (`codex login`) to view usage limits.", now)
            }
            throw IllegalStateException("Codex app-server could not read rate limits")
        }
        val result = MetadataJsonParser.rawTopLevelField(response, "result")?.trim()?.takeIf { it.startsWith("{") } ?: return null
        val limits = MetadataJsonParser.rawTopLevelField(result, "rateLimits")?.trim()?.takeIf { it.startsWith("{") } ?: return null

        val windows = mutableListOf<UsageWindow>()
        parseWindow(limits, "primary", defaultMinutes = 300)?.let(windows::add)
        parseWindow(limits, "secondary", defaultMinutes = 10_080)?.let(windows::add)
        MetadataJsonParser.rawTopLevelField(limits, "credits")?.trim()?.takeIf { it.startsWith("{") }
            ?.let(::parseCredits)?.let(windows::add)
        if (windows.isEmpty()) return null

        val plan = CodexUsageFormat.planLabel(MetadataJsonParser.topLevelStringFields(limits, setOf("planType"))["planType"])
        return AgentUsageSnapshot(
            agentId = AGENT_ID,
            status = UsageStatus.OK,
            sourceKind = sourceKind,
            planLabel = plan,
            observedAt = now,
            windows = windows,
            statusNote = "Account usage reported by the Codex app-server.",
            billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
            resetCredits = parseResetCredits(result, now),
        )
    }

    /** `result.rateLimitResetCredits`: the saved resets, only the ones still `available` and not expired. */
    private fun parseResetCredits(result: String, now: Instant): UsageResetCredits? {
        val summary = MetadataJsonParser.rawTopLevelField(result, "rateLimitResetCredits")?.trim()?.takeIf { it.startsWith("{") } ?: return null
        val count = number(summary, "availableCount")?.toInt() ?: return null
        val rows = MetadataJsonParser.rawTopLevelField(summary, "credits")?.trim()?.takeIf { it.startsWith("[") }
        val credits = rows?.let(HeadlessCommandSupport::splitJsonObjects).orEmpty().mapNotNull { row ->
            val strings = MetadataJsonParser.topLevelStringFields(row, setOf("status", "title"))
            if (strings["status"] != "available") return@mapNotNull null
            val expiresAt = MetadataJsonParser.topLevelLongFields(row, setOf("expiresAt"))["expiresAt"]?.let(LocalSessionSupport::epochTimestamp)
            if (expiresAt != null && !expiresAt.isAfter(now)) return@mapNotNull null
            val grantedAt = MetadataJsonParser.topLevelLongFields(row, setOf("grantedAt"))["grantedAt"]?.let(LocalSessionSupport::epochTimestamp)
            UsageResetCredit(title = ResetCreditText.title(strings["title"]), expiresAt = expiresAt, grantedAt = grantedAt)
        }.sortedBy { it.expiresAt ?: Instant.MAX }.take(MAX_RESET_CREDITS)
        return UsageResetCredits(availableCount = count.coerceIn(0, 999), credits = credits)
    }

    private fun parseWindow(limitsJson: String, field: String, defaultMinutes: Int): UsageWindow? {
        val raw = MetadataJsonParser.rawTopLevelField(limitsJson, field)?.trim()?.takeIf { it.startsWith("{") } ?: return null
        val used = number(raw, "usedPercent")?.coerceIn(0.0, 100.0) ?: return null
        val minutes = number(raw, "windowDurationMins")?.toInt()?.takeIf { it > 0 } ?: defaultMinutes
        val resetsAt = MetadataJsonParser.topLevelLongFields(raw, setOf("resetsAt"))["resetsAt"]
            ?.let(LocalSessionSupport::epochTimestamp)
        return UsageWindow(
            id = field,
            label = CodexUsageFormat.windowLabel(minutes, field),
            kind = CodexUsageFormat.windowKind(minutes),
            usedPercent = used,
            windowMinutes = minutes,
            resetsAt = resetsAt,
        )
    }

    private fun parseCredits(creditsJson: String): UsageWindow? {
        val flags = MetadataJsonParser.topLevelBooleanFields(creditsJson, setOf("hasCredits", "unlimited"))
        val unlimited = flags["unlimited"] == true
        val balance = number(creditsJson, "balance")?.takeIf { it > 0.0 }
        if (!unlimited && flags["hasCredits"] != true && balance == null) return null
        return UsageWindow(
            id = "credits",
            label = "Credits",
            kind = UsageWindowKind.CREDITS,
            usedPercent = null,
            limitAmount = balance,
            unit = if (balance != null) "credits" else null,
            unlimited = unlimited,
        )
    }

    private fun number(json: String, field: String): Double? {
        val raw = MetadataJsonParser.rawTopLevelField(json, field)?.trim() ?: return null
        val unquoted = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) raw.substring(1, raw.length - 1) else raw
        return unquoted.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    }

    companion object {
        private const val AGENT_ID = "codex"
        private const val COMMAND = "codex app-server"
        private const val TIMEOUT_SECONDS = 20L
        private const val MAX_RESET_CREDITS = 10
        private const val MAX_READ_CHARS = 2 * 1024 * 1024
        private const val REQUEST_ID = 2L
        private val AUTH_ERROR = Regex("""(?i)auth|sign.?in|log.?in|chatgpt|token|credential""")

        /**
         * Runs `codex app-server`, performs the `initialize` handshake and returns the response line
         * of `account/rateLimits/read`, or null on timeout/failure. The whole process tree is killed
         * afterwards (on Windows `cmd /c` would otherwise leave the real server running).
         */
        private fun defaultFetchRateLimitsResponse(): String? {
            var process: Process? = null
            try {
                val started = ProcessBuilder(*AgentDetector.shellArgv(COMMAND))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                process = started
                val lines = LinkedBlockingQueue<String>()
                Thread {
                    runCatching {
                        BufferedReader(InputStreamReader(started.inputStream, StandardCharsets.UTF_8)).use { reader ->
                            var total = 0
                            while (total < MAX_READ_CHARS) {
                                val line = reader.readLine() ?: break
                                total += line.length
                                if (line.startsWith("{")) lines.put(line)
                            }
                        }
                    }
                }.apply {
                    isDaemon = true
                    name = "agenthub-codex-app-server-reader"
                }.start()

                val version = pluginVersion().filter { it.isLetterOrDigit() || it in ".-+" }
                val writer = started.outputStream.bufferedWriter(StandardCharsets.UTF_8)
                writer.write("""{"id":1,"method":"initialize","params":{"clientInfo":{"name":"agenthub","title":"AgentHub","version":"$version"}}}""" + "\n")
                writer.write("""{"method":"initialized"}""" + "\n")
                writer.write("""{"id":$REQUEST_ID,"method":"account/rateLimits/read"}""" + "\n")
                writer.flush()

                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
                while (true) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) return null
                    val line = lines.poll(remaining, TimeUnit.NANOSECONDS) ?: return null
                    // Server-initiated requests carry a `method`; only the response to our request counts.
                    if (MetadataJsonParser.rawTopLevelField(line, "method") != null) continue
                    if (MetadataJsonParser.topLevelLongFields(line, setOf("id"))["id"] == REQUEST_ID) return line
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } catch (_: IOException) {
                return null
            } finally {
                process?.let { running ->
                    runCatching { running.outputStream.close() }
                    runCatching { running.descendants().forEach { it.destroyForcibly() } }
                    running.destroyForcibly()
                }
            }
        }
    }
}
