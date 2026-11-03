package io.github.tieo.arbay.chat

import java.io.File

/**
 * The market account sidecars (vinted_chat.py on market_browser.py), extracted together into one
 * directory so they can import each other.
 */
internal val scriptDir: File by lazy {
    val dir = File(System.getProperty("java.io.tmpdir"), "arbay-sidecars").apply { mkdirs() }
    for (name in listOf("market_browser.py", "vinted_chat.py")) {
        val res = MarketSidecar::class.java.getResourceAsStream("/$name") ?: error("$name not found in classpath")
        File(dir, name).outputStream().use { res.copyTo(it) }
    }
    dir
}
