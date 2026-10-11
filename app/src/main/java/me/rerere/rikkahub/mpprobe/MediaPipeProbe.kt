package me.rerere.rikkahub.mpprobe

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import java.io.File
import java.util.Date

/**
 * One-shot, file-triggered probe: **can MediaPipe's Universal Embedder (Google's supported
 * on-device embedding task) run EmbeddingGemma 2 on this device, and what does each delegate
 * cost?**
 *
 * Spike branch `feat/moxw-mediapipe`. Rationale: Google AI Edge now documents Universal
 * Embedder as the supported way to run EmbeddingGemma 2 on device, and it *wraps LiteRT-LM's
 * `EmbeddingEngine`* -- the very API whose NPU path crashed for us in the earlier LiteRT spike
 * (`nativeCreateEmbeddingEngine` SIGBUS), and whose GPU path spun the CPU on this exact
 * Adreno 830. So the only way to know is to run it here.
 *
 * Protocol (same file-driven trick as `NpuProbe`/`OrtQnnProbe`: reaching an NPU runs native
 * code that can kill the process instead of throwing, so every step is appended to the result
 * file *before* the risky call runs):
 *
 * ```
 * mp-probe-request        line 1: absolute path of a .litertlm model
 *                         line 2: optional text to embed
 *                         line 3: optional image to embed (drives arm 3's cross-modal check)
 * mp-probe-result.txt     appended to as the probe progresses, in the same directory
 * ```
 *
 * The request is looked for in the app's external files dir first (that is the only place a
 * shell can drop a file into), then in the internal one and in each workspace's files dir.
 */
object MediaPipeProbe {

    const val REQUEST_FILE = "mp-probe-request"
    const val RESULT_FILE = "mp-probe-result.txt"
    private const val DEFAULT_TEXT = "a yellow piece of fruit"

    /** Which of the model's input signatures to load (it ships 128/256/512/1024/2048/8192). */
    private const val DEFAULT_SIGNATURE = 128

