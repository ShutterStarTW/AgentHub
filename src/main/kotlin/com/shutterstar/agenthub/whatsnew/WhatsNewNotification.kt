package com.shutterstar.agenthub.whatsnew

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil

/** One notification after a plugin update: the top items of the new version's change-notes and a changelog link. */
object WhatsNewNotification {
    private const val CHANGELOG_URL = "https://shutterstartw.github.io/AgentHub/changelog/"

    /** True when a notification was shown (nothing is shown if the change-notes have no block for [version]). */
    fun show(project: Project?, version: String): Boolean {
        val items = ChangeNotesSummary.summarize(ChangeNotesSummary.ownChangeNotes(WhatsNewNotification::class.java.classLoader), version)
        if (items.isEmpty()) return false
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AgentHub")
            .createNotification(
                "AgentHub updated to $version",
                items.joinToString("<br>") { "• " + StringUtil.escapeXmlEntities(it) },
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimple("Full changelog") { BrowserUtil.browse(CHANGELOG_URL) })
            .notify(project)
        return true
    }
}
