package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.projects.discovery.ProjectDiscoveryService
import com.shutterstar.agenthub.usage.ui.UsageUiModel
import java.time.Instant

fun main(arguments: Array<String>) {
    if (arguments.any { it == "--help" || it == "-h" }) {
        println("Usage: usage-discovery-smoke [--live]")
        return
    }
    val live = arguments.any { it == "--live" }
    val now = Instant.now()
    val projects = ProjectDiscoveryService().discover().projects
    val result = UsageDiscoveryService(liveQueriesEnabled = { live }).discover(projects)
    println("AgentHub Usage Discovery (${if (live) "live + local" else "local only"})")
    println("Snapshots: ${result.snapshots.size}")
    result.warnings.forEach { warning ->
        println("Warning [${safe(warning.agentId)}]: ${safe(warning.message)}")
    }
    println()
    result.snapshots.forEach { snapshot ->
        val badge = UsageUiModel.badgeText(snapshot, now)
        val plan = snapshot.planLabel?.let { " ($it)" }.orEmpty()
        println("- ${safe(snapshot.agentId)}$plan | status=${snapshot.status} | badge=$badge | source=${snapshot.sourceKind}")
        snapshot.windows.forEach { window ->
            println("    window: ${safe(window.label)} -> ${UsageUiModel.windowDetailValue(window)}")
        }
        snapshot.resetCredits?.let { resets ->
            println("    saved resets: ${resets.availableCount}" + resets.credits.joinToString("") { " | ${safe(it.title ?: "reset")} (expires ${it.expiresAt}, requiresLimit=${it.requiresLimit})" })
        }
        snapshot.localSummary?.takeIf { it.hasAnyActivity }?.let { local ->
            println("    24h=${UsageUiModel.formatBucketDetailed(local.last24Hours)} | 7d=${UsageUiModel.formatBucketDetailed(local.last7Days)} | 30d=${UsageUiModel.formatBucketDetailed(local.last30Days)}")
        }
    }
}

private fun safe(value: String): String = value
    .map { ch -> if (ch.isISOControl()) '?' else ch }
    .joinToString("")
    .take(240)
