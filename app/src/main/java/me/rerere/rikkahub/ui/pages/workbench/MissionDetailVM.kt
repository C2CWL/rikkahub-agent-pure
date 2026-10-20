package me.rerere.rikkahub.ui.pages.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.mission.Artifact
import me.rerere.rikkahub.data.mission.ArtifactsRepository
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionExecutionState
import me.rerere.rikkahub.data.mission.MissionLimits
import me.rerere.rikkahub.data.mission.MissionRepository
import me.rerere.rikkahub.data.mission.MissionSettlement
import me.rerere.rikkahub.data.mission.MissionStatus
import me.rerere.rikkahub.data.mission.acceptanceChecked
import me.rerere.rikkahub.data.mission.executionStateEnum
import me.rerere.rikkahub.data.mission.isLive
import me.rerere.rikkahub.data.runevent.RunEventEntity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.mission.LaunchSource
import me.rerere.rikkahub.service.mission.MissionLauncher
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.uuid.Uuid

class MissionDetailVM(
    private val missionId: String,
    private val missionRepository: MissionRepository,
    private val artifactsRepository: ArtifactsRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
    private val missionLauncher: MissionLauncher,
) : ViewModel() {

    val mission: StateFlow<MissionEntity?> = missionRepository.observeMission(missionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val events: StateFlow<List<RunEventEntity>> = missionRepository.observeEvents(missionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Settlement card data — null until the mission row is first read. */
    val settlement: StateFlow<MissionSettlement?> = artifactsRepository.observeSettlement(missionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Artifacts produced by the mission, oldest first. */
    val artifacts: StateFlow<List<Artifact>> = artifactsRepository.observeArtifacts(missionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleAcceptance(index: Int) {
        viewModelScope.launch {
            val current = mission.value ?: return@launch
            val checked = current.acceptanceChecked.toMutableList()
            if (index in checked) {
                checked.remove(index)
            } else {
                checked.add(index)
            }
            missionRepository.setAcceptanceChecked(missionId, checked.sorted())
        }
    }

    fun transition(to: MissionStatus) {
        viewModelScope.launch {
            val m = runCatching { missionRepository.getById(missionId) }.getOrNull()
            // A transition out of ACTIVE ends the mission, not just its label. Stop the round
            // that may still be generating and park the runtime, so a later restart cannot
            // resume it as an unfinished mission.
            if (m != null && to != MissionStatus.ACTIVE && m.executionStateEnum.isLive) {
                missionRepository.logEvent(
                    missionId = missionId,
                    type = RunEventType.NOTE,
                    title = "user_stop",
                    detail = "mission -> $to",
                    runId = m.lastRunId,
                )
                // Park the posture before cancelling: the generation's terminal callback may
                // fire inside stopGeneration, and the supervisor must already see IDLE.
                missionRepository.updateExecutionState(missionId, MissionExecutionState.IDLE)
                m.conversationId
                    ?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }
                    ?.let { conv -> runCatching { chatService.stopGeneration(conv) } }
            }
            missionRepository.transition(missionId, to)
        }
    }

    /** 批 B — arm / pause the mission's start trigger. */
    fun setTriggerEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            missionRepository.setTriggerEnabled(id, enabled)
        }
    }

    /**
     * 批 B.5 — edit the circuit-breaker limits after creation. Previously they could only
     * be set in the create sheet; a mission that keeps parking itself needs to be tuned
     * from the detail page without being recreated.
     */
    fun updateLimits(limits: MissionLimits) {
        viewModelScope.launch {
            missionRepository.updateLimits(missionId, limits)
        }
    }

    fun addNote(text: String) {
        viewModelScope.launch {
            missionRepository.logEvent(
                missionId = missionId,
                type = RunEventType.NOTE,
                title = text,
            )
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            missionRepository.delete(missionId)
            onDeleted()
        }
    }

    // ------------------------------------------------------------------
    // Batch A — run controls (launch / stop / relaunch)
    // ------------------------------------------------------------------

    /** One-shot launch failure message; consumed by the page via a toast, then cleared. */
    val launchError = MutableStateFlow<String?>(null)

    fun clearLaunchError() {
        launchError.value = null
    }

    /** User-initiated stop: halt generation on the bound conversation and park the runtime. */
    fun stopRun() {
        viewModelScope.launch {
            val m = mission.value ?: return@launch
            val convId = m.conversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                ?: return@launch
            // Journal the stop BEFORE cancelling the generation: the round then ends as a
            // normal COMPLETED run, and the supervisor must be able to see this NOTE on the
            // run's own journal (hence the runId) or it will happily "continue" the stop.
            missionRepository.logEvent(
                missionId = missionId,
                type = RunEventType.NOTE,
                title = "user_stop",
                runId = m.lastRunId,
            )
            // Park the posture before cancelling: the generation's terminal callback may fire
            // inside stopGeneration, and the supervisor must already see IDLE.
            missionRepository.updateExecutionState(missionId, MissionExecutionState.IDLE)
            runCatching { chatService.stopGeneration(convId) }
        }
    }

    /** First / manual launch of the mission (wish-style Create source). */
    fun launchMission() {
        viewModelScope.launch {
            val o = missionLauncher.launch(missionId, LaunchSource.Create)
            if (!o.ok) launchError.value = o.error
        }
    }

    /** Restart a blocked mission: reset the auto-continue budget, re-activate, launch fresh. */
    fun relaunch() {
        viewModelScope.launch {
            missionRepository.resetResumeCount(missionId)
            missionRepository.transition(missionId, MissionStatus.ACTIVE)
            val o = missionLauncher.launch(missionId, LaunchSource.Create)
            if (!o.ok) launchError.value = o.error
        }
    }

    // ------------------------------------------------------------------
    // Batch 1 — artifact open / share (delivery loop)
    // ------------------------------------------------------------------

    /**
     * Load an artifact file's text through the mission's bound workspace, for the
     * in-app preview dialog. Best-effort: null when the assistant / workspace / file
     * cannot be resolved (deleted workspace, oversized file, unreadable content).
     */
    suspend fun loadArtifactText(path: String): String? = try {
        val workspaceId = resolveWorkspaceId() ?: return null
        val size = workspaceRepository.rootfsFileSize(workspaceId, path)
        if (size > MAX_ARTIFACT_READ_BYTES) return null
        ByteArrayOutputStream().use { out ->
            workspaceRepository.exportRootfsFile(workspaceId, path, out)
            out.toString(Charsets.UTF_8.name())
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Export an artifact into [cacheDir] so the page can hand the file to a
     * FileProvider share intent. Best-effort; null when the file cannot be read.
     */
    suspend fun exportArtifactToCache(path: String, cacheDir: File): File? = try {
        val workspaceId = resolveWorkspaceId() ?: return null
        val dir = File(cacheDir, "artifact_share").apply { mkdirs() }
        val file = File(dir, path.substringAfterLast('/').ifBlank { "artifact" })
        file.outputStream().use { out ->
            workspaceRepository.exportRootfsFile(workspaceId, path, out)
        }
        file
    } catch (e: Exception) {
        null
    }

    /** Resolve the workspace bound to the mission, if any. */
    private suspend fun resolveWorkspaceId(): String? {
        // The mission's own binding wins; fall back to its assistant's workspace.
        mission.value?.workspaceId?.takeIf { it.isNotBlank() }?.let { return it }
        val assistantId = mission.value?.assistantId ?: return null
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.assistants.firstOrNull { it.id.toString() == assistantId } ?: return null
        return assistant.workspaceId?.toString()
    }

    companion object {
        /** Preview / share cap — 2 MB, matching the workspace preview bound. */
        private const val MAX_ARTIFACT_READ_BYTES = 2L * 1024 * 1024
    }
}
