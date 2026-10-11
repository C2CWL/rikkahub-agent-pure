package me.rerere.rikkahub.ortqnn

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer
import java.util.Date

/**
 * One-shot, file-triggered probe: **can ONNX Runtime's QNN execution provider compile and run
 * our EmbeddingGemma-2 text graph on this device's Hexagon NPU?**
 *
 * Spike branch `feat/moxw-ortqnn`. Not a product feature -- no UI, no settings, no wiring into
 * the vector pipeline. The offline (x86_64 host) attempt could never answer the question: the
 * graph composes fine and the QNN *CPU* reference backend accepts it, but HTP graph
 * finalization dies with `QNN_COMMON_ERROR_MEM_ALLOC (1002)` on a 1.6 GB host, so the result
 * is only meaningful on real hardware.
 *
 * Protocols is a pair of files in the app's own files dir (same trick as `NpuProbe`): reaching
 * an NPU runs native code that can fault the whole process rather than throw, so every step is
 * appended to the result file *before* the risky call runs, and the request file is consumed
 * first so a native abort cannot become a crash loop.
 *
 * ```
 * ortqnn-probe-request     line 1: absolute path of the .onnx model
 *                          line 2: optional sequence length (default 128)
 * ortqnn-probe-result.txt  appended to as the probe progresses, in the same directory
 * ```
 *
 * The request is searched for in the files dir *and* in every `workspaces/<uuid>/files`
 * beneath it, because that is where the proot workspace actually lands.
 */
object OrtQnnProbe {

    const val REQUEST_FILE = "ortqnn-probe-request"
    const val RESULT_FILE = "ortqnn-probe-result.txt"
    private const val DEFAULT_SEQ = 128

    /** `soc_model` / `htp_arch` for Snapdragon 8 Elite (vivo V2463A). */
    private const val SOC_MODEL = "SM8750"
    private const val HTP_ARCH = "79"

    fun runIfRequested(
        appFilesDir: File,
        appNativeLibDir: String,
        appExternalFilesDir: File? = null,
        socModel: String = "",
    ) {
        val request = findRequest(appFilesDir, appExternalFilesDir) ?: return
        val lines = runCatching { request.readLines() }.getOrDefault(emptyList())
        runCatching { request.delete() }              // point of no return

        val dir = request.parentFile ?: appFilesDir
        val result = File(dir, RESULT_FILE)
        runCatching { result.delete() }
        fun say(line: String) {
            runCatching { result.appendText(line + "\n") }   // append + close == flushed
        }

        val modelPath = lines.getOrNull(0)?.trim().orEmpty()
        val seq = lines.getOrNull(1)?.trim()?.toIntOrNull() ?: DEFAULT_SEQ

        say("=== OrtQnnProbe ===")
        say("at            = ${Date()}")
        say("device        = ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
            "(SDK ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.SUPPORTED_ABIS.joinToString()})")
        say("socModel      = ${socModel.ifEmpty { android.os.Build.SOC_MODEL }}")
        say("nativeLibDir  = $appNativeLibDir")
        say("model         = $modelPath")
        say("seqLen        = $seq")

        val libs = File(appNativeLibDir)
        say("--- native libs ---")
        libs.listFiles()
            ?.filter { it.name.contains("qnn", true) || it.name.contains("onnxruntime", true) }
            ?.sortedBy { it.name }
            ?.forEach { say("  ${it.name}  ${it.length()} B") }
            ?: say("  (cannot list nativeLibDir)")

        val model = File(modelPath)
        if (!model.isFile) {
            say("ABORT: model file not found")
            return
        }
        say("modelBytes    = ${model.length()}")

        // Everything native is behind this: a missing/mismatched .so throws here, and an
        // incompatible one can still take the process down later (hence the progressive log).
        val env = try {
            OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE, "ortqnnprobe")
        } catch (t: Throwable) {
            say("FATAL loading onnxruntime: ${t.javaClass.name}: ${t.message}")
            return
        }
        try {
            say("ortProviders  = ${OrtEnvironment.getAvailableProviders()}")
        } catch (t: Throwable) {
            say("ortProviders  = ERR ${t.javaClass.name}: ${t.message}")
        }

        val htpLib = File(appNativeLibDir, "libQnnHtp.so").absolutePath
        val ctxPath = File(dir, "ortqnn-eg2-ctx.onnx").absolutePath

