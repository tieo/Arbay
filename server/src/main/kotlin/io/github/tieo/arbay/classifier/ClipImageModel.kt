package io.github.tieo.arbay.classifier

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.github.tieo.arbay.DataDir
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.FloatBuffer
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.slf4j.LoggerFactory

/**
 * CLIP ViT-B/32 image encoder (ONNX) producing a 512-dim L2-normalized embedding of a
 * listing photo, so the free-item model understands what is IN the image, not just the
 * title. Runs on the same ONNX Runtime already in the classpath; model auto-downloaded
 * from HuggingFace on first use (~350MB). CPU inference ~50-150ms per image.
 *
 * Text (distiluse) and image (CLIP) embeddings live in different spaces, so cosine between
 * a text and an image vector is meaningless; image similarity is only ever compared
 * image-to-image (a listing photo vs a loved/disliked photo), and the arena models consume
 * the two halves concatenated and learn their own weights.
 */
object ClipImageModel {

    private val log = LoggerFactory.getLogger(ClipImageModel::class.java)
    // immich's CLIP ViT-B/32 visual encoder: input "image" [1,3,224,224] → output
    // "embedding" [1,512]. Hosted on HuggingFace Xet storage, so plain HTTP 403s;
    // huggingface_hub (python) fetches it (installed in the server image).
    private const val HF_REPO = "immich-app/ViT-B-32__openai"
    private const val HF_FILE = "visual/model.onnx"
    private const val MODEL_FILE = "clip-vit-b32-vision.onnx"
    const val DIM = 512
    private const val SIZE = 224

    // CLIP normalization constants (per channel, RGB).
    private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    init {
        // Preprocessing uses java.awt; force headless before any Toolkit init so no X11 libs are needed.
        System.setProperty("java.awt.headless", "true")
    }

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private val loadedSession = Reloadable("CLIP image model", log) {
        env.createSession(ensureDownloaded().absolutePath)
    }
    private val session: OrtSession? get() = loadedSession.get()

    val isAvailable: Boolean get() = session != null

