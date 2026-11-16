package com.shutterstar.agenthub.storage

import com.intellij.util.xmlb.XmlSerializer
import com.shutterstar.agenthub.environment.persistence.EnvironmentIndexConfigHighlightState
import com.shutterstar.agenthub.environment.persistence.EnvironmentIndexConfigState
import com.shutterstar.agenthub.environment.persistence.EnvironmentIndexInstructionState
import com.shutterstar.agenthub.environment.persistence.EnvironmentIndexMcpServerState
import com.shutterstar.agenthub.environment.persistence.EnvironmentIndexMcpSourceState
import com.shutterstar.agenthub.environment.persistence.EnvironmentIndexProjectState
import com.shutterstar.agenthub.environment.persistence.EnvironmentIndexSkillSourceState
import com.shutterstar.agenthub.environment.persistence.EnvironmentIndexSkillState
import com.shutterstar.agenthub.environment.persistence.EnvironmentIndexState
import com.shutterstar.agenthub.environment.persistence.EnvironmentIndexWarningState
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillOwnershipEntryState
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillOwnershipState
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncAuditEntryState
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncAuditState
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncSettingsState
import com.shutterstar.agenthub.projects.persistence.ProjectIndexAgentState
import com.shutterstar.agenthub.projects.persistence.ProjectIndexProjectState
import com.shutterstar.agenthub.projects.persistence.ProjectIndexSessionState
import com.shutterstar.agenthub.projects.persistence.ProjectIndexState
import org.jdom.Element

/**
 * Direct, reflection-free JDOM serializers and deserializers for AgentHub's persisted state classes.
 * Produces and consumes the exact `<option name="..." value="..."/>` / `<list>` / `<map>` XML schema
 * used by IntelliJ's [XmlSerializer], avoiding reflection overhead on large session/environment indexes.
 */
internal object XmlStateCodecs {
    fun <S : Any> serialize(state: S): Element = when (state) {
        is ProjectIndexState -> encodeProjectIndexState(state)
        is EnvironmentIndexState -> encodeEnvironmentIndexState(state)
        is SkillOwnershipState -> encodeSkillOwnershipState(state)
        is SkillSyncAuditState -> encodeSkillSyncAuditState(state)
        is SkillSyncSettingsState -> encodeSkillSyncSettingsState(state)
        else -> XmlSerializer.serialize(state)
    }

    @Suppress("UNCHECKED_CAST")
    fun <S : Any> deserialize(element: Element, stateClass: Class<S>): S = when (stateClass) {
        ProjectIndexState::class.java -> decodeProjectIndexState(element) as S
        EnvironmentIndexState::class.java -> decodeEnvironmentIndexState(element) as S
        SkillOwnershipState::class.java -> decodeSkillOwnershipState(element) as S
        SkillSyncAuditState::class.java -> decodeSkillSyncAuditState(element) as S
        SkillSyncSettingsState::class.java -> decodeSkillSyncSettingsState(element) as S
        else -> XmlSerializer.deserialize(element, stateClass)
    }

    // --- ProjectIndexState -------------------------------------------------------------------

    private fun encodeProjectIndexState(state: ProjectIndexState): Element =
        Element("ProjectIndexState").apply {
            addIntOption("schemaVersion", state.schemaVersion, ProjectIndexState.CURRENT_SCHEMA_VERSION)
            addLongOption("refreshedAtEpochMillis", state.refreshedAtEpochMillis, 0L)
            addBeanListOption("projects", state.projects, ::encodeProjectState)
        }

    private fun decodeProjectIndexState(element: Element): ProjectIndexState {
        val state = ProjectIndexState()
        for (option in element.children) {
            if (option.name != "option") continue
            when (option.getAttributeValue("name")) {
                "schemaVersion" -> option.intValue()?.let { state.schemaVersion = it }
                "refreshedAtEpochMillis" -> option.longValue()?.let { state.refreshedAtEpochMillis = it }
                "projects" -> state.projects = readBeanList(option, "ProjectIndexProjectState", ::decodeProjectState)
            }
        }
        return state
    }

