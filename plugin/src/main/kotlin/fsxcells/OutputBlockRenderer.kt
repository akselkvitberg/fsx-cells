package fsxcells

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D

/** How a single output line is drawn. */
enum class OutputLineKind { Normal, Dim, Error }

data class OutputLine(val text: String, val kind: OutputLineKind = OutputLineKind.Normal)

/**
 * Draws a multi-line output block as a block inlay under a line of code.
 * Height is (number of lines × editor line height) plus padding, so the code below is pushed down.
 */
class OutputBlockRenderer(lines: List<OutputLine>) : EditorCustomElementRenderer {
    private val lines: List<OutputLine> = lines.map { it.copy(text = it.text.replace("\t", "    ")) }

    private fun font(editor: Editor): Font = editor.colorsScheme.getFont(EditorFontType.PLAIN)

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val metrics = editor.contentComponent.getFontMetrics(font(editor))
        val textWidth = lines.maxOfOrNull { metrics.stringWidth(it.text) } ?: 0
        return LEFT_INSET + textWidth + PADDING * 2
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int =
        inlay.editor.lineHeight * lines.size.coerceAtLeast(1) + PADDING * 2

    override fun paint(inlay: Inlay<*>, g: Graphics2D, targetRegion: Rectangle2D, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val scheme = editor.colorsScheme
        val font = font(editor)
        val metrics = editor.contentComponent.getFontMetrics(font)
        val lineHeight = editor.lineHeight

        val x = targetRegion.x.toInt()
        val y = targetRegion.y.toInt()
        val width = targetRegion.width.toInt()
        val height = targetRegion.height.toInt()

        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            // Slightly tinted background and an accent bar on the left, so the block reads as output, not code.
            g2.color = blend(scheme.defaultBackground, scheme.defaultForeground, 0.05)
            g2.fillRect(x, y + 1, width, height - 2)
            g2.color = ACCENT
            g2.fillRect(x, y + 1, 3, height - 2)

            g2.font = font
            val textX = x + LEFT_INSET + PADDING
            val baselineOffset = (lineHeight - metrics.height) / 2 + metrics.ascent
            lines.forEachIndexed { i, line ->
                g2.color = when (line.kind) {
                    OutputLineKind.Normal -> scheme.defaultForeground
                    OutputLineKind.Dim -> blend(scheme.defaultForeground, scheme.defaultBackground, 0.5)
                    OutputLineKind.Error -> ERROR
                }
                g2.drawString(line.text, textX, y + PADDING + i * lineHeight + baselineOffset)
            }
        } finally {
            g2.dispose()
        }
    }

    companion object {
        private const val PADDING = 4
        private const val LEFT_INSET = 8
        private val ACCENT = JBColor(Color(0x4A90D9), Color(0x5C9DE0))
        private val ERROR = JBColor(Color(0xC62828), Color(0xFF6B68))

        private fun blend(a: Color, b: Color, t: Double): Color = Color(
            (a.red + (b.red - a.red) * t).toInt(),
            (a.green + (b.green - a.green) * t).toInt(),
            (a.blue + (b.blue - a.blue) * t).toInt(),
        )
    }
}
