package me.rerere.rikkahub.service.mission

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B1 — every branch of the continuation decision matrix, plus the priority order between
 * branches. Pure function, so the whole file runs on a bare JVM.
 */
class MissionContinuePolicyTest {

    /** ACTIVE mission, no signals, well below every limit — the resume baseline. */
    private fun input(
        missionStatus: String = "ACTIVE",
        hasDoneSignal: Boolean = false,
        hasBlockedSignal: Boolean = false,
        resumeCount: Int = 0,
        stallRounds: Int = 0,
        maxResumeRounds: Int = 5,
        stallThreshold: Int = 3,
        totalTokens: Long = 0L,
        tokenBudget: Long = 1_000L,
        userIntervened: Boolean = false,
    ) = ContinueInput(
        missionStatus = missionStatus,
        hasDoneSignal = hasDoneSignal,
        hasBlockedSignal = hasBlockedSignal,
        resumeCount = resumeCount,
        stallRounds = stallRounds,
        maxResumeRounds = maxResumeRounds,
        stallThreshold = stallThreshold,
        totalTokens = totalTokens,
        tokenBudget = tokenBudget,
        userIntervened = userIntervened,
    )

    @Test
    fun `user intervention yields even when everything else would fire`() {
        assertEquals(
            ContinueDecision.Yield,
            MissionContinuePolicy.decide(
                input(
                    userIntervened = true,
                    missionStatus = "BLOCKED",
                    hasDoneSignal = true,
                    hasBlockedSignal = true,
                    resumeCount = 99,
                    stallRounds = 99,
                    totalTokens = 9_999L,
                ),
            ),
        )
    }

    @Test
    fun `a non-active mission is a no-op no matter what signals exist`() {
        for (status in listOf("BLOCKED", "REVIEW", "DONE", "ARCHIVED")) {
            assertEquals(
                "status=$status",
                ContinueDecision.NoOp,
                MissionContinuePolicy.decide(input(missionStatus = status, hasDoneSignal = true)),
            )
        }
    }

    @Test
    fun `a blocked signal stops with the model reason`() {
        assertEquals(
            ContinueDecision.Block("blocked by model"),
            MissionContinuePolicy.decide(input(hasBlockedSignal = true)),
        )
    }

    @Test
    fun `a blocked signal outranks a done signal`() {
        assertEquals(
            ContinueDecision.Block("blocked by model"),
            MissionContinuePolicy.decide(input(hasBlockedSignal = true, hasDoneSignal = true)),
        )
    }

    @Test
    fun `a done signal completes the mission`() {
        assertEquals(
            ContinueDecision.Complete,
            MissionContinuePolicy.decide(input(hasDoneSignal = true)),
        )
    }

    @Test
    fun `a done signal outranks every circuit breaker`() {
        assertEquals(
            ContinueDecision.Complete,
            MissionContinuePolicy.decide(
                input(
                    hasDoneSignal = true,
                    resumeCount = 5,
                    stallRounds = 9,
                    totalTokens = 9_999L,
                ),
            ),
        )
    }

    @Test
    fun `the round breaker fires at the limit and outranks stall and budget`() {
        assertEquals(
            ContinueDecision.Block("熔断:轮数"),
            MissionContinuePolicy.decide(
                input(resumeCount = 5, stallRounds = 9, totalTokens = 9_999L),
            ),
        )
    }

    @Test
    fun `the stall breaker fires at the threshold and outranks the budget breaker`() {
        assertEquals(
            ContinueDecision.Block("熔断:无进展"),
            MissionContinuePolicy.decide(
                input(resumeCount = 1, stallRounds = 3, totalTokens = 9_999L),
            ),
        )
    }

    @Test
    fun `the budget breaker fires when tokens reach the budget`() {
        assertEquals(
            ContinueDecision.Block("熔断:预算"),
            MissionContinuePolicy.decide(input(totalTokens = 1_000L)),
        )
    }

    @Test
    fun `one below every limit still resumes with the next round number`() {
        assertEquals(
            ContinueDecision.Resume(5),
            MissionContinuePolicy.decide(
                input(resumeCount = 4, stallRounds = 2, totalTokens = 999L),
            ),
        )
    }

    @Test
    fun `the first continuation resumes at round one`() {
        assertEquals(
            ContinueDecision.Resume(1),
            MissionContinuePolicy.decide(input()),
        )
    }
}
