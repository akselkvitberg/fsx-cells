package fsxcells

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Identifies a cell across edits. A marker cell is tracked by a range marker over its whole marker line: inserting
 * text before the line (even right at its start) moves the range with it. The first cell has no marker line; it is
 * always "the text before the first marker".
 */
class CellKey private constructor(private val marker: RangeMarker?) {
    val isFirstCell: Boolean get() = marker == null
    val isValid: Boolean get() = marker == null || marker.isValid

    /** The cell this key refers to now, or null if its marker line is gone. */
    fun resolve(document: Document): Cell? = resolveIn(CellParser.parse(document.charsSequence), document)

    /** Like [resolve], in already parsed [cells]. */
    fun resolveIn(cells: List<Cell>, document: Document): Cell? {
        if (marker == null) return cells.firstOrNull { it.markerLine < 0 }
        if (!marker.isValid) return null
        val line = document.getLineNumber(marker.startOffset)
        return cells.firstOrNull { it.markerLine == line }
    }

    fun matches(document: Document, cell: Cell): Boolean =
        if (cell.markerLine < 0) marker == null
        else marker != null && marker.isValid && marker.startOffset == document.getLineStartOffset(cell.markerLine)

    fun dispose() {
        marker?.dispose()
    }

    companion object {
        fun of(document: Document, cell: Cell): CellKey =
            if (cell.markerLine < 0) CellKey(null)
            else CellKey(document.createRangeMarker(document.getLineStartOffset(cell.markerLine), document.getLineEndOffset(cell.markerLine)))
    }
}

/** Tracks the output block of each cell in an editor. */
class CellOutputs private constructor(private val editor: Editor) {
    private class Entry(
        val key: CellKey,
        var inlay: Inlay<*>,
        var block: OutputBlock,
        val highlighters: MutableList<RangeHighlighter> = mutableListOf(),
    )

    private val entries = mutableListOf<Entry>()
    private val document get() = editor.document

    /**
     * Replaces the output block of [cell]. When [result] is given, its errors are underlined in the script
     * (callers pass it only if the cell's text hasn't changed since it was sent; [lineShift] is how many lines the
     * cell has moved since, so the result's line numbers still match).
     */
    fun show(cell: Cell, output: CellOutput, result: EvalResult? = null, lineShift: Int = 0) {
        val editorEx = editor as? EditorEx ?: return
        val existing = find(cell)
        if (existing != null) removeHighlighters(existing)

        // Add the new block before removing the old one. Removing first makes the document briefly shorter; when the
        // editor is scrolled to the end of the file it then scrolls up, and doesn't scroll back when the new block is added.
        val block = OutputBlock(editorEx, output, ::openInTab, ::interrupt)
        val inlay = EditorEmbeddedComponentManager.getInstance().addComponent(
            editorEx,
            block,
            EditorEmbeddedComponentManager.Properties(
                EditorEmbeddedComponentManager.ResizePolicy.none(),
                /* rendererFactory = */ null,
                /* relatesToPrecedingText = */ true,
                /* showAbove = */ false,
                /* priority = */ 0,
                /* offset = */ blockOffset(cell),
            ),
        )
        if (existing != null) Disposer.dispose(existing.inlay)
        if (inlay == null) {
            Disposer.dispose(block)
            if (existing != null) { existing.key.dispose(); entries.remove(existing) }
            return
        }
        // The block (and its browser, for HTML output) goes away with the inlay.
        Disposer.register(inlay, block)
        block.inlay = inlay

        val entry = existing?.also { it.inlay = inlay; it.block = block }
            ?: Entry(CellKey.of(document, cell), inlay, block).also { entries += it }
        if (result != null) addErrorHighlights(entry, result, lineShift)
    }

    /**
     * Shows that [cell] is running and returns its block. An existing block stays where it is (dimmed, "running…"),
     * so the editor doesn't jump; a cell without output gets a small "running…" block.
     */
    fun markRunning(cell: Cell): OutputBlock? {
        find(cell)?.let { it.block.running = true; return it.block }
        show(cell, OutputFormatter.running())
        return find(cell)?.block?.also { it.running = true }
    }

    /**
     * Shows what the running [cell] has written so far. Its block is updated in place, or replaced when it shows
     * HTML. Returns the block.
     */
    fun showLive(cell: Cell, output: CellOutput): OutputBlock? {
        val existing = find(cell)
        if (existing != null && existing.block.canShowLive) {
            existing.block.showLive(output)
            return existing.block
        }
        show(cell, output)
        return find(cell)?.block?.also { it.showLive(output) }
    }

