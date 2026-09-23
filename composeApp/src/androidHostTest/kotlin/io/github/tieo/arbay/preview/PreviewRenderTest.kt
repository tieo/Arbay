package io.github.tieo.arbay.preview

import com.github.takahirom.roborazzi.ComposePreviewTester
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner
import java.io.File

/**
 * Draws every @Preview to a PNG through Robolectric, with no device.
 *
 * The other way of doing what the off-screen renderer does, kept so the two can
 * be measured against each other rather than argued about: same screens, same
 * sample data, different toolchain.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class PreviewRenderTest {

    @Test
    fun everyPreview() {
        val out = File(System.getProperty("preview.out") ?: "build/previews")
        out.mkdirs()
        val started = System.currentTimeMillis()
        val previews = AndroidComposablePreviewScanner()
            .scanPackageTrees("io.github.tieo.arbay.preview")
            .getPreviews()
        previews.forEach { preview ->
            captureRoboImage(
                file = File(out, "${preview.declaringClass.substringAfterLast('.')}-${preview.methodName}.png"),
                roborazziOptions = RoborazziOptions(),
            ) {
                preview()
            }
        }
        println("${previews.size} previews in ${(System.currentTimeMillis() - started) / 1000.0}s")
    }
}
