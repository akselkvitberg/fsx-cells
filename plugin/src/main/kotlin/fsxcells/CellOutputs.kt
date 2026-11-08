package fsxcells

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
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Tracks the output block of each cell in an editor. A cell is identified by a range marker at the start
 * of its marker line (or offset 0 for the first cell), so the link survives edits elsewhere in the file.
 */
class CellOutputs private constructor(private val editor: Editor) {
    private class Entry(
        val anchor: RangeMarker,
        var inlay: Inlay<*>,
        var block: OutputBlock,
        val highlighters: MutableList<RangeHighlighter> = mutableListOf(),
    )

    private val entries = mutableListOf<Entry>()

    /**
     * Replaces the output block of the cell starting at [cellStartOffset]; the block goes under [lastLineEndOffset].
     * When [result] is given, its errors are underlined in the script.
     */
    fun show(cellStartOffset: Int, lastLineEndOffset: Int, output: CellOutput, result: EvalResult? = null) {
        val editorEx = editor as? EditorEx ?: return
        val existing = find(cellStartOffset)
        if (existing != null) removeHighlighters(existing)

        // Add the new block before removing the old one. Removing first makes the document briefly shorter; when the
        // editor is scrolled to the end of the file it then scrolls up, and doesn't scroll back when the new block is added.
        val block = OutputBlock(editorEx, output, ::openInTab)
        val inlay = EditorEmbeddedComponentManager.getInstance().addComponent(
            editorEx,
            block,
            EditorEmbeddedComponentManager.Properties(
                EditorEmbeddedComponentManager.ResizePolicy.none(),
                /* rendererFactory = */ null,
                /* relatesToPrecedingText = */ true,
                /* showAbove = */ false,
                /* priority = */ 0,
                /* offset = */ lastLineEndOffset,
            ),
        )
        if (existing != null) Disposer.dispose(existing.inlay)
        if (inlay == null) {
            if (existing != null) { existing.anchor.dispose(); entries.remove(existing) }
            return
        }

        val entry = existing?.also { it.inlay = inlay; it.block = block }
            ?: Entry(editor.document.createRangeMarker(cellStartOffset, cellStartOffset), inlay, block).also { entries += it }
        if (result != null) addErrorHighlights(entry, result)
    }

    /**
     * Shows that the cell starting at [cellStartOffset] is running. An existing block stays where it is (dimmed,
     * "running…"), so the editor doesn't jump; a cell without output gets a small "running…" block.
     */
    fun markRunning(cellStartOffset: Int, lastLineEndOffset: Int) {
        val existing = find(cellStartOffset)
        if (existing != null) existing.block.running = true
        else show(cellStartOffset, lastLineEndOffset, OutputFormatter.running())
    }

    /** Greys out the output of the cell containing [offset] (called after an edit). */
    fun markStaleAt(offset: Int) {
        if (entries.isEmpty()) return
        val document = editor.document
        val cells = CellParser.parse(document.charsSequence)
        val cell = CellParser.cellAt(cells, document.getLineNumber(offset.coerceIn(0, document.textLength))) ?: return
        val start = if (cell.markerLine >= 0) document.getLineStartOffset(cell.markerLine) else 0
        entries.firstOrNull { it.anchor.isValid && it.anchor.startOffset == start }?.block?.stale = true
    }

    /** The output shown for the cell starting at [cellStartOffset], if any. */
    fun outputAt(cellStartOffset: Int): CellOutput? = find(cellStartOffset)?.block?.content

    fun clearAll() {
        entries.forEach { Disposer.dispose(it.inlay); removeHighlighters(it); it.anchor.dispose() }
        entries.clear()
    }

    fun openInTab(output: CellOutput) {
        val project = editor.project ?: return
        val file = LightVirtualFile("Cell output.txt", PlainTextFileType.INSTANCE, output.fullText)
        file.isWritable = false
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun find(cellStartOffset: Int): Entry? {
        val dead = entries.filter { !it.anchor.isValid || !it.inlay.isValid }
        dead.forEach { Disposer.dispose(it.inlay); removeHighlighters(it); it.anchor.dispose() }
        entries.removeAll(dead)
        return entries.firstOrNull { it.anchor.startOffset == cellStartOffset }
    }

    private fun removeHighlighters(entry: Entry) {
        entry.highlighters.forEach { editor.markupModel.removeHighlighter(it) }
        entry.highlighters.clear()
    }

    /** Wavy underlines for compile errors in this script, and for the script line a runtime exception came from. */
    private fun addErrorHighlights(entry: Entry, result: EvalResult) {
        val scriptPath = FileDocumentManager.getInstance().getFile(editor.document)?.path ?: return
        val document = editor.document

        fun lineStart(line1: Int) = document.getLineStartOffset((line1 - 1).coerceIn(0, document.lineCount - 1))
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
        val line = frame.groupValues[2].toInt() - 1
        if (line !in 0 until document.lineCount) return
        val text = document.charsSequence.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line))
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
        val stdout = result.stdout.trimEnd('\n', '\r')
        if (stdout.isNotEmpty()) stdout.lines().forEach { lines += OutputLine(it) }
        result.valueText?.lines()?.forEach { lines += OutputLine(it) }
        result.error?.lines()?.forEach { lines += OutputLine(it, OutputLineKind.Error) }

        val fullText = lines.joinToString("\n") { it.text }
        val shown =
            if (lines.size > MAX_LINES) lines.take(MAX_LINES) + OutputLine("… ${lines.size - MAX_LINES} more lines (Open in Editor Tab to see all)", OutputLineKind.Dim)
            else lines

        val summary = buildString {
            result.typeName?.let { append(": ").append(it).append("   ") }
            append("(").append(result.elapsedMs).append(" ms)")
            if (lines.size > OutputBlock.MAX_VISIBLE_LINES) append("   ").append(lines.size).append(" lines")
        }
        return CellOutput(shown, summary, fullText)
    }

    fun running() = CellOutput(emptyList(), "running…", "")

    fun failure(message: String) = CellOutput(listOf(OutputLine(message, OutputLineKind.Error)), "", message)
}
