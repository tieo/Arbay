package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.SmartScreens
import io.github.tieo.arbay.model.SmartReading
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test

/**
 * Parses every OCR text in the directory named by SMART_EVAL_DIR and writes each reading beside it
 * as JSON, so the reader can be scored against photos judged by eye. Skipped where the variable is
 * not set, which is every ordinary test run.
 */
class SmartScreensEval {
    @Test
    fun `parse a directory of OCR texts`() {
        val dir = System.getenv("SMART_EVAL_DIR")?.let(::File) ?: return
        val json = Json { encodeDefaults = true }
        dir.walkTopDown().filter { it.isFile && it.extension == "txt" }.forEach { f ->
            val r = SmartScreens.parse(f.readText(), 1)
            File(f.path.removeSuffix(".txt") + ".json").writeText(r?.let { json.encodeToString(SmartReading.serializer(), it) } ?: "null")
        }
    }
}
