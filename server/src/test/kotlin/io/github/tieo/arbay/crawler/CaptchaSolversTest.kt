package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
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
            .apply { environment()["ARBAY_MODELS_DIR"] = DataDir.models.absolutePath }
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

    @Test
    fun `reCAPTCHA grids are read to the squares Google accepted`() {
        // Squares row by row from 0. The car, bicycle, bridge and bus grids passed with these answers
        // on Google's demo page; the stairs grid was refused four times with [2] alone, and its
        // other steps are in squares 1, 3 and 7.
        val expected = mapOf(
            "car-3x3-a" to ("car" to listOf(0, 1, 3, 4)),
            "car-3x3-b" to ("car" to listOf(0, 2, 4, 8)),
            "bicycle-3x3" to ("bicycle" to listOf(3, 6, 8)),
            "stairs-3x3" to ("stairs" to listOf(1, 2, 3, 7)),
            // Cars drive under two of the bridges, which a one-class reading takes for car squares.
            "bridge-3x3" to ("bridge" to listOf(2, 3, 5, 7)),
            "bus-3x3" to ("bus" to listOf(0, 1, 4)),
        )
        val wrong = expected.mapNotNull { (name, want) ->
            val out = python("recaptcha_tiles.py", File(dir, "recaptcha/$name.png").path, "3", want.first).trim().lines().last()
            val got = Regex("\\d+").findAll(out).map { it.value.toInt() }.toList()
            if (got != want.second) "$name: chose $got, want ${want.second}" else null
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }
}
