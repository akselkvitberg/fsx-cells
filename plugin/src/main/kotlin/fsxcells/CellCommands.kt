package fsxcells

import com.intellij.icons.AllIcons
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

object CellIcons {
    /** Folder-with-run icon: reads as "run a part", distinct from Rider's run-whole-script icon on line 1. */
    @JvmField
    val RunCell: Icon = AllIcons.RunConfigurations.Compound
}

/** Cell operations shared by the gutter menu, the cell toolbars and the editor actions. */
class CellCommands(private val project: Project, private val editor: Editor, private val file: VirtualFile) {
    private val document get() = editor.document
    private fun cells() = CellParser.parse(document.charsSequence)

    fun run(cell: Cell) {
        CellRunner.run(project, editor, file, cell)
    }

    /** Cells from the top up to and including [cell], one after another, stopping at the first failure. */
    fun runUpTo(cell: Cell) {
        val index = cells().indexOfFirst { it.markerLine == cell.markerLine }
        if (index >= 0) CellRunner.runFirst(project, editor, file, index + 1)
    }

    /** [cell] and every cell after it, one after another, stopping at the first failure. */
    fun runFrom(cell: Cell) {
        val all = cells()
        val index = all.indexOfFirst { it.markerLine == cell.markerLine }
        if (index >= 0) CellRunner.runFirst(project, editor, file, all.size, index)
    }

    fun runAll() {
        CellRunner.runFirst(project, editor, file, cells().size)
    }

    /** Stops the file's session, clears its output, then runs every cell. */
    fun restartAndRunAll() {
        HostSessionManager.getInstance(project).stop(file.path)
        CellOutputs.of(editor).clearAll()
        runAll()
    }

    /** Inserts a new empty cell before [cell] and puts the caret in it. */
    fun addCellAbove(cell: Cell) {
        val marker = markerStyle(cell)
        if (cell.markerLine < 0) {
            // The first cell has no marker line: give it one, so it stays a cell of its own below the new one.
            insert(0, "$marker\n\n$marker\n", caretLine = 1)
        } else {
            insert(document.getLineStartOffset(cell.markerLine), "$marker\n\n", caretLine = cell.markerLine + 1)
        }
    }

    /** Inserts a new empty cell after [cell] (before the next marker, or at the end of the file). */
    fun addCellBelow(cell: Cell?) {
        val all = cells()
        val marker = markerStyle(cell ?: all.lastOrNull())
        val next = cell?.let { c -> all.firstOrNull { it.markerLine > maxOf(c.markerLine, c.lastLine) } }
        if (next != null) {
            insert(document.getLineStartOffset(next.markerLine), "$marker\n\n", caretLine = next.markerLine + 1)
        } else {
            val text = document.charsSequence
            val prefix = when {
                text.isEmpty() -> ""
                text.endsWith("\n\n") -> ""
                text.endsWith("\n") -> "\n"
                else -> "\n\n"
            }
            val insertAt = document.textLength
            val markerLine = document.getLineNumber(insertAt) + prefix.count { it == '\n' }
            insert(insertAt, "$prefix$marker\n", caretLine = markerLine + 1)
        }
    }

    /** Uses the same marker style as [cell] (or the file's first marker), defaulting to `// %%`. */
    private fun markerStyle(cell: Cell?): String {
        val line = cell?.markerLine?.takeIf { it >= 0 } ?: cells().firstOrNull { it.markerLine >= 0 }?.markerLine
        val text = line?.let { document.charsSequence.subSequence(document.getLineStartOffset(it), document.getLineEndOffset(it)).toString() }
        return if (text != null && text.trimStart().startsWith("(*")) "(* cell *)" else "// %%"
    }

    private fun insert(offset: Int, text: String, caretLine: Int) {
        WriteCommandAction.runWriteCommandAction(project, "Add Cell", null, {
            document.insertString(offset, text)
        })
        val line = caretLine.coerceAtMost(document.lineCount - 1)
        editor.caretModel.moveToOffset(document.getLineStartOffset(line))
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        editor.contentComponent.requestFocusInWindow()
    }
}
