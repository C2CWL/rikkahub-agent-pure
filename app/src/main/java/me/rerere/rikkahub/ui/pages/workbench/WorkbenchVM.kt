package me.rerere.rikkahub.ui.pages.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionRepository
import me.rerere.rikkahub.data.mission.MissionStatus
import me.rerere.rikkahub.data.mission.statusEnum
import me.rerere.rikkahub.subagent.SubAgentRegistry
import me.rerere.rikkahub.subagent.SubAgentRun
import me.rerere.rikkahub.subagent.SubAgentStatus
import java.util.Calendar

class WorkbenchVM(
    private val missionRepository: MissionRepository,
    subAgentRegistry: SubAgentRegistry,
) : ViewModel() {

    val missions: StateFlow<List<MissionEntity>> = missionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeSubAgents: StateFlow<List<SubAgentRun>> = subAgentRegistry.runs
        .map { runs ->
            runs.values
                .filter { it.status == SubAgentStatus.PENDING || it.status == SubAgentStatus.RUNNING }
                .sortedBy { it.startedAtMs }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeMissions: StateFlow<List<MissionEntity>> = missions
        .map { list ->
            list.filter {
                it.statusEnum == MissionStatus.ACTIVE || it.statusEnum == MissionStatus.BLOCKED
            }.sortedByDescending { it.updatedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val reviewMissions: StateFlow<List<MissionEntity>> = missions
        .map { list ->
            list.filter { it.statusEnum == MissionStatus.REVIEW }
                .sortedByDescending { it.updatedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentDoneMissions: StateFlow<List<MissionEntity>> = missions
        .map { list ->
            list.filter { it.statusEnum == MissionStatus.DONE }
                .sortedByDescending { it.completedAt ?: it.updatedAt }
                .take(3)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val doneToday: StateFlow<Int> = missions
        .map { list ->
            list.count { mission ->
                mission.statusEnum == MissionStatus.DONE &&
                    mission.completedAt?.let { ts -> isToday(ts) } == true
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun createMission(title: String, description: String, acceptance: List<String>, conversationId: String? = null) {
        viewModelScope.launch {
            missionRepository.create(
                title = title,
                description = description,
                acceptance = acceptance,
                conversationId = conversationId,
            )
        }
    }

    fun transition(id: String, to: MissionStatus) {
        viewModelScope.launch {
            missionRepository.transition(id, to)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            missionRepository.delete(id)
        }
    }
}

private fun isToday(timestamp: Long): Boolean {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    return now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
}