    private fun encodeProjectState(project: ProjectIndexProjectState): Element =
        Element("ProjectIndexProjectState").apply {
            addStringOption("identityId", project.identityId, "")
            addStringOption("name", project.name, "")
            addStringOption("path", project.path, null)
            addStringOption("gitRoot", project.gitRoot, null)
            addStringOption("gitRemote", project.gitRemote, null)
            addStringOption("currentBranch", project.currentBranch, null)
            addBooleanOption("missing", project.missing, false)
            addBeanListOption("agents", project.agents, ::encodeProjectAgentState)
        }

    private fun decodeProjectState(element: Element): ProjectIndexProjectState {
        val state = ProjectIndexProjectState()
        for (option in element.children) {
            if (option.name != "option") continue
            when (option.getAttributeValue("name")) {
                "identityId" -> option.stringValue()?.let { state.identityId = it }
                "name" -> option.stringValue()?.let { state.name = it }
                "path" -> state.path = option.stringValue()
                "gitRoot" -> state.gitRoot = option.stringValue()
                "gitRemote" -> state.gitRemote = option.stringValue()
                "currentBranch" -> state.currentBranch = option.stringValue()
                "missing" -> option.booleanValue()?.let { state.missing = it }
                "agents" -> state.agents = readBeanList(option, "ProjectIndexAgentState", ::decodeProjectAgentState)
            }
        }
        return state
    }

    private fun encodeProjectAgentState(agent: ProjectIndexAgentState): Element =
        Element("ProjectIndexAgentState").apply {
            addStringOption("agentId", agent.agentId, "")
            addBeanListOption("sessions", agent.sessions, ::encodeProjectSessionState)
        }

    private fun decodeProjectAgentState(element: Element): ProjectIndexAgentState {
        val state = ProjectIndexAgentState()
        for (option in element.children) {
            if (option.name != "option") continue
            when (option.getAttributeValue("name")) {
                "agentId" -> option.stringValue()?.let { state.agentId = it }
                "sessions" -> state.sessions = readBeanList(option, "ProjectIndexSessionState", ::decodeProjectSessionState)
            }
        }
        return state
    }

    private fun encodeProjectSessionState(session: ProjectIndexSessionState): Element =
        Element("ProjectIndexSessionState").apply {
            addStringOption("id", session.id, "")
            addStringOption("projectPath", session.projectPath, null)
            addLongOption("startedAtEpochMillis", session.startedAtEpochMillis, null)
            addLongOption("updatedAtEpochMillis", session.updatedAtEpochMillis, null)
            addStringOption("sourcePath", session.sourcePath, null)
            addStringOption("nativeResumeId", session.nativeResumeId, null)
            addIntOption("messageCount", session.messageCount, null)
            addStringOption("title", session.title, null)
            addStringOption("firstMessage", session.firstMessage, null)
            addStringMapOption("statistics", session.statistics)
        }

    private fun decodeProjectSessionState(element: Element): ProjectIndexSessionState {
        val state = ProjectIndexSessionState()
        for (option in element.children) {
            if (option.name != "option") continue
            when (option.getAttributeValue("name")) {
                "id" -> option.stringValue()?.let { state.id = it }
                "projectPath" -> state.projectPath = option.stringValue()
                "startedAtEpochMillis" -> state.startedAtEpochMillis = option.longValue()
                "updatedAtEpochMillis" -> state.updatedAtEpochMillis = option.longValue()
                "sourcePath" -> state.sourcePath = option.stringValue()
                "nativeResumeId" -> state.nativeResumeId = option.stringValue()
                "messageCount" -> state.messageCount = option.intValue()
                "title" -> state.title = option.stringValue()
                "firstMessage" -> state.firstMessage = option.stringValue()
                "statistics" -> state.statistics = readStringMap(option)
            }
        }
        return state
    }

    // --- EnvironmentIndexState ---------------------------------------------------------------

