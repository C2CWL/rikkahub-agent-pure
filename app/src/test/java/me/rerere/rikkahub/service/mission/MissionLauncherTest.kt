package me.rerere.rikkahub.service.mission

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.ai.tools.ConversationSystemAddendum
import me.rerere.rikkahub.data.mission.FakeMissionDao
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionExecutionState
import me.rerere.rikkahub.data.mission.MissionRepository
import me.rerere.rikkahub.data.mission.MissionStatus
import me.rerere.rikkahub.data.runevent.FakeRunEventDao
import me.rerere.rikkahub.data.runevent.RunEventEntity
import me.rerere.rikkahub.data.runevent.RunEventRepository
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.service.run.RunHandle
import me.rerere.rikkahub.service.run.RunRequest
import me.rerere.rikkahub.service.run.RunResult
import me.rerere.rikkahub.service.run.RunService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * JVM unit tests for [MissionLauncher] + [MissionSessions].
 *
 * Fake stack: [FakeMissionDao] + [FakeRunEventDao] back a REAL [MissionRepository]
 * (read-modify-write through the DAO, exactly like production), while [FakeRunService]
 * records every submitted [RunRequest] and the [RunHandle] it returned. No Room, no
 * Android — runs fast on the JVM. The mission directive / addendum assertions read
 * [ConversationSystemAddendum] directly and clean up after themselves.
 */
class MissionLauncherTest {

    // ---------------------------------------------------------------- fixtures

    private class FakeRunService(
        /**
         * When true the handle echoes a FRESH conversation id even when the request asked
         * to reuse one. Probe used by the reuse test: a wrongly-backfilled mission row
         * would be overwritten with that fresh id and the assertion would catch it.
         */
        private val freshHandleConversation: Boolean = false,
    ) : RunService {
        val submitted = mutableListOf<RunRequest>()
        val handles = mutableListOf<RunHandle>()

        /**
         * What the mission↔conversation registry held for the requested conversation at the
         * instant generation was dispatched. Proves the launcher registers BEFORE submit: a
         * model request can start as soon as submit dispatches, so a mapping written after
         * submit returns would race the first tool assembly.
         */
        var missionAtSubmit: String? = null

        override suspend fun submit(request: RunRequest): RunHandle {
            missionAtSubmit = request.conversationId
                ?.let { MissionSessions.missionIdFor(it.toString()) }
            submitted += request
            val handle = RunHandle(
                id = "run-${submitted.size}",
                conversationId = if (freshHandleConversation) {
                    Uuid.random()
                } else {
                    request.conversationId ?: Uuid.random()
                },
                timeoutMs = request.timeoutMs,
                missionId = request.missionId,
            )
            handles += handle
            return handle
        }

        override suspend fun awaitTerminal(handle: RunHandle): RunResult =
            error("awaitTerminal is not exercised by MissionLauncherTest")

        override suspend fun cancel(handle: RunHandle) = Unit

        override suspend fun resume(runId: String): RunResult? = null

        override fun events(handle: RunHandle): Flow<List<RunEventEntity>> = flowOf(emptyList())
    }

    private class Fixture(freshHandleConversation: Boolean = false) {
        val missionDao = FakeMissionDao()
        val runEventDao = FakeRunEventDao()
        val runEvents = RunEventRepository(runEventDao)
        val missionRepo = MissionRepository(missionDao, runEvents)
        val runService = FakeRunService(freshHandleConversation)

        /** What the resolver returns; tests set it to null to exercise the no-assistant path. */
        var assistantResult: Uuid? = Uuid.random()

        val launcher = MissionLauncher(
            missionRepository = missionRepo,
            runService = runService,
            runEventRepository = runEvents,
            assistantResolver = { assistantResult },
        )

        suspend fun seed(mission: MissionEntity) = missionDao.upsert(mission)

        /** The launcher's `launch` NOTE events for [missionId]. */
        fun launchNotes(missionId: String): List<RunEventEntity> =
            runEventDao.snapshot().filter {
                it.missionId == missionId &&
                    it.type == RunEventType.NOTE.name &&
                    it.title == "launch"
            }
    }

