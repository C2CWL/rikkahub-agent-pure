package me.rerere.rikkahub.ui.pages.gettingstarted

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Locks down the first-run checklist's completion rules — the only bit of the guide with
 * real logic. The Compose screen itself is thin; these are the predicates it renders from.
 */
class GettingStartedTest {

    private fun chatModel() = Model(modelId = "gpt-4o", displayName = "gpt-4o", type = ModelType.CHAT)

    private fun settings(
        providers: List<ProviderSetting> = emptyList(),
        assistants: List<Assistant> = listOf(Assistant()),
    ) = Settings(providers = providers, assistants = assistants)

    private fun steps(
        settings: Settings = settings(),
        deniedPermissions: Int = 0,
        embeddingReady: Boolean = false,
    ) = evaluateGettingStartedSteps(settings, deniedPermissions, embeddingReady)

    @Test
    fun `model step is done only when an enabled provider carries a model`() {
        assertFalse(settings().hasConfiguredModel())

        assertTrue(
            settings(
                providers = listOf(
                    ProviderSetting.OpenAI(name = "OpenAI", models = listOf(chatModel()))
                )
            ).hasConfiguredModel()
        )

        // A disabled provider with a model does not count — the user cannot chat with it yet.
        assertFalse(
            settings(
                providers = listOf(
                    ProviderSetting.OpenAI(name = "Off", enabled = false, models = listOf(chatModel()))
                )
            ).hasConfiguredModel()
        )
    }

    @Test
    fun `embedding step tracks the embedding readiness the caller resolved`() {
        assertFalse(steps(embeddingReady = false).getValue(GettingStartedStep.Embedding))
        assertTrue(steps(embeddingReady = true).getValue(GettingStartedStep.Embedding))
    }

    @Test
    fun `only the chat model and the embedding model are required`() {
        assertEquals(
            setOf(GettingStartedStep.Model, GettingStartedStep.Embedding),
            REQUIRED_GETTING_STARTED_STEPS,
        )
    }

    @Test
    fun `tools step ignores the default TimeInfo-only assistant`() {
        assertFalse(steps().getValue(GettingStartedStep.Tools))
    }

    @Test
    fun `tools step flips once a gated group is enabled`() {
        assertTrue(
            steps(
                settings = settings(
                    assistants = listOf(
                        Assistant(localTools = listOf(LocalToolOption.TimeInfo, LocalToolOption.Termux))
                    )
                ),
            ).getValue(GettingStartedStep.Tools)
        )
    }

    @Test
    fun `tools step ignores a non-gated group, which no longer has a switch`() {
        // Under the retrieval-driven catalogue, only the gated groups still have switches;
        // an always-on group in the stored list means nothing and must not mark the step done.
        assertFalse(
            steps(
                settings = settings(
                    assistants = listOf(
                        Assistant(localTools = listOf(LocalToolOption.Clipboard))
                    )
                ),
            ).getValue(GettingStartedStep.Tools)
        )
    }

    @Test
    fun `permissions step is done only when nothing is denied`() {
        assertTrue(steps(deniedPermissions = 0).getValue(GettingStartedStep.Permissions))
        assertFalse(steps(deniedPermissions = 2).getValue(GettingStartedStep.Permissions))
    }

    @Test
    fun `workspace step tracks whether any assistant is bound`() {
        assertFalse(steps().getValue(GettingStartedStep.Workspace))

        assertTrue(
            steps(
                settings = settings(assistants = listOf(Assistant(workspaceId = Uuid.random())))
            ).getValue(GettingStartedStep.Workspace)
        )
    }
}
