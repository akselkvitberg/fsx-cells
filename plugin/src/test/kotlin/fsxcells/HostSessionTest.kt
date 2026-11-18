package fsxcells

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs cells in the real host (published by the build), with `dotnet` from PATH. */
class HostSessionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var session: HostSession

    @Before
    fun start() {
        val dll = File(System.getProperty("fsxcells.hostDll") ?: error("Run this test with Gradle: it needs fsxcells.hostDll"))
        val script = temp.newFile("test.fsx")
        session = HostSession(script.absolutePath, listOf("dotnet", dll.absolutePath), System.getenv())
    }

    @After
    fun stop() = session.dispose()

    private fun <T> java.util.concurrent.Future<T>.within(seconds: Long): T = get(seconds, TimeUnit.SECONDS)

    @Test
    fun `output arrives while the cell runs, and interrupt stops it`() {
        val chunks = ConcurrentLinkedQueue<String>()
        val started = CountDownLatch(1)
        val running = session.eval("printfn \"one\"\nSystem.Threading.Thread.Sleep 60_000", 1) {
            chunks += it
            started.countDown()
        }
        assertTrue("no output while the cell ran", started.await(60, TimeUnit.SECONDS))
        assertFalse(running.isDone)
        assertTrue(session.isBusy)

        val waiting = session.interrupt()
        val result = running.within(10)
        assertTrue(result.interrupted)
        assertFalse(result.ok)
        assertEquals("Interrupted.", result.error)
        assertEquals("one\n", result.stdout)
        assertEquals("one\n", chunks.joinToString(""))
        assertFalse(session.isWaitingFor(waiting))
        assertFalse(session.isBusy)

        val after = session.eval("1 + 1", 1).within(30)
        assertTrue(after.ok)
        assertFalse(after.interrupted)
        assertEquals("2", after.valueText)
    }

    @Test
    fun `interrupt with nothing running sends nothing`() {
        assertTrue(session.interrupt().isEmpty())
        assertEquals("3", session.eval("3", 1).within(60).valueText)
    }

    @Test
    fun `dispose answers waiting cells with its message`() {
        val running = session.eval("System.Threading.Thread.Sleep 60_000", 1)
        session.dispose("stopped for the test")
        val result = running.within(10)
        assertFalse(result.ok)
        assertEquals("stopped for the test", result.error)
    }

    @Test
    fun `a host that exits answers waiting cells with its exit code`() {
        val result = session.eval("printfn \"bye\"\nexit 3", 1).within(60)
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty(), result.error.orEmpty().startsWith("The F# host process exited (code 3)."))
        assertFalse(session.isAlive)
        assertFalse(session.isBusy)
    }

    @Test
    fun `non-ASCII text round-trips through the host`() {
        val result = session.eval("printfn \"æøå 🙂\"\n\"æøå 🙂\"", 1).within(60)
        assertTrue(result.error.orEmpty(), result.ok)
        assertEquals("æøå 🙂\n", result.stdout)
        assertEquals("æøå 🙂", result.valueText)
    }

    @Test
    fun `a session is not idle while a cell runs, and is idle from when the cell is done`() {
        val running = session.eval("System.Threading.Thread.Sleep 2_000\n1", 1)
        assertEquals(0L, session.idleNanos(System.nanoTime() + TimeUnit.HOURS.toNanos(1)))
        assertEquals("1", running.within(60).valueText)
        // The answer arrived before now, so the session has been idle a little longer than 45 minutes then.
        val later = System.nanoTime() + TimeUnit.MINUTES.toNanos(45)
        val idle = session.idleNanos(later)
        assertTrue("idle for ${TimeUnit.NANOSECONDS.toMinutes(idle)} min", idle in TimeUnit.MINUTES.toNanos(45)..TimeUnit.MINUTES.toNanos(46))
    }
}
