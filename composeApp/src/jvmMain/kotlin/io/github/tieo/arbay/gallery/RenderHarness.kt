package io.github.tieo.arbay.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.tieo.arbay.ui.LocalPreloadedImages
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.ui.theme.ArbayTheme
import org.jetbrains.skia.Image
import java.io.File

/**
 * Off-screen renderer: paints a composable to a PNG with no display, so every view in the app can
 * be reviewed as a gallery of images. Runs on the JVM (`compose.desktop.currentOs`) target.
 */
// Files decoded once and kept, so drawing forty scenes does not read the same four
// photos forty times.
private val decoded = mutableMapOf<String, Painter?>()

/** Where a named picture lives. The task runs with the module as its working directory,
 *  so a name is resolved against the model beside it rather than against wherever Gradle
 *  happened to start. */
private val photos = File(System.getProperty("gallery.photos") ?: "../docs/model").absoluteFile.normalize()

/** The picture at an address, decoded here and now, or null if there is none to decode. */
private fun alreadyDecoded(address: String): Painter? = decoded.getOrPut(address) {
    val file = File(address.removePrefix("file://")).let { if (it.isAbsolute) it else File(photos, address) }
    runCatching {
        BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap())
    }.getOrNull()
}

fun renderToPng(
    name: String,
    widthDp: Int,
    heightDp: Int,
    dark: Boolean = false,
    outDir: File,
    // Pixels per dp. A phone render is read at its own size and wants two; a
    // window-wide one is only ever looked at small, and at two it is five times
    // the pixels of a phone screen and takes longer than every other render put
    // together.
    scale: Float = 2f,
    content: @Composable () -> Unit,
) {
    val density = Density(scale)
    val scene = ImageComposeScene(
        width = (widthDp * density.density).toInt(),
        height = (heightDp * density.density).toInt(),
        density = density,
    ) {
        ArbayTheme(darkTheme = dark) {
            // The window behind the screen. A sheet drawn in place carries no
            // background of its own - in the app a dialog's surface paints it -
            // so without this a dark render came out as pale text on white.
            CompositionLocalProvider(LocalPreloadedImages provides ::alreadyDecoded) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
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
