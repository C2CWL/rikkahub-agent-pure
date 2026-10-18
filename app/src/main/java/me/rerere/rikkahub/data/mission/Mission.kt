package me.rerere.rikkahub.data.mission

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.utils.JsonInstant

/**
 * Workbench Mission — lifecycle status for a mission row.
 *
 * The state machine allows missions to flow from active work through review to completion
 * (and eventual archival). Terminal states ([DONE], [ARCHIVED]) cannot be left except via
 * the single [DONE] → [ARCHIVED] edge.
 */
enum class MissionStatus(val isTerminal: Boolean) {
    ACTIVE(false),
    BLOCKED(false),
    REVIEW(false),
    DONE(true),
    ARCHIVED(true);

    /**
     * Whether a transition from `this` to [to] is legal under the mission state machine.
     */
    fun canTransitionTo(to: MissionStatus): Boolean = when (this) {
        ACTIVE -> to in setOf(BLOCKED, REVIEW, DONE, ARCHIVED)
        BLOCKED -> to in setOf(ACTIVE, ARCHIVED)
        REVIEW -> to in setOf(ACTIVE, DONE, ARCHIVED)
        DONE -> to == ARCHIVED
        ARCHIVED -> false
    }
}

/**
 * Discriminator for the mission event timeline. Stored as the enum's [name] in the
 * `type` column of [MissionEventEntity].
 */
enum class MissionEventType {
    CREATED,
    STATUS_CHANGED,
    NOTE,
    ARTIFACT,
    MILESTONE,
    SUBAGENT_DISPATCHED,
    SUBAGENT_FINISHED,
    ACCEPTANCE_UPDATED
}

/**
 * A workbench mission — a tracked unit of work with acceptance criteria, lifecycle
 * status, and a timeline of events ([MissionEventEntity]).
 *
 * JSON columns ([acceptanceJson], [acceptanceCheckedJson]) are decoded via the
 * convenience extensions ([acceptanceCriteria], [acceptanceChecked]) using
 * [JsonInstant].
 */
@Entity(
    tableName = "missions",
    indices = [
        Index("status"),
        Index("conversation_id"),
        Index("updated_at"),
    ],
)
data class MissionEntity(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("description")
    val description: String = "",
    @ColumnInfo("status")
    val status: String = "ACTIVE",
    @ColumnInfo("acceptance_json")
    val acceptanceJson: String = "[]",
    @ColumnInfo("acceptance_checked_json")
    val acceptanceCheckedJson: String = "[]",
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("assistant_id")
    val assistantId: String? = null,
    @ColumnInfo("artifact_count")
    val artifactCount: Int = 0,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("completed_at")
    val completedAt: Long? = null,
)

/**
 * A single timeline entry for a mission. Events are append-only and capped at 500 per
 * mission via [MissionDao.trimEvents] (called by [MissionRepository.logEvent]).
 */
@Entity(
    tableName = "mission_events",
    indices = [
        Index("mission_id", "created_at"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = MissionEntity::class,
            parentColumns = ["id"],
            childColumns = ["mission_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MissionEventEntity(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("mission_id")
    val missionId: String,
    @ColumnInfo("type")
    val type: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("detail")
    val detail: String? = null,
    @ColumnInfo("payload_json")
    val payloadJson: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
)

// ---------------------------------------------------------------------------
// Convenience extensions — decode JSON columns via JsonInstant.
// ---------------------------------------------------------------------------

/** Decode the stored status string into a [MissionStatus], defaulting to [MissionStatus.ACTIVE]. */
val MissionEntity.statusEnum: MissionStatus
    get() = MissionStatus.entries.firstOrNull { it.name == status }
        ?: MissionStatus.ACTIVE

/** Decode [MissionEntity.acceptanceJson] into a list of acceptance criteria strings. */
val MissionEntity.acceptanceCriteria: List<String>
    get() = runCatching {
        JsonInstant.decodeFromString<List<String>>(acceptanceJson)
    }.getOrDefault(emptyList())

/** Decode [MissionEntity.acceptanceCheckedJson] into a list of checked criterion indices. */
val MissionEntity.acceptanceChecked: List<Int>
    get() = runCatching {
        JsonInstant.decodeFromString<List<Int>>(acceptanceCheckedJson)
    }.getOrDefault(emptyList())
