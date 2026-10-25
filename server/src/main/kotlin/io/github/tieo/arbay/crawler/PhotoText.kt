package io.github.tieo.arbay.crawler

import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.seconds

/**
 * The text in a photo, read with Tesseract: a drive's SMART screenshot ([SmartScreens]), or what a
 * machine has printed on it and on its type plate ([ModelGuesses]).
 */
object PhotoText {
    private val log = LoggerFactory.getLogger(PhotoText::class.java)

    /** The largest photo read, in bytes and in pixels a side, so a hostile file cannot exhaust the server. */
    private const val MOST_BYTES = 8_000_000
    private const val MOST_SIDE = 5_000

    /** A photo this narrow is upscaled before reading: Tesseract wants letters some 20 pixels high. */
    private const val READ_WIDTH = 1_400

    /** How the text sits: a screenshot's one block, or a photo's words scattered over a machine. */
    enum class Layout(val psm: String) { BLOCK("6"), SCATTERED("11") }

    val present: Boolean by lazy {
        runCatching { ProcessBuilder("tesseract", "--version").start().waitFor() == 0 }.getOrDefault(false)
            .also { if (!it) log.warn("tesseract is not installed: photos are not read") }
    }

    /** The photo's text, or null where it is not an image this reads or the reader gave none in time. */
    suspend fun read(bytes: ByteArray, enlarge: Double, layout: Layout): String? {
        if (!present || bytes.size > MOST_BYTES) return null
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        if (image.width > MOST_SIDE || image.height > MOST_SIDE || image.width < 200 || image.height < 150) return null
        val scale = (READ_WIDTH.toDouble() / image.width).coerceIn(1.0, 3.0) * enlarge
        val gray = BufferedImage((image.width * scale).toInt(), (image.height * scale).toInt(), BufferedImage.TYPE_BYTE_GRAY)
        gray.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            drawImage(image, 0, 0, gray.width, gray.height, null)
            dispose()
        }
        // Tesseract reads dark text on light; a terminal or a dark theme is turned over first, which
        // took its zeros from "9", "]" and "e" to "0".
        val raster = gray.raster
        var sum = 0L
        for (y in 0 until gray.height step 4) for (x in 0 until gray.width step 4) sum += raster.getSample(x, y, 0)
        val samples = ((gray.height + 3) / 4).toLong() * ((gray.width + 3) / 4)
        if (sum < samples * 255 * 0.45) {
            for (y in 0 until gray.height) for (x in 0 until gray.width) raster.setSample(x, y, 0, 255 - raster.getSample(x, y, 0))
        }
        val file: File = Files.createTempFile("photo", ".png").toFile()
        return try {
            ImageIO.write(gray, "png", file)
            // A photo with little text can keep Tesseract busy for long; none needs more than this.
            withTimeoutOrNull(30.seconds) {
                runProcess(listOf("tesseract", file.absolutePath, "stdout", "-l", "deu+eng", "--psm", layout.psm))
            }?.takeIf { it.exitCode == 0 }?.stdout?.decodeToString()
        } finally {
            file.delete()
        }
    }
}
