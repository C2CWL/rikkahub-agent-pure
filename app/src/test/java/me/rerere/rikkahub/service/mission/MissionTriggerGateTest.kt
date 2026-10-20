package me.rerere.rikkahub.service.mission

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B1 — every branch of the trigger gate, plus the priority order between branches.
 *
 * The gate is the only thing standing between a noisy trigger feed (Wi-Fi flaps, notification
 * storms) and a runaway mission, so each skip condition gets its own case and the ordering
 * (busy → cap → cooldown) is pinned explicitly.
 */
class MissionTriggerGateTest {

    private fun input(
        status: String = "ACTIVE",
        triggerArmed: Boolean = true,
        executionState: String = "idle",
        lastFiredAt: Long? = null,
        firesToday: Int = 0,
        maxRunsPerDay: Int? = null,
        cooldownSeconds: Int = 0,
        nowMs: Long = 10_000_000L,
    ) = MissionTriggerGate.Input(
        status = status,
        triggerArmed = triggerArmed,
        executionState = executionState,
        lastFiredAt = lastFiredAt,
        firesToday = firesToday,
        maxRunsPerDay = maxRunsPerDay,
        cooldownSeconds = cooldownSeconds,
        nowMs = nowMs,
    )

    @Test
    fun `active idle mission with no limits fires`() {
        assertEquals(TriggerFireGate.FIRED, MissionTriggerGate.evaluate(input()))
    }

    @Test
    fun `non-active mission is skipped`() {
        for (status in listOf("BLOCKED", "REVIEW", "DONE", "ARCHIVED")) {
            assertEquals(
                "status=$status",
                TriggerFireGate.SKIPPED_NOT_ACTIVE,
                MissionTriggerGate.evaluate(input(status = status)),
            )
        }
    }

    @Test
    fun `disabled trigger is skipped`() {
        assertEquals(
            TriggerFireGate.SKIPPED_DISABLED,
            MissionTriggerGate.evaluate(input(triggerArmed = false)),
        )
    }

    @Test
    fun `running round is busy`() {
        assertEquals(
            TriggerFireGate.SKIPPED_BUSY,
            MissionTriggerGate.evaluate(input(executionState = "running")),
        )
    }

    @Test
    fun `waiting approval is busy`() {
        assertEquals(
            TriggerFireGate.SKIPPED_BUSY,
            MissionTriggerGate.evaluate(input(executionState = "waiting_approval")),
        )
    }

    @Test
    fun `daily cap reached skips before cooldown`() {
        assertEquals(
            TriggerFireGate.SKIPPED_DAILY_CAP,
            MissionTriggerGate.evaluate(
                input(
                    firesToday = 3,
                    maxRunsPerDay = 3,
                    cooldownSeconds = 600,
                    lastFiredAt = 9_999_000L,
                )
            ),
        )
    }

    @Test
    fun `daily cap not yet reached still fires`() {
        assertEquals(
            TriggerFireGate.FIRED,
            MissionTriggerGate.evaluate(input(firesToday = 2, maxRunsPerDay = 3)),
        )
    }

    @Test
    fun `zero daily cap means unlimited`() {
        assertEquals(
            TriggerFireGate.FIRED,
            MissionTriggerGate.evaluate(input(firesToday = 99, maxRunsPerDay = 0)),
        )
    }

    @Test
    fun `inside cooldown is skipped`() {
        assertEquals(
            TriggerFireGate.SKIPPED_COOLDOWN,
            MissionTriggerGate.evaluate(
                input(cooldownSeconds = 60, lastFiredAt = 10_000_000L - 30_000L)
            ),
        )
    }

    @Test
    fun `cooldown elapsed fires`() {
        assertEquals(
            TriggerFireGate.FIRED,
            MissionTriggerGate.evaluate(
                input(cooldownSeconds = 60, lastFiredAt = 10_000_000L - 60_000L)
            ),
        )
    }

    @Test
    fun `cooldown with no previous fire fires`() {
        assertEquals(
            TriggerFireGate.FIRED,
            MissionTriggerGate.evaluate(input(cooldownSeconds = 3600, lastFiredAt = null)),
        )
    }

    @Test
    fun `busy wins over daily cap`() {
        assertEquals(
            TriggerFireGate.SKIPPED_BUSY,
            MissionTriggerGate.evaluate(
                input(executionState = "running", firesToday = 9, maxRunsPerDay = 1)
            ),
        )
    }

    @Test
    fun `disabled wins over busy`() {
        assertEquals(
            TriggerFireGate.SKIPPED_DISABLED,
            MissionTriggerGate.evaluate(
                input(triggerArmed = false, executionState = "running")
            ),
        )
    }

    @Test
    fun `stale day counters are the caller's responsibility and fire when zeroed`() {
        // The repository zeroes firesToday when firesDay != today; the gate itself only sees
        // the effective count.
        assertEquals(
            TriggerFireGate.FIRED,
            MissionTriggerGate.evaluate(input(firesToday = 0, maxRunsPerDay = 5)),
        )
    }
}