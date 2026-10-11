package me.rerere.locallm.litert

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Which accelerator an embedding engine is built on. */
enum class LitertEmbedBackend { CPU, GPU, NPU }

/**
 * What a loaded `.litertlm` tells us about itself.
 *
 * [dim] is *measured* rather than declared: unlike a GGUF, a `.litertlm` does not expose an
 * embedding width before you run it, and the service refuses a vector whose width does not match
 * what it recorded for the model. So the load embeds one throwaway string and takes the width of
 * what comes back -- which doubles as the proof that this file works on this backend at all.
 *
 * [signature] is echoed back because it is the model's real window: the engine refuses a text
 * longer than the signature it was loaded with, and everything that sizes chunks reads it.
 */
data class LitertEmbedInfo(
    val dim: Int,
    val signature: Int,
    /**
     * The accelerator the engine came up on, lower-case (`cpu`, `gpu`, `npu`).
     *
     * Reported because it is part of the model's *identity* as far as the index is concerned: the
     * two accelerators do not compute the same function - the same sentence comes back with cosine
     * 0.9997 between them - so a chunk embedded on one is slightly wrong for a query embedded on the
     * other. Read off the engine that is actually running rather than off the setting, so the
     * identity cannot disagree with the vectors that were produced. See
     * `EmbeddingService.ActiveModel.modelId`.
     */
    val accelerator: String,
)

/**
 * One loaded engine.
 *
 * Deliberately closes over the SDK's `EmbeddingEngine` instead of exposing it, so the state
 * machine below can be exercised on the JVM with a fake: the SDK class is final and its
 * constructor does native work, which is exactly the kind of thing a unit test must not run.
 */
interface LitertEmbedSession : AutoCloseable {
    /** Order is the contract: the caller zips the answer back onto its own list. */
    fun embed(texts: List<String>): List<FloatArray>

    /**
     * Embeds one image, from its encoded bytes.
     *
     * No task prefix: the model card is explicit that the instruction prefixes apply to text, and
     * an image is passed as it is. Only a model whose catalogue entry says `vision` should ever
     * reach this, and the engine refuses it outright on a text-only file.
     */
    fun embedImage(bytes: ByteArray): FloatArray
}

/** The seam over the SDK, so [LitertLmEmbedder] is testable without native code. */
interface LitertEmbedNative {
    fun open(
        modelPath: String,
        backend: LitertEmbedBackend,
        nativeLibraryDir: String,
        cacheDir: String?,
        signature: Int,
    ): LitertEmbedSession
}

/** Delegates straight to LiteRT-LM. */
class RealLitertEmbedNative : LitertEmbedNative {
    override fun open(
        modelPath: String,
        backend: LitertEmbedBackend,
        nativeLibraryDir: String,
        cacheDir: String?,
        signature: Int,
    ): LitertEmbedSession {
        val accelerator = when (backend) {
            LitertEmbedBackend.CPU -> Backend.CPU()
            LitertEmbedBackend.GPU -> Backend.GPU()
            // The NPU backend loads its dispatch/QNN runtime from the app's own lib dir, so the
            // caller has to hand that path over; an empty path means "the APK carries none", which
            // is the normal case and simply means this backend cannot be used.
            LitertEmbedBackend.NPU -> Backend.NPU(nativeLibraryDir = nativeLibraryDir)
        }
        val engine = EmbeddingEngine(
            EmbeddingEngineConfig(
                modelPath = modelPath,
                backend = accelerator,
                // Vision is pinned to the CPU deliberately, and not because this class only embeds
                // text - it embeds images too ([LitertEmbedSession.embedImage]). It is pinned
                // because the text and vision towers on one GPU is the combination nobody has run:
                // the SDK is asked for two backends and returns no error when the second one cannot
                // come up, so the failure mode would be a silent fallback rather than a load that
                // does not work. Images are the expensive half (~2.1s against ~60ms for a text), so
                // this is the open question about the GPU switch, not a settled one - see
                // `Settings.embeddingUseGpu`.
                visionBackend = Backend.CPU(),
                cacheDir = cacheDir,
                // Picks which of the file's input signatures to load -- see the comment on
                // [LitertEmbedInfo.signature]. Omitting it makes the engine load the largest one
                // and the compiled-model tensor allocation fails on a phone.
                maxInputLength = signature,
            )
        )
        engine.initialize()
        return object : LitertEmbedSession {
            override fun embed(texts: List<String>): List<FloatArray> = texts.map { text ->
                engine.computeEmbedding(
                    listOf(InputData.Text(text)),
                    // L2-normalised, which is what every caller here stores and compares.
                    EmbeddingOptions(normalize = true),
                ).embedding
            }

            // No prefix, and no text: the bytes go in as they are.
            override fun embedImage(bytes: ByteArray): FloatArray = engine.computeEmbedding(
                listOf(InputData.Image(bytes)),
                EmbeddingOptions(normalize = true),
            ).embedding

            override fun close() {
                engine.close()
            }
        }
    }
}