    private fun encodeEnvironmentIndexState(state: EnvironmentIndexState): Element =
        Element("EnvironmentIndexState").apply {
            addIntOption("schemaVersion", state.schemaVersion, EnvironmentIndexState.CURRENT_SCHEMA_VERSION)
            // Global skills repeat in every project; each distinct skill is written once and projects refer to it.
            val pool = LinkedHashMap<EnvironmentIndexSkillState, Int>()
            val refs = state.projects.map { project -> project.skills.map { pool.getOrPut(it) { pool.size } } }
            addBeanListOption("skillPool", pool.keys.toList(), ::encodeEnvironmentSkillState)
            addBeanListOption("projects", state.projects.indices.toList()) { encodeEnvironmentProjectState(state.projects[it], refs[it]) }
        }

    private fun decodeEnvironmentIndexState(element: Element): EnvironmentIndexState {
        val state = EnvironmentIndexState()
        // Files written before the skill pool have no "skillPool"; their projects carry the skills inline.
        val pool = element.children
            .firstOrNull { it.name == "option" && it.getAttributeValue("name") == "skillPool" }
            ?.let { readBeanList(it, "EnvironmentIndexSkillState", ::decodeEnvironmentSkillState) }
            .orEmpty()
        for (option in element.children) {
            if (option.name != "option") continue
            when (option.getAttributeValue("name")) {
                "schemaVersion" -> option.intValue()?.let { state.schemaVersion = it }
                "projects" -> state.projects = readBeanList(option, "EnvironmentIndexProjectState") { decodeEnvironmentProjectState(it, pool) }
            }
        }
        return state
    }

    private fun encodeEnvironmentProjectState(project: EnvironmentIndexProjectState, skillRefs: List<Int>): Element =
        Element("EnvironmentIndexProjectState").apply {
            addStringOption("projectId", project.projectId, "")
            addLongOption("refreshedAtEpochMillis", project.refreshedAtEpochMillis, 0L)
            addStringListOption("agentIds", project.agentIds)
            addStringOption("skillRefs", skillRefs.joinToString(","), "")
            addBeanListOption("mcpServers", project.mcpServers, ::encodeEnvironmentMcpServerState)
            addBeanListOption("instructions", project.instructions, ::encodeEnvironmentInstructionState)
            addBeanListOption("configs", project.configs, ::encodeEnvironmentConfigState)
            addBeanListOption("warnings", project.warnings, ::encodeEnvironmentWarningState)
            addStringListOption("linkPaths", project.linkPaths)
        }

    private fun decodeEnvironmentProjectState(element: Element, pool: List<EnvironmentIndexSkillState>): EnvironmentIndexProjectState {
        val state = EnvironmentIndexProjectState()
        for (option in element.children) {
            if (option.name != "option") continue
            when (option.getAttributeValue("name")) {
                "projectId" -> option.stringValue()?.let { state.projectId = it }
                "refreshedAtEpochMillis" -> option.longValue()?.let { state.refreshedAtEpochMillis = it }
                "agentIds" -> state.agentIds = readStringList(option)
                "skills" -> state.skills = readBeanList(option, "EnvironmentIndexSkillState", ::decodeEnvironmentSkillState)
                "skillRefs" -> option.stringValue()?.takeIf { it.isNotEmpty() }?.let { refs ->
                    // Copies, so no two projects share a mutable skill state.
                    state.skills = refs.split(',').mapNotNullTo(ArrayList()) { pool.getOrNull(it.toInt())?.deepCopy() }
                }
                "mcpServers" -> state.mcpServers = readBeanList(option, "EnvironmentIndexMcpServerState", ::decodeEnvironmentMcpServerState)
                "instructions" -> state.instructions = readBeanList(option, "EnvironmentIndexInstructionState", ::decodeEnvironmentInstructionState)
                "configs" -> state.configs = readBeanList(option, "EnvironmentIndexConfigState", ::decodeEnvironmentConfigState)
                "warnings" -> state.warnings = readBeanList(option, "EnvironmentIndexWarningState", ::decodeEnvironmentWarningState)
                "linkPaths" -> state.linkPaths = readStringList(option)
            }
        }
        return state
    }

