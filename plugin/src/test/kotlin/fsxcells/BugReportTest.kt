package fsxcells

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class BugReportTest {
    private val info = BugReportInfo(
        pluginVersion = "0.7.0",
        ideVersion = "JetBrains Rider 2026.2.3 (build RD-262.1)",
        os = "Linux 6.8 (amd64)",
        dotnetVersion = "10.0.100",
        description = "Ran a cell",
        error = "java.lang.IllegalStateException: boom & bust\n\tat fsxcells.X.run(X.kt:1)",
        hostStderr = listOf("host: one", "host: two"),
    )

    /** The query parameters of [url], decoded. */
    private fun query(url: String): Map<String, String> =
        URI(url).rawQuery.split('&').associate {
            val (k, v) = it.split('=', limit = 2)
            k to URLDecoder.decode(v, StandardCharsets.UTF_8)
        }

    @Test
    fun `the url opens the issue form with every field filled in`() {
        val url = BugReport.issueUrl(BugReport.title(info), BugReport.fields(info))
        assertTrue(url, url.startsWith("https://github.com/akselkvitberg/fsx-cells/issues/new?template=bug_report.yml&"))
        val q = query(url)
        assertEquals("java.lang.IllegalStateException: boom & bust", q["title"])
        assertEquals("Ran a cell", q["what-happened"])
        assertEquals(
            "FSX Cells: 0.7.0\nIDE: JetBrains Rider 2026.2.3 (build RD-262.1)\nOS: Linux 6.8 (amd64)\ndotnet --version: 10.0.100",
            q["versions"],
        )
        assertEquals(info.error, q["error"])
        assertEquals("host: one\nhost: two", q["host-stderr"])
        assertFalse("spaces are %20, not +", url.contains('+'))
    }

    @Test
    fun `an empty description leaves the field out, for the user to fill in`() {
        val q = query(BugReport.issueUrl("t", BugReport.fields(info.copy(description = null))))
        assertFalse(q.containsKey("what-happened"))
    }

    @Test
    fun `long fields are cut so the url fits`() {
        val trace = (1..2000).joinToString("\n") { "\tat fsxcells.Frame$it.run(Frame.kt:$it) é" }
        val url = BugReport.issueUrl("t", BugReport.fields(info.copy(error = "Boom\n$trace")))
        assertTrue("${url.length}", url.length <= BugReport.MAX_URL_LENGTH)
        val error = query(url).getValue("error")
        assertTrue(error.startsWith("Boom\n\tat fsxcells.Frame1.run"))
        assertTrue(error.endsWith("… (cut to fit in the URL)"))
        assertEquals("host: one\nhost: two", query(url)["host-stderr"])
    }

    @Test
    fun `the home folder and user name are hidden`() {
        assertEquals(
            "at ~/src/a.fsx by <user>, not ada99 or bada",
            BugReport.redact("at /home/ada/src/a.fsx by Ada, not ada99 or bada", "/home/ada", "ada"),
        )
        assertEquals("~\\proj\\a.fsx", BugReport.redact("C:\\Users\\ada\\proj\\a.fsx", "C:\\Users\\ada", "ada"))
        assertEquals("~/proj", BugReport.redact("C:/Users/ada/proj", "C:\\Users\\ada", "ada"))
        assertEquals("unchanged /x", BugReport.redact("unchanged /x", null, "x"))
        assertEquals("/home/adam/x", BugReport.redact("/home/adam/x", "/home/ada", "zed"))
    }

    @Test
    fun `a long first line makes a short title`() {
        val title = BugReport.title(info.copy(error = "\n" + "x".repeat(300)))
        assertEquals(100, title.length)
        assertTrue(title.endsWith("…"))
    }

    @Test
    fun `stderr lines are kept from every session, the last ones only`() {
        repeat(40) { RecentHostStderr.add("line $it") }
        val lines = RecentHostStderr.lines()
        assertEquals(30, lines.size)
        assertEquals("line 39", lines.last())
    }
}
