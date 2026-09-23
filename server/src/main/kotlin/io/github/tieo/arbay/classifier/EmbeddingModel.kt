package io.github.tieo.arbay.classifier

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.github.tieo.arbay.DataDir
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.LongBuffer
import java.text.Normalizer
import kotlin.math.sqrt
import org.slf4j.LoggerFactory

/**
 * Sentence embedding model using distiluse-base-multilingual-cased-v1 (ONNX).
 * 512-dimensional embeddings, supports 15 languages including German.
 * CPU-friendly (~10ms per embedding). Model auto-downloaded from HuggingFace on first use (~260MB).
 */
object EmbeddingModel {

    private val log = LoggerFactory.getLogger(EmbeddingModel::class.java)
    private const val MODEL_URL = "https://huggingface.co/sentence-transformers/distiluse-base-multilingual-cased-v1/resolve/main/onnx/model.onnx"
    private const val VOCAB_URL = "https://huggingface.co/sentence-transformers/distiluse-base-multilingual-cased-v1/resolve/main/vocab.txt"
    private const val MAX_SEQ_LEN = 256

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private val loadedSession = Reloadable("Embedding model", log) {
        env.createSession(ensureDownloaded("distiluse-multilingual-v1.onnx", MODEL_URL).absolutePath)
    }
    private val session: OrtSession? get() = loadedSession.get()

    private val loadedTokenizer = Reloadable("Embedding tokenizer", log) {
        // stripAccents=false: multilingual vocab contains ä/ö/ü/etc. as distinct tokens
        WordPieceTokenizer(ensureDownloaded("distiluse-multilingual-v1-vocab.txt", VOCAB_URL), stripAccents = false)
    }
    private val tokenizer: WordPieceTokenizer? get() = loadedTokenizer.get()

    val isAvailable: Boolean get() = session != null && tokenizer != null

    /** Embed text into a 384-dim float vector (L2-normalized). Returns null if model unavailable. */
    fun embed(text: String): FloatArray? {
        val tok = tokenizer ?: return null
        val sess = session ?: return null

        val (ids, mask) = tok.tokenize(text, MAX_SEQ_LEN)
        val seqLen = ids.size.toLong()
        val shape = longArrayOf(1L, seqLen)

        val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
        val attMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape)

        // distiluse model: input_ids + attention_mask only (no token_type_ids — DistilBERT)
        val inputs = mapOf(
            "input_ids" to inputIdsTensor,
            "attention_mask" to attMaskTensor,
        )

        return try {
            val result = sess.run(inputs)
            // last_hidden_state: [1, seq_len, hidden_dim] → mean pool → L2 normalize
            @Suppress("UNCHECKED_CAST")
            val hidden = result.get(0).value as Array<Array<FloatArray>>
            meanPoolAndNormalize(hidden[0], mask)
        } finally {
            inputIdsTensor.close()
            attMaskTensor.close()
        }
    }

    /** Cosine similarity between two embeddings (both assumed L2-normalized → just dot product). */
    fun similarity(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        for (i in a.indices) dot += a[i] * b[i]
        return dot.coerceIn(-1.0, 1.0)
    }

    private fun meanPoolAndNormalize(hidden: Array<FloatArray>, mask: LongArray): FloatArray {
        val dim = hidden[0].size
        val result = FloatArray(dim)
        var count = 0
        for (i in hidden.indices) {
            if (mask[i] == 1L) {
                for (j in 0 until dim) result[j] += hidden[i][j]
                count++
            }
        }
        if (count > 0) for (j in 0 until dim) result[j] /= count
        // L2 normalize
        val norm = sqrt(result.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 1e-9f) for (j in 0 until dim) result[j] /= norm
        return result
    }

    private fun ensureDownloaded(filename: String, startUrl: String): File {
        val dir = DataDir.models.also { it.mkdirs() }
        val file = File(dir, filename)
        if (!file.exists() || file.length() < 1024) {
            log.info("Downloading $filename from HuggingFace...")
            // Follow redirects manually — HuggingFace CDN uses 307 redirects that
            // Java's openStream() may not handle if the redirect crosses hosts.
            var currentUrl = startUrl
            var stream: java.io.InputStream? = null
            repeat(10) {
                if (stream != null) return@repeat
                val conn = URI(currentUrl).toURL().openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", "Java/HuggingFace-download")
                conn.connect()
                when (conn.responseCode) {
                    in 301..308 -> {
                        val location = conn.getHeaderField("Location")
                        // Resolve relative redirects against the current URL
                        currentUrl = if (location.startsWith("http")) location
                                     else URI(currentUrl).resolve(location).toString()
                        conn.disconnect()
                    }
                    200 -> stream = conn.inputStream
                    else -> throw java.io.IOException("HTTP ${conn.responseCode} for $currentUrl")
                }
            }
            val inputStream = stream ?: throw java.io.IOException("Too many redirects for $startUrl")
            // Downloaded beside the model and renamed once complete: a download cut short must not
            // leave a file at the model's name, where it would pass for the model on every start.
            val partial = File(dir, "$filename.part")
            inputStream.use { input -> partial.outputStream().use { output -> input.copyTo(output) } }
            java.nio.file.Files.move(
                partial.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
            log.info("Downloaded $filename (${file.length() / 1024}KB)")
        }
        return file
    }
}