    /** Stops the cell running in this file (the Stop link of a running block). */
    private fun interrupt() {
        val project = editor.project ?: return
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        HostSessionManager.getInstance(project).interrupt(file.path)
    }

    /**
     * Moves blocks that are no longer at the end of their cell (after a marker was inserted above the block's line,
     * or lines were added at the end of the cell). [cells] is the current parse of the document. Blocks of running
     * cells stay: their result is shown at the right place when it arrives.
     */
    fun realign(cells: List<Cell>) {
        for (entry in entries.toList()) {
            if (!entry.key.isValid || !entry.inlay.isValid || entry.block.running) continue
            val cell = entry.key.resolveIn(cells, document) ?: continue
            if (entry.inlay.offset == blockOffset(cell)) continue
            val stale = entry.block.stale
            show(cell, entry.block.content)
            find(cell)?.block?.stale = stale
        }
    }

    /** Greys out the output of the cell containing [offset] (called after an edit). */
    fun markStaleAt(offset: Int) {
        if (entries.isEmpty()) return
        // Walk back to the cell's marker line instead of parsing the whole document on every keystroke.
        var line = document.getLineNumber(offset.coerceIn(0, document.textLength))
        while (line >= 0 && !CellParser.isMarker(lineText(line).toString())) line--
        val entry =
            if (line < 0) entries.firstOrNull { it.key.isFirstCell }
            else entries.firstOrNull { !it.key.isFirstCell && it.key.matches(document, Cell(line, line + 1, line, null)) }
        entry?.block?.stale = true
    }

    /** The output shown for [cell], if any. */
    fun outputAt(cell: Cell): CellOutput? = find(cell)?.block?.content

    fun clearAll() {
        entries.forEach { Disposer.dispose(it.inlay); removeHighlighters(it); it.key.dispose() }
        entries.clear()
    }

    fun openInTab(output: CellOutput) {
        val project = editor.project ?: return
        val file = LightVirtualFile("Cell output.txt", PlainTextFileType.INSTANCE, output.fullText)
        file.isWritable = false
        OpenFileDescriptor(project, file).navigate(true)
    }

    /** The block goes under the cell's last line (or its marker line, for an empty cell). */
    private fun blockOffset(cell: Cell): Int {
        val line = if (cell.isEmpty) maxOf(cell.markerLine, 0) else cell.lastLine
        return document.getLineEndOffset(line.coerceAtMost(document.lineCount - 1))
    }

    private fun lineText(line: Int): CharSequence =
        document.charsSequence.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line))

    private fun find(cell: Cell): Entry? {
        val dead = entries.filter { !it.key.isValid || !it.inlay.isValid }
        dead.forEach { Disposer.dispose(it.inlay); removeHighlighters(it); it.key.dispose() }
        entries.removeAll(dead)
        return entries.firstOrNull { it.key.matches(document, cell) }
    }

    private fun removeHighlighters(entry: Entry) {
        entry.highlighters.forEach { editor.markupModel.removeHighlighter(it) }
        entry.highlighters.clear()
    }

    /** Wavy underlines for compile errors in this script, and for the script line a runtime exception came from. */
    private fun addErrorHighlights(entry: Entry, result: EvalResult, lineShift: Int) {
        val scriptPath = FileDocumentManager.getInstance().getFile(document)?.path ?: return

        fun lineStart(line1: Int) = document.getLineStartOffset((line1 - 1 + lineShift).coerceIn(0, document.lineCount - 1))
        fun add(start: Int, end: Int, message: String, color: Color) {
            val s = start.coerceIn(0, document.textLength)
            val e = end.coerceIn(s, document.textLength)
            val attrs = TextAttributes().apply {
                effectType = EffectType.WAVE_UNDERSCORE
                effectColor = color
            }
            val h = editor.markupModel.addRangeHighlighter(
                s, if (e == s) minOf(s + 1, document.textLength) else e,
                HighlighterLayer.ERROR + 1, attrs, HighlighterTargetArea.EXACT_RANGE,
            )
            h.errorStripeTooltip = message
            h.setErrorStripeMarkColor(color)
            entry.highlighters += h
        }

        for (d in result.diagnostics) {
            if (!samePath(d.fileName, scriptPath)) continue
            val color = when (d.severity) {
                "error" -> ERROR
                "warning" -> WARNING
                else -> continue
            }
            add(lineStart(d.startLine) + d.startCol, lineStart(d.endLine) + d.endCol, d.message, color)
        }

        // Runtime exception: underline the first stack frame line that is in this script.
        val error = result.error ?: return
        if (result.diagnostics.any { it.severity == "error" }) return
        val frame = STACK_LINE.findAll(error).firstOrNull { samePath(it.groupValues[1], scriptPath) } ?: return
        val line = frame.groupValues[2].toInt() - 1 + lineShift
        if (line !in 0 until document.lineCount) return
        val text = lineText(line)
        val indent = text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        add(document.getLineStartOffset(line) + indent, document.getLineEndOffset(line), error.lineSequence().first(), ERROR)
    }

    companion object {
        private val KEY = Key.create<CellOutputs>("fsxcells.cellOutputs")
        private val ERROR = JBColor(Color(0xC62828), Color(0xFF6B68))
        private val WARNING = JBColor(Color(0xB58900), Color(0xD9A343))
        private val STACK_LINE = Regex("""in (.+?):line (\d+)""")

        private fun samePath(a: String, b: String) = a.replace('\\', '/').equals(b.replace('\\', '/'), ignoreCase = true)

        fun of(editor: Editor): CellOutputs =
            editor.getUserData(KEY) ?: CellOutputs(editor).also { editor.putUserData(KEY, it) }
    }
}

