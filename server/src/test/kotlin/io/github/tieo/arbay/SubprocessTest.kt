package io.github.tieo.arbay.crawler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubprocessTest {

    // A shell that starts a grandchild the way xvfb-run starts python and Chrome, and writes the
    // grandchild's pid where the test can find it.
    private fun treeScript(pidFile: File) =
        listOf("sh", "-c", "sleep 60 & echo \$! > ${pidFile.absolutePath}; wait")

    private fun alive(pidFile: File): Boolean {
        val pid = pidFile.readText().trim().toLong()
        return ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }

    private fun awaitPid(pidFile: File) {
        repeat(100) { if (pidFile.length() > 0) return; Thread.sleep(20) }
        error("the script never started its child")
    }

    @Test
    fun `what the helper prints is what comes back`() = runBlocking {
        val outcome = runProcess(listOf("sh", "-c", "printf page; printf oops >&2; exit 3"), timeoutMs = 10_000)
        assertEquals(3, outcome.exitCode)
        assertEquals("page", outcome.stdout.toString(Charsets.UTF_8))
        assertEquals("oops", outcome.stderr)
    }

    @Test
    fun `running out of time kills the helper and what it started`() = runBlocking {
        val pidFile = File.createTempFile("grandchild", ".pid")
        assertFailsWith<ProcessTimedOut> { runProcess(treeScript(pidFile), timeoutMs = 500) }
        awaitPid(pidFile)
        Thread.sleep(200)
        assertFalse(alive(pidFile), "the grandchild outlived its parent's timeout")
    }

    @Test
    fun `a search that is cancelled takes its helper down with it`() = runBlocking {
        val pidFile = File.createTempFile("grandchild", ".pid")
        val run = async(Dispatchers.Default) { runProcess(treeScript(pidFile), timeoutMs = 60_000) }
        awaitPid(pidFile)
        assertTrue(alive(pidFile))
        run.cancel()
        runCatching { run.await() }
        delay(200)
        assertFalse(alive(pidFile), "the grandchild outlived the cancelled search")
    }
}
