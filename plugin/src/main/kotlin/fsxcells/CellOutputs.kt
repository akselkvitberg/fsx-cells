package fsxcells

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key

/**
 * Tracks the output block of each cell in an editor. A cell is identified by a range marker at the start
 * of its marker line (or offset 0 for the first cell), so the link survives edits elsewhere in the file.
 */
class CellOutputs private constructor(private val editor: Editor) {
    private class Entry(val anchor: RangeMarker, var inlay: Inlay<*>)

    private val entries = mutableListOf<Entry>()

    /** Replaces the output block of the cell starting at [cellStartOffset]; the block goes under [lastLineEndOffset]. */
    fun show(cellStartOffset: Int, lastLineEndOffset: Int, lines: List<OutputLine>) {
        val entry = find(cellStartOffset)
        if (entry != null) Disposer.dispose(entry.inlay)

        val inlay = editor.inlayModel.addBlockElement(
            lastLineEndOffset,
            /* relatesToPrecedingText = */ true,
            /* showAbove = */ false,
            /* priority = */ 0,
            OutputBlockRenderer(lines),
        )
        if (inlay == null) {
            if (entry != null) { entry.anchor.dispose(); entries.remove(entry) }
            return
        }

        if (entry != null) {
            entry.inlay = inlay
        } else {
            entries += Entry(editor.document.createRangeMarker(cellStartOffset, cellStartOffset), inlay)
        }
    }

    fun clearAll() {
        entries.forEach { Disposer.dispose(it.inlay); it.anchor.dispose() }
        entries.clear()
    }

    private fun find(cellStartOffset: Int): Entry? {
        val dead = entries.filter { !it.anchor.isValid || !it.inlay.isValid }
        dead.forEach { Disposer.dispose(it.inlay); it.anchor.dispose() }
        entries.removeAll(dead)
        return entries.firstOrNull { it.anchor.startOffset == cellStartOffset }
    }

    companion object {
        private val KEY = Key.create<CellOutputs>("fsxcells.cellOutputs")

        fun of(editor: Editor): CellOutputs =
            editor.getUserData(KEY) ?: CellOutputs(editor).also { editor.putUserData(KEY, it) }
    }
}

/** Turns a host result into display lines: output, value, errors in red, then a dim summary line. */
object OutputFormatter {
    const val MAX_LINES = 40

    fun format(result: EvalResult): List<OutputLine> {
        val lines = mutableListOf<OutputLine>()
        val stdout = result.stdout.trimEnd('\n', '\r')
        if (stdout.isNotEmpty()) stdout.lines().forEach { lines += OutputLine(it) }
        result.valueText?.lines()?.forEach { lines += OutputLine(it) }
        result.error?.lines()?.forEach { lines += OutputLine(it, OutputLineKind.Error) }

        val capped =
            if (lines.size > MAX_LINES) lines.take(MAX_LINES) + OutputLine("… ${lines.size - MAX_LINES} more lines", OutputLineKind.Dim)
            else lines

        val summary = buildString {
            result.typeName?.let { append(": ").append(it).append("   ") }
            append("(").append(result.elapsedMs).append(" ms)")
        }
        return capped + OutputLine(summary, OutputLineKind.Dim)
    }

    fun running(): List<OutputLine> = listOf(OutputLine("running…", OutputLineKind.Dim))
}
