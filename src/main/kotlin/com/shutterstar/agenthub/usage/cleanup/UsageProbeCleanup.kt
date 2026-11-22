package com.shutterstar.agenthub.usage.cleanup

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.AgentSettingsState
import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.projects.persistence.ProjectIndexService
import java.nio.file.Path

/**
 * Removes the `/usage` sessions older AgentHub versions left in Claude Code's history. Another IDE that still runs an
 * old plugin keeps creating them, so the scan runs on every start; the first deletion asks, and the answer is kept.
 */
object UsageProbeCleanup {
    const val ALLOWED = "allowed"
    const val DECLINED = "declined"

    fun runOnStartup() {
        val settings = AgentSettingsState.getInstance()
        val mode = settings.usageProbeCleanup
        if (mode == DECLINED) return
        val files = UsageProbeSessions.find(claudeProjectsDirectory())
        if (files.isEmpty()) return
        if (mode == ALLOWED) {
            if (UsageProbeSessions.delete(files) > 0) ProjectIndexService.getInstance().refreshInBackground()
            return
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AgentHub")
            .createNotification(
                "AgentHub — Usage sessions",
                "${files.size} Claude Code ${if (files.size == 1) "session" else "sessions"} created by earlier AgentHub " +
                    "versions while reading usage limits. They contain only the /usage command. " +
                    "Deleting also lets AgentHub remove such sessions automatically from now on.",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring("Delete") {
                settings.setUsageProbeCleanup(ALLOWED)
                if (UsageProbeSessions.delete(UsageProbeSessions.find(claudeProjectsDirectory())) > 0) {
                    ProjectIndexService.getInstance().refreshInBackground()
                }
            })
            .addAction(NotificationAction.createSimpleExpiring("Keep") { settings.setUsageProbeCleanup(DECLINED) })
            .notify(null)
    }

    private fun claudeProjectsDirectory(): Path =
        EnvHomeDirectorySupport.resolveGuarded("CLAUDE_CONFIG_DIR", AgentRuntime.userHome(), ".claude").resolve("projects")
}
