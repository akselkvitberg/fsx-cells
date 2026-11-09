package fsxcells

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HtmlPageTest {
    private fun result(
        stdout: String = "",
        valueText: String? = null,
        valueHtml: String? = null,
        error: String? = null,
    ) = EvalResult(true, stdout, valueText, null, error, 0, emptyList(), valueHtml)

    @Test
    fun `no value html means the text block is used`() {
        assertNull(HtmlPage.body(result(stdout = "hi\n", valueText = "1")))
    }

    @Test
    fun `printed text and errors are escaped around the value html`() {
        assertEquals(
            "<pre>a &lt;b&gt;</pre><div class=\"fc-value\"><table></table></div><pre class=\"fc-error\">boom</pre>",
            HtmlPage.body(result(stdout = "a <b>\n", valueText = "t", valueHtml = "<table></table>", error = "boom")),
        )
    }
}