/**
 * Owns one loaded `.litertlm` embedding engine.
 *
 * The lifecycle mirrors `LlamaCppEmbedder` -- load on demand, unload on request, one at a time --
 * with one deliberate difference: the engine is *not* kept alive across calls to [load] for a
 * different file, because the service that owns this embeds with exactly one model and a stale
 * engine would be a few hundred megabytes of weights nothing can reach.
 *
 * A failed load does not poison the instance: nothing is recorded, so a retry after freeing memory
 * or re-downloading a truncated file behaves like the first attempt.
 */
class LitertLmEmbedder(
    private val native: LitertEmbedNative = RealLitertEmbedNative(),
    /** Chosen per load, from the user's setting; CPU unless they asked for otherwise. */
    private val backend: () -> LitertEmbedBackend = { LitertEmbedBackend.CPU },
    /** Where the NPU backend would look for its dispatch runtime. */
    private val nativeLibraryDir: () -> String = { "" },
    /** Where the engine may cache its compiled model, so the second load is cheap. */
    private val cacheDir: () -> String? = { null },
) {

    private val lock = Mutex()
    private var session: LitertEmbedSession? = null
    private var info: LitertEmbedInfo? = null
    private var loadedPath: String? = null

    val isLoaded: Boolean get() = session != null

    val loadedFile: String? get() = loadedPath

    /**
     * Opens [path] and returns what it reports about itself.
     *
     * Loading a file that is already open is a no-op for the *same* path, so the service's
     * per-round `ensureLoaded` does not tear the engine down and rebuild it.
     */
    suspend fun load(path: String, signature: Int): LitertEmbedInfo = lock.withLock {
        info?.let { if (loadedPath == path) return@withLock it }

        unloadLocked()

        // Asked once: [backend] is a lambda so the setting cannot lag a change, but the engine that
        // opens is the one whose name has to end up in the identity, so both read this one answer.
        val chosen = backend()

        val opened = native.open(
            modelPath = path,
            backend = chosen,
            nativeLibraryDir = nativeLibraryDir(),
            cacheDir = cacheDir(),
            signature = signature,
        )

        // Measure the width before publishing the session: a session that cannot embed is not
        // loaded, and leaving it in the field would make the next call fail instead of retrying.
        val measured = try {
            opened.embed(listOf(" ")).firstOrNull()
                ?: throw IllegalStateException("the embedding engine returned nothing")
        } catch (t: Throwable) {
            runCatching { opened.close() }
            throw t
        }

        session = opened
        loadedPath = path
        LitertEmbedInfo(
            dim = measured.size,
            signature = signature,
            accelerator = chosen.name.lowercase(),
        ).also { info = it }
    }

    /**
     * Embeds one text, L2-normalised.
     *
     * Throws when nothing is loaded, which the service turns into "semantic search is off" by
     * checking [isLoaded] first.
     */
    suspend fun embedNormalized(text: String): FloatArray = lock.withLock {
        val open = session ?: error("no .litertlm embedding model is loaded")
        open.embed(listOf(text)).single()
    }

    /**
     * Embeds one image, L2-normalised.
     *
     * A separate door from [embedNormalized] rather than an overload of it, because the two take
     * different arguments *and* only the text one is allowed a task prefix. It costs an order of
     * magnitude more than a text - the vision tower runs per image, measured at ~2.1s against
     * ~60ms on this hardware - which is what forces every caller that walks a directory to cap how
     * many images it does per round.
     */
    suspend fun embedImage(bytes: ByteArray): FloatArray = lock.withLock {
        val open = session ?: error("no .litertlm embedding model is loaded")
        open.embedImage(bytes)
    }

    /** Frees the engine and whatever the accelerator held on to. */
    suspend fun unload() = lock.withLock { unloadLocked() }
    private fun unloadLocked() {
        val open = session ?: return
        session = null
        info = null
        loadedPath = null
        runCatching { open.close() }
    }
}