    private fun mission(
        id: String,
        conversationId: String? = null,
        status: String = MissionStatus.ACTIVE.name,
        approvalMode: String? = null,
    ) = MissionEntity(
        id = id,
        title = "写周报",
        description = "总结本周进展",
        status = status,
        conversationId = conversationId,
        approvalMode = approvalMode,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    // ---------------------------------------------------------------- create

    @Test
    fun `Create without a conversation submits fresh and backfills the mission`() = runBlocking {
        val fx = Fixture()
        fx.seed(mission(id = "m-create"))

        val outcome = fx.launcher.launch("m-create", LaunchSource.Create)
        assertTrue(outcome.ok)
        assertEquals("run-1", outcome.runId)

        val request = fx.runService.submitted.single()
        assertNull("a fresh conversation means no conversationId on the request", request.conversationId)
        assertEquals("m-create", request.missionId)
        assertEquals(AgentRunKind.Mission, request.kind)
        assertEquals("任务：写周报", request.title)
        assertNull("promptPrefix must not be used by the launcher", request.promptPrefix)
        assertTrue("null approvalMode inherits the default headless posture", request.headless)
        assertEquals(30L * 60_000L, request.timeoutMs)

        // Create prompt = mission directive + kickoff instruction.
        val expectedDirective = MissionContinueComposer.composeDirective("写周报", "总结本周进展", emptyList())
        assertTrue(request.prompt.startsWith(expectedDirective))
        assertTrue(request.prompt.contains("现在开始执行这个任务"))

        val handle = fx.runService.handles.single()
        val row = fx.missionDao.getById("m-create")!!
        assertEquals("the new conversation is backfilled onto the mission", handle.conversationId.toString(), row.conversationId)
        assertEquals(MissionExecutionState.RUNNING.name, row.executionState)
        assertEquals(handle.id, row.lastRunId)

        // Registered for tool wiring + yield checks, journaled, directive armed.
        assertEquals("m-create", MissionSessions.missionIdFor(handle.conversationId.toString()))
        assertEquals(handle.conversationId.toString(), MissionSessions.conversationIdFor("m-create"))
        val note = fx.launchNotes("m-create").single()
        assertEquals("create", note.detail)
        assertEquals(handle.id, note.runId)
        assertEquals(handle.conversationId.toString(), note.conversationId)
        assertNotNull("system addendum is armed for the fresh conversation", ConversationSystemAddendum.get(handle.conversationId))

        MissionSessions.unregister(handle.conversationId.toString())
        ConversationSystemAddendum.clear(handle.conversationId)
    }

    @Test
    fun `Create with an existing conversation reuses it and keeps the mission binding`() = runBlocking {
        val fx = Fixture()
        val conv = "22222222-2222-2222-2222-222222222222"
        fx.seed(mission(id = "m-reuse", conversationId = conv))

        val outcome = fx.launcher.launch("m-reuse", LaunchSource.Create)
        assertTrue(outcome.ok)

        val request = fx.runService.submitted.single()
        assertEquals("the existing conversation is requested for reuse", Uuid.parse(conv), request.conversationId)
        assertEquals(
            "the mission is registered before submit dispatches generation",
            "m-reuse",
            fx.runService.missionAtSubmit,
        )

        val handle = fx.runService.handles.single()
        val row = fx.missionDao.getById("m-reuse")!!
        assertEquals("a reused conversation is not re-written onto the mission", conv, row.conversationId)
        assertNotNull(
            "directive is armed for the reused conversation before submit",
            ConversationSystemAddendum.get(Uuid.parse(conv)),
        )
        assertEquals("m-reuse", MissionSessions.missionIdFor(handle.conversationId.toString()))

        MissionSessions.unregister(handle.conversationId.toString())
        ConversationSystemAddendum.clear(Uuid.parse(conv))
        ConversationSystemAddendum.clear(handle.conversationId)
    }

    @Test
    fun `a submit that falls back to a fresh conversation rebinds the mission to it`() = runBlocking {
        // The seeded id no longer resolves in production, so submit creates a fresh
        // conversation. The mission must follow the handle, otherwise every later round
        // reports into a conversation that no longer exists.
        val fx = Fixture(freshHandleConversation = true)
        val stale = "22222222-2222-2222-2222-222222222222"
        fx.seed(mission(id = "m-rebind", conversationId = stale))

        val outcome = fx.launcher.launch("m-rebind", LaunchSource.Create)
        assertTrue(outcome.ok)

        val handle = fx.runService.handles.single()
        assertNotEquals(stale, handle.conversationId.toString())
        assertEquals(
            "the mission follows the conversation submit actually used",
            handle.conversationId.toString(),
            fx.missionDao.getById("m-rebind")!!.conversationId,
        )
        assertEquals("m-rebind", MissionSessions.missionIdFor(handle.conversationId.toString()))
        assertNull(
            "the stale conversation is no longer bound to the mission",
            MissionSessions.missionIdFor(stale),
        )

        MissionSessions.unregister(handle.conversationId.toString())
        ConversationSystemAddendum.clear(handle.conversationId)
    }

    @Test
    fun `Create with an unparseable conversation id falls back to a fresh conversation`() = runBlocking {
        val fx = Fixture()
        fx.seed(mission(id = "m-garbage", conversationId = "not-a-uuid"))

        val outcome = fx.launcher.launch("m-garbage", LaunchSource.Create)
        assertTrue(outcome.ok)

        assertNull("an unparseable id is treated as no conversation", fx.runService.submitted.single().conversationId)
        val handle = fx.runService.handles.single()
        assertEquals(
            "the freshly created conversation replaces the garbage id",
            handle.conversationId.toString(),
            fx.missionDao.getById("m-garbage")!!.conversationId,
        )

        MissionSessions.unregister(handle.conversationId.toString())
        ConversationSystemAddendum.clear(handle.conversationId)
    }

    // ---------------------------------------------------------------- continue

    @Test
    fun `Continue reuses the mission conversation and passes the continue prompt through`() = runBlocking {
        val fx = Fixture()
        val conv = "33333333-3333-3333-3333-333333333333"
        fx.seed(mission(id = "m-cont", conversationId = conv))

        val outcome = fx.launcher.launch(
            "m-cont",
            LaunchSource.Continue(round = 2),
            continuePrompt = "继续第 2 轮：先跑测试再收尾。",
        )
        assertTrue(outcome.ok)

        val request = fx.runService.submitted.single()
        assertEquals(Uuid.parse(conv), request.conversationId)
        assertEquals("the continue prompt is used verbatim", "继续第 2 轮：先跑测试再收尾。", request.prompt)
        assertEquals(
            "a continuation is registered before submit dispatches generation",
            "m-cont",
            fx.runService.missionAtSubmit,
        )

        val note = fx.launchNotes("m-cont").single()
        assertEquals("continue round 2", note.detail)

        val handle = fx.runService.handles.single()
        MissionSessions.unregister(handle.conversationId.toString())
        ConversationSystemAddendum.clear(Uuid.parse(conv))
    }

    @Test
    fun `Continue without a conversation is refused`() = runBlocking {
        val fx = Fixture()
        fx.seed(mission(id = "m-noconv")) // conversationId = null

        val outcome = fx.launcher.launch("m-noconv", LaunchSource.Continue(round = 1), continuePrompt = "go")
        assertFalse(outcome.ok)
        assertNotNull(outcome.error)
        assertNull(outcome.runId)
        assertTrue("nothing may be submitted on a refusal", fx.runService.submitted.isEmpty())
    }

    @Test
    fun `Continue with an unparseable conversation id is refused`() = runBlocking {
        val fx = Fixture()
        fx.seed(mission(id = "m-badconv", conversationId = "not-a-uuid"))

        val outcome = fx.launcher.launch("m-badconv", LaunchSource.Continue(round = 1), continuePrompt = "go")
        assertFalse(outcome.ok)
        assertTrue(fx.runService.submitted.isEmpty())
    }

    @Test
    fun `Continue is refused when the mission is not ACTIVE`() = runBlocking {
        val fx = Fixture()
        val conv = "55555555-5555-5555-5555-555555555555"
        fx.seed(mission(id = "m-done", conversationId = conv, status = MissionStatus.DONE.name))

        val outcome = fx.launcher.launch("m-done", LaunchSource.Continue(round = 1), continuePrompt = "go")
        assertFalse(outcome.ok)
        assertTrue(outcome.error.orEmpty().contains("ACTIVE"))
        assertTrue(fx.runService.submitted.isEmpty())
    }

    // ---------------------------------------------------------------- refusals

    @Test
    fun `launch on a missing mission returns an error`() = runBlocking {
        val fx = Fixture()

        val outcome = fx.launcher.launch("m-missing", LaunchSource.Create)
        assertFalse(outcome.ok)
        assertTrue(outcome.error.orEmpty().contains("m-missing"))
        assertTrue(fx.runService.submitted.isEmpty())
    }

    @Test
    fun `launch is refused when no assistant resolves`() = runBlocking {
        val fx = Fixture()
        fx.assistantResult = null
        fx.seed(mission(id = "m-noassistant"))

        val outcome = fx.launcher.launch("m-noassistant", LaunchSource.Create)
        assertFalse(outcome.ok)
        assertNotNull(outcome.error)
        assertNull(outcome.runId)
        assertTrue(fx.runService.submitted.isEmpty())
    }

    // ---------------------------------------------------------------- approval posture

    @Test
    fun `SEMI and MANUAL approval modes turn headless off while AUTO keeps it on`() = runBlocking {
        val fx = Fixture()
        val conv = "77777777-7777-7777-7777-777777777777"
        fx.seed(mission(id = "m-semi", conversationId = conv, approvalMode = "SEMI"))

        fx.launcher.launch("m-semi", LaunchSource.Continue(round = 1), continuePrompt = "go")
        fx.missionDao.upsert(fx.missionDao.getById("m-semi")!!.copy(approvalMode = "MANUAL"))
        fx.launcher.launch("m-semi", LaunchSource.Continue(round = 2), continuePrompt = "go")
        fx.missionDao.upsert(fx.missionDao.getById("m-semi")!!.copy(approvalMode = "AUTO"))
        fx.launcher.launch("m-semi", LaunchSource.Continue(round = 3), continuePrompt = "go")

        assertFalse(fx.runService.submitted[0].headless)
        assertFalse(fx.runService.submitted[1].headless)
        assertTrue(fx.runService.submitted[2].headless)

        MissionSessions.unregister(conv)
        ConversationSystemAddendum.clear(Uuid.parse(conv))
    }

    // ---------------------------------------------------------------- trigger

    @Test
    fun `Trigger launch on an ACTIVE mission reuses the conversation and names the trigger`() = runBlocking {
        val fx = Fixture()
        val conv = "44444444-4444-4444-4444-444444444444"
        fx.seed(mission(id = "m-trigger", conversationId = conv))

        val outcome = fx.launcher.launch("m-trigger", LaunchSource.Trigger("Wi-Fi 连接"))
        assertTrue(outcome.ok)
        assertEquals("run-1", outcome.runId)

        val request = fx.runService.submitted.single()
        assertEquals(AgentRunKind.Mission, request.kind)
        assertEquals("m-trigger", request.missionId)
        assertEquals(
            "the existing execution conversation is reused",
            Uuid.parse(conv),
            request.conversationId,
        )
        assertTrue("the prompt names the trigger", request.prompt.contains("Wi-Fi 连接"))
        assertTrue("the prompt carries the trigger preamble", request.prompt.contains("[系统]"))

        val handle = fx.runService.handles.single()
        val row = fx.missionDao.getById("m-trigger")!!
        assertEquals("a reused conversation is not rewritten", conv, row.conversationId)
        assertEquals(MissionExecutionState.RUNNING.name, row.executionState)
        assertEquals(handle.id, row.lastRunId)
        assertEquals("trigger: Wi-Fi 连接", fx.launchNotes("m-trigger").single().detail)

        MissionSessions.unregister(handle.conversationId.toString())
        ConversationSystemAddendum.clear(Uuid.parse(conv))
    }

    @Test
    fun `Trigger launch on a DONE mission is refused without submitting`() = runBlocking {
        val fx = Fixture()
        val conv = "66666666-6666-6666-6666-666666666666"
        fx.seed(
            mission(
                id = "m-trigger-done",
                conversationId = conv,
                status = MissionStatus.DONE.name,
            )
        )

        val outcome = fx.launcher.launch("m-trigger-done", LaunchSource.Trigger("定时 09:00"))
        assertFalse(outcome.ok)
        assertTrue(outcome.error.orEmpty().contains("ACTIVE"))
        assertTrue("a refused trigger must not submit", fx.runService.submitted.isEmpty())
        assertEquals(MissionStatus.DONE.name, fx.missionDao.getById("m-trigger-done")!!.status)
    }

    // ---------------------------------------------------------------- sessions registry

    @Test
    fun `MissionSessions maps both directions, re-points, and clears on unregister`() {
        MissionSessions.register("c-1", "m-1")
        assertEquals("m-1", MissionSessions.missionIdFor("c-1"))
        assertEquals("c-1", MissionSessions.conversationIdFor("m-1"))

        // Re-pointing the same mission to a new conversation drops the stale reverse pointer.
        MissionSessions.register("c-2", "m-1")
        assertNull(MissionSessions.missionIdFor("c-1"))
        assertEquals("c-2", MissionSessions.conversationIdFor("m-1"))

        // Re-pointing the same conversation to a new mission drops the stale forward pointer.
        MissionSessions.register("c-2", "m-2")
        assertNull(MissionSessions.conversationIdFor("m-1"))
        assertEquals("m-2", MissionSessions.missionIdFor("c-2"))

        MissionSessions.unregister("c-2")
        assertNull(MissionSessions.missionIdFor("c-2"))
        assertNull(MissionSessions.conversationIdFor("m-2"))

        // Unregister is a no-op for unknown conversations.
        MissionSessions.unregister("c-unknown")
        assertNull(MissionSessions.missionIdFor("c-unknown"))
    }
}
