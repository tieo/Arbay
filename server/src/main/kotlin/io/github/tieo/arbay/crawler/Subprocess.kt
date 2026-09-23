package io.github.tieo.arbay.crawler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** What a helper process left behind once it exited. */
internal class ProcessOutcome(val exitCode: Int, val stdout: ByteArray, val stderr: String)

/** The helper process outlived the time it was given and was killed. */
internal class ProcessTimedOut(val timeoutMs: Long) : Exception("process did not finish within ${timeoutMs}ms")

// Reading a pipe blocks its thread until the other end closes, so the readers get threads of
// their own instead of taking coroutine or common-pool threads that other work waits on.
private val pipeReaders = Executors.newCachedThreadPool { task ->
    Thread(task, "subprocess-pipe").apply { isDaemon = true }
}

/**
 * Run a helper (python fetchers, xvfb-run with Chrome under it) to completion and collect what
 * it printed.
 *
 * The process and everything it started are killed on every way out that is not a clean exit:
 * the time limit running out, and the calling coroutine being cancelled because the search that
 * wanted the page is gone. Killing only the direct child is not enough, since xvfb-run leaves
 * its python, Chrome and Xvfb running when it alone is killed.
 */
internal suspend fun runProcess(args: List<String>, timeoutMs: Long): ProcessOutcome {
    val process = ProcessBuilder(args).redirectErrorStream(false).start()
    val stdout = CompletableFuture.supplyAsync({ process.inputStream.readBytes() }, pipeReaders)
    val stderr = CompletableFuture.supplyAsync({ process.errorStream.bufferedReader().readText() }, pipeReaders)
    try {
        val exited = runInterruptible(Dispatchers.IO) { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
        if (!exited) throw ProcessTimedOut(timeoutMs)
        // Anything the helper left running would hold the pipes open and the reads below with them.
        killDescendants(process)
        return ProcessOutcome(
            exitCode = process.exitValue(),
            stdout = stdout.get(10, TimeUnit.SECONDS),
            stderr = stderr.get(10, TimeUnit.SECONDS),
        )
    } finally {
        killTree(process)
    }
}

/** Kill [process] and every process it started, children first so none is orphaned. */
internal fun killTree(process: Process) {
    killDescendants(process)
    if (process.isAlive) process.destroyForcibly()
}

private fun killDescendants(process: Process) {
    process.descendants().forEach { it.destroyForcibly() }
}
