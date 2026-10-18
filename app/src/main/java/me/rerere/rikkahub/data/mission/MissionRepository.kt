package me.rerere.rikkahub.data.mission

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

private const val TAG = "MissionRepository"

/**
 * Workbench Mission — single shared writer/reader for the mission tables.
 *
 * All mutating methods serialise through a [Mutex] so two concurrent transitions on the
 * same mission can't last-writer-win each other's status field — each transition is
 * read-modify-write under the lock.
 *
 * [logEvent] and [attachArtifact] are best-effort: failures are logged, never thrown.
 * The timeline is observability infrastructure — a write failure here must never break
 * the mission it is tracking. [transition] validates the state machine and returns
 * `false` for illegal transitions without throwing.
 */
class MissionRepository(private val dao: MissionDao) {

    private val writeMutex = Mutex()

    fun observeAll(): Flow<List<MissionEntity>> = dao.observeAll()

    fun observeMission(id: String): Flow<MissionEntity?> = dao.observeMission(id)

    fun observeEvents(missionId: String): Flow<List<MissionEventEntity>> = dao.observeEvents(missionId)

    /**
     * Create a new mission in [MissionStatus.ACTIVE] and log a [MissionEventType.CREATED]
     * event. Returns the persisted entity.
     */
    suspend fun create(
        title: String,
        description: String = "",
        acceptance: List<String> = emptyList(),
        conversationId: String? = null,
        assistantId: String? = null,
    ): MissionEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val entity = MissionEntity(
            id = id,
            title = title,
            description = description,
            status = MissionStatus.ACTIVE.name,
            acceptanceJson = JsonInstant.encodeToString(acceptance),
            acceptanceCheckedJson = "[]",
            conversationId = conversationId,
            assistantId = assistantId,
            artifactCount = 0,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
        )
        writeMutex.withLock {
            dao.upsert(entity)
        }
        logEvent(id, MissionEventType.CREATED, title = "Mission created: $title")
        return entity
    }

    /**
     * Transition a mission to [to]. Returns `true` if the transition was legal and applied,
     * `false` if the state machine ([MissionStatus.canTransitionTo]) rejects it or the
     * mission does not exist. On success logs a [MissionEventType.STATUS_CHANGED] event.
     *
     * Entering [MissionStatus.DONE] stamps [MissionEntity.completedAt]; leaving
     * [MissionStatus.DONE] clears it.
     */
    suspend fun transition(id: String, to: MissionStatus, note: String? = null): Boolean {
        var result = false
        writeMutex.withLock {
            val existing = dao.getById(id) ?: return@withLock
            val current = existing.statusEnum
            if (!current.canTransitionTo(to)) return@withLock
            val now = System.currentTimeMillis()
            val completedAt = when {
                to == MissionStatus.DONE -> now
                current == MissionStatus.DONE -> null
                else -> existing.completedAt
            }
            dao.upsert(
                existing.copy(
                    status = to.name,
                    updatedAt = now,
                    completedAt = completedAt,
                )
            )
            runCatching {
                dao.insertEvent(
                    MissionEventEntity(
                        id = Uuid.random().toString(),
                        missionId = id,
                        type = MissionEventType.STATUS_CHANGED.name,
                        title = "${current.name} -> ${to.name}",
                        detail = note,
                        payloadJson = null,
                        createdAt = now,
                    )
                )
                dao.trimEvents(id)
            }.onFailure { logSafe { Log.w(TAG, "transition($id) event log failed", it) } }
            result = true
        }
        return result
    }

    /**
     * Update the acceptance criteria checked-state for a mission. The [checked] list holds
     * indices of criteria that are satisfied; stored as JSON in
     * [MissionEntity.acceptanceCheckedJson].
     */
    suspend fun setAcceptanceChecked(id: String, checked: List<Int>) {
        writeMutex.withLock {
            val existing = dao.getById(id) ?: return@withLock
            dao.upsert(
                existing.copy(
                    acceptanceCheckedJson = JsonInstant.encodeToString(checked),
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * Append a timeline event to a mission. Best-effort — failures are logged, never
     * thrown. After inserting, trims the mission's events to the latest 500 via
     * [MissionDao.trimEvents].
     */
    suspend fun logEvent(
        missionId: String,
        type: MissionEventType,
        title: String,
        detail: String? = null,
        payloadJson: String? = null,
    ) {
        runCatching {
            writeMutex.withLock {
                dao.insertEvent(
                    MissionEventEntity(
                        id = Uuid.random().toString(),
                        missionId = missionId,
                        type = type.name,
                        title = title,
                        detail = detail,
                        payloadJson = payloadJson,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                dao.trimEvents(missionId)
            }
        }.onFailure { logSafe { Log.w(TAG, "logEvent($missionId, $type) failed", it) } }
    }

    /**
     * Record an artifact produced by a tool execution. Logs an
     * [MissionEventType.ARTIFACT] event, increments [MissionEntity.artifactCount], and
     * bumps [MissionEntity.updatedAt]. Best-effort — failures are logged, never thrown.
     */
    suspend fun attachArtifact(
        missionId: String,
        path: String,
        sourceTool: String,
        summary: String? = null,
    ) {
        runCatching {
            writeMutex.withLock {
                val existing = dao.getById(missionId) ?: return@withLock
                val now = System.currentTimeMillis()
                dao.upsert(
                    existing.copy(
                        artifactCount = existing.artifactCount + 1,
                        updatedAt = now,
                    )
                )
                dao.insertEvent(
                    MissionEventEntity(
                        id = Uuid.random().toString(),
                        missionId = missionId,
                        type = MissionEventType.ARTIFACT.name,
                        title = path,
                        detail = summary,
                        payloadJson = JsonInstant.encodeToString(
                            buildJsonObject {
                                put("path", path)
                                put("sourceTool", sourceTool)
                            }
                        ),
                        createdAt = now,
                    )
                )
                dao.trimEvents(missionId)
            }
        }.onFailure { logSafe { Log.w(TAG, "attachArtifact($missionId) failed", it) } }
    }

    /**
     * Delete a mission and all its events. The [ForeignKey] CASCADE on
     * [MissionEventEntity] also handles event cleanup at the DB level, but events are
     * deleted explicitly first for clarity and for the in-memory fake.
     */
    suspend fun delete(id: String) {
        writeMutex.withLock {
            dao.deleteEvents(id)
            dao.deleteById(id)
        }
    }

    /** The most recently updated non-terminal mission bound to [cid], or null. */
    suspend fun findActiveByConversation(cid: String): MissionEntity? =
        runCatching { dao.findActiveByConversation(cid) }.getOrNull()
}

/**
 * Run a [android.util.Log] call inside a guard so JVM unit tests — where
 * `android.util.Log` is an unmocked stub that throws — don't crash the code under test.
 * Mirrors [AgentRunRepository]'s `logSafe`.
 */
private inline fun logSafe(block: () -> Unit) {
    runCatching { block() }
}
