package fsxcells

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.SeparatorPlacement
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.Alarm
import java.awt.Color
import javax.swing.Icon

/** Separator lines above cell markers, ▶ run icons in the gutter, and greying out output after edits. */
class CellDecorations(
    private val editor: Editor,
    private val project: Project,
    private val file: VirtualFile,
    parent: Disposable,
) {
    private val highlighters = mutableListOf<RangeHighlighter>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)

    init {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                CellOutputs.of(editor).markStaleAt(event.offset)
                alarm.cancelAllRequests()
                alarm.addRequest(::refresh, 250)
            }
        }, parent)
        refresh()
    }

    fun refresh() {
        if (editor.isDisposed) return
        highlighters.forEach { editor.markupModel.removeHighlighter(it) }
        highlighters.clear()

        val document = editor.document
        for (cell in CellParser.parse(document.charsSequence)) {
            if (cell.markerLine >= 0) {
                val separator = editor.markupModel.addLineHighlighter(cell.markerLine, HighlighterLayer.ADDITIONAL_SYNTAX, null)
                separator.lineSeparatorColor = SEPARATOR
                separator.lineSeparatorPlacement = SeparatorPlacement.TOP
                highlighters += separator
            }
            if (cell.isEmpty) continue
            val iconLine = iconLine(cell) ?: continue
            val icon = editor.markupModel.addLineHighlighter(iconLine, HighlighterLayer.ADDITIONAL_SYNTAX, null)
            icon.gutterIconRenderer = RunCellGutterIcon(icon)
            highlighters += icon
        }
    }

    /**
     * The line for a cell's ▶: its marker line, or its first line for the first cell. Rider puts its own
     * "run whole script" icon on line 0 of .fsx files, so ours moves to the next non-blank line of the cell.
     */
    private fun iconLine(cell: Cell): Int? {
        val preferred = if (cell.markerLine >= 0) cell.markerLine else cell.firstLine
        if (preferred != 0) return preferred
        val document = editor.document
        return (1..cell.lastLine).firstOrNull { line ->
            document.charsSequence.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).isNotBlank()
        }
    }

    /** ▶ in the gutter; runs the cell at the highlighter's current line. */
    private inner class RunCellGutterIcon(private val highlighter: RangeHighlighter) : GutterIconRenderer() {
        override fun getIcon(): Icon = AllIcons.Actions.Execute
        override fun getTooltipText() = "Run cell (Ctrl+Enter)"
        override fun isNavigateAction() = true
        override fun getAlignment() = Alignment.LEFT

        override fun getClickAction() = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (!highlighter.isValid) return
                val document = editor.document
                val line = document.getLineNumber(highlighter.startOffset)
                val cell = CellParser.cellAt(CellParser.parse(document.charsSequence), line) ?: return
                CellRunner.run(project, editor, file, cell)
            }
        }

        override fun equals(other: Any?) = other is RunCellGutterIcon && other.highlighter == highlighter
        override fun hashCode() = highlighter.hashCode()
    }

    companion object {
        private val SEPARATOR = JBColor(Color(0xC8CDD6), Color(0x43454A))
    }
}

/** Attaches [CellDecorations] to every .fsx editor. */
class FsxEditorListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (!file.extension.equals("fsx", ignoreCase = true)) return

        val disposable = Disposer.newDisposable("fsx-cells editor")
        Disposer.register(project, disposable)
        editor.putUserData(DISPOSABLE, disposable)
        CellDecorations(editor, project, file, disposable)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        event.editor.getUserData(DISPOSABLE)?.let(Disposer::dispose)
        event.editor.putUserData(DISPOSABLE, null)
    }

    companion object {
        private val DISPOSABLE = Key.create<Disposable>("fsxcells.editorDisposable")
    }
}