        // NOTE: soc_model must be NUMERIC with this build (QAIRT 2.42): the chip-family form
        // ("SM8750") makes it throw "stoi: no conversion". htp_arch alone is enough.
        val attempts = listOf(
            "B htp minimal" to Pair(
                mapOf("backend_path" to htpLib),
                emptyMap(),
            ),
            "C htp arch79, no-cpu-fallback" to Pair(
                mapOf("backend_path" to htpLib, "htp_arch" to HTP_ARCH),
                mapOf("session.disable_cpu_ep_fallback" to "1"),
            ),
            "D htp tuned, no-cpu-fallback" to Pair(
                mapOf(
                    "backend_path" to htpLib,
                    "htp_arch" to HTP_ARCH,
                    "htp_performance_mode" to "burst",
                    "htp_graph_finalization_optimization_mode" to "3",
                    "vtcm_mb" to "8",
                ),
                mapOf("session.disable_cpu_ep_fallback" to "1"),
            ),
            "E cpu (control)" to Pair(emptyMap(), emptyMap()),   // no EP added at all
        )

        var reference: FloatArray? = null
        for ((name, cfg) in attempts) {
            val (epOpts, cfgEntries) = cfg
            say("--- $name ---")
            say("  epOpts     = $epOpts")
            say("  cfgEntries = $cfgEntries")
            var options: OrtSession.SessionOptions? = null
            try {
                options = OrtSession.SessionOptions()
                options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE)
                cfgEntries.forEach { (k, v) -> options.addConfigEntry(k, v) }
                if (epOpts.isNotEmpty()) options.addQnn(epOpts)

                val t0 = System.currentTimeMillis()
                val session = env.createSession(model.absolutePath, options)
                say("  SESSION OK  (${System.currentTimeMillis() - t0} ms)")
                say("  inputs  = ${session.inputNames}")
                say("  outputs = ${session.outputNames}")

                try {
                    // Deterministic but NOT degenerate input: a sentence-ish token sequence.
                    // (All-BOS input made the fp16 graph emit Inf/NaN, which says nothing.)
                    val idsArr = LongArray(seq) { i -> if (i == 0) 2L else ((i * 37) % 900 + 100).toLong() }
                    val maskArr = LongArray(seq) { 1L }
                    val shape = longArrayOf(1, seq.toLong())
                    OnnxTensor.createTensor(env, LongBuffer.wrap(idsArr), shape).use { ti ->
                        OnnxTensor.createTensor(env, LongBuffer.wrap(maskArr), shape).use { tm ->
                            var last: FloatArray? = null
                            repeat(3) { i ->
                                val t1 = System.currentTimeMillis()
                                val res = session.run(mapOf("input_ids" to ti, "attention_mask" to tm))
                                val ms = System.currentTimeMillis() - t1
                                val out = res.get(0)
                                if (out is OnnxTensor) {
                                    val fb = out.floatBuffer
                                    val vec = FloatArray(fb.remaining())
                                    var sum = 0.0
                                    var bad = 0
                                    for (k in vec.indices) {
                                        vec[k] = fb.get(); val v = vec[k].toDouble()
                                        if (v.isNaN() || v.isInfinite()) bad++ else sum += v * v
                                    }
                                    last = vec
                                    say("  run[$i] ${ms} ms  l2=${"%.4f".format(kotlin.math.sqrt(sum))} " +
                                        "bad=$bad  head=${vec.take(3).joinToString { "%.4f".format(it) }}")
                                }
                                res.close()
                            }
                            last?.let { v ->
                                val ref = reference
                                if (ref == null) {
                                    reference = v
                                    say("  (kept as the reference vector)")
                                } else {
                                    var dot = 0.0; var na = 0.0; var nb = 0.0
                                    for (k in v.indices) {
                                        dot += v[k].toDouble() * ref[k].toDouble()
                                        na += v[k].toDouble() * v[k].toDouble()
                                        nb += ref[k].toDouble() * ref[k].toDouble()
                                    }
                                    val cos = if (na > 0 && nb > 0) dot / kotlin.math.sqrt(na * nb) else Double.NaN
                                    say("  cosine vs the reference = ${"%.6f".format(cos)}")
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    say("  RUN FAILED: ${t.javaClass.name}: ${t.message?.take(500)}")
                }
                session.close()
            } catch (t: Throwable) {
                say("  SESSION FAILED: ${t.javaClass.name}: ${t.message?.take(700)}")
            } finally {
                runCatching { options?.close() }
            }
        }
        say("=== done ===")
    }

    private fun findRequest(appFilesDir: File, appExternalFilesDir: File?): File? {
        // The external files dir comes first on purpose: it is the only one a shell (uid 2000)
        // can drop a file into, which is how the probe is driven from outside the app.
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
