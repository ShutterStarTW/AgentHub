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
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncAuditState
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncAuditEntryState
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncSettingsState
import com.shutterstar.agenthub.projects.persistence.ProjectIndexAgentState
import com.shutterstar.agenthub.projects.persistence.ProjectIndexProjectState
import com.shutterstar.agenthub.projects.persistence.ProjectIndexSessionState
import com.shutterstar.agenthub.projects.persistence.ProjectIndexState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SharedXmlStoreTest {
    @TempDir lateinit var directory: Path

    private fun store(home: AgentHubHome = AgentHubHome(directory), timeout: Long = 3000) = SharedXmlStore(
        home, "state/audit.xml", SkillSyncAuditState::class.java, ::SkillSyncAuditState,
        statIntervalMillis = 0, lockTimeoutMillis = timeout,
    )

    @Test fun `should share durable state across fresh instances and detach snapshots`() {
        val first = store()
        first.update { it.copy(entries = mutableListOf(SkillSyncAuditEntryState(operationId = "first"))) }
        val second = store()
        assertEquals("first", second.snapshot().entries.single().operationId)
        second.snapshot().entries.clear()
        assertEquals(1, second.snapshot().entries.size)
        val before = first.stamp()
        second.update { it.copy(entries = (it.entries + SkillSyncAuditEntryState(operationId = "second")).toMutableList()) }
        assertTrue(first.stamp() != before)
        assertEquals(2, first.snapshot().entries.size)
        assertTrue(Files.exists(directory.resolve("format.json")))
    }

    @Test fun `should keep all updates made by two competing store instances`() {
        val a = store()
        val b = store()
        val pool = Executors.newFixedThreadPool(2)
        try {
            listOf(a, b).mapIndexed { index, instance -> pool.submit {
                repeat(30) { number ->
                    instance.update { state -> state.copy(entries = (state.entries + SkillSyncAuditEntryState(operationId = "$index-$number")).toMutableList()) }
                }
            } }.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertEquals(60, store().snapshot().entries.map { it.operationId }.toSet().size)
    }

    @Test fun `should quarantine malformed empty and truncated XML without losing the original`() {
        val home = AgentHubHome(directory)
        home.prepare()
        listOf("", "<broken", "<wrong/>", "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///private'>]><x>&e;</x>").forEachIndexed { index, content ->
            Files.writeString(directory.resolve("state/audit.xml"), content)
            val snapshot = store(home).snapshot()
            assertTrue(snapshot.entries.isEmpty())
            Files.list(directory.resolve("state")).use { paths -> assertEquals((index + 1).toLong(), paths.filter { it.fileName.toString().contains(".corrupt-") }.count()) }
        }
    }

    @Test fun `should reject writes to newer schema and home formats without altering bytes`() {
        store().update { it }
        val path = directory.resolve("state/audit.xml")
        val newer = Files.readString(path).replace("version=\"1\"", "version=\"2\"")
        Files.writeString(path, newer)
        assertThrows(SharedStorageException::class.java) { store().update { it } }
        assertEquals(newer, Files.readString(path))
        Files.writeString(directory.resolve("format.json"), "{\"formatVersion\":2,\"minimumReaderVersion\":2}")
        assertThrows(SharedStorageException::class.java) { store().update { it } }
        assertEquals(newer, Files.readString(path))
    }

    @Test fun `should never write without a lock when another process owns it`() {
        val home = AgentHubHome(directory)
        home.prepare()
        FileChannel.open(directory.resolve("locks/state-audit.xml.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use {
                assertThrows(SharedStorageException::class.java) { store(home, 50).update { it } }
            }
        }
        assertFalse(Files.exists(directory.resolve("state/audit.xml")))
    }

    @Test fun `should fall back to memory and warn once if the home cannot be created`() {
        val root = directory.resolve("not-a-directory")
        Files.writeString(root, "keep")
        val messages = mutableListOf<String>()
        val home = AgentHubHome(root, messages::add)
        val memory = store(home)
        memory.update { it.copy(entries = mutableListOf(SkillSyncAuditEntryState(operationId = "memory"))) }
        memory.update { it }
        assertEquals("memory", memory.snapshot().entries.single().operationId)
        assertEquals(1, messages.size)
        assertEquals("keep", Files.readString(root))
    }

    @Test fun `should isolate runtime cache paths and prefer the sandbox property over the environment`() {
        val userHome = directory.resolve("user")
        assertEquals(userHome.resolve(".agenthub"), AgentHubHome.resolvePath(userHome, null, null))
        assertEquals(directory.resolve("sandbox"), AgentHubHome.resolvePath(userHome, directory.resolve("real").toString(), directory.resolve("sandbox").toString()))
        assertFalse(AgentHubHome.runtimeDirectory("wsl:a/b") == AgentHubHome.runtimeDirectory("wsl:a?b"))
        assertFalse(AgentHubHome.runtimeDirectory("../../escape").contains(".."))
    }

    @Test fun `should round-trip all persisted state types identically with XmlSerializer and deepCopy`() {
        val states = listOf<Pair<Class<out Any>, CopyableState<*>>>(
            ProjectIndexState::class.java to ProjectIndexState(
                schemaVersion = 3,
                refreshedAtEpochMillis = 123456L,
                projects = mutableListOf(
                    ProjectIndexProjectState(
                        identityId = "id-1",
                        name = "demo",
                        path = "/projects/demo",
                        gitRoot = "/projects/demo",
                        gitRemote = "github.com/org/demo",
                        currentBranch = "main",
                        missing = true,
                        agents = mutableListOf(
                            ProjectIndexAgentState(
                                agentId = "claude",
                                sessions = mutableListOf(
                                    ProjectIndexSessionState(
                                        id = "s-1",
                                        projectPath = "/projects/demo",
                                        startedAtEpochMillis = 100L,
                                        updatedAtEpochMillis = 200L,
                                        sourcePath = "/sessions/s-1.jsonl",
                                        nativeResumeId = "r-1",
                                        messageCount = 7,
                                        title = "Title",
                                        firstMessage = "Prompt",
                                        statistics = mutableMapOf("tokens" to "500"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            EnvironmentIndexState::class.java to EnvironmentIndexState(
                schemaVersion = 1,
                projects = mutableListOf(
                    EnvironmentIndexProjectState(
                        projectId = "p-1",
                        refreshedAtEpochMillis = 789L,
                        agentIds = mutableListOf("claude", "codex"),
                        skills = mutableListOf(
                            EnvironmentIndexSkillState(
                                identityId = "sk-1",
                                name = "review",
                                description = "Review",
                                scope = "PROJECT",
                                consistency = "IDENTICAL",
                                compatibleAgents = mutableListOf("claude"),
                                sources = mutableListOf(
                                    EnvironmentIndexSkillSourceState(
                                        agentId = "claude",
                                        path = "/skills/review",
                                        scope = "PROJECT",
                                        shared = true,
                                        fingerprint = "fp-1",
                                        displayTitle = "Review",
                                        projectName = "demo",
                                        system = true,
                                        realPath = "/real/review",
                                    ),
                                ),
                            ),
                        ),
                        mcpServers = mutableListOf(
                            EnvironmentIndexMcpServerState(
                                id = "m-1",
                                name = "server",
                                transport = "STDIO",
                                environmentVariableNames = mutableListOf("TOKEN"),
                                scope = "GLOBAL",
                                consistency = "SINGLE_SOURCE",
                                sources = mutableListOf(
                                    EnvironmentIndexMcpSourceState("claude", "/mcp.json", "server", "demo"),
                                ),
                            ),
                        ),
                        instructions = mutableListOf(
                            EnvironmentIndexInstructionState(
                                path = "/AGENTS.md",
                                scope = "PROJECT",
                                type = "AGENTS_MD",
                                agentIds = mutableListOf("claude"),
                                projectName = "demo",
                                agentNotes = mutableListOf("claude=note"),
                            ),
                        ),
                        configs = mutableListOf(
                            EnvironmentIndexConfigState(
                                agentId = "claude",
                                path = "/settings.json",
                                scope = "GLOBAL",
                                kind = "SETTINGS",
                                format = "JSON",
                                exists = false,
                                sizeBytes = 42L,
                                modifiedAtEpochMillis = 99L,
                                projectName = "demo",
                                highlights = mutableListOf(EnvironmentIndexConfigHighlightState("model", "sonnet")),
                            ),
                        ),
                        warnings = mutableListOf(
                            EnvironmentIndexWarningState("skill", "claude", "PROJECT", "warn"),
                        ),
                        linkPaths = mutableListOf("/skills/review"),
                    ),
                ),
            ),
            SkillOwnershipState::class.java to SkillOwnershipState(
                schemaVersion = 1,
                entries = mutableListOf(
                    SkillOwnershipEntryState("sk", "host", "GLOBAL", "", "claude", "/p", "SYMLINK", "SYMLINK", "fp", "op", 10L),
                ),
                removedEntries = mutableListOf(
                    SkillOwnershipEntryState("sk2", "host", "GLOBAL", "", "codex", "/p2", "COPY", "COPY", null, null, 20L),
                ),
            ),
            SkillSyncAuditState::class.java to SkillSyncAuditState(
                schemaVersion = 1,
                entries = mutableListOf(
                    SkillSyncAuditEntryState("op-1", 55L, "sk", "host", "GLOBAL", "", "SYNC", mutableListOf("claude"), "SUCCESS"),
                ),
            ),
            SkillSyncSettingsState::class.java to SkillSyncSettingsState(
                schemaVersion = 1,
                preferredSyncMode = "COPY",
                backupBeforeReplacement = false,
                manageExistingTargets = true,
                reviewPlanBeforeApplying = false,
            ),
        )

        for ((clazz, state) in states) {
            val fastXml = XmlStateCodecs.serialize(state)
            // The environment index pools skills in its own layout, which XmlSerializer does not read back.
            val fromXmlSerializer =
                if (clazz == EnvironmentIndexState::class.java) XmlStateCodecs.deserialize(fastXml, clazz) else XmlSerializer.deserialize(fastXml, clazz)
            assertEquals(state, fromXmlSerializer)

            val platformXml = XmlSerializer.serialize(state)
            val fromFastCodec = XmlStateCodecs.deserialize(platformXml, clazz)
            assertEquals(state, fromFastCodec)
            assertEquals(state, state.deepCopy())
        }
    }

    @Test fun `should store a skill shared by several projects once in the environment index`() {
        fun skill(id: String, scope: String) = EnvironmentIndexSkillState(
            identityId = id,
            name = id,
            scope = scope,
            compatibleAgents = mutableListOf("claude"),
            sources = mutableListOf(EnvironmentIndexSkillSourceState(agentId = "claude", path = "/skills/$id", scope = scope)),
        )
        val state = EnvironmentIndexState(
            projects = mutableListOf(
                EnvironmentIndexProjectState(projectId = "a", skills = mutableListOf(skill("global", "GLOBAL"), skill("only-a", "PROJECT"))),
                EnvironmentIndexProjectState(projectId = "b", skills = mutableListOf(skill("global", "GLOBAL"))),
                EnvironmentIndexProjectState(projectId = "c"),
            ),
        )

        val xml = XmlStateCodecs.serialize(state)
        val written = org.jdom.output.XMLOutputter().outputString(xml)

        assertEquals(2, Regex("<option name=\"identityId\"").findAll(written).count())
        val decoded = XmlStateCodecs.deserialize(xml, EnvironmentIndexState::class.java)
        assertEquals(state, decoded)
        assertFalse(decoded.projects[0].skills[0] === decoded.projects[1].skills[0])
    }
}
