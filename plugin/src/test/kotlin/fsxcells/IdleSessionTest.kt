package fsxcells

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class IdleSessionTest {
    private fun minutes(m: Long) = TimeUnit.MINUTES.toNanos(m)

    @Test
    fun `a session is stopped once it has been unused for the timeout`() {
        assertFalse(HostSessionManager.isIdleTooLong(minutes(29), 30))
        assertTrue(HostSessionManager.isIdleTooLong(minutes(30), 30))
        assertTrue(HostSessionManager.isIdleTooLong(minutes(90), 30))
    }

    @Test
    fun `a timeout of 0 never stops a session`() {
        assertFalse(HostSessionManager.isIdleTooLong(minutes(100_000), 0))
    }

    @Test
    fun `a busy session reports no idle time, so it is never stopped`() {
        assertFalse(HostSessionManager.isIdleTooLong(0, 1))
    }

    @Test
    fun `the notice says the values are gone`() {
        val notice = HostSessionManager.idleRestartNotice(30)
        assertTrue(notice, notice.startsWith("New F# session: the previous one was stopped after 30 minutes without use"))
        assertTrue(notice, notice.contains("values from earlier cells are gone"))
        assertTrue(HostSessionManager.idleRestartNotice(1).contains("after 1 minute without"))
    }

    @Test
    fun `the notice is the first output line, dimmed`() {
        val result = EvalResult(true, "printed\n", "42", "int", null, 3, notice = "first\nsecond")
        val output = OutputFormatter.format(result)
        assertEquals(
            listOf(
                OutputLine("first", OutputLineKind.Dim),
                OutputLine("second", OutputLineKind.Dim),
                OutputLine("printed"),
                OutputLine("42"),
            ),
            output.lines,
        )
        assertEquals("first\nsecond\nprinted\n42", output.fullText)
    }
}
