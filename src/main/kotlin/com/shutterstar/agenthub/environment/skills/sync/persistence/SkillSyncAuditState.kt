package com.shutterstar.agenthub.environment.skills.sync.persistence

import com.shutterstar.agenthub.storage.CopyableState

data class SkillSyncAuditState(
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    var entries: MutableList<SkillSyncAuditEntryState> = mutableListOf(),
) : CopyableState<SkillSyncAuditState> {
    override fun deepCopy(): SkillSyncAuditState = SkillSyncAuditState(
        schemaVersion = schemaVersion,
        entries = entries.mapTo(ArrayList(entries.size)) { it.deepCopy() },
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_ENTRIES = 1000
    }
}

data class SkillSyncAuditEntryState(
    var operationId: String = "",
    var timestampEpochMillis: Long = 0L,
    var skillId: String = "",
    var runtimeId: String = "",
    var scope: String = "",
    var contextPath: String = "",
    var action: String = "",
    var affectedAgents: MutableList<String> = mutableListOf(),
    var result: String = "",
) {
    fun deepCopy(): SkillSyncAuditEntryState = SkillSyncAuditEntryState(
        operationId = operationId,
        timestampEpochMillis = timestampEpochMillis,
        skillId = skillId,
        runtimeId = runtimeId,
        scope = scope,
        contextPath = contextPath,
        action = action,
        affectedAgents = ArrayList(affectedAgents),
        result = result,
    )
}
