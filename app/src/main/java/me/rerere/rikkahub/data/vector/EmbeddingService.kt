package me.rerere.rikkahub.data.vector

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.llamacpp.LlamaCppEmbedder
import me.rerere.llamacpp.LlamaCppEmbeddingCatalog
import me.rerere.llamacpp.LlamaCppEmbeddingEntry
import me.rerere.locallm.litert.LitertEmbeddingCatalog
import me.rerere.locallm.litert.LitertEmbeddingEntry
import me.rerere.locallm.litert.LitertLmEmbedder
import java.io.File

/**
 * The app's one door to the embedding model.
 *
 * One model at a time, on purpose: it is a few hundred megabytes resident and a device has one
 * pair of hands. Everything that wants a vector - the memory index today, tools and files later -
 * comes through here, so the "which model, is it loaded, what width is it" questions have exactly
 * one answer, and a model swap invalidates the index in one place rather than in each caller.
 *
 * The model is resolved from what is installed rather than from a baked-in choice: the file names
 * in [LlamaCppEmbeddingCatalog] are what the ordinary local-model page writes into
 * `local-models/llamacpp`, so installing the recommended GGUF is all it takes. See
 * [EmbeddingModelRules.pick] for how an explicit setting and that fallback interact.
 *
 * There are two backends behind this door. The local one is the default and came first: a GGUF
 * file, resident, nothing leaving the device. The cloud one - off unless the user turns it on and
 * picks a model - replaces it wholesale rather than supplementing it, because every row of the
 * index stores *which* model produced it ([ActiveModel.modelId]) and mixing two models' vectors in
 * one index is the one mistake that would keep returning plausible answers. Switching between the
 * two therefore invalidates the index by construction, and the search layer already says so.
 *
 * Whoever embeds something has to size it for the *loaded* model's window
 * ([ActiveModel.contextTokens]), not for a number written down here: the models on offer do not
 * agree on one, and the runtime refuses - rather than silently truncates - a text that does not
 * fit. See [ChunkSpec.fitting].
 */
