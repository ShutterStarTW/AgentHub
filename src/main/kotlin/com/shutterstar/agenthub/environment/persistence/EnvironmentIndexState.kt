package com.shutterstar.agenthub.environment.persistence

import com.shutterstar.agenthub.storage.CopyableState

data class EnvironmentIndexState(
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    var projects: MutableList<EnvironmentIndexProjectState> = mutableListOf(),
) : CopyableState<EnvironmentIndexState> {
    override fun deepCopy(): EnvironmentIndexState = EnvironmentIndexState(
        schemaVersion = schemaVersion,
        projects = projects.mapTo(ArrayList(projects.size)) { it.deepCopy() },
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class EnvironmentIndexProjectState(
    var projectId: String = "",
    var refreshedAtEpochMillis: Long = 0L,
    var agentIds: MutableList<String> = mutableListOf(),
    var skills: MutableList<EnvironmentIndexSkillState> = mutableListOf(),
    var mcpServers: MutableList<EnvironmentIndexMcpServerState> = mutableListOf(),
    var instructions: MutableList<EnvironmentIndexInstructionState> = mutableListOf(),
    var configs: MutableList<EnvironmentIndexConfigState> = mutableListOf(),
    var warnings: MutableList<EnvironmentIndexWarningState> = mutableListOf(),
    /** Skill source paths that are links, so a cached skill list folds them exactly like the live one. */
    var linkPaths: MutableList<String> = mutableListOf(),
) {
    fun deepCopy(): EnvironmentIndexProjectState = EnvironmentIndexProjectState(
        projectId = projectId,
        refreshedAtEpochMillis = refreshedAtEpochMillis,
        agentIds = ArrayList(agentIds),
        skills = skills.mapTo(ArrayList(skills.size)) { it.deepCopy() },
        mcpServers = mcpServers.mapTo(ArrayList(mcpServers.size)) { it.deepCopy() },
        instructions = instructions.mapTo(ArrayList(instructions.size)) { it.deepCopy() },
        configs = configs.mapTo(ArrayList(configs.size)) { it.deepCopy() },
        warnings = warnings.mapTo(ArrayList(warnings.size)) { it.copy() },
        linkPaths = ArrayList(linkPaths),
    )
}

data class EnvironmentIndexSkillState(
    var identityId: String = "",
    var name: String = "",
    var description: String? = null,
    var scope: String = "",
    var consistency: String = "",
    var compatibleAgents: MutableList<String> = mutableListOf(),
    var sources: MutableList<EnvironmentIndexSkillSourceState> = mutableListOf(),
) {
    fun deepCopy(): EnvironmentIndexSkillState = EnvironmentIndexSkillState(
        identityId = identityId,
        name = name,
        description = description,
        scope = scope,
        consistency = consistency,
        compatibleAgents = ArrayList(compatibleAgents),
        sources = sources.mapTo(ArrayList(sources.size)) { it.copy() },
    )
}

data class EnvironmentIndexSkillSourceState(
    var agentId: String? = null,
    var path: String = "",
    var scope: String = "",
    var shared: Boolean = false,
    var fingerprint: String = "",
    var displayTitle: String? = null,
    var projectName: String? = null,
    var system: Boolean = false,
    var realPath: String? = null,
)

data class EnvironmentIndexMcpServerState(
    var id: String = "",
    var name: String = "",
    var transport: String = "",
    var environmentVariableNames: MutableList<String> = mutableListOf(),
    var scope: String = "",
    var consistency: String = "",
    var sources: MutableList<EnvironmentIndexMcpSourceState> = mutableListOf(),
) {
    fun deepCopy(): EnvironmentIndexMcpServerState = EnvironmentIndexMcpServerState(
        id = id,
        name = name,
        transport = transport,
        environmentVariableNames = ArrayList(environmentVariableNames),
        scope = scope,
        consistency = consistency,
        sources = sources.mapTo(ArrayList(sources.size)) { it.copy() },
    )
}

data class EnvironmentIndexMcpSourceState(
    var agentId: String = "",
    var configPath: String = "",
    var sourceName: String = "",
    var projectName: String? = null,
)

data class EnvironmentIndexInstructionState(
    var path: String = "",
    var scope: String = "",
    var type: String = "",
    var agentIds: MutableList<String> = mutableListOf(),
    var projectName: String? = null,
    /** `agentId=reason` entries (see InstructionSource.agentNotes). */
    var agentNotes: MutableList<String> = mutableListOf(),
) {
    fun deepCopy(): EnvironmentIndexInstructionState = EnvironmentIndexInstructionState(
        path = path,
        scope = scope,
        type = type,
        agentIds = ArrayList(agentIds),
        projectName = projectName,
        agentNotes = ArrayList(agentNotes),
    )
}

data class EnvironmentIndexWarningState(
    var capability: String = "",
    var agentId: String? = null,
    var scope: String = "",
    var message: String = "",
)

/** No raw settings, hook commands or connection values are persisted. */
data class EnvironmentIndexConfigState(
    var agentId: String = "",
    var path: String = "",
    var scope: String = "",
    var kind: String = "",
    var format: String = "",
    var exists: Boolean = true,
    var sizeBytes: Long = 0,
    var modifiedAtEpochMillis: Long = 0,
    var projectName: String? = null,
    var highlights: MutableList<EnvironmentIndexConfigHighlightState> = mutableListOf(),
) {
    fun deepCopy(): EnvironmentIndexConfigState = EnvironmentIndexConfigState(
        agentId = agentId,
        path = path,
        scope = scope,
        kind = kind,
        format = format,
        exists = exists,
        sizeBytes = sizeBytes,
        modifiedAtEpochMillis = modifiedAtEpochMillis,
        projectName = projectName,
        highlights = highlights.mapTo(ArrayList(highlights.size)) { it.copy() },
    )
}

data class EnvironmentIndexConfigHighlightState(
    var key: String = "",
    var value: String = "",
)
