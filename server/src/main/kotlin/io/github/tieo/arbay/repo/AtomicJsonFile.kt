package io.github.tieo.arbay.repo

import org.slf4j.Logger
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Replace a file's contents in one step.
 *
 * Everything the server cannot rebuild lives in a single JSON file per kind: the saved searches,
 * what each watch has already seen, the crawled listings. Writing those in place means the window
 * between truncating and finishing is a window where the file holds half a document, and a
 * container restart inside it loses the lot. Writing a sibling and renaming makes the swap atomic:
 * a reader sees either the old file or the new one.
 *
 * The sibling has a name of its own per call, so two writers never write into each other's
 * half-finished copy, and it is flushed to the disk before the rename, so a power cut cannot
 * leave the new name pointing at data that never arrived.
 */
fun File.writeTextAtomically(text: String) = writeBytesAtomically(text.toByteArray(Charsets.UTF_8))

/** [writeTextAtomically] for bytes, such as a mirrored image. */
fun File.writeBytesAtomically(content: ByteArray) {
    val dir = absoluteFile.parentFile.also { it.mkdirs() }
    val tmp = Files.createTempFile(dir.toPath(), "$name.", ".tmp")
    try {
        FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val bytes = ByteBuffer.wrap(content)
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        Files.move(tmp, toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Exception) {
        Files.deleteIfExists(tmp)
        throw e
    }
}

/**
 * Read a store's file and decode it, or return null when there is nothing to read.
 *
 * A file that exists but cannot be decoded is moved aside before null is returned. The store
 * then starts empty, and its next save writes a new file instead of writing that emptiness over
 * the only copy of what it held; the moved file keeps the data for whoever reads the log.
 */
fun <T> File.readStore(log: Logger, decode: (String) -> T): T? {
    if (!exists()) return null
    return try {
        decode(readText())
    } catch (e: Exception) {
        val aside = File(parentFile, "$name.unreadable-${System.currentTimeMillis()}")
        val moved = renameTo(aside)
        log.error(
            "{} could not be read ({}); {}",
            this, e.message,
            if (moved) "moved it to $aside so it is not overwritten" else "it could not be moved aside either",
        )
        null
    }
}
