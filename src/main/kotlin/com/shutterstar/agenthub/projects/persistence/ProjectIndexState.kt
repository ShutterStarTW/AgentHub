package com.shutterstar.agenthub.projects.persistence

import com.shutterstar.agenthub.storage.CopyableState

data class ProjectIndexState(
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    var refreshedAtEpochMillis: Long = 0L,
    var projects: MutableList<ProjectIndexProjectState> = mutableListOf(),
) : CopyableState<ProjectIndexState> {
    override fun deepCopy(): ProjectIndexState = ProjectIndexState(
        schemaVersion = schemaVersion,
        refreshedAtEpochMillis = refreshedAtEpochMillis,
        projects = projects.mapTo(ArrayList(projects.size)) { it.deepCopy() },
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION = 3
    }
}

data class ProjectIndexProjectState(
    var identityId: String = "",
    var name: String = "",
    var path: String? = null,
    var gitRoot: String? = null,
    var gitRemote: String? = null,
    var currentBranch: String? = null,
    var missing: Boolean = false,
    var agents: MutableList<ProjectIndexAgentState> = mutableListOf(),
) {
    fun deepCopy(): ProjectIndexProjectState = ProjectIndexProjectState(
        identityId = identityId,
        name = name,
        path = path,
        gitRoot = gitRoot,
        gitRemote = gitRemote,
        currentBranch = currentBranch,
        missing = missing,
        agents = agents.mapTo(ArrayList(agents.size)) { it.deepCopy() },
    )
}

data class ProjectIndexAgentState(
    var agentId: String = "",
    var sessions: MutableList<ProjectIndexSessionState> = mutableListOf(),
) {
    fun deepCopy(): ProjectIndexAgentState = ProjectIndexAgentState(
        agentId = agentId,
        sessions = sessions.mapTo(ArrayList(sessions.size)) { it.deepCopy() },
    )
}

data class ProjectIndexSessionState(
    var id: String = "",
    var projectPath: String? = null,
    var startedAtEpochMillis: Long? = null,
    var updatedAtEpochMillis: Long? = null,
    var sourcePath: String? = null,
    var nativeResumeId: String? = null,
    var messageCount: Int? = null,
    var title: String? = null,
    var firstMessage: String? = null,
    var statistics: MutableMap<String, String> = mutableMapOf(),
) {
    fun deepCopy(): ProjectIndexSessionState = ProjectIndexSessionState(
        id = id,
        projectPath = projectPath,
        startedAtEpochMillis = startedAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        sourcePath = sourcePath,
        nativeResumeId = nativeResumeId,
        messageCount = messageCount,
        title = title,
        firstMessage = firstMessage,
        statistics = if (statistics.isEmpty()) mutableMapOf() else LinkedHashMap(statistics),
    )
}
