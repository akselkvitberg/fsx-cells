package fsxcells

import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.event.MouseWheelEvent
import java.awt.datatransfer.StringSelection
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTextPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.border.MatteBorder
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/** How a single output line is drawn. */
enum class OutputLineKind { Normal, Dim, Error }

data class OutputLine(val text: String, val kind: OutputLineKind = OutputLineKind.Normal)

/** What a cell's output block shows: the body lines, a summary line under them, and the full text for copying. */
data class CellOutput(val lines: List<OutputLine>, val summary: String, val fullText: String)

/**
 * The output block under a cell: a real Swing component embedded in the editor, so text can be selected and
 * copied. It is at most [MAX_VISIBLE_LINES] lines high and scrolls beyond that; the summary line stays visible.
 */
class OutputBlock(
    private val editor: EditorEx,
    val content: CellOutput,
    private val onOpenInTab: (CellOutput) -> Unit,
) : JPanel(BorderLayout()) {

    private val textPane = object : JTextPane() {
        // Don't wrap: wide grids get a horizontal scrollbar instead.
        override fun getScrollableTracksViewportWidth(): Boolean =
            parent == null || parent.width >= ui.getPreferredSize(this).width
    }
    private val scrollPane = JBScrollPane(textPane)
    private val summaryLabel = JBLabel()

    /** True once the cell has been edited after it ran: everything is drawn dimmed. */
    var stale: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                render()
            }
        }

    /** True while the cell is being run again: the old output stays (dimmed) so the editor doesn't jump. */
    var running: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                render()
            }
        }

    private val dimmed get() = stale || running

    private val scheme get() = editor.colorsScheme
    private val blockBackground: Color get() = blend(scheme.defaultBackground, scheme.defaultForeground, 0.05)

    init {
        val font = scheme.getFont(EditorFontType.PLAIN)
        textPane.isEditable = false
        textPane.font = font
        textPane.border = JBUI.Borders.empty(2, 8, 2, 8)
        textPane.componentPopupMenu = popupMenu()

        scrollPane.border = JBUI.Borders.empty()
        scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        // We handle the wheel ourselves so it falls through to the editor at the top/bottom of the output.
        scrollPane.isWheelScrollingEnabled = false
        scrollPane.addMouseWheelListener(::onMouseWheel)

        summaryLabel.font = font
        summaryLabel.border = JBUI.Borders.empty(1, 8, 2, 8)
        summaryLabel.componentPopupMenu = textPane.componentPopupMenu

        if (content.lines.isNotEmpty()) add(scrollPane, BorderLayout.CENTER)
        add(summaryLabel, BorderLayout.SOUTH)
        render()
    }

    private fun color(kind: OutputLineKind): Color {
        val base = when (kind) {
            OutputLineKind.Normal -> scheme.defaultForeground
            OutputLineKind.Dim -> blend(scheme.defaultForeground, scheme.defaultBackground, 0.5)
            OutputLineKind.Error -> ERROR
        }
        return if (dimmed) blend(base, scheme.defaultBackground, 0.55) else base
    }

    private fun render() {
        val bg = blockBackground
        isOpaque = true
        setBackground(bg)
        textPane.background = bg
        scrollPane.viewport.background = bg
        border = MatteBorder(0, 3, 0, 0, if (dimmed) blend(ACCENT, scheme.defaultBackground, 0.6) else ACCENT)

        val doc = textPane.styledDocument
        doc.remove(0, doc.length)
        content.lines.forEachIndexed { i, line ->
            val attrs = SimpleAttributeSet().also { StyleConstants.setForeground(it, color(line.kind)) }
            doc.insertString(doc.length, if (i == 0) line.text else "\n" + line.text, attrs)
        }
        textPane.caretPosition = 0

        summaryLabel.text = if (running) "running…" else content.summary
        summaryLabel.foreground = if (running) blend(scheme.defaultForeground, scheme.defaultBackground, 0.4)
                                  else color(OutputLineKind.Dim)
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val summaryHeight = summaryLabel.preferredSize.height
        // Computed from font metrics, not from the text pane's own preferred size: that one is wrong until the pane
        // has been laid out, and the editor measures the block right when it's added. A wrong first size makes the
        // block shrink and grow again, which scrolls the editor.
        val metrics = textPane.getFontMetrics(textPane.font)
        val textInsets = textPane.insets
        val lineHeight = metrics.height
        val contentWidth = (content.lines.maxOfOrNull { metrics.stringWidth(it.text) } ?: 0) +
            textInsets.left + textInsets.right + JBUI.scale(6)
        val summaryWidth = summaryLabel.preferredSize.width
        val maxWidth = (editor.scrollingModel.visibleArea.width - JBUI.scale(40)).coerceAtLeast(JBUI.scale(300))

        if (content.lines.isEmpty()) {
            return Dimension(summaryWidth + JBUI.scale(3), summaryHeight)
        }

        val needsVertical = content.lines.size > MAX_VISIBLE_LINES
        val vBar = if (needsVertical) scrollPane.verticalScrollBar.preferredSize.width else 0
        val width = minOf(maxOf(contentWidth + vBar, summaryWidth), maxWidth)
        val needsHorizontal = contentWidth + vBar > width
        val hBar = if (needsHorizontal) scrollPane.horizontalScrollBar.preferredSize.height else 0

        val visibleLines = minOf(content.lines.size, MAX_VISIBLE_LINES)
        val height = visibleLines * lineHeight + textInsets.top + textInsets.bottom + hBar + summaryHeight
        return Dimension(width + JBUI.scale(3), height)
    }

    private fun onMouseWheel(e: MouseWheelEvent) {
        val horizontal = e.isShiftDown
        val bar = if (horizontal) scrollPane.horizontalScrollBar else scrollPane.verticalScrollBar
        val delta = (e.preciseWheelRotation * textPane.getFontMetrics(textPane.font).height * 3).toInt()
        val canScroll = bar.isVisible && delta != 0 &&
            ((delta < 0 && bar.value > bar.minimum) || (delta > 0 && bar.value + bar.visibleAmount < bar.maximum))
        if (canScroll) {
            bar.value += delta
            e.consume()
        } else {
            // Let the editor scroll.
            val target = editor.scrollPane
            target.dispatchEvent(SwingUtilities.convertMouseEvent(e.component, e, target))
        }
    }

    private fun popupMenu() = JPopupMenu().apply {
        add(JMenuItem("Copy").apply {
            addActionListener {
                val text = textPane.selectedText?.takeIf { it.isNotEmpty() } ?: content.fullText
                CopyPasteManager.getInstance().setContents(StringSelection(text))
            }
        })
        add(JMenuItem("Copy All").apply {
            addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(content.fullText)) }
        })
        add(JMenuItem("Open in Editor Tab").apply {
            addActionListener { onOpenInTab(content) }
        })
    }

    companion object {
        const val MAX_VISIBLE_LINES = 20
        private val ACCENT = JBColor(Color(0x4A90D9), Color(0x5C9DE0))
        private val ERROR = JBColor(Color(0xC62828), Color(0xFF6B68))

        fun blend(a: Color, b: Color, t: Double): Color = Color(
            (a.red + (b.red - a.red) * t).toInt(),
            (a.green + (b.green - a.green) * t).toInt(),
            (a.blue + (b.blue - a.blue) * t).toInt(),
        )
    }
}
