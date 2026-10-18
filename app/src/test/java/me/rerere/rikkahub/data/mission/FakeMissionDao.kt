package me.rerere.rikkahub.data.mission

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [MissionDao] for JVM unit tests — no Room, no instrumentation.
 *
 * Mirrors the real DAO's semantics (upsert = insert-or-replace, `trimEvents` keeps
 * the 500 newest events by `created_at`, `findActiveByConversation` filters terminal
 * statuses) so [MissionRepository]'s state-machine serialisation, event-trim, and
 * cascade-delete logic can be unit-tested fast on the JVM.
 *
 * Backed by [ConcurrentHashMap]s so concurrent writes from the repository's
 * coroutine Mutex don't ConcurrentModificationException — the repository's own
 * Mutex is what's actually under test.
 */
class FakeMissionDao : MissionDao {

    private val missions = ConcurrentHashMap<String, MissionEntity>()
    private val events = ConcurrentHashMap<String, MissionEventEntity>()

    private val missionsFlow = MutableStateFlow<List<MissionEntity>>(emptyList())
    private val eventsFlow = MutableStateFlow<List<MissionEventEntity>>(emptyList())

    private fun publishMissions() {
        missionsFlow.value = missions.values.sortedByDescending { it.updatedAt }
    }

    private fun publishEvents(missionId: String) {
        eventsFlow.value = events.values
            .filter { it.missionId == missionId }
            .sortedBy { it.createdAt }
    }

    fun snapshotMissions(): List<MissionEntity> =
        missions.values.sortedByDescending { it.updatedAt }

    fun snapshotEvents(missionId: String): List<MissionEventEntity> =
        events.values.filter { it.missionId == missionId }.sortedBy { it.createdAt }

    override suspend fun upsert(m: MissionEntity) {
        missions[m.id] = m
        publishMissions()
    }

    override fun observeMission(id: String): Flow<MissionEntity?> =
        missionsFlow.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun getById(id: String): MissionEntity? = missions[id]

    override fun observeAll(): Flow<List<MissionEntity>> = missionsFlow

    override suspend fun findActiveByConversation(cid: String): MissionEntity? =
        missions.values
            .filter {
                it.conversationId == cid &&
                    it.statusEnum !in setOf(MissionStatus.DONE, MissionStatus.ARCHIVED)
            }
            .maxByOrNull { it.updatedAt }

    override suspend fun deleteById(id: String) {
        missions.remove(id)
        publishMissions()
    }

    override suspend fun insertEvent(e: MissionEventEntity) {
        events[e.id] = e
        publishEvents(e.missionId)
    }

    override fun observeEvents(missionId: String): Flow<List<MissionEventEntity>> =
        eventsFlow.map { list -> list.filter { it.missionId == missionId } }

    override suspend fun deleteEvents(missionId: String) {
        events.entries.removeAll { it.value.missionId == missionId }
        publishEvents(missionId)
    }

    override suspend fun trimEvents(missionId: String) {
        val all = events.values.filter { it.missionId == missionId }
            .sortedByDescending { it.createdAt }
        val keep = all.take(500).map { it.id }.toSet()
        events.entries.removeAll { it.value.missionId == missionId && it.value.id !in keep }
        publishEvents(missionId)
    }
}
