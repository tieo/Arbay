package io.github.tieo.arbay.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.ui.theme.ArbayTheme
import org.jetbrains.skia.Image
import java.io.File

/**
 * Off-screen renderer: paints a composable to a PNG with no display, so every view in the app can
 * be reviewed as a gallery of images. Runs on the JVM (`compose.desktop.currentOs`) target.
 */
fun renderToPng(
    name: String,
    widthDp: Int,
    heightDp: Int,
    dark: Boolean = false,
    outDir: File,
    content: @Composable () -> Unit,
) {
    val density = Density(2f)
    val scene = ImageComposeScene(
        width = (widthDp * density.density).toInt(),
        height = (heightDp * density.density).toInt(),
        density = density,
    ) {
        ArbayTheme(darkTheme = dark) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
    try {
        val img: Image = scene.render()
        val png = img.encodeToData()!!.bytes
        outDir.mkdirs()
        File(outDir, "$name.png").writeBytes(png)
    } finally {
        scene.close()
    }
}

fun main() {
    val outDir = File(System.getProperty("gallery.out") ?: "build/gallery")
    renderToPng("smoke", 360, 120, outDir = outDir) {
        Text("render harness works", Modifier.padding(24.dp))
    }
    println("wrote ${File(outDir, "smoke.png").absolutePath}")
}