/** Turns a host result into what the output block shows: output, value and errors, then a summary line. */
object OutputFormatter {
    /** Lines kept in the block itself; the full text is still available via Copy All / Open in Editor Tab. */
    const val MAX_LINES = 5000

    fun format(result: EvalResult): CellOutput {
        val lines = mutableListOf<OutputLine>()
        result.notice?.lines()?.forEach { lines += OutputLine(it, OutputLineKind.Dim) }
        val stdout = result.stdout.trimEnd('\n', '\r')
        if (stdout.isNotEmpty()) stdout.lines().forEach { lines += OutputLine(it) }
        result.valueText?.lines()?.forEach { lines += OutputLine(it) }
        result.error?.lines()?.forEach { lines += OutputLine(it, OutputLineKind.Error) }
        for (d in result.diagnostics) {
            if (d.severity == "warning") lines += OutputLine("(${d.startLine},${d.startCol + 1}): ${d.message}", OutputLineKind.Dim)
        }

        val fullText = lines.joinToString("\n") { it.text }
        val html = HtmlPage.body(result)
        val shown =
            if (lines.size > MAX_LINES) lines.take(MAX_LINES) + OutputLine("… ${lines.size - MAX_LINES} more lines (Open in Editor Tab to see all)", OutputLineKind.Dim)
            else lines

        val summary = buildString {
            result.typeName?.let { append(": ").append(it).append("   ") }
            if (result.interrupted) append("(interrupted after ").append(result.elapsedMs).append(" ms)")
            else append("(").append(result.elapsedMs).append(" ms)")
            if (lines.size > OutputBlock.MAX_VISIBLE_LINES) append("   ").append(lines.size).append(" lines")
        }
        return CellOutput(shown, summary, fullText, html)
    }

    fun running() = CellOutput(emptyList(), "running…", "")

    /** Lines (and characters) shown while a cell runs; the result shows them all. */
    const val MAX_LIVE_LINES = 1000
    private const val MAX_LIVE_CHARS = 200_000

    /** What a running cell has written so far: the end of it, so a chatty cell stays cheap to show. */
    fun live(stdout: String): CellOutput {
        val tail = if (stdout.length <= MAX_LIVE_CHARS) stdout
                   else stdout.substring(stdout.length - MAX_LIVE_CHARS).substringAfter('\n')
        val all = tail.trimEnd('\n', '\r').lines()
        val lines =
            if (all.size > MAX_LIVE_LINES || tail.length < stdout.length)
                listOf(OutputLine("… earlier lines are shown when the cell finishes", OutputLineKind.Dim)) +
                    all.takeLast(MAX_LIVE_LINES).map { OutputLine(it) }
            else all.map { OutputLine(it) }
        return CellOutput(lines, "running…", stdout)
    }

    fun failure(message: String) = CellOutput(listOf(OutputLine(message, OutputLineKind.Error)), "", message)
}