    fun runIfRequested(
        context: Context,
        appFilesDir: File,
        appExternalFilesDir: File?,
    ) {
        val request = findRequest(appFilesDir, appExternalFilesDir) ?: return
        val lines = runCatching { request.readLines() }
            .onFailure { /* logged below, once we know where to write */ }
            .getOrNull()
        runCatching { request.delete() }              // point of no return

        val dir = request.parentFile ?: appFilesDir
        val result = File(dir, RESULT_FILE)
        runCatching { result.delete() }
        fun say(line: String) {
            runCatching { result.appendText(line + "\n") }
        }

        if (lines == null) {
            say("=== MediaPipeProbe ===")
            say("ABORT: could not read the request file (permissions? try chmod 666)")
            return
        }
        val modelPath = lines.getOrNull(0)?.trim().orEmpty()
        val text = lines.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_TEXT
        val signature = lines.getOrNull(3)?.trim()?.toIntOrNull() ?: DEFAULT_SIGNATURE

        say("=== MediaPipeProbe ===")
        say("at            = ${Date()}")
        say("device        = ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
            "(SDK ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.SUPPORTED_ABIS.joinToString()})")
        say("model         = $modelPath")
        say("text          = $text")

        val model = File(modelPath)
        if (!model.isFile) {
            say("ABORT: model file not found")
            return
        }
        say("modelBytes    = ${model.length()}")

        val libDir = File(context.applicationInfo.nativeLibraryDir)
        say("--- native libs ---")
        libDir.listFiles()
            ?.filter {
                it.name.contains("mediapipe", true) || it.name.contains("litertlm", true) ||
                    it.name.contains("litert", true) || it.name.contains("tflite", true) ||
                    it.name.contains("sqlite", true) || it.name.contains("qnn", true)
            }
            ?.sortedBy { it.name }
            ?.forEach { say("  ${it.name}  ${it.length()} B") }
            ?: say("  (cannot list nativeLibraryDir)")

        // The compiled-model cache matters a lot here: the first build of an EmbeddingEngine
        // can take tens of seconds, and re-building it for every delegate attempt would both
        // be slow and hide which delegate actually compiled.
        val cacheDir = File(dir, "mp-cache").apply { mkdirs() }

        var reference: FloatArray? = null

        fun reportVector(tag: String, ms: Long, v: FloatArray?) {
            if (v == null) {
                say("  $tag ${ms} ms  <no embedding>")
                return
            }
            var sum = 0.0
            var bad = 0
            for (x in v) {
                val d = x.toDouble()
                if (d.isNaN() || d.isInfinite()) bad++ else sum += d * d
            }
            say("  $tag ${ms} ms  dim=${v.size} l2=${"%.4f".format(kotlin.math.sqrt(sum))} " +
                "bad=$bad  head=${v.take(3).joinToString { "%.4f".format(it) }}")
            val ref = reference
            if (ref == null) {
                reference = v
                say("    (kept as the reference vector)")
            } else if (ref.size == v.size) {
                var dot = 0.0; var na = 0.0; var nb = 0.0
                for (k in v.indices) {
                    dot += v[k].toDouble() * ref[k].toDouble()
                    na += v[k].toDouble() * v[k].toDouble()
                    nb += ref[k].toDouble() * ref[k].toDouble()
                }
                val cos = if (na > 0 && nb > 0) dot / kotlin.math.sqrt(na * nb) else Double.NaN
                say("    cosine vs the reference = ${"%.6f".format(cos)}")
            }
        }

        // ---- arm 1: MediaPipe Universal Embedder (the documented, supported wrapper) ----
        // KNOWN BROKEN as of tasks-retrieval 1.1.0: it declares litertlm-android
        // 0.17.0-alpha1, yet its own code references com.google.ai.edge.litertlm.
        // ActivationDataType, which only exists in litertlm 0.18.0 (verified by unzipping
        // 0.17.0-alpha1 / 0.17.0 / 0.17.1 / 0.18.0: only 0.18.0 has the class). So every
        // delegate fails with NoClassDefFoundError before any measurement happens. Making it
        // work means forcing litertlm 0.18.0 -- which removes `Capabilities`, the class our
        // LiteRtRuntime uses. Kept here so the failure is recorded rather than assumed.
        for (delegate in listOf(Delegate.CPU, Delegate.NPU, Delegate.GPU)) {
            say("--- mp: delegate=$delegate ---")
            var embedder: UniversalEmbedder? = null
            try {
                val base = BaseOptions.builder()
                    .setModelAssetPath(model.absolutePath)
                    .setDelegate(delegate)
                    .build()
                val options = UniversalEmbedderOptions.builder()
                    .setBaseOptions(base)
                    .setTextDelegate(delegate)
                    .setVisionDelegate(delegate)
                    .setL2Normalize(true)
                    .setCacheDir(cacheDir.absolutePath)
                    .build()

                val t0 = System.currentTimeMillis()
                embedder = UniversalEmbedder.createFromOptions(context, options)
                say("  CREATE OK  (${System.currentTimeMillis() - t0} ms)")

                repeat(3) { i ->
                    val t1 = System.currentTimeMillis()
                    val res = embedder.embedText(text)
                    reportVector("run[$i]", System.currentTimeMillis() - t1,
                        res.embeddings().firstOrNull()?.floatEmbedding())
                }
            } catch (t: Throwable) {
                say("  FAILED: ${t.javaClass.name}: ${t.message?.take(600)}")
            } finally {
                runCatching { embedder?.close() }
            }
        }

        // ---- arm 2: LiteRT-LM's EmbeddingEngine directly, no MediaPipe wrapper ----
        // Same engine underneath, so this is an apples-to-apples check of the wrapper's cost
        // (one less native layer + no MPImage/asset plumbing) and of whether the wrapper is
        // hiding anything. LiteRT-LM takes images natively too (InputData.Image), so nothing
        // multimodal is lost by going direct.
        for ((name, backend) in listOf(
            "CPU" to Backend.CPU(),
            "NPU" to Backend.NPU(nativeLibraryDir = libDir.absolutePath),
            "GPU" to Backend.GPU(),
        )) {
            say("--- direct litertlm: backend=$name ---")
            var engine: EmbeddingEngine? = null
            try {
                val cfg = EmbeddingEngineConfig(
                    modelPath = model.absolutePath,
                    backend = backend,
                    cacheDir = cacheDir.absolutePath,
                    // The .litertlm is a MULTI-SIGNATURE model: without this the engine loads
                    // the largest signature and the compiled-model tensor allocation fails
                    // ("Failed to allocate tensors", seen on both CPU and NPU). MediaPipe's
                    // own option list has the same knob for the same reason.
                    maxInputLength = 128,
                )
                val t0 = System.currentTimeMillis()
                engine = EmbeddingEngine(cfg)
                engine.initialize()
                say("  INIT OK  (${System.currentTimeMillis() - t0} ms)")

                repeat(3) { i ->
                    val t1 = System.currentTimeMillis()
                    val res = engine.computeEmbedding(
                        listOf(InputData.Text(text)),
                        EmbeddingOptions(normalize = true),
                    )
                    reportVector("run[$i]", System.currentTimeMillis() - t1, res.embedding)
                }
            } catch (t: Throwable) {
                say("  FAILED: ${t.javaClass.name}: ${t.message?.take(600)}")
            } finally {
                runCatching { engine?.close() }
            }
        }

        // ---- arm 3: is the vector MEANINGFUL, not merely deterministic? ----
        // Repeats agreeing with each other (cos == 1.000000) only proves the pipeline is stable.
        // What decides whether this can replace the llama.cpp path is whether the space is
        // *semantic*: "a yellow piece of fruit" must sit nearer "a ripe banana" than
        // "an automobile engine". And since the whole reason to switch is multimodal, an image
        // has to land near the text that describes it.
        fun cos(a: FloatArray, b: FloatArray): Double {
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (k in a.indices) {
                dot += a[k].toDouble() * b[k].toDouble()
                na += a[k].toDouble() * a[k].toDouble()
                nb += b[k].toDouble() * b[k].toDouble()
            }
            return if (na > 0 && nb > 0) dot / kotlin.math.sqrt(na * nb) else Double.NaN
        }

        say("--- semantics: CPU, no task prefix, cosine matrix ---")
        var semEngine: EmbeddingEngine? = null
        try {
            semEngine = EmbeddingEngine(
                EmbeddingEngineConfig(
                    modelPath = model.absolutePath,
                    backend = Backend.CPU(),
                    visionBackend = Backend.CPU(),
                    cacheDir = cacheDir.absolutePath,
                    // Signature to load. The model ships 128 / 256 / 512 / 1024 / 2048 / 8192 and
                    // this picks one; it decides both the arena and the longest text the engine
                    // will accept (it refuses rather than truncating). Read from request line 4 so
                    // one build can be re-run at several widths.
                    maxInputLength = signature,
                )
            ).also { it.initialize() }

            val probes = listOf(
                "a yellow piece of fruit",
                "a ripe banana",
                "an automobile engine",
                "the Eiffel Tower in Paris",
            )
            val vectors = probes.map { p ->
                val t = System.currentTimeMillis()
                val v = semEngine.computeEmbedding(
                    listOf(InputData.Text(p)),
                    EmbeddingOptions(normalize = true),
                ).embedding
                say("  text  ${v.size}d  ${System.currentTimeMillis() - t} ms  [$p]")
                v
            }
            say("  cosine matrix, row i vs column j:")
            say("         " + probes.indices.joinToString("") { "%9d".format(it) })
            probes.indices.forEach { i ->
                say("    [" + i + "]  " + probes.indices.joinToString("") { j ->
                    "%9.4f".format(cos(vectors[i], vectors[j]))
                })
            }

            val imagePath = lines?.getOrNull(2)?.trim().orEmpty()
            if (imagePath.isEmpty()) {
                say("  (no image path on request line 3 -- image arm skipped)")
            } else {
                val image = File(imagePath)
                if (!image.isFile) {
                    say("  image NOT FOUND: $imagePath")
                } else {
                    val bytes = image.readBytes()
                    val t = System.currentTimeMillis()
                    val iv = semEngine.computeEmbedding(
                        listOf(InputData.Image(bytes)),
                        EmbeddingOptions(normalize = true),
                    ).embedding
                    say("  image  ${image.name}  ${bytes.size} B  ${iv.size}d  " +
                        "${System.currentTimeMillis() - t} ms")
                    probes.forEachIndexed { i, p ->
                        say("    cos(image, [" + i + "] " + p + ") = " +
                            "%.4f".format(cos(iv, vectors[i])))
                    }
                }
            }
        } catch (t: Throwable) {
            say("  FAILED: ${t.javaClass.name}: ${t.message?.take(600)}")
        } finally {
            runCatching { semEngine?.close() }
        }
        say("=== done ===")
    }

    private fun findRequest(appFilesDir: File, appExternalFilesDir: File?): File? {
        appExternalFilesDir?.let { ext ->
            File(ext, REQUEST_FILE).takeIf { it.isFile }?.let { return it }
        }
        File(appFilesDir, REQUEST_FILE).takeIf { it.isFile }?.let { return it }
        appFilesDir.listFiles { f -> f.isDirectory && f.name == "workspaces" }?.forEach { ws ->
            ws.listFiles { f -> f.isDirectory }?.forEach { w ->
                File(w, "files/$REQUEST_FILE").takeIf { it.isFile }?.let { return it }
            }
        }
        return null
    }
}