class EmbeddingService(
    /**
     * Where installed embedding models live - one directory per runtime, see [EmbeddingModelFiles].
     * A lambda, so tests never touch the filesystem.
     *
     * A *list* rather than one directory because which engine runs is decided by the extension of
     * the file that resolves: `.gguf` goes to llama.cpp, `.litertlm` to LiteRT-LM. The file is a
     * name ([EmbeddingModelRules.pick] reasons in names), so it has to be looked for in both.
     */
    private val modelDirs: () -> List<File>,
    /** The user's explicit choice, if any. */
    private val configuredFileName: suspend () -> String?,
    private val embedder: LlamaCppEmbedder = LlamaCppEmbedder(),
    /**
     * The LiteRT-LM engine, used when the resolved file is a `.litertlm`.
     *
     * Two engines behind this one door rather than one abstraction over both: they share nothing
     * except the idea of producing a vector - one owns a GGUF context, the other a compiled
     * `.litertlm` - and the choice between them is a property of the *installed file*, not of a
     * setting, so there is nothing for an abstraction to decide.
     */
    private val litert: LitertLmEmbedder = LitertLmEmbedder(),
    /** The curated `.litertlm` entry for a file name, which carries its signature and prefixes. */
    private val litertEntryFor: (String) -> LitertEmbeddingEntry? = LitertEmbeddingCatalog::entryFor,
    private val curatedOrder: List<String> = EmbeddingCatalog.curatedOrder,
    /** Prefix lookup, injectable so a test can exercise the behaviour without the catalogue. */
    private val entryFor: (String) -> LlamaCppEmbeddingEntry? = LlamaCppEmbeddingCatalog::entryFor,
    /**
     * The cloud model to embed with, when the user chose one this build can call.
     *
     * Asked on every load rather than captured once, so turning the cloud backend on takes effect
     * on the next round of indexing instead of the next launch. Null means local: that is what an
     * untouched install answers, and what a cloud selection that cannot be used falls back to.
     */
    private val cloudChoice: suspend () -> CloudEmbeddingCandidate? = { null },
    /** The transport a cloud model is called through. Unused while [cloudChoice] answers null. */
    private val cloud: CloudEmbeddingCaller? = null,
) {

    /**
     * The loaded model: what it is, how wide its vectors are, and how many tokens it can take.
     *
     * The task prefixes ride along because they are a property of the *loaded file*, and a caller
     * that embeds a document without knowing them would store a vector from the wrong side of the
     * model's contrastive pair - silently, since nothing about that fails.
     *
     * [contextTokens] is the model's own window, and it is what a caller must size its inputs by:
     * the runtime refuses a longer text, and the models people actually install do not agree on a
     * window (8K here, 32K there, 512 for a small BERT).
     */
    data class ActiveModel(
        val fileName: String,
        val dim: Int,
        val contextTokens: Int,
        val queryPrefix: String = "",
        val documentPrefix: String = "",
        /**
         * True when LiteRT-LM is the engine behind this, false when it is llama.cpp.
         *
         * Recorded on the loaded model rather than re-derived from the file name at each call,
         * because the two engines have separate state and [release] has to free the right one -
         * freeing the wrong one leaves a few hundred megabytes resident with nothing referencing it.
         */
        val litert: Boolean = false,
        /**
         * The cloud model behind this, or null when it is the local file.
         *
         * [dim] is 0 when this is set: the width is the provider's business and is only known once
         * a response has come back, so nothing sizes anything by it. The identity in [modelId] is
         * what keeps two models' vectors apart, and that is known before the first call.
         */
        val cloud: CloudEmbeddingCandidate? = null,
    ) {
        /** What goes into `vector_chunks.model_id`. */
        val modelId: String get() = EmbeddingModelRules.modelIdOf(fileName)

        /** True when the vectors are computed off the device, from text that is sent to do it. */
        val isCloud: Boolean get() = cloud != null
    }

    private val lock = Mutex()

    @Volatile
    private var active: ActiveModel? = null

    /**
     * Loads the model if it is not loaded yet. Returns null when there is nothing usable to
     * embed with, which callers treat as "semantic search is off", not as an error.
     *
     * A model that fails to load is *not* remembered as failed: the next call tries again, so
     * freeing memory or re-downloading a truncated file fixes the situation without a restart.
     */
    suspend fun ensureLoaded(): ActiveModel? = lock.withLock {
        active?.let { return@withLock it }

        // The cloud choice is checked first and answers without a request: which model to use is a
        // setting, and a "load" that had to reach the network would make every round of background
        // indexing wait on one.
        cloudChoice()?.let { candidate ->
            val loaded = ActiveModel(
                fileName = CloudEmbeddingRules.indexModelId(candidate),
                dim = 0,
                contextTokens = CloudEmbeddingRules.contextTokens(candidate),
                cloud = candidate,
            )
            active = loaded
            return@withLock loaded
        }

        val installed = modelDirs().flatMap { dir ->
            dir.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
        }
        val fileName = EmbeddingModelRules.pick(
            configured = configuredFileName(),
            installed = installed,
            curatedOrder = curatedOrder,
        ) ?: return@withLock null

        val file = modelDirs().asSequence().map { File(it, fileName) }.firstOrNull { it.isFile }
            ?: return@withLock null

        val loaded = if (EmbeddingModelRules.isLitert(fileName)) {
            loadLitert(fileName, file)
        } else {
            loadLlamaCpp(fileName, file)
        } ?: return@withLock null
        active = loaded
        loaded
    }

    /**
     * Opens a `.litertlm`.
     *
     * The signature and the prefixes come from the *catalogue*, not from the file: a `.litertlm`
     * carries neither, and both matter in ways that fail quietly. The signature is what bounds
     * every text we will ever hand this engine (it refuses a longer one), and the prefixes are what
     * tell the model which side of its contrastive pair a text is on. An uncurated `.litertlm` is
     * therefore refused rather than guessed at - a wrong window fails loudly, but a missing prefix
     * only ranks worse, which is exactly the kind of damage nothing downstream can detect.
     */
    private suspend fun loadLitert(fileName: String, file: File): ActiveModel? {
        val entry = litertEntryFor(fileName) ?: return null
        val info = litert.load(file.absolutePath, entry.signature)
        return ActiveModel(
            fileName = fileName,
            dim = info.dim,
            // The loaded signature, not the model's headline context: see the catalogue.
            contextTokens = info.signature,
            queryPrefix = entry.queryPrefix,
            documentPrefix = entry.documentPrefix,
            litert = true,
        )
    }

    private suspend fun loadLlamaCpp(fileName: String, file: File): ActiveModel {
        val info = embedder.load(file.absolutePath)
        val entry = entryFor(fileName)
        return ActiveModel(
            fileName = fileName,
            dim = info.dim,
            // The model's own window, not the one we asked the context for: a small BERT is
            // trained on 512 tokens and the runtime clamps to that, so sizing inputs by anything
            // larger is what would make the next call fail.
            contextTokens = info.nCtxTrain.takeIf { it > 0 }
                ?: LlamaCppEmbedder.DEFAULT_CONTEXT_TOKENS,
            queryPrefix = entry?.queryPrefix.orEmpty(),
            documentPrefix = entry?.documentPrefix.orEmpty(),
        )
    }

    /**
     * Embeds the *documents* that get indexed - memory notes, library files, past turns, tool
     * entries - in order, with the model's document-side task prefix applied.
     *
     * Throws when no model is available; callers that can live without embeddings check
     * [ensureLoaded] first and skip instead.
     */
    suspend fun embedDocuments(texts: List<String>): List<FloatArray> {
        val model = ensureLoaded() ?: error("no embedding model is installed")
        if (model.isCloud) return embedCloud(model, texts)
        return texts.map { text -> embedWith(model, model.documentPrefix + text) }
    }

    /**
     * Embeds one *query* with the model's query-side task prefix applied.
     *
     * A separate door from [embedDocuments] on purpose. Most embedding models are asymmetric, and
     * several of the curated entries are: searching a query embedded the way the documents were is
     * a mistake that returns plausible results rather than an error, so the two cases are told
     * apart by the type system rather than by a comment.
     */
    suspend fun embedQuery(text: String): FloatArray {
        val model = ensureLoaded() ?: error("no embedding model is installed")
        if (model.isCloud) return embedCloud(model, listOf(text)).single()
        return embedWith(model, model.queryPrefix + text)
    }

    private suspend fun embedWith(model: ActiveModel, text: String): FloatArray {
        val vector = if (model.litert) litert.embedNormalized(text) else embedder.embedNormalized(text)
        require(vector.size == model.dim) {
            "the model returned ${vector.size} values, expected ${model.dim}"
        }
        return vector
    }

    /**
     * Embeds through the provider, in batches, in order.
     *
     * No prefixes: this service's two doors exist because most *local* embedding models are
     * asymmetric and need to be told which side of the pair they are on. A provider's embedding
     * endpoint takes the text as it is - sending it an instruction it does not expect would be a
     * silent quality problem rather than an error.
     *
     * The order of the answer is the one thing a batching loop can get wrong, and it is the one
     * thing nothing downstream could detect: [CloudEmbeddingCaller] answers in the order of the
     * batch it was given, and the batches are concatenated in order.
     */
    private suspend fun embedCloud(model: ActiveModel, texts: List<String>): List<FloatArray> {
        val candidate = model.cloud ?: error("no cloud model is active")
        val caller = cloud ?: error("no cloud embedding client is wired up")
        // The batching and the order it preserves are [CloudEmbeddingRules.embedInBatches], which
        // is pure and therefore tested; what is left here is naming the model for the error and
        // handing the request to the transport.
        return CloudEmbeddingRules.embedInBatches(texts) { batch -> caller.embed(candidate, batch) }
    }

    /**
     * Frees the model. Called when the index has just been rebuilt - holding a few hundred
     * megabytes of idle weights through a phone's whole idle period is exactly the kind of thing
     * that makes a background feature feel expensive.
     */
    suspend fun release() = lock.withLock {
        val current = active
        if (current != null) {
            // Nothing is held for a cloud model: what it costs is the request, not a resident copy
            // of any weights.
            if (!current.isCloud) {
                if (current.litert) litert.unload() else embedder.unload()
            }
            active = null
        }
    }
}