    private fun encodeEnvironmentSkillState(skill: EnvironmentIndexSkillState): Element =
        Element("EnvironmentIndexSkillState").apply {
            addStringOption("identityId", skill.identityId, "")
            addStringOption("name", skill.name, "")
            addStringOption("description", skill.description, null)
            addStringOption("scope", skill.scope, "")
            addStringOption("consistency", skill.consistency, "")
            addStringListOption("compatibleAgents", skill.compatibleAgents)
            addBeanListOption("sources", skill.sources, ::encodeEnvironmentSkillSourceState)
        }

    private fun decodeEnvironmentSkillState(element: Element): EnvironmentIndexSkillState {
        val state = EnvironmentIndexSkillState()
        for (option in element.children) {
            if (option.name != "option") continue
            when (option.getAttributeValue("name")) {
                "identityId" -> option.stringValue()?.let { state.identityId = it }
                "name" -> option.stringValue()?.let { state.name = it }
                "description" -> state.description = option.stringValue()
                "scope" -> option.stringValue()?.let { state.scope = it }
                "consistency" -> option.stringValue()?.let { state.consistency = it }
                "compatibleAgents" -> state.compatibleAgents = readStringList(option)
                "sources" -> state.sources = readBeanList(option, "EnvironmentIndexSkillSourceState", ::decodeEnvironmentSkillSourceState)
            }
        }
        return state
    }

    private fun encodeEnvironmentSkillSourceState(source: EnvironmentIndexSkillSourceState): Element =
        Element("EnvironmentIndexSkillSourceState").apply {
            addStringOption("agentId", source.agentId, null)
            addStringOption("path", source.path, "")
            addStringOption("scope", source.scope, "")
            addBooleanOption("shared", source.shared, false)
            addStringOption("fingerprint", source.fingerprint, "")
            addStringOption("displayTitle", source.displayTitle, null)
            addStringOption("projectName", source.projectName, null)
            addBooleanOption("system", source.system, false)
            addStringOption("realPath", source.realPath, null)
        }

    private fun decodeEnvironmentSkillSourceState(element: Element): EnvironmentIndexSkillSourceState {
        val state = EnvironmentIndexSkillSourceState()
        for (option in element.children) {
            if (option.name != "option") continue
            when (option.getAttributeValue("name")) {
                "agentId" -> state.agentId = option.stringValue()
                "path" -> option.stringValue()?.let { state.path = it }
                "scope" -> option.stringValue()?.let { state.scope = it }
                "shared" -> option.booleanValue()?.let { state.shared = it }
                "fingerprint" -> option.stringValue()?.let { state.fingerprint = it }
                "displayTitle" -> state.displayTitle = option.stringValue()
                "projectName" -> state.projectName = option.stringValue()
                "system" -> option.booleanValue()?.let { state.system = it }
                "realPath" -> state.realPath = option.stringValue()
            }
        }
        return state
    }

    private fun encodeEnvironmentMcpServerState(server: EnvironmentIndexMcpServerState): Element =
        Element("EnvironmentIndexMcpServerState").apply {
            addStringOption("id", server.id, "")
            addStringOption("name", server.name, "")
            addStringOption("transport", server.transport, "")
            addStringListOption("environmentVariableNames", server.environmentVariableNames)
            addStringOption("scope", server.scope, "")
            addStringOption("consistency", server.consistency, "")
            addBeanListOption("sources", server.sources, ::encodeEnvironmentMcpSourceState)
        }

    private fun decodeEnvironmentMcpServerState(element: Element): EnvironmentIndexMcpServerState {
        val state = EnvironmentIndexMcpServerState()
        for (option in element.children) {
            if (option.name != "option") continue
            when (option.getAttributeValue("name")) {
                "id" -> option.stringValue()?.let { state.id = it }
                "name" -> option.stringValue()?.let { state.name = it }
                "transport" -> option.stringValue()?.let { state.transport = it }
                "environmentVariableNames" -> state.environmentVariableNames = readStringList(option)
                "scope" -> option.stringValue()?.let { state.scope = it }
                "consistency" -> option.stringValue()?.let { state.consistency = it }
                "sources" -> state.sources = readBeanList(option, "EnvironmentIndexMcpSourceState", ::decodeEnvironmentMcpSourceState)
            }
        }
        return state
    }

