package io.github.tieo.arbay

import java.io.File

/**
 * Where the server keeps everything it writes: saved searches, caches, settings, snapshots,
 * logs. One place, so a test run can be pointed somewhere empty and never reads or rewrites
 * the data of the machine it runs on.
 *
 * `-Darbay.dataDir` names the directory; without it the data lives in `~/.arbay`, which is
 * where production has it (the container sets `user.home` to its volume). The downloaded
 * models are a cache rather than state and can be pointed elsewhere on their own with
 * `-Darbay.modelsDir`, so tests reuse the models instead of downloading them again.
 * `logback.xml` resolves the log directory from the same property.
 */
object DataDir {
    val root: File = System.getProperty("arbay.dataDir")?.let(::File)
        ?: File(System.getProperty("user.home"), ".arbay")

    val models: File = System.getProperty("arbay.modelsDir")?.let(::File) ?: File(root, "models")

    fun file(relative: String): File = File(root, relative)
}
