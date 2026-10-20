package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** The statuses `mission_report` accepts. */
private val MISSION_REPORT_STATUSES: Set<String> = setOf("done", "blocked", "progress")

/**
 * B3 (批 A · Mission Runtime) — `mission_report`, the signal tool the model calls from
 * inside a mission round to tell the runtime where it stands.
 *
 * This is only the model-facing half: the tool validates the call and hands
 * `(status, note)` to the [report] lambda, returning its string verbatim. Persisting the
 * call — writing a `NOTE` run event with `title = "mission_report: <status>"` and
 * payload `{"mission_signal":"<status>","note":"..."}`, plus the mission-binding columns
 * — is the assembly site's job, not this file's. The mission supervisor consumes those
 * events by exact title match (`mission_report: done` / `mission_report: blocked`), so
 * the persistence side must keep that format.
 *
 * Statuses:
 * - `done` — the mission is complete (note carries the delivery summary);
 * - `blocked` — genuinely stuck, needs the user (decision / permission / information);
 * - `progress` — a stage-level progress note.
 *
 * An unknown/missing `status` is rejected with a compact JSON error envelope and never
 * reaches [report], so a malformed call cannot be mistaken for a real signal.
 *
 * @param report sink handed `(status, note)` when the call is valid; returns the string
 *   the model sees (built by the assembly site).
 */
fun buildMissionReportTool(
    report: suspend (status: String, note: String?, goals: List<Int>) -> String,
): Tool = Tool(
    name = "mission_report",
    description = buildString {
        append(
            """
            任务模式下向系统汇报执行状态（仅在任务执行会话中使用）。

            - done：任务已完成。调用时必须附交付摘要（note：做了什么、产物在哪）。任务完成时必须调用 done。
            - blocked：确实被卡住、需要用户决策/权限/信息。调用时必须在 note 中说明原因。被卡住时必须调用 blocked。
            - progress：阶段性进展记录（可选；note 简述本轮进展即可）。

            完成时必须调用 done；被卡住时必须调用 blocked。系统会据此判断任务是否完成、是否停下等待用户，以及是否需要自动续跑。
            """.trimIndent()
        )
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put(
                    "status",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add(JsonPrimitive("done"))
                                add(JsonPrimitive("blocked"))
                                add(JsonPrimitive("progress"))
                            },
                        )
                        put(
                            "description",
                            "done=任务已完成（附交付摘要）；blocked=被卡住、需要用户介入（说明原因）；progress=阶段性进展。",
                        )
                    },
                )
                put(
                    "note",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "备注。done 时给交付摘要；blocked 时给原因；progress 时简述进展，可省略。",
                        )
                    },
                )
                put(
                    "goals",
                    buildJsonObject {
                        put("type", "array")
                        put(
                            "description",
                            "仅 done 时使用：本次真正达成的目标序号（从 0 开始，对应任务目标清单）。" +
                                "省略或留空表示全部目标达成。",
                        )
                        put(
                            "items",
                            buildJsonObject { put("type", "integer") },
                        )
                    },
                )
            },
            required = listOf("status"),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val status = params["status"]?.jsonPrimitive?.contentOrNull
        if (status == null || status !in MISSION_REPORT_STATUSES) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "invalid_status")
                        put(
                            "detail",
                            "status must be one of done|blocked|progress; got: ${status ?: "null"}",
                        )
                    }.toString()
                )
            )
        } else {
            val note = params["note"]?.jsonPrimitive?.contentOrNull
            val goals = params["goals"]
                ?.takeIf { it is JsonArray }
                ?.let { it as JsonArray }
                ?.mapNotNull { it.jsonPrimitive.intOrNull }
                ?: emptyList()
            listOf(UIMessagePart.Text(report(status, note, goals)))
        }
    },
)
