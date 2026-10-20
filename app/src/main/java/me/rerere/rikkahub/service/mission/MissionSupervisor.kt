package me.rerere.rikkahub.service.mission

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionExecutionState
import me.rerere.rikkahub.data.mission.MissionRepository
import me.rerere.rikkahub.data.mission.MissionStatus
import me.rerere.rikkahub.data.mission.isRecurring
import me.rerere.rikkahub.data.mission.limits
import me.rerere.rikkahub.data.mission.statusEnum
import me.rerere.rikkahub.data.mission.toArtifact
import me.rerere.rikkahub.data.runevent.RunEventEntity
import me.rerere.rikkahub.data.runevent.RunEventRepository
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.runtime.util.JsonInstant

/** Exact NOTE title the mission_report persistence writes for `done` (see MissionReportTools). */
private const val SIGNAL_DONE_TITLE: String = "mission_report: done"

/** Exact NOTE title the mission_report persistence writes for `blocked` (see MissionReportTools). */
private const val SIGNAL_BLOCKED_TITLE: String = "mission_report: blocked"

/**
 * NOTE title the mission detail page journals when the user presses "stop" (see
 * MissionDetailVM.stopRun). The round still ends as a normal COMPLETED run — without
 * this check the supervisor would happily "continue" what the user just halted.
 */
private const val USER_STOP_TITLE: String = "user_stop"

/** How many recent journal entries [MissionSupervisor.start] subscribes to. */
private const val OBSERVE_RECENT_LIMIT: Int = 200

// AgentRunStatus names — the runStatusProbe returns `status.name`.
private const val RUN_STATUS_QUEUED: String = "queued"
private const val RUN_STATUS_AWAITING_APPROVAL: String = "awaiting_approval"
private const val RUN_STATUS_RUNNING: String = "running"
private const val RUN_STATUS_CANCELLED: String = "cancelled"
private const val RUN_STATUS_SUCCEEDED: String = "succeeded"
private const val RUN_STATUS_FAILED: String = "failed"
private const val RUN_STATUS_PROCESS_LOST: String = "process_lost"

/**
 * 批 A · Mission Runtime (B3) — the mission supervisor (兜底督导).
 *
 * The supervisor is the fallback layer that keeps a mission moving when a round stops
 * before its model declared done/blocked. Two feeds, both funnelling into the same
 * evaluate-and-act core:
 *
 * - [start] subscribes to the recent tail of the run-event journal
 *   ([RunEventRepository.observeRecent]) and reacts to `RUN_FINISHED` events of
 *   mission-bound runs (one evaluation per run id);
 * - [onBoot] scans mission rows once per process for a restart orphan: status `ACTIVE`
 *   with execution posture `RUNNING` whose latest run already reached a terminal state
 *   (the run-finished event may never have been emitted if the process died mid-run).
 *
 * Acting on a finished round means: collect the run's facts (done / blocked signals,
 * whether anything progressed, consecutive stall rounds, user intervention), ask
 * [MissionContinuePolicy] for a verdict, and execute it — complete + notify, block +
 * notify, auto-continue via [MissionLauncher], or yield to the user.
 *
 * Invariants (batch A):
 * - **Never throws.** Every public entry point swallows failures via `runCatching`;
 *   journal, probe and notification hiccups degrade to a no-op.
 * - In-memory state only: [processedRuns] and [stallCounts] reset with the process.
 *   The boot scan is the recovery path and is expected to run once per process — the
 *   dedup set covers replays within a single process, not across restarts.
 * - Notification copy is hardcoded Chinese for batch A — i18n TODO (move to
 *   strings.xml once the notification texts settle).
 * - Token circuit breaker: [ContinueInput.totalTokens] is passed as 0 — per-mission
 *   usage aggregation (usage_records) is not wired yet. TODO after it lands.
 */
