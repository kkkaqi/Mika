package com.wangzan101.mika.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

class RootCommandSessionTest {
    @Test fun closingControlInputStopsTheShellAndItsChild() = withSession("sleep 30 & echo \$!; wait", fork = true) { process ->
        val stdout = process.inputStream.bufferedReader()
        val child = CompletableFuture.supplyAsync { stdout.readLine() }.get(5, SECONDS).toLong()
        try {
            process.outputStream.close()
            assertTrue("root shell still running", process.waitFor(5, SECONDS))
            assertTrue("cancellation reported success", process.exitValue() != 0)
            val stat = File("/proc/$child/stat")
            val deadline = System.nanoTime() + SECONDS.toNanos(2)
            while (true) {
                val state = runCatching { stat.readText().substringAfterLast(')').trim().first() }.getOrNull()
                if (state == null || state == 'Z') break
                assertTrue("child still running: $state", System.nanoTime() < deadline)
                MILLISECONDS.sleep(20)
            }
        } finally {
            ProcessHandle.of(child).ifPresent { if (it.isAlive) it.destroyForcibly() }
        }
    }

    @Test fun normalExitStopsTheWatcherAndPreservesTheExitCode() = withSession("exit 7") { process ->
        assertTrue("watcher blocked normal exit", process.waitFor(5, SECONDS))
        assertEquals(7, process.exitValue())
    }

    private fun withSession(command: String, fork: Boolean = false, test: (Process) -> Unit) {
        val shell = "/bin/sh"
        val setsid = "/usr/bin/setsid"
        val kill = "/bin/kill"
        assumeTrue(File("/proc/self/stat").isFile && listOf(shell, setsid, kill).all { File(it).canExecute() })
        // Older Android setsid versions fork even without job control.
        val launch = if (fork) "$setsid -f" else setsid
        val process = ProcessBuilder(shell, "-c", RootCommandSession.supervise(shell, launch, kill))
            .apply { environment()["c"] = command }.start()
        try {
            test(process)
        } finally {
            process.outputStream.close()
            if (!process.waitFor(1, SECONDS)) process.destroyForcibly()
        }
    }
}
