package me.rerere.rikkahub.service.mission

/**
 * B1 (批 A · Mission Runtime) — input snapshot for the continuation decision.
 *
 * Pure data: no Room, no Android, no clock. The whole decision matrix therefore runs
 * in a bare-JVM unit test (see `MissionContinuePolicyTest`).
 */
data class ContinueInput(
    /** Mission lifecycle status: `ACTIVE` / `BLOCKED` / `REVIEW` / `DONE` / `ARCHIVED`. */
    val missionStatus: String,
    /** The model declared done in its last round. */
    val hasDoneSignal: Boolean,
    /** The model declared blocked in its last round. */
    val hasBlockedSignal: Boolean,
    /** Auto-continuation rounds already spent on this mission. */
    val resumeCount: Int,
    /** Consecutive rounds without measurable progress. */
    val stallRounds: Int,
    /** Circuit breaker: refuse to auto-continue past this many rounds. */
    val maxResumeRounds: Int,
    /** Circuit breaker: refuse to auto-continue after this many stalled rounds. */
    val stallThreshold: Int,
    /** Tokens burnt by this mission so far. */
    val totalTokens: Long,
    /** Circuit breaker: refuse to auto-continue once [totalTokens] reaches this value. */
    val tokenBudget: Long,
    /** The user touched the mission while a continuation was pending — yield to them. */
    val userIntervened: Boolean,
)

/**
 * The supervisor's verdict for "the previous round stopped — now what?".
 *
 * [Block] reasons are user-visible strings: `"blocked by model"` for a model-declared
 * blockage, or one of the circuit-breaker reasons `"熔断:轮数"` / `"熔断:无进展"` / `"熔断:预算"`.
 */
sealed interface ContinueDecision {
    /** Not active (or already handled elsewhere) — the supervisor leaves the mission alone. */
    object NoOp : ContinueDecision

    /** The model declared done — hand over to the completion flow. */
    object Complete : ContinueDecision

    /** Stop auto-continuing; [reason] is shown to the user. */
    data class Block(val reason: String) : ContinueDecision

    /** Auto-continue; [round] is the 1-based number of the upcoming round. */
    data class Resume(val round: Int) : ContinueDecision

    /** The user stepped in — give the floor back to them. */
    object Yield : ContinueDecision
}

object MissionContinuePolicy {

    /** The only [ContinueInput.missionStatus] value eligible for continuation. */
    const val STATUS_ACTIVE: String = "ACTIVE"

    /**
     * Decide whether a stopped mission round should be automatically continued.
     *
     * Decision order (first match wins):
     * 1. [ContinueInput.userIntervened]             -> [ContinueDecision.Yield]
     * 2. [ContinueInput.missionStatus] != `ACTIVE`  -> [ContinueDecision.NoOp]
     * 3. [ContinueInput.hasBlockedSignal]           -> [ContinueDecision.Block] (`"blocked by model"`)
     * 4. [ContinueInput.hasDoneSignal]              -> [ContinueDecision.Complete]
     * 5. `resumeCount >= maxResumeRounds`           -> [ContinueDecision.Block] (`"熔断:轮数"`)
     * 6. `stallRounds >= stallThreshold`            -> [ContinueDecision.Block] (`"熔断:无进展"`)
     * 7. `totalTokens >= tokenBudget`               -> [ContinueDecision.Block] (`"熔断:预算"`)
     * 8. otherwise                                  -> [ContinueDecision.Resume] (round = resumeCount + 1)
     *
     * The order matters and is intentional: an explicit user intervention always wins;
     * a terminal/explicit model signal (blocked, done) outranks every automatic breaker;
     * and among the breakers the cheapest/most informative reason is reported first
     * (rounds before stall before budget).
     */
    fun decide(input: ContinueInput): ContinueDecision {
        if (input.userIntervened) return ContinueDecision.Yield
        if (input.missionStatus != STATUS_ACTIVE) return ContinueDecision.NoOp
        if (input.hasBlockedSignal) return ContinueDecision.Block("blocked by model")
        if (input.hasDoneSignal) return ContinueDecision.Complete
        if (input.resumeCount >= input.maxResumeRounds) return ContinueDecision.Block("熔断:轮数")
        if (input.stallRounds >= input.stallThreshold) return ContinueDecision.Block("熔断:无进展")
        if (input.totalTokens >= input.tokenBudget) return ContinueDecision.Block("熔断:预算")
        return ContinueDecision.Resume(round = input.resumeCount + 1)
    }
}
