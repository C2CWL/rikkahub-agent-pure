package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.agentdef.AgentDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 — a mission task's `agent` must resolve to the named expert, or fail loudly.
 *
 * The failure half matters as much as the match: a child that silently inherited the parent's
 * assistant would LOOK like the expert had run, and the model would draw conclusions from a
 * report the expert never wrote.
 */
class MissionExpertResolutionTest {

    private fun expert(name: String, modelId: String? = null, prompt: String = "p") =
        AgentDefinition(id = name, name = name, systemPrompt = prompt, modelId = modelId)

    @Test
    fun `no agent means inherit`() {
        assertEquals(MissionExpert.Inherit, resolveMissionExpert(null, listOf(expert("a"))))
        assertEquals(MissionExpert.Inherit, resolveMissionExpert("   ", listOf(expert("a"))))
    }

    @Test
    fun `matching ignores case and surrounding whitespace`() {
        val target = expert("deepseek-高速-1")
        val resolution = resolveMissionExpert("  DeepSeek-高速-1 ", listOf(expert("other"), target))

        assertTrue("expected a match, got $resolution", resolution is MissionExpert.Resolved)
        assertSame(target, (resolution as MissionExpert.Resolved).definition)
    }

    @Test
    fun `an unknown name fails loudly and lists the roster`() {
        val failure = runCatching {
            resolveMissionExpert("nope", listOf(expert("alpha"), expert("beta")))
        }.exceptionOrNull()

        assertTrue("expected a hard failure, got $failure", failure != null)
        val message = failure!!.message.orEmpty()
        assertTrue("must name the requested expert: $message", message.contains("nope"))
        assertTrue("must list the available experts: $message", message.contains("alpha"))
        assertTrue("must list the available experts: $message", message.contains("beta"))
    }
}