    private fun encodeEnvironmentMcpSourceState(source: EnvironmentIndexMcpSourceState): Element =
        Element("EnvironmentIndexMcpSourceState").apply {
            addStringOption("agentId", source.agentId, "")
            addStringOption("configPath", source.configPath, "")
            addStringOption("sourceName", source.sourceName, "")
            addStringOption("projectName", source.projectName, null)
        }

    private fun decodeEnvironmentMcpSourceState(element: Element): EnvironmentIndexMcpSourceState {
        val state = EnvironmentIndexMcpSourceState()
        for (option in element.children) {
            if (option.name != "option") continue
            when (option.getAttributeValue("name")) {
                "agentId" -> option.stringValue()?.let { state.agentId = it }
                "configPath" -> option.stringValue()?.let { state.configPath = it }
                "sourceName" -> option.stringValue()?.let { state.sourceName = it }
                "projectName" -> state.projectName = option.stringValue()
            }
        }
        return state
    }

    private fun encodeEnvironmentInstructionState(instruction: EnvironmentIndexInstructionState): Element =
        Element("EnvironmentIndexInstructionState").apply {
            addStringOption("path", instruction.path, "")
            addStringOption("scope", instruction.scope, "")
            addStringOption("type", instruction.type, "")
            addStringListOption("agentIds", instruction.agentIds)
            addStringOption("projectName", instruction.projectName, null)
            addStringListOption("agentNotes", instruction.agentNotes)
        }

    private fun decodeEnvironmentInstructionState(element: Element): EnvironmentIndexInstructionState {
        val state = EnvironmentIndexInstructionState()
        for (option in element.children) {
            if (option.name != "option") continue
            when (option.getAttributeValue("name")) {
                "path" -> option.stringValue()?.let { state.path = it }
                "scope" -> option.stringValue()?.let { state.scope = it }
                "type" -> option.stringValue()?.let { state.type = it }
                "agentIds" -> state.agentIds = readStringList(option)
                "projectName" -> state.projectName = option.stringValue()
                "agentNotes" -> state.agentNotes = readStringList(option)
            }
        }
        return state
    }

    private fun encodeEnvironmentWarningState(warning: EnvironmentIndexWarningState): Element =
        Element("EnvironmentIndexWarningState").apply {
            addStringOption("capability", warning.capability, "")
            addStringOption("agentId", warning.agentId, null)
            addStringOption("scope", warning.scope, "")
            addStringOption("message", warning.message, "")
        }

    private fun decodeEnvironmentWarningState(element: Element): EnvironmentIndexWarningState {
        val state = EnvironmentIndexWarningState()
        for (option in element.children) {
            if (option.name != "option") continue
            when (option.getAttributeValue("name")) {
                "capability" -> option.stringValue()?.let { state.capability = it }
                "agentId" -> state.agentId = option.stringValue()
                "scope" -> option.stringValue()?.let { state.scope = it }
                "message" -> option.stringValue()?.let { state.message = it }
            }
        }
        return state
    }

    private fun encodeEnvironmentConfigState(config: EnvironmentIndexConfigState): Element =
        Element("EnvironmentIndexConfigState").apply {
            addStringOption("agentId", config.agentId, "")
            addStringOption("path", config.path, "")
            addStringOption("scope", config.scope, "")
            addStringOption("kind", config.kind, "")
            addStringOption("format", config.format, "")
            addBooleanOption("exists", config.exists, true)
            addLongOption("sizeBytes", config.sizeBytes, 0L)
            addLongOption("modifiedAtEpochMillis", config.modifiedAtEpochMillis, 0L)
            addStringOption("projectName", config.projectName, null)
            addBeanListOption("highlights", config.highlights, ::encodeEnvironmentConfigHighlightState)
        }