class MissionSupervisor(
    private val missionRepository: MissionRepository,
    private val launcher: MissionLauncher,
    private val runEventRepository: RunEventRepository,
    /** runId → 台账状态名（DI 接 AgentRunRepository.getById(id)?.status 的 name）；null=未知。 */
    private val runStatusProbe: RunStatusProbe,
    /** 会话级探针（DI 接 ConversationRepository）。 */
    private val conversationProbe: MissionConversationProbe,
    /** 通知出口（DI 接 NotificationUtil；JVM 测试用记录器）。 */
    private val notify: (title: String, content: String) -> Unit,
    private val appScope: CoroutineScope,
) {

    /** run id → ledger status name; null when the run is unknown to the ledger. */
    fun interface RunStatusProbe {
        suspend fun statusOf(runId: String): String?
    }

    /** Conversation-scoped questions the supervisor asks before acting. */
    interface MissionConversationProbe {
        /** 会话最后一条消息是否来自用户（用户在场 → 让位）。 */
        suspend fun userSpokeLast(conversationId: String): Boolean

        /** 会话最后一条 assistant 文本（续跑交接用）；无则 null。 */
        suspend fun lastAssistantText(conversationId: String): String?
    }

    /** Run ids already evaluated by this process (replay guard; empty after a restart). */
    private val processedRuns: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * mission id → consecutive no-progress rounds. In-memory only: a process restart
     * resets the count (the boot scan grants one fresh round before the breaker can
     * trip again — acceptable for batch A; a persisted counter is a later refinement).
     */
    private val stallCounts = ConcurrentHashMap<String, Int>()

    /** The journal subscription started by [start]; null until it ran. */
    private var observeJob: Job? = null

    /**
     * Subscribe to the journal tail and dispatch `RUN_FINISHED` events to
     * [handleRunFinished]. Idempotent: an active subscription makes further calls
     * no-ops. Runs on [appScope] and never throws — any failure inside the collector
     * (including a broken flow) is swallowed; the next process boot re-scans via
     * [onBoot].
     */
    fun start() {
        if (observeJob?.isActive == true) return
        observeJob = runCatching {
            appScope.launch {
                runCatching {
                    runEventRepository.observeRecent(OBSERVE_RECENT_LIMIT).collect { events ->
                        for (event in events) {
                            runCatching {
                                if (
                                    event.type == RunEventType.RUN_FINISHED.name &&
                                    event.missionId != null &&
                                    event.runId != null
                                ) {
                                    handleRunFinished(event)
                                }
                            }
                        }
                    }
                }
            }
        }.getOrNull()
    }

    /**
     * Core dispatch for one finished mission-round run.
     *
     * Deduplicates by run id (first evaluation wins), loads the mission, and only acts
     * while it is still [MissionStatus.ACTIVE]. A run the user cancelled parks the
     * mission back to [MissionExecutionState.IDLE] and is never resurrected; every other
     * ending falls through to [evaluateAndAct], which decides complete / block / resume
     * / yield. Never throws.
     */
    suspend fun handleRunFinished(event: RunEventEntity) {
        runCatching {
            val missionId = event.missionId ?: return
            val runId = event.runId ?: return
            if (!processedRuns.add(runId)) return

            val mission = missionRepository.getById(missionId) ?: return
            if (mission.statusEnum != MissionStatus.ACTIVE) return

            val status = runStatusProbe.statusOf(runId)
            if (status == RUN_STATUS_CANCELLED) {
                // The user stopped this run — do not resurrect it.
                missionRepository.updateExecutionState(missionId, MissionExecutionState.IDLE)
                return
            }

            evaluateAndAct(mission, runId)
        }
    }

    /**
     * Boot-time orphan scan: for every `ACTIVE` mission whose execution posture is
     * `RUNNING`, check the recorded [MissionEntity.lastRunId]. A run that still looks
     * alive (`null` / queued / awaiting_approval / running) is left alone; a cancelled
     * run parks the mission `IDLE`; a terminal run (succeeded / failed / process_lost)
     * is evaluated exactly like a `RUN_FINISHED` event — that is how a mission resumes
     * after the process died before the run-finished event could be written.
     *
     * Runs each mission in its own `runCatching`; never throws. Expected to be called
     * once per process — the in-memory dedup set is empty after a restart.
     */
    suspend fun onBoot() {
        runCatching {
            val missions = missionRepository.observeAll().first()
            for (mission in missions) {
                runCatching { scanOnBoot(mission) }
            }
        }
    }

    private suspend fun scanOnBoot(mission: MissionEntity) {
        if (mission.status != MissionStatus.ACTIVE.name) return
        if (mission.executionState != MissionExecutionState.RUNNING.name) return
        val runId = mission.lastRunId ?: return

        when (runStatusProbe.statusOf(runId)) {
            null,
            RUN_STATUS_QUEUED,
            RUN_STATUS_AWAITING_APPROVAL,
            RUN_STATUS_RUNNING -> Unit // alive, or managed by the P5 resume machinery
            RUN_STATUS_CANCELLED -> missionRepository.updateExecutionState(
                mission.id,
                MissionExecutionState.IDLE,
            )
            RUN_STATUS_SUCCEEDED,
            RUN_STATUS_FAILED,
            RUN_STATUS_PROCESS_LOST -> if (processedRuns.add(runId)) evaluateAndAct(mission, runId)
            else -> Unit // unknown future status — stay conservative, do nothing
        }
    }

    /**
     * Gather the finished round's facts, ask [MissionContinuePolicy], and act.
     * Everything below is best-effort; failures are swallowed by the caller chain.
     */
    private suspend fun evaluateAndAct(mission: MissionEntity, runId: String) {
        runCatching {
            val events = runEventRepository.observeByRun(runId).first()

            val hasDoneSignal = events.any {
                it.type == RunEventType.NOTE.name && it.title == SIGNAL_DONE_TITLE
            }
            val hasBlockedSignal = events.any {
                it.type == RunEventType.NOTE.name && it.title == SIGNAL_BLOCKED_TITLE
            }
            val hasProgress = events.any {
                it.type == RunEventType.ARTIFACT.name || it.type == RunEventType.TOOL_RESULT.name
            }

            // Stall counter: cleared by any progress in the round, bumped otherwise.
            val stallRounds = if (hasProgress) {
                stallCounts.remove(mission.id)
                0
            } else {
                stallCounts.merge(mission.id, 1, Int::plus) ?: 1
            }

            val userIntervened = mission.conversationId
                ?.let { conversationProbe.userSpokeLast(it) }
                ?: false

            // 批 A — the user pressed "stop" on this mission: the detail page journals a NOTE
            // titled `user_stop`, and the round ends as a normal COMPLETED run, so without
            // this check the supervisor would "continue" what the user just halted. Treat it
            // like Yield: park the posture and leave the mission alone.
            val userStopped = events.any {
                it.type == RunEventType.NOTE.name && it.title == USER_STOP_TITLE
            }
            if (userStopped) {
                missionRepository.updateExecutionState(mission.id, MissionExecutionState.IDLE)
                return@runCatching
            }

            val input = ContinueInput(
                missionStatus = mission.status,
                hasDoneSignal = hasDoneSignal,
                hasBlockedSignal = hasBlockedSignal,
                resumeCount = mission.resumeCount,
                stallRounds = stallRounds,
                maxResumeRounds = mission.limits.maxResumeRounds,
                stallThreshold = mission.limits.stallRounds,
                // TODO(批 A 之后): aggregate usage_records for this mission — the token
                // breaker has its input slot but is not wired yet.
                totalTokens = 0L,
                tokenBudget = mission.limits.tokenBudget,
                userIntervened = userIntervened,
            )

            when (val decision = MissionContinuePolicy.decide(input)) {
                is ContinueDecision.Complete -> complete(mission, events)
                is ContinueDecision.Block -> block(mission, decision.reason)
                is ContinueDecision.Resume -> resume(mission)
                is ContinueDecision.Yield, is ContinueDecision.NoOp -> Unit // leave it to the user
            }
        }
    }

    /** Done signal: mission → DONE, notify the delivery summary (payload `note`, or a generic line). */
    private suspend fun complete(mission: MissionEntity, events: List<RunEventEntity>) {
        val summary = doneNote(events) ?: "目标已达成"

        // 批 B — a trigger-driven mission recurs. A done signal ends THIS occurrence, not the
        // mission: park it back to IDLE (resume budget reset) and keep it ACTIVE so the next
        // fire can launch a fresh round. The user archives it when they no longer want it.
        if (mission.isRecurring) {
            missionRepository.updateExecutionState(mission.id, MissionExecutionState.IDLE)
            missionRepository.resetResumeCount(mission.id)
            missionRepository.logEvent(
                missionId = mission.id,
                type = RunEventType.NOTE,
                title = "occurrence_done",
                detail = summary,
            )
            notify("任务本轮完成：${mission.title}", summary)
            return
        }

        missionRepository.transition(mission.id, MissionStatus.DONE, note = "mission_report(done)")
        missionRepository.updateExecutionState(mission.id, MissionExecutionState.IDLE)
        notify("任务完成：${mission.title}", summary)
    }

    /** Blocked signal / circuit breaker: mission → BLOCKED, notify the reason. */
    private suspend fun block(mission: MissionEntity, reason: String) {
        missionRepository.transition(mission.id, MissionStatus.BLOCKED, note = reason)
        missionRepository.updateExecutionState(mission.id, MissionExecutionState.BLOCKED)
        notify("任务已停下：${mission.title}", reason)
    }

    /**
     * Auto-continue: spend one resume round, compose the handoff prompt (artifact paths
     * so far + the previous round's closing text) and launch the next round in the SAME
     * conversation. A failed launch only notifies — the mission state is left untouched
     * for the user to handle.
     */
    private suspend fun resume(mission: MissionEntity) {
        val newCount = missionRepository.bumpResumeCount(mission.id)
        val artifacts = runEventRepository.observeByMission(mission.id).first()
            .mapNotNull { it.toArtifact()?.path }
        val tail = mission.conversationId?.let { conversationProbe.lastAssistantText(it) }
        val prompt = MissionContinueComposer.compose(
            HandoffContext(
                title = mission.title,
                goal = mission.description,
                goalsChecked = emptyList(),
                artifacts = artifacts,
                lastRunTail = tail,
                round = newCount,
                maxRounds = mission.limits.maxResumeRounds,
            )
        )
        val outcome = launcher.launch(
            missionId = mission.id,
            source = LaunchSource.Continue(newCount),
            continuePrompt = prompt,
        )
        if (!outcome.ok) {
            notify("任务续跑失败：${mission.title}", outcome.error ?: "unknown")
        }
    }

    /** The `note` field of the latest `mission_report: done` payload, when parseable. */
    private fun doneNote(events: List<RunEventEntity>): String? =
        events.firstOrNull {
            it.type == RunEventType.NOTE.name && it.title == SIGNAL_DONE_TITLE
        }
            ?.payloadJson
            ?.let { raw ->
                runCatching {
                    JsonInstant.parseToJsonElement(raw)
                        .jsonObject["note"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                }.getOrNull()
            }
            ?.takeIf { it.isNotBlank() }
}
