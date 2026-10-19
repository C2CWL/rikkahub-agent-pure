package me.rerere.rikkahub.service.mission

import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.ai.tools.ConversationSystemAddendum
import me.rerere.rikkahub.data.mission.MissionExecutionState
import me.rerere.rikkahub.data.mission.MissionRepository
import me.rerere.rikkahub.data.mission.MissionStatus
import me.rerere.rikkahub.data.mission.acceptanceCriteria
import me.rerere.rikkahub.data.mission.statusEnum
import me.rerere.rikkahub.data.runevent.RunEventRepository
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.service.run.RunRequest
import me.rerere.rikkahub.service.run.RunService
import kotlin.uuid.Uuid

/** Kickoff instruction appended to the mission directive on the Create launch (built-in first round). */
private const val CREATE_KICKOFF =
    "现在开始执行这个任务：先给出简短计划，然后立即动手。产出放进工作区。"

/** Wall-clock cap for every mission round; retry/continue decisions belong to the supervisor. */
private const val LAUNCH_TIMEOUT_MS: Long = 30L * 60_000L

/** Where a launch originated — the wish-style first run ("Create") or a supervisor continuation. */
sealed interface LaunchSource {

    /** 许愿式首跑：无执行会话时由 RunService 新建（之后回写 mission），prompt 用内置守则 + 启动指令。 */
    object Create : LaunchSource

    /**
     * 自动续跑第 [round] 轮（1-based）。prompt 由调用方（Supervisor）用
     * [MissionContinueComposer.compose] 组装好后经 `continuePrompt` 传入。
     */
    data class Continue(val round: Int) : LaunchSource
}

/** Result of [MissionLauncher.launch]; `ok = false` carries a human-readable [error]. */
data class LaunchOutcome(val ok: Boolean, val runId: String? = null, val error: String? = null)

/**
 * Batch A — the single entry point that turns a mission row into an execution run.
 *
 * One [launch] does the whole dance: read + validate the mission → resolve the execution
 * conversation → compose the prompt → resolve the assistant → [RunService.submit] →
 * best-effort bookkeeping (backfill conversation, execution state, session registry,
 * launch journal entry).
 *
 * Create vs Continue:
 * - [LaunchSource.Create]: wish-style first run. With no (or an unparseable) mission
 *   conversation, submit is asked to create a fresh conversation and the handle's id is
 *   backfilled onto the mission; an existing conversation id is reused as-is.
 * - [LaunchSource.Continue]: the supervisor loop re-enters the SAME conversation. Refused
 *   (without submitting) when the mission is not ACTIVE, has no usable conversation, or
 *   the caller did not supply `continuePrompt`.
 *
 * The mission directive ([MissionContinueComposer.composeDirective]) is armed as the
 * conversation's system addendum on every launch so each round's generation sees it
 * without the transcript carrying scaffolding (awaitTerminal clears it at run end; the
 * next launch re-arms it).
 *
 * Bookkeeping is deliberately best-effort — a journal or backfill hiccup must never turn
 * a successfully started run into a launch failure. Fatal problems (mission missing, no
 * assistant) come back as [LaunchOutcome.ok] = false. [launch] never throws.
 */
class MissionLauncher(
    private val missionRepository: MissionRepository,
    private val runService: RunService,
    private val runEventRepository: RunEventRepository,
    /** mission.assistantId (nullable) → the assistant Uuid to run with; fallback logic lives in DI. */
    private val assistantResolver: suspend (String?) -> Uuid?,
) {

    /** Launch (or continue) mission [missionId]; see the class KDoc for the full dance. */
    suspend fun launch(
        missionId: String,
        source: LaunchSource,
        continuePrompt: String? = null,
    ): LaunchOutcome {
        return try {
            val mission = missionRepository.getById(missionId)
                ?: return LaunchOutcome(ok = false, error = "Mission not found: $missionId")

            if (source is LaunchSource.Continue && mission.statusEnum != MissionStatus.ACTIVE) {
                return LaunchOutcome(
                    ok = false,
                    error = "Mission is ${mission.status}, not ACTIVE — continue refused",
                )
            }

            // Resolve the conversation this run targets. An id that fails to parse counts as
            // "no conversation" (Create: fresh one; Continue: refusal — handled below).
            val missionConversation = mission.conversationId
                ?.takeIf { it.isNotBlank() }
                ?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }
            val conversationUuid: Uuid? = when (source) {
                LaunchSource.Create -> missionConversation
                is LaunchSource.Continue -> missionConversation
                    ?: return LaunchOutcome(
                        ok = false,
                        error = "Mission $missionId has no usable execution conversation; continue refused",
                    )
            }
            // Create with no usable conversation → submit creates a fresh one, and the
            // handle's id is backfilled onto the mission after it returns.
            val isNewConversation = conversationUuid == null

            val directive = MissionContinueComposer.composeDirective(
                mission.title,
                mission.description,
                mission.acceptanceCriteria,
            )
            val prompt = when (source) {
                LaunchSource.Create -> "$directive\n\n$CREATE_KICKOFF"
                is LaunchSource.Continue -> continuePrompt
                    ?: return LaunchOutcome(
                        ok = false,
                        error = "Continue refused: continuePrompt is required",
                    )
            }

            val assistantId = assistantResolver(mission.assistantId)
                ?: return LaunchOutcome(
                    ok = false,
                    error = "No assistant available for mission $missionId",
                )

            // Approval posture → headless flag: SEMI / MANUAL keep the human in the loop;
            // AUTO, unset and anything unrecognised run headless (inherit-friendly default).
            val headless = when (mission.approvalMode?.trim()?.uppercase()) {
                "SEMI", "MANUAL" -> false
                else -> true
            }

            // Arm the mission directive before submit when the conversation is known, so the
            // generation that submit kicks off is guaranteed to see it.
            conversationUuid?.let { conv ->
                runCatching { ConversationSystemAddendum.set(conv, directive) }
            }

            val handle = runService.submit(
                RunRequest(
                    assistantId = assistantId,
                    prompt = prompt,
                    title = "任务：${mission.title}",
                    kind = AgentRunKind.Mission,
                    missionId = missionId,
                    conversationId = conversationUuid,
                    headless = headless,
                    timeoutMs = LAUNCH_TIMEOUT_MS,
                )
            )

            // ---- post-submit bookkeeping — best-effort, never fails the launch --------------
            // A fresh conversation only materialises inside submit; arm its directive too
            // (idempotent for the reused-conversation case).
            runCatching { ConversationSystemAddendum.set(handle.conversationId, directive) }
            if (isNewConversation) {
                runCatching {
                    missionRepository.updateConversation(missionId, handle.conversationId.toString())
                }
            }
            runCatching {
                missionRepository.updateExecutionState(
                    missionId,
                    MissionExecutionState.RUNNING,
                    lastRunId = handle.id,
                )
            }
            runCatching { MissionSessions.register(handle.conversationId.toString(), missionId) }
            runCatching {
                runEventRepository.emit(
                    type = RunEventType.NOTE,
                    title = "launch",
                    detail = when (source) {
                        LaunchSource.Create -> "create"
                        is LaunchSource.Continue -> "continue round ${source.round}"
                    },
                    missionId = missionId,
                    conversationId = handle.conversationId.toString(),
                    runId = handle.id,
                )
            }

            LaunchOutcome(ok = true, runId = handle.id)
        } catch (t: Throwable) {
            LaunchOutcome(
                ok = false,
                error = "launch failed: ${t.message ?: t::class.simpleName ?: "unknown error"}",
            )
        }
    }
}
