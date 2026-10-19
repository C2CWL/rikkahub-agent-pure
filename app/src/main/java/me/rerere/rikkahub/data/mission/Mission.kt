package me.rerere.rikkahub.data.mission

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.runtime.util.JsonInstant

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
 * 批 A — execution posture of a mission, stored as the name in [MissionEntity.executionState].
 *
 * Kept separate from [MissionStatus] on purpose: status is the *workflow* state the user
 * manages (active → blocked/review → done), while this tracks what the runtime is doing
 * right now (idle / a run in flight / parked on a tool approval / stopped by the circuit
 * breaker). One mission can be ACTIVE status while its execution state cycles many times.
 */
enum class MissionExecutionState {
    IDLE,
    RUNNING,
    WAITING_APPROVAL,
    BLOCKED,
}

/**
 * A workbench mission — a tracked unit of work with acceptance criteria, lifecycle
 * status, and a timeline of events (see [me.rerere.rikkahub.data.runevent.RunEventEntity],
 * the unified run-event journal that superseded the day-one `mission_events` table).
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
    // -------------------------------------------------------------------------------------
    // 批 A（任务运行时，v35）— execution & configuration columns
    // -------------------------------------------------------------------------------------
    /** Execution workspace (WorkspaceEntity.id); null = not bound to a workspace. */
    @ColumnInfo("workspace_id")
    val workspaceId: String? = null,
    /** Per-mission approval posture (ApprovalMode name); null = inherit the global setting. */
    @ColumnInfo("approval_mode")
    val approvalMode: String? = null,
    /** Per-mission runtime-limit overrides ([MissionLimits] JSON); null = global defaults. */
    @ColumnInfo("limits_json")
    val limitsJson: String? = null,
    /** Auto-continue rounds already consumed (circuit-breaker counter; reset on user action). */
    @ColumnInfo("resume_count", defaultValue = "0")
    val resumeCount: Int = 0,
    /** Latest execution run id (= agent_runs.id); null before the first launch. */
    @ColumnInfo("last_run_id")
    val lastRunId: String? = null,
    /** Execution posture, stored as [MissionExecutionState]'s name. Default "idle". */
    @ColumnInfo("execution_state", defaultValue = "idle")
    val executionState: String = "idle",
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

/**
 * 批 A — per-mission runtime limits with balanced defaults ("均衡值"), overridable per mission
 * via [MissionEntity.limitsJson] and globally via the task settings screen.
 *
 * - [maxResumeRounds]: the auto-continue circuit breaker — how many extra rounds the supervisor
 *   may start after a round ends without a done/blocked signal.
 * - [stallRounds]: consecutive no-progress rounds (no new artifact, no tool result) that stop
 *   the loop even before [maxResumeRounds] is reached.
 * - [tokenBudget]: cumulative token ceiling for the whole mission (checked when the usage
 *   aggregation lands; kept here so the wire format does not change later).
 */
@Serializable
data class MissionLimits(
    val maxResumeRounds: Int = 5,
    val stallRounds: Int = 2,
    val tokenBudget: Long = 500_000L,
)

/** Decode [MissionEntity.limitsJson]; null / blank / malformed → balanced defaults. */
val MissionEntity.limits: MissionLimits
    get() = runCatching {
        limitsJson?.takeIf { it.isNotBlank() }
                ?.let { JsonInstant.decodeFromString<MissionLimits>(it) }
    }.getOrNull() ?: MissionLimits()

/** Decode [MissionEntity.executionState] into a [MissionExecutionState], defaulting to [MissionExecutionState.IDLE]. */
val MissionEntity.executionStateEnum: MissionExecutionState
    get() = MissionExecutionState.entries.firstOrNull { it.name == executionState }
        ?: MissionExecutionState.IDLE
