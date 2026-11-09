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
        outputs: List<OutputItem> = emptyList(),
    ) = EvalResult(true, stdout, valueText, null, error, 0, emptyList(), valueHtml, outputs)

    @Test
    fun `no html means the text block is used`() {
        assertNull(HtmlPage.body(result(stdout = "hi\n", valueText = "1")))
        assertNull(HtmlPage.body(result(outputs = listOf(OutputItem("display", "x", null)))))
    }

    @Test
    fun `printed text and value text are escaped around the value html`() {
        assertEquals(
            "<pre>a &lt;b&gt;</pre><div class=\"fc-value\"><table></table></div><pre class=\"fc-error\">boom</pre>",
            HtmlPage.body(result(stdout = "a <b>\n", valueText = "t", valueHtml = "<table></table>", error = "boom")),
        )
    }

    @Test
    fun `outputs keep their order`() {
        val outputs = listOf(
            OutputItem("text", "first\n", null),
            OutputItem("display", "T", "<table>T</table>"),
            OutputItem("text", "then\n", null),
            OutputItem("display", "plain", null),
        )
        assertEquals(
            "<pre>first</pre><div class=\"fc-display\"><table>T</table></div><pre>then</pre><pre>plain</pre><pre>42</pre>",
            HtmlPage.body(result(stdout = "ignored", valueText = "42", outputs = outputs)),
        )
    }
}
