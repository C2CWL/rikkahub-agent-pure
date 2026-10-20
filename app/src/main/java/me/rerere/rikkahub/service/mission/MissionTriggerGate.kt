package me.rerere.rikkahub.service.mission

/**
 * 批 B — outcome of one trigger fire attempt. Only [FIRED] starts a run; every other value is
 * a silent skip (journalled by the scheduler, never surfaced as a notification — a busy or
 * capped trigger must not spam the user).
 */
enum class TriggerFireGate {
    FIRED,

    /** Mission is gone or no longer ACTIVE (done / archived / blocked). */
    SKIPPED_NOT_ACTIVE,

    /** Trigger paused, or the mission has no usable trigger spec. */
    SKIPPED_DISABLED,

    /** A round is already running / waiting for approval — never run two rounds at once. */
    SKIPPED_BUSY,

    /** Last accepted fire is still inside the cooldown window. */
    SKIPPED_COOLDOWN,

    /** Today's fire budget is exhausted. */
    SKIPPED_DAILY_CAP,
}

/**
 * 批 B — pure gate in front of every trigger fire.
 *
 * Kept free of Android / Room so the whole decision matrix is unit-testable on the JVM; the
 * repository wraps it in its write mutex so "check + consume" is atomic.
 *
 * Evaluation order matters and mirrors the contract:
 * status → armed → busy → daily cap → cooldown → fire.
 * - A busy mission is skipped *before* the cap/cooldown so a long run never burns a daily slot.
 * - The daily cap is checked before the cooldown because a capped mission should stay quiet for
 *   the rest of the day even if the cooldown has elapsed.
 */
object MissionTriggerGate {

    /** Everything the gate needs; all values come from a freshly-read mission row. */
    data class Input(
        /** MissionStatus name (ACTIVE / BLOCKED / REVIEW / DONE / ARCHIVED). */
        val status: String,
        /** Trigger configured + enabled. */
        val triggerArmed: Boolean,
        /** MissionExecutionState name (idle / running / waiting_approval / blocked). */
        val executionState: String,
        val lastFiredAt: Long?,
        /** Fires already consumed today (caller must zero this when the stored day is stale). */
        val firesToday: Int,
        val maxRunsPerDay: Int?,
        val cooldownSeconds: Int,
        val nowMs: Long,
    )

    fun evaluate(input: Input): TriggerFireGate {
        if (input.status != "ACTIVE") return TriggerFireGate.SKIPPED_NOT_ACTIVE
        if (!input.triggerArmed) return TriggerFireGate.SKIPPED_DISABLED
        // Execution state is written as the enum's name ("RUNNING" / "WAITING_APPROVAL") but
        // the column default is lowercase "idle" — normalise before comparing.
        val posture = input.executionState.uppercase()
        if (posture == "RUNNING" || posture == "WAITING_APPROVAL") {
            return TriggerFireGate.SKIPPED_BUSY
        }
        val cap = input.maxRunsPerDay
        if (cap != null && cap > 0 && input.firesToday >= cap) {
            return TriggerFireGate.SKIPPED_DAILY_CAP
        }
        val last = input.lastFiredAt
        if (input.cooldownSeconds > 0 && last != null &&
            input.nowMs < last + input.cooldownSeconds * 1000L
        ) {
            return TriggerFireGate.SKIPPED_COOLDOWN
        }
        return TriggerFireGate.FIRED
    }
}