    // Small LRU of image embeddings keyed by URL, so repeated listings in a session (and
    // re-scoring after retrain) don't re-fetch/re-embed the same photo.
    private const val CACHE_MAX = 512
    private val urlCache = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, FloatArray>?) = size > CACHE_MAX
    }

    /** Fetch an image URL and embed it, cached by URL. Null on any failure (unavailable
     *  model, network error, undecodable). Never throws — image is an optional signal. */
    fun embedUrl(url: String?): FloatArray? {
        if (url.isNullOrBlank() || !url.startsWith("http") || session == null) return null
        synchronized(urlCache) { urlCache[url]?.let { return it } }
        val bytes = try {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            log.debug("Image fetch failed for {}: {}", url, e.message); return null
        }
        val vec = embed(bytes) ?: return null
        synchronized(urlCache) { urlCache[url] = vec }
        return vec
    }

    /** Embed raw image bytes into a 512-dim L2-normalized vector, or null if unavailable/undecodable. */
    fun embed(imageBytes: ByteArray): FloatArray? {
        val sess = session ?: return null
        val image = try {
            ImageIO.read(ByteArrayInputStream(imageBytes))
        } catch (_: Exception) { null } ?: return null

        val pixels = preprocess(image)
        val inputName = sess.inputNames.first()
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong()))
        return try {
            val result = sess.run(mapOf(inputName to tensor))
            val vec = extractVector(result.get(0).value) ?: return null
            l2normalize(vec)
        } catch (e: Exception) {
            log.warn("CLIP inference failed: ${e.message}")
            null
        } finally {
            tensor.close()
        }
    }

    /** How much [candidate] resembles loved photos minus disliked photos (max cosine to
     *  each set), in [-1,1]. 0 when there is no candidate or no reference photos — so it's
     *  a no-op until the user has swiped items that had images. */
    fun affinity(candidate: FloatArray?, loved: List<FloatArray>, disliked: List<FloatArray>): Double {
        if (candidate == null) return 0.0
        val love = loved.maxOfOrNull { cosine(candidate, it) } ?: 0.0
        val dislike = disliked.maxOfOrNull { cosine(candidate, it) } ?: 0.0
        return (love - dislike).coerceIn(-1.0, 1.0)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    /** The ONNX output is [1, 512] (Array<FloatArray>) for this model; tolerate a flat [512] too. */
    private fun extractVector(value: Any?): FloatArray? = when (value) {
        is Array<*> -> (value.firstOrNull() as? FloatArray)
        is FloatArray -> value
        else -> null
    }

    /** Resize (shorter side to 224) + center-crop 224, then normalize to a CHW float array. */
    private fun preprocess(src: BufferedImage): FloatArray {
        val w = src.width; val h = src.height
        val scale = SIZE.toDouble() / minOf(w, h)
        val rw = (w * scale).roundToInt().coerceAtLeast(SIZE)
        val rh = (h * scale).roundToInt().coerceAtLeast(SIZE)

        val resized = BufferedImage(rw, rh, BufferedImage.TYPE_INT_RGB)
        val g = resized.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(src, 0, 0, rw, rh, null)
        g.dispose()

        val offX = (rw - SIZE) / 2
        val offY = (rh - SIZE) / 2

        // CHW layout: [3][224][224], channels R,G,B.
        val out = FloatArray(3 * SIZE * SIZE)
        val plane = SIZE * SIZE
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val rgb = resized.getRGB(offX + x, offY + y)
                val r = ((rgb shr 16) and 0xFF) / 255f
                val gg = ((rgb shr 8) and 0xFF) / 255f
                val b = (rgb and 0xFF) / 255f
                val idx = y * SIZE + x
                out[idx] = (r - MEAN[0]) / STD[0]
                out[plane + idx] = (gg - MEAN[1]) / STD[1]
                out[2 * plane + idx] = (b - MEAN[2]) / STD[2]
            }
        }
        return out
    }

    private fun l2normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 1e-9f) for (i in v.indices) v[i] /= norm
        return v
    }

    private fun ensureDownloaded(): File {
        val dir = DataDir.models.also { it.mkdirs() }
        val file = File(dir, MODEL_FILE)
        if (file.exists() && file.length() > 1024) return file

        log.info("Downloading CLIP image model via huggingface_hub ($HF_REPO/$HF_FILE)...")
        // The file is on HF Xet storage; huggingface_hub handles the Xet protocol where a
        // plain HTTP GET is rejected. Copy the resolved cache path to our models dir.
        val script = "from huggingface_hub import hf_hub_download; import shutil, sys; " +
            "p = hf_hub_download('$HF_REPO', '$HF_FILE'); shutil.copyfile(p, sys.argv[1])"
        // Copied beside the model and renamed once complete, so a download cut short never sits
        // at the model's name. The output goes to a file rather than a pipe, so the time limit
        // holds even when the script stops writing without exiting.
        val partial = File(dir, "$MODEL_FILE.part")
        val output = File(dir, "$MODEL_FILE.download.log")
        val proc = ProcessBuilder("python3", "-c", script, partial.absolutePath)
            .redirectErrorStream(true).redirectOutput(output).start()
        val finished = proc.waitFor(15, java.util.concurrent.TimeUnit.MINUTES)
        if (!finished) { proc.destroyForcibly(); throw java.io.IOException("CLIP model download timed out") }
        if (proc.exitValue() != 0 || !partial.exists() || partial.length() < 1024) {
            throw java.io.IOException("huggingface_hub download failed (exit ${proc.exitValue()}): ${output.readText().take(400)}")
        }
        java.nio.file.Files.move(
            partial.toPath(), file.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
        output.delete()
        log.info("Downloaded $MODEL_FILE (${file.length() / 1024}KB)")
        return file
    }
}
