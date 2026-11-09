package fsxcells

import java.awt.Color

/** Colours and font for the HTML output page, taken from the editor so the output matches the editor theme. */
data class HtmlTheme(
    val background: Color,
    val foreground: Color,
    val error: Color,
    val fontFamily: String,
    val fontSizePx: Int,
    val dark: Boolean,
)

/** Builds the HTML shown in an output block when a cell's value has HTML (tables and objects). */
object HtmlPage {

    /**
     * The page body for [result], or null when its value has no HTML (then the text block is used).
     * Printed output and errors are kept as preformatted text around the value.
     */
    fun body(result: EvalResult): String? {
        if (result.valueHtml == null) return null
        val sb = StringBuilder()
        fun pre(text: String, cssClass: String? = null) {
            val t = text.trimEnd('\n', '\r')
            if (t.isEmpty()) return
            sb.append(if (cssClass == null) "<pre>" else "<pre class=\"$cssClass\">").append(escape(t)).append("</pre>")
        }
        pre(result.stdout)
        sb.append("<div class=\"fc-value\">").append(result.valueHtml).append("</div>")
        result.error?.let { pre(it, "fc-error") }
        return sb.toString()
    }

    /** A complete page around [body], styled with [theme]. */
    fun page(body: String, theme: HtmlTheme): String {
        val fg = css(theme.foreground)
        val muted = css(OutputBlock.blend(theme.foreground, theme.background, 0.45))
        val border = css(OutputBlock.blend(theme.foreground, theme.background, 0.8))
        val header = css(OutputBlock.blend(theme.foreground, theme.background, 0.92))
        val stripe = css(OutputBlock.blend(theme.foreground, theme.background, 0.96))
        val font = theme.fontFamily.replace("\\", "").replace("'", "")
        return """<!DOCTYPE html>
<html><head><meta charset="utf-8">
<style>
:root { color-scheme: ${if (theme.dark) "dark" else "light"}; }
html, body { margin: 0; padding: 0; background: ${css(theme.background)}; color: $fg; }
body { font-family: '$font', monospace; font-size: ${theme.fontSizePx}px; line-height: 1.35; overflow-y: auto; overflow-x: hidden; }
#fc-root { padding: 2px 8px 4px 8px; overflow-x: auto; }
pre { margin: 0; font: inherit; white-space: pre; }
.fc-error { color: ${css(theme.error)}; }
.fc-value { margin: 2px 0; white-space: normal; }
img { max-width: 100%; }
table { border-collapse: collapse; font: inherit; }
th, td { border: 1px solid $border; padding: 1px 6px; text-align: left; vertical-align: top; white-space: pre; }
thead th { background: $header; font-weight: 600; position: sticky; top: 0; }
.fc-table tbody tr:nth-child(even) { background: $stripe; }
.fc-props th { background: $header; font-weight: normal; }
td.fc-num, td.fc-index { text-align: right; }
td.fc-index { color: $muted; }
.fc-null, .fc-more { color: $muted; }
.fc-more { margin: 1px 0 2px 0; }
details > summary { cursor: pointer; color: $muted; }
details[open] > summary { margin-bottom: 1px; }
td details { margin: 0; }
a { color: inherit; }
html.fc-dim body { opacity: 0.45; }
</style></head>
<body><div id="fc-root">$body</div></body></html>"""
    }

    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    private fun css(c: Color) = "#%02x%02x%02x".format(c.red, c.green, c.blue)
}