    private fun decodeEnvironmentConfigState(element: Element): EnvironmentIndexConfigState {
        val state = EnvironmentIndexConfigState()
        for (option in element.children) {
            if (option.name != "option") continue
            when (option.getAttributeValue("name")) {
                "agentId" -> option.stringValue()?.let { state.agentId = it }
                "path" -> option.stringValue()?.let { state.path = it }
                "scope" -> option.stringValue()?.let { state.scope = it }
                "kind" -> option.stringValue()?.let { state.kind = it }
                "format" -> option.stringValue()?.let { state.format = it }
                "exists" -> option.booleanValue()?.let { state.exists = it }
                "sizeBytes" -> option.longValue()?.let { state.sizeBytes = it }
                "modifiedAtEpochMillis" -> option.longValue()?.let { state.modifiedAtEpochMillis = it }
                "projectName" -> state.projectName = option.stringValue()
                "highlights" -> state.highlights = readBeanList(option, "EnvironmentIndexConfigHighlightState", ::decodeEnvironmentConfigHighlightState)
            }
        }
        return state
    }

    private fun encodeEnvironmentConfigHighlightState(highlight: EnvironmentIndexConfigHighlightState): Element =
        Element("EnvironmentIndexConfigHighlightState").apply {
            addStringOption("key", highlight.key, "")
            addStringOption("value", highlight.value, "")
        }

    private fun decodeEnvironmentConfigHighlightState(element: Element): EnvironmentIndexConfigHighlightState {
        val state = EnvironmentIndexConfigHighlightState()
        for (option in element.children) {
            if (option.name != "option") continue
            when (option.getAttributeValue("name")) {
                "key" -> option.stringValue()?.let { state.key = it }
                "value" -> option.stringValue()?.let { state.value = it }
            }
        }
        return state
    }

    // --- SkillOwnershipState -----------------------------------------------------------------

    private fun encodeSkillOwnershipState(state: SkillOwnershipState): Element =
        Element("SkillOwnershipState").apply {
            addIntOption("schemaVersion", state.schemaVersion, SkillOwnershipState.CURRENT_SCHEMA_VERSION)
            addBeanListOption("entries", state.entries, ::encodeSkillOwnershipEntryState)
            addBeanListOption("removedEntries", state.removedEntries, ::encodeSkillOwnershipEntryState)
        }

    private fun decodeSkillOwnershipState(element: Element): SkillOwnershipState {
        val state = SkillOwnershipState()
        for (option in element.children) {
            if (option.name != "option") continue
            when (option.getAttributeValue("name")) {
                "schemaVersion" -> option.intValue()?.let { state.schemaVersion = it }
                "entries" -> state.entries = readBeanList(option, "SkillOwnershipEntryState", ::decodeSkillOwnershipEntryState)
                "removedEntries" -> state.removedEntries = readBeanList(option, "SkillOwnershipEntryState", ::decodeSkillOwnershipEntryState)
            }
        }
        return state
    }

    private fun encodeSkillOwnershipEntryState(entry: SkillOwnershipEntryState): Element =
        Element("SkillOwnershipEntryState").apply {
            addStringOption("skillId", entry.skillId, "")
            addStringOption("runtimeId", entry.runtimeId, "")
            addStringOption("scope", entry.scope, "")
            addStringOption("contextPath", entry.contextPath, "")
            addStringOption("agentId", entry.agentId, "")
            addStringOption("path", entry.path, "")
            addStringOption("requestedMode", entry.requestedMode, "")
            addStringOption("effectiveMode", entry.effectiveMode, "")
            addStringOption("lastFingerprint", entry.lastFingerprint, null)
            addStringOption("operationId", entry.operationId, null)
            addLongOption("recordedAtEpochMillis", entry.recordedAtEpochMillis, 0L)
        }

