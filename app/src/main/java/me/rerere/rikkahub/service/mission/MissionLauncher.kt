package me.rerere.rikkahub.service.mission

import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.ai.tools.ConversationSystemAddendum
import me.rerere.rikkahub.data.mission.MissionEntity
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

    /**
     * 批 B — 定时 / 触发唤醒的一轮。复用已有执行会话（没有则新建），prompt 是任务守则 +
     * 触发说明。任务必须是 ACTIVE；[label] 用于时间线与开场指令（如「定时 09:00」「Wi-Fi 连接」）。
     */
    data class Trigger(val label: String) : LaunchSource
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
    /**
     * 批 C — 工作流程文档的读取出口（DI 接 WorkspaceRepository）；null = 不注入流程。
     * 测试与旧构造点不传时行为与批 B 完全一致。
     */
    private val workflowSource: MissionWorkflowSource? = null,
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

            if (source !is LaunchSource.Create && mission.statusEnum != MissionStatus.ACTIVE) {
                return LaunchOutcome(
                    ok = false,
                    error = "Mission is ${mission.status}, not ACTIVE — launch refused",
                )
            }

            // Resolve the conversation this run targets. An id that fails to parse counts as
            // "no conversation" (Create: fresh one; Continue: refusal — handled below).
            val missionConversation = mission.conversationId
                ?.takeIf { it.isNotBlank() }
                ?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }
            val conversationUuid: Uuid? = when (source) {
                LaunchSource.Create, is LaunchSource.Trigger -> missionConversation
                is LaunchSource.Continue -> missionConversation
                    ?: return LaunchOutcome(
                        ok = false,
                        error = "Mission $missionId has no usable execution conversation; continue refused",
                    )
            }
            val directive = MissionContinueComposer.composeDirective(
                mission.title,
                mission.description,
                mission.acceptanceCriteria,
            )
            val prompt = when (source) {
                LaunchSource.Create -> joinBlocks(directive, missionWorkflowBlock(mission), CREATE_KICKOFF)
                is LaunchSource.Trigger ->
                    joinBlocks(
                        directive,
                        missionWorkflowBlock(mission),
                        "[系统] 本任务的开始方式被触发（${source.label}）。" +
                            "现在开始执行本轮任务：先给简短计划，然后立即动手；完成后调用 mission_report(done)。",
                    )
                is LaunchSource.Continue -> {
                    val continueText = continuePrompt
                        ?: return LaunchOutcome(
                            ok = false,
                            error = "Continue refused: continuePrompt is required",
                        )
                    joinBlocks(missionWorkflowContinueHint(mission), continueText)
                }
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

            // Arm the mission directive and bind the mission↔conversation mapping before submit
            // when the conversation is known, so the generation that submit kicks off is
            // guaranteed to see both. For a fresh conversation the id only exists inside
            // RunService.submit; that path registers the binding itself before dispatching.
            conversationUuid?.let { conv ->
                runCatching { ConversationSystemAddendum.set(conv, directive) }
                runCatching { MissionSessions.register(conv.toString(), missionId) }
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
            // Backfill whenever submit did not hand back the conversation we asked for: a
            // mission with no conversation yet, or a stale id that no longer resolves (submit
            // then creates a fresh conversation). Without the second case the mission keeps
            // pointing at the dead conversation, so every later round reports into nothing.
            if (handle.conversationId != conversationUuid) {
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
                        is LaunchSource.Trigger -> "trigger: ${source.label}"
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

    /**
     * 批 C — the workflow section of a fresh round's prompt.
     *
     * - pinned (`missions.workflow_path` set): read the document and inject its body. A
     *   missing/unreadable file degrades to a warning line instead of failing the launch —
     *   a deleted playbook must not block the mission.
     * - chooser (no path): list `workflows/ 下的 .md` and tell the model to pick one.
     * - no source, no workspace, or no documents: inject nothing.
     */
    private suspend fun missionWorkflowBlock(mission: MissionEntity): String {
        val source = workflowSource ?: return ""
        val workspaceId = mission.workspaceId?.takeIf { it.isNotBlank() } ?: return ""

        val pinned = mission.workflowPath?.takeIf { it.isNotBlank() }
        if (pinned != null) {
            val content = runCatching { source.read(workspaceId, pinned) }.getOrNull()
            return if (content.isNullOrBlank()) {
                "[工作流程] 任务指定的流程文档 $pinned 无法读取（可能已被删除）；请按任务目标自行推进。"
            } else {
                MissionWorkflowDocs.composePinned(pinned, content)
            }
        }

        val names = runCatching { source.list(workspaceId) }.getOrNull().orEmpty()
            .filter { MissionWorkflowDocs.isWorkflowDoc(MissionWorkflowDocs.pathOf(it)) }
        return MissionWorkflowDocs.composeChooser(names)
    }

    /**
     * 批 C — continuation rounds only carry a one-line reminder: the playbook body is
     * already in the transcript (injected on the first/trigger round), so re-injecting it
     * every round would just burn tokens.
     */
    private fun missionWorkflowContinueHint(mission: MissionEntity): String =
        mission.workflowPath?.takeIf { it.isNotBlank() }
            ?.let { MissionWorkflowDocs.composeContinueHint(it) }
            ?: ""

    /** Joins prompt sections, dropping blanks so no stray double-newlines end up in a prompt. */
    private fun joinBlocks(vararg blocks: String): String =
        blocks.filter { it.isNotBlank() }.joinToString("\n\n")
}
