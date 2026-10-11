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
                // Vision runs on the CPU: this class only ever embeds text, and paying for a GPU
                // vision tower we do not call would be the expensive half of a multimodal model.
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

        val opened = native.open(
            modelPath = path,
            backend = backend(),
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
        LitertEmbedInfo(dim = measured.size, signature = signature).also { info = it }
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