    private fun decodeSkillOwnershipEntryState(element: Element): SkillOwnershipEntryState {
        val state = SkillOwnershipEntryState()
        for (option in element.children) {
            if (option.name != "option") continue
            when (option.getAttributeValue("name")) {
                "skillId" -> option.stringValue()?.let { state.skillId = it }
                "runtimeId" -> option.stringValue()?.let { state.runtimeId = it }
                "scope" -> option.stringValue()?.let { state.scope = it }
                "contextPath" -> option.stringValue()?.let { state.contextPath = it }
                "agentId" -> option.stringValue()?.let { state.agentId = it }
                "path" -> option.stringValue()?.let { state.path = it }
                "requestedMode" -> option.stringValue()?.let { state.requestedMode = it }
                "effectiveMode" -> option.stringValue()?.let { state.effectiveMode = it }
                "lastFingerprint" -> state.lastFingerprint = option.stringValue()
                "operationId" -> state.operationId = option.stringValue()
                "recordedAtEpochMillis" -> option.longValue()?.let { state.recordedAtEpochMillis = it }
            }
        }
        return state
    }

    // --- SkillSyncAuditState -----------------------------------------------------------------

    private fun encodeSkillSyncAuditState(state: SkillSyncAuditState): Element =
        Element("SkillSyncAuditState").apply {
            addIntOption("schemaVersion", state.schemaVersion, SkillSyncAuditState.CURRENT_SCHEMA_VERSION)
            addBeanListOption("entries", state.entries, ::encodeSkillSyncAuditEntryState)
        }

    private fun decodeSkillSyncAuditState(element: Element): SkillSyncAuditState {
        val state = SkillSyncAuditState()
        for (option in element.children) {
            if (option.name != "option") continue
            when (option.getAttributeValue("name")) {
                "schemaVersion" -> option.intValue()?.let { state.schemaVersion = it }
                "entries" -> state.entries = readBeanList(option, "SkillSyncAuditEntryState", ::decodeSkillSyncAuditEntryState)
            }
        }
        return state
    }

    private fun encodeSkillSyncAuditEntryState(entry: SkillSyncAuditEntryState): Element =
        Element("SkillSyncAuditEntryState").apply {
            addStringOption("operationId", entry.operationId, "")
            addLongOption("timestampEpochMillis", entry.timestampEpochMillis, 0L)
            addStringOption("skillId", entry.skillId, "")
            addStringOption("runtimeId", entry.runtimeId, "")
            addStringOption("scope", entry.scope, "")
            addStringOption("contextPath", entry.contextPath, "")
            addStringOption("action", entry.action, "")
            addStringListOption("affectedAgents", entry.affectedAgents)
            addStringOption("result", entry.result, "")
        }

    private fun decodeSkillSyncAuditEntryState(element: Element): SkillSyncAuditEntryState {
        val state = SkillSyncAuditEntryState()
        for (option in element.children) {
            if (option.name != "option") continue
            when (option.getAttributeValue("name")) {
                "operationId" -> option.stringValue()?.let { state.operationId = it }
                "timestampEpochMillis" -> option.longValue()?.let { state.timestampEpochMillis = it }
                "skillId" -> option.stringValue()?.let { state.skillId = it }
                "runtimeId" -> option.stringValue()?.let { state.runtimeId = it }
                "scope" -> option.stringValue()?.let { state.scope = it }
                "contextPath" -> option.stringValue()?.let { state.contextPath = it }
                "action" -> option.stringValue()?.let { state.action = it }
                "affectedAgents" -> state.affectedAgents = readStringList(option)
                "result" -> option.stringValue()?.let { state.result = it }
            }
        }
        return state
    }

    // --- SkillSyncSettingsState --------------------------------------------------------------

    private fun encodeSkillSyncSettingsState(state: SkillSyncSettingsState): Element =
        Element("SkillSyncSettingsState").apply {
            addIntOption("schemaVersion", state.schemaVersion, SkillSyncSettingsState.CURRENT_SCHEMA_VERSION)
            addStringOption("preferredSyncMode", state.preferredSyncMode, "SYMLINK")
            addBooleanOption("backupBeforeReplacement", state.backupBeforeReplacement, true)
            addBooleanOption("manageExistingTargets", state.manageExistingTargets, false)
            addBooleanOption("reviewPlanBeforeApplying", state.reviewPlanBeforeApplying, true)
        }

