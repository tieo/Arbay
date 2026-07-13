package io.github.tieo.arbay

import io.github.tieo.arbay.classifier.ClipImageModel
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Smoke test — needs the ~350MB CLIP ONNX (downloaded on first use). Skips cleanly when
 *  the model can't load (offline/CI) so it never fails the gate for the wrong reason. */
class ClipImageModelTest {

    private fun solid(color: Color, w: Int = 300, h: Int = 200): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.color = color; g.fillRect(0, 0, w, h); g.dispose()
        val out = ByteArrayOutputStream(); ImageIO.write(img, "png", out); return out.toByteArray()
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var d = 0.0; for (i in a.indices) d += a[i] * b[i]; return d
    }

    @Test
    fun embedsImagesIntoNormalized512Vectors() {
        if (!ClipImageModel.isAvailable) {
            println("ClipImageModel unavailable (no model / offline) — skipping smoke test")
            return
        }
        val red = ClipImageModel.embed(solid(Color.RED))
        val blue = ClipImageModel.embed(solid(Color.BLUE))
        assertNotNull(red); assertNotNull(blue)
        assertEquals(ClipImageModel.DIM, red.size)
        assertEquals(ClipImageModel.DIM, blue.size)

        // L2-normalized → norm ≈ 1.
        val norm = sqrt(red.sumOf { (it * it).toDouble() })
        assertTrue(norm in 0.98..1.02, "expected unit-norm, got $norm")

        // Same image → cosine ≈ 1; different colors → clearly less similar.
        val redAgain = ClipImageModel.embed(solid(Color.RED))!!
        assertTrue(cosine(red, redAgain) > 0.999, "same image should be ~identical")
        assertTrue(cosine(red, blue) < 0.999, "red vs blue should differ")
    }

    @Test
    fun undecodableBytesReturnNull() {
        if (!ClipImageModel.isAvailable) return
        assertEquals(null, ClipImageModel.embed(byteArrayOf(1, 2, 3, 4)))
    }
}
