package com.shutterstar.agenthub.environment.skills.sync.persistence

import com.shutterstar.agenthub.storage.AgentHubStorage
import com.shutterstar.agenthub.storage.StateStore
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.shutterstar.agenthub.environment.skills.sync.settings.SkillSyncSettings

/** Shared production state; standalone tests default to an injectable in-memory store. */
@Service(Service.Level.APP)
class SkillSyncSettingsStateService(
    private val store: StateStore<SkillSyncSettingsState> = AgentHubStorage.state("sync-settings", SkillSyncSettingsState::class.java, ::SkillSyncSettingsState),
) {
    private class StoredView(val stamp: String, val settings: SkillSyncSettings)

    @Volatile
    private var storedView: StoredView? = null

    private var storedState: SkillSyncSettingsState
        get() = store.snapshot()
        set(value) { store.update { value } }

    val state: SkillSyncSettingsState get() = storedState

    fun loadState(state: SkillSyncSettingsState) {
        this.storedState = state
    }

    fun current(): SkillSyncSettings {
        val stamp = store.stamp()
        storedView?.takeIf { it.stamp == stamp }?.let { return it.settings }
        val settings = SkillSyncSettingsStateMapper.toSettings(store.snapshot())
        storedView = StoredView(stamp, settings)
        return settings
    }

    @Synchronized
    fun update(settings: SkillSyncSettings) {
        store.update { SkillSyncSettingsStateMapper.toState(settings) }
    }

    fun storageStamp(): String = store.externalStamp()

    companion object {
        fun getInstance(): SkillSyncSettingsStateService = service()
    }
}