    private fun decodeSkillSyncSettingsState(element: Element): SkillSyncSettingsState {
        val state = SkillSyncSettingsState()
        for (option in element.children) {
            if (option.name != "option") continue
            when (option.getAttributeValue("name")) {
                "schemaVersion" -> option.intValue()?.let { state.schemaVersion = it }
                "preferredSyncMode" -> option.stringValue()?.let { state.preferredSyncMode = it }
                "backupBeforeReplacement" -> option.booleanValue()?.let { state.backupBeforeReplacement = it }
                "manageExistingTargets" -> option.booleanValue()?.let { state.manageExistingTargets = it }
                "reviewPlanBeforeApplying" -> option.booleanValue()?.let { state.reviewPlanBeforeApplying = it }
            }
        }
        return state
    }

    // --- Low-level JDOM option helpers -------------------------------------------------------

    private fun Element.addStringOption(name: String, value: String?, default: String?) {
        if (value == null || value == default) return
        addContent(Element("option").setAttribute("name", name).setAttribute("value", value))
    }

    private fun Element.addIntOption(name: String, value: Int?, default: Int?) {
        if (value == null || value == default) return
        addContent(Element("option").setAttribute("name", name).setAttribute("value", value.toString()))
    }

    private fun Element.addLongOption(name: String, value: Long?, default: Long?) {
        if (value == null || value == default) return
        addContent(Element("option").setAttribute("name", name).setAttribute("value", value.toString()))
    }

    private fun Element.addBooleanOption(name: String, value: Boolean, default: Boolean) {
        if (value == default) return
        addContent(Element("option").setAttribute("name", name).setAttribute("value", value.toString()))
    }

    private fun Element.addStringListOption(name: String, items: List<String>) {
        if (items.isEmpty()) return
        val list = Element("list")
        for (item in items) {
            list.addContent(Element("option").setAttribute("value", item))
        }
        addContent(Element("option").setAttribute("name", name).addContent(list))
    }

    private fun <T> Element.addBeanListOption(name: String, items: List<T>, encode: (T) -> Element) {
        if (items.isEmpty()) return
        val list = Element("list")
        for (item in items) {
            list.addContent(encode(item))
        }
        addContent(Element("option").setAttribute("name", name).addContent(list))
    }

    private fun Element.addStringMapOption(name: String, entries: Map<String, String>) {
        if (entries.isEmpty()) return
        val map = Element("map")
        for ((k, v) in entries) {
            map.addContent(Element("entry").setAttribute("key", k).setAttribute("value", v))
        }
        addContent(Element("option").setAttribute("name", name).addContent(map))
    }

    private fun Element.stringValue(): String? =
        getAttributeValue("value") ?: getChild("value")?.text

    private fun Element.intValue(): Int? =
        stringValue()?.toInt()

    private fun Element.longValue(): Long? =
        stringValue()?.toLong()

    private fun Element.booleanValue(): Boolean? =
        stringValue()?.toBooleanStrict()

    private fun readStringList(option: Element): MutableList<String> {
        val container = option.getChild("list") ?: option
        val children = container.children
        val result = ArrayList<String>(children.size)
        for (item in children) {
            val value = item.getAttributeValue("value") ?: item.text
            if (value != null) result.add(value)
        }
        return result
    }

    private fun <T> readBeanList(option: Element, expectedTag: String, decode: (Element) -> T): MutableList<T> {
        val container = option.getChild("list") ?: option
        val children = container.children
        val result = ArrayList<T>(children.size)
        for (item in children) {
            if (item.name == expectedTag) {
                result.add(decode(item))
            }
        }
        return result
    }

    private fun readStringMap(option: Element): MutableMap<String, String> {
        val container = option.getChild("map") ?: option
        val children = container.children
        if (children.isEmpty()) return mutableMapOf()
        val result = LinkedHashMap<String, String>(children.size)
        for (entry in children) {
            if (entry.name == "entry") {
                val key = entry.getAttributeValue("key") ?: continue
                val value = entry.getAttributeValue("value") ?: continue
                result[key] = value
            }
        }
        return result
    }
}
