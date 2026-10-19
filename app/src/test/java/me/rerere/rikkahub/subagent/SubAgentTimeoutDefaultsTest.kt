package me.rerere.rikkahub.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The background default timeout exists because reusing the 180s foreground default killed every
 * background coding run that left `timeout_seconds` out (three scene-writing runs at 180s, zero
 * output). These tests pin the resolution rule and the invariants that keep the two defaults from
 * drifting outside what [SubAgentRequestValidator] accepts.
 */
class SubAgentTimeoutDefaultsTest {

    @Test
    fun `explicit timeout always wins`() {
        assertEquals(42, SubAgentDefaults.resolveTimeoutSeconds(42, runInBackground = false))
        assertEquals(42, SubAgentDefaults.resolveTimeoutSeconds(42, runInBackground = true))
    }

    @Test
    fun `foreground default is the blocking-safe one`() {
        assertEquals(
            SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
            SubAgentDefaults.resolveTimeoutSeconds(null, runInBackground = false),
        )
    }

    @Test
    fun `background default gives a long run room`() {
        assertEquals(
            SubAgentDefaults.DEFAULT_BACKGROUND_TIMEOUT_SECONDS,
            SubAgentDefaults.resolveTimeoutSeconds(null, runInBackground = true),
        )
    }

    @Test
    fun `defaults stay inside the validator's accepted range`() {
        val defaults = listOf(
            SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
            SubAgentDefaults.DEFAULT_BACKGROUND_TIMEOUT_SECONDS,
        )
        defaults.forEach { assertTrue("$it must be at least 1", it >= 1) }
        defaults.forEach {
            assertTrue("$it must be within the max", it <= SubAgentDefaults.MAX_TIMEOUT_SECONDS)
        }
        assertTrue(
            "the background default must exceed the foreground one, or it fixes nothing",
            SubAgentDefaults.DEFAULT_BACKGROUND_TIMEOUT_SECONDS > SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
        )
    }
}
