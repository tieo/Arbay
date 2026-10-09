package io.github.tieo.arbay.crawler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** What a helper process left behind once it exited. */
internal class ProcessOutcome(val exitCode: Int, val stdout: ByteArray, val stderr: String)

// Reading a pipe blocks its thread until the other end closes, so the readers get threads of
// their own instead of taking coroutine or common-pool threads that other work waits on.
private val pipeReaders = Executors.newCachedThreadPool { task ->
    Thread(task, "subprocess-pipe").apply { isDaemon = true }
}

/**
 * Run a helper (python fetchers, xvfb-run with Chrome under it) to completion and collect what
 * it printed.
 *
 * The process and everything it started are killed on every way out that is not a clean exit,
 * which is the calling coroutine being cancelled: the search that wanted the page is gone, or
 * its market's time limit ran out. Killing only the direct child is not enough, since xvfb-run
 * leaves its python, Chrome and Xvfb running when it alone is killed.
 */
internal suspend fun runProcess(args: List<String>): ProcessOutcome {
    val process = ProcessBuilder(args).redirectErrorStream(false).start()
    val stdout = CompletableFuture.supplyAsync({ process.inputStream.readBytes() }, pipeReaders)
    val stderr = CompletableFuture.supplyAsync({ process.errorStream.bufferedReader().readText() }, pipeReaders)
    try {
        // Both waits are interruptible, so a cancellation ends them wherever they block.
        runInterruptible(Dispatchers.IO) { process.waitFor() }
        // Anything the helper left running would hold the pipes open and the reads below with them.
        killDescendants(process)
        return runInterruptible(Dispatchers.IO) {
            ProcessOutcome(exitCode = process.exitValue(), stdout = stdout.get(), stderr = stderr.get())
        }
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
