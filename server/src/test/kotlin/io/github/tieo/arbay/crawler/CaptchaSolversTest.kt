package io.github.tieo.arbay.crawler

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The puzzle readers the search browser answers checks with, on puzzles the server's searches
 * met. Each fixture's answer is one the site accepted, or for the first three one read off the
 * picture by eye, where the slide was refused for an unscaled drag rather than a wrong spot.
 */
class CaptchaSolversTest {
    private val dir = File("src/test/resources/fixtures/captcha")

    private fun python(script: String, vararg args: String): String {
        val p = ProcessBuilder("python3", File(StealthBrowserClient.scriptDir, script).absolutePath, *args)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "$script failed: $out" }
        return out
    }

    @Test
    fun `Brave's slider is read to the bright disc`() {
        // Target centre x in canvas pixels, by fixture number.
        val expected = mapOf(1 to 137, 2 to 244, 3 to 80, 4 to 279, 5 to 96, 6 to 85, 7 to 174, 8 to 198, 9 to 130)
        val wrong = expected.mapNotNull { (n, want) ->
            val out = python("brave_captcha.py", File(dir, "brave/$n-picture.png").path, File(dir, "brave/$n-piece.png").path)
            val target = Regex("\"target\": \\[([0-9.]+)").find(out)!!.groupValues[1].toDouble()
            // The slide passes within a few pixels; the accepted answers came from a reading
            // one pixel to the right of this one.
            if (abs(target - want) > 3) "$n: read $target, accepted $want" else null
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }
}
