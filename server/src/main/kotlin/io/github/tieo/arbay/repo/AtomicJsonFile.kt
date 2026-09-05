package io.github.tieo.arbay.repo

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Replace a file's contents in one step.
 *
 * Everything the server cannot rebuild lives in a single JSON file per kind: the saved searches,
 * what each watch has already seen, the crawled listings. Writing those in place means the window
 * between truncating and finishing is a window where the file holds half a document, and a
 * container restart inside it loses the lot. Writing a sibling and renaming makes the swap atomic:
 * a reader sees either the old file or the new one.
 */
fun File.writeTextAtomically(text: String) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    Files.move(tmp.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}