// ── WordPiece Tokenizer ────────────────────────────────────────────────────────

private class WordPieceTokenizer(vocabFile: File, private val stripAccents: Boolean = true) {

    private val vocab: Map<String, Int>
    private val unkId: Int

    init {
        vocab = vocabFile.readLines().withIndex().associate { (idx, token) -> token to idx }
        unkId = vocab["[UNK]"] ?: 100
    }

    /** Returns (input_ids, attention_mask), both LongArrays of same length. */
    fun tokenize(text: String, maxLength: Int): Pair<LongArray, LongArray> {
        val ids = mutableListOf<Long>()
        ids.add(vocab["[CLS]"]?.toLong() ?: 101L)

        val words = basicTokenize(text)
        for (word in words) {
            val wordIds = wordPiece(word)
            if (ids.size + wordIds.size + 1 > maxLength) break
            ids.addAll(wordIds.map { it.toLong() })
        }
        ids.add(vocab["[SEP]"]?.toLong() ?: 102L)

        val mask = LongArray(ids.size) { 1L }
        return Pair(ids.toLongArray(), mask)
    }

    private fun basicTokenize(text: String): List<String> {
        // Lowercase + NFD normalize. For multilingual models (stripAccents=false),
        // keep combining marks so umlauts (ä/ö/ü) survive as NFC tokens in the vocab.
        val nfd = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        val normalized = if (stripAccents) {
            nfd.filter { c -> Character.getType(c) != Character.NON_SPACING_MARK.toInt() }
        } else {
            // Re-compose to NFC so vocab lookup finds precomposed ä/ö/ü
            Normalizer.normalize(nfd, Normalizer.Form.NFC)
        }

        val result = StringBuilder()
        for (c in normalized) {
            result.append(
                when {
                    c.isWhitespace() -> " "
                    isPunctuation(c) -> " $c "
                    else -> c
                }
            )
        }
        return result.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    private fun isPunctuation(c: Char): Boolean {
        val type = Character.getType(c)
        return type == Character.CONNECTOR_PUNCTUATION.toInt() ||
               type == Character.DASH_PUNCTUATION.toInt() ||
               type == Character.START_PUNCTUATION.toInt() ||
               type == Character.END_PUNCTUATION.toInt() ||
               type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
               type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
               type == Character.OTHER_PUNCTUATION.toInt() ||
               (c.code in 33..47) || (c.code in 58..64) ||
               (c.code in 91..96) || (c.code in 123..126)
    }

    private fun wordPiece(word: String): List<Int> {
        if (word.length > 100) return listOf(unkId)
        val tokens = mutableListOf<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: Int? = null
            while (start < end) {
                val sub = if (start == 0) word.substring(start, end) else "##" + word.substring(start, end)
                val id = vocab[sub]
                if (id != null) { found = id; break }
                end--
            }
            if (found == null) return listOf(unkId)
            tokens.add(found)
            start = end
        }
        return tokens
    }
}
