package fsxcells

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseWheelEvent
import java.awt.datatransfer.StringSelection
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTextPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.border.MatteBorder
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/** How a single output line is drawn. */
enum class OutputLineKind { Normal, Dim, Error }

data class OutputLine(val text: String, val kind: OutputLineKind = OutputLineKind.Normal)

/**
 * What a cell's output block shows: the body lines, a summary line under them, and the full text for copying.
 * [html] is the body as HTML when the result has tables or objects; it is shown instead of [lines] when JCEF works.
 */
data class CellOutput(val lines: List<OutputLine>, val summary: String, val fullText: String, val html: String? = null)

/**
 * The output block under a cell: a real Swing component embedded in the editor, so text can be selected and
 * copied. It is at most [MAX_VISIBLE_LINES] lines high and scrolls beyond that; the summary line stays visible.
 * Output with HTML is shown in an embedded browser instead of the text pane, up to [MAX_HTML_LINES] lines high.
 *
 * While its cell runs, the block says so (with the time so far and a Stop link calling [onStop]); output the cell
 * writes meanwhile is shown with [showLive].
 */
class OutputBlock(
    private val editor: EditorEx,
    content: CellOutput,
    private val onOpenInTab: (CellOutput) -> Unit,
    private val onStop: (() -> Unit)? = null,
) : JPanel(BorderLayout()), Disposable {

    var content: CellOutput = content
        private set

    /** The inlay holding this block; set by CellOutputs so the block can ask it to re-measure. */
    var inlay: Inlay<*>? = null

    private val textPane = object : JTextPane() {
        // Don't wrap: wide grids get a horizontal scrollbar instead.
        override fun getScrollableTracksViewportWidth(): Boolean =
            parent == null || parent.width >= ui.getPreferredSize(this).width
    }
    private val scrollPane = JBScrollPane(textPane)
    private val summaryLabel = JBLabel()
    private val stopLink = ActionLink("Stop") { onStop?.invoke() }.apply {
        toolTipText = "Interrupt the running cell"
        isFocusable = false
    }
    private val footer = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))

    /** Updates the "running… 12 s" summary while the cell runs. */
    private val clock = Timer(1000) { render(); remeasure() }
    private var runningSince = 0L

    /** True once the cell has been edited after it ran: everything is drawn dimmed. */
    var stale: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                render()
            }
        }

    /**
     * True while the cell is being run again: the old output stays (dimmed) so the editor doesn't jump, until the
     * cell writes something ([showLive]).
     */
    var running: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    runningSince = System.currentTimeMillis()
                    clock.start()
                } else {
                    clock.stop()
                }
                render()
                remeasure()
            }
        }

    /** True when the block shows what the running cell has written so far, rather than an earlier result. */
    private var live = false

    private val dimmed get() = stale || (running && !live)

    private val scheme get() = editor.colorsScheme
    private val blockBackground: Color get() = blend(scheme.defaultBackground, scheme.defaultForeground, 0.05)

    private val htmlView: HtmlView? =
        content.html?.takeIf { HtmlSupport.isSupported }?.let { body ->
            HtmlView(HtmlPage.page(body, htmlTheme()), ::onHtmlHeight, ::scrollEditor)
                .also { Disposer.register(this, it) }
        }

    /** Height of the HTML page's content as the browser reports it; a guess from the text until then. */
    private var htmlHeight: Int = -1

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
        stopLink.font = JBUI.Fonts.smallFont()
        stopLink.border = JBUI.Borders.empty(1, 0, 2, 8)
        footer.isOpaque = false
        footer.add(summaryLabel)
        footer.add(stopLink)

        if (htmlView != null) add(htmlView.component, BorderLayout.CENTER)
        else if (content.lines.isNotEmpty()) add(scrollPane, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
        render()
    }

    override fun dispose() {
        clock.stop()
    }

    /** False for a block showing HTML: live output then needs a new block. */
    val canShowLive: Boolean get() = htmlView == null

    /**
     * Shows [output], written by the running cell so far, instead of the block's content, scrolled to its end.
     * Only for blocks that [canShowLive].
     */
    fun showLive(output: CellOutput) {
        check(canShowLive) { "An HTML block can't show live output." }
        val hadLines = content.lines.isNotEmpty()
        content = output
        live = true
        measuredTextWidth = -1
        if (!running) running = true
        if (output.lines.isNotEmpty() != hadLines) {
            if (hadLines) remove(scrollPane) else add(scrollPane, BorderLayout.CENTER)
        }
        render()
        remeasure()
    }

    /** Asks the editor to lay the block out again after its preferred size changed. */
    private fun remeasure() {
        revalidate()
        inlay?.update()
    }

    private fun htmlTheme() = HtmlTheme(
        background = blockBackground,
        foreground = scheme.defaultForeground,
        error = ERROR,
        fontFamily = scheme.editorFontName,
        fontSizePx = scheme.editorFontSize,
        dark = ColorUtil.isDark(scheme.defaultBackground),
    )

    private fun onHtmlHeight(height: Int) {
        if (height == htmlHeight) return
        htmlHeight = height
        revalidate()
        inlay?.update()
        repaint()
    }

    private fun scrollEditor(deltaPx: Double) {
        val scrolling = editor.scrollingModel
        scrolling.scrollVertically(scrolling.verticalScrollOffset + deltaPx.toInt())
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
        // Live output follows its end, like a terminal.
        textPane.caretPosition = if (live) doc.length else 0
        htmlView?.setDimmed(dimmed)

        summaryLabel.text = if (running) runningText() else content.summary
        summaryLabel.foreground = if (running) blend(scheme.defaultForeground, scheme.defaultBackground, 0.4)
                                  else color(OutputLineKind.Dim)
        stopLink.isVisible = running && onStop != null
        repaint()
    }

    private fun runningText(): String {
        val seconds = (System.currentTimeMillis() - runningSince) / 1000
        return if (seconds < 1) "running…   " else "running… ${seconds} s   "
    }

    private var measuredTextWidth = -1

    /** Width of the widest line. Measured once per content: layout asks for it often. */
    private val textWidth: Int
        get() {
            if (measuredTextWidth < 0) {
                val metrics = textPane.getFontMetrics(textPane.font)
                measuredTextWidth = content.lines.maxOfOrNull { metrics.stringWidth(it.text) } ?: 0
            }
            return measuredTextWidth
        }

    override fun getPreferredSize(): Dimension {
        val summaryHeight = footer.preferredSize.height
        // Computed from font metrics, not from the text pane's own preferred size: that one is wrong until the pane
        // has been laid out, and the editor measures the block right when it's added. A wrong first size makes the
        // block shrink and grow again, which scrolls the editor.
        val metrics = textPane.getFontMetrics(textPane.font)
        val textInsets = textPane.insets
        val lineHeight = metrics.height
        val contentWidth = textWidth + textInsets.left + textInsets.right + JBUI.scale(6)
        val summaryWidth = footer.preferredSize.width
        val maxWidth = (editor.scrollingModel.visibleArea.width - JBUI.scale(40)).coerceAtLeast(JBUI.scale(300))

        if (htmlView != null) {
            // Until the browser reports its height, guess from the text lines so the editor jumps less.
            val guess = minOf(content.lines.size, MAX_VISIBLE_LINES) * lineHeight + textInsets.top + textInsets.bottom
            val pageHeight = if (htmlHeight >= 0) htmlHeight else guess
            val height = minOf(pageHeight, MAX_HTML_LINES * lineHeight).coerceAtLeast(lineHeight)
            return Dimension(maxWidth + JBUI.scale(3), height + summaryHeight)
        }

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
        /** HTML output (tables) gets a little more room before it scrolls. */
        const val MAX_HTML_LINES = 25
        private val ACCENT = JBColor(Color(0x4A90D9), Color(0x5C9DE0))
        private val ERROR = JBColor(Color(0xC62828), Color(0xFF6B68))

        fun blend(a: Color, b: Color, t: Double): Color = Color(
            (a.red + (b.red - a.red) * t).toInt(),
            (a.green + (b.green - a.green) * t).toInt(),
            (a.blue + (b.blue - a.blue) * t).toInt(),
        )
    }
}
