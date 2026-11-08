package fsxcells

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.SeparatorPlacement
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.Alarm
import java.awt.Color
import javax.swing.Icon

/**
 * Per-editor cell UI: separator lines above markers, a run icon (with a right-click menu) in the gutter, a toolbar
 * of links above each marker, an "Add cell below" link under the last line, and greying out output after edits.
 */
class CellDecorations(
    private val editor: Editor,
    project: Project,
    file: VirtualFile,
    parent: Disposable,
) {
    private val commands = CellCommands(project, editor, file)
    private val highlighters = mutableListOf<RangeHighlighter>()
    private val toolbars = mutableListOf<Inlay<*>>()
    private var addBelowBar: Inlay<*>? = null
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)
    private val document get() = editor.document

    init {
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                CellOutputs.of(editor).markStaleAt(event.offset)
                alarm.cancelAllRequests()
                alarm.addRequest(::refresh, 250)
            }
        }, parent)
        Disposer.register(parent) {
            toolbars.forEach(Disposer::dispose)
            addBelowBar?.let(Disposer::dispose)
        }
        refresh()
    }

    /** The cell containing [line], parsed now (the document may have changed since the UI was built). */
    private fun cellAtLine(line: Int): Cell? = CellParser.cellAt(CellParser.parse(document.charsSequence), line)

    fun refresh() {
        if (editor.isDisposed) return
        val cells = CellParser.parse(document.charsSequence)

        highlighters.forEach { editor.markupModel.removeHighlighter(it) }
        highlighters.clear()
        for (cell in cells) {
            if (cell.markerLine >= 0) {
                val separator = editor.markupModel.addLineHighlighter(cell.markerLine, HighlighterLayer.ADDITIONAL_SYNTAX, null)
                separator.lineSeparatorColor = SEPARATOR
                separator.lineSeparatorPlacement = SeparatorPlacement.TOP
                highlighters += separator
            }
            if (cell.isEmpty) continue
            // The marker line, or the first line for the first cell. Rider's own run-script icon on line 1 sits in
            // the left slot and ours in the right one, so they can share the line.
            val iconLine = if (cell.markerLine >= 0) cell.markerLine else cell.firstLine
            val icon = editor.markupModel.addLineHighlighter(iconLine, HighlighterLayer.ADDITIONAL_SYNTAX, null)
            icon.gutterIconRenderer = RunCellGutterIcon(icon)
            highlighters += icon
        }

        refreshToolbars(cells)
        refreshAddBelow()
    }

    // ---- Toolbars above markers -------------------------------------------------------------------------------

    /** Keeps one toolbar per marker line. Existing ones are reused (they move with the text), so typing doesn't flicker. */
    private fun refreshToolbars(cells: List<Cell>) {
        val wanted = cells.filter { it.markerLine >= 0 }.map { document.getLineStartOffset(it.markerLine) }.toMutableSet()
        // The first cell has no marker line; its toolbar goes above the top of the file.
        if (cells.firstOrNull()?.markerLine == -1) wanted += 0
        val iterator = toolbars.iterator()
        while (iterator.hasNext()) {
            val inlay = iterator.next()
            if (inlay.isValid && wanted.remove(inlay.offset)) continue
            Disposer.dispose(inlay)
            iterator.remove()
        }
        for (offset in wanted) addToolbar(offset)?.let { toolbars += it }
    }

    private fun addToolbar(lineStartOffset: Int): Inlay<*>? {
        val editorEx = editor as? EditorEx ?: return null
        var inlay: Inlay<*>? = null
        // Resolve the cell when clicked: the toolbar moves with its marker line.
        fun cell(): Cell? = inlay?.takeIf { it.isValid }?.let { cellAtLine(document.getLineNumber(it.offset)) }
        val bar = CellToolbar(editor, listOf(
            CellToolbar.Item("Run cell", CellIcons.RunCell, "Run this cell (Ctrl+Enter)") { cell()?.let(commands::run) },
            CellToolbar.Item("Run up to here", null, "Run all cells from the top to this one") { cell()?.let(commands::runUpTo) },
            CellToolbar.Item("Add cell above", AllIcons.General.Add, "Insert an empty cell above this one") { cell()?.let(commands::addCellAbove) },
        ))
        inlay = EditorEmbeddedComponentManager.getInstance().addComponent(
            editorEx, bar,
            EditorEmbeddedComponentManager.Properties(
                EditorEmbeddedComponentManager.ResizePolicy.none(), null,
                /* relatesToPrecedingText = */ false, /* showAbove = */ true, /* priority = */ 0, lineStartOffset,
            ),
        )
        return inlay
    }

    /** "Add cell below" under the last line of the file. */
    private fun refreshAddBelow() {
        val end = document.textLength
        val current = addBelowBar
        if (current != null && current.isValid && current.offset == end) return
        current?.let(Disposer::dispose)
        val editorEx = editor as? EditorEx ?: return
        val bar = CellToolbar(editor, listOf(
            CellToolbar.Item("Add cell below", AllIcons.General.Add, "Add an empty cell at the end of the file") {
                commands.addCellBelow(CellParser.parse(document.charsSequence).lastOrNull())
            },
        ))
        addBelowBar = EditorEmbeddedComponentManager.getInstance().addComponent(
            editorEx, bar,
            EditorEmbeddedComponentManager.Properties(
                EditorEmbeddedComponentManager.ResizePolicy.none(), null,
                /* relatesToPrecedingText = */ false, /* showAbove = */ false, /* priority = */ -100, end,
            ),
        )
    }

    // ---- Gutter icon --------------------------------------------------------------------------------------------

    /** Run icon in the gutter: click runs the cell, right-click shows the cell menu. */
    private inner class RunCellGutterIcon(private val highlighter: RangeHighlighter) : GutterIconRenderer() {
        private fun cell(): Cell? =
            if (highlighter.isValid) cellAtLine(document.getLineNumber(highlighter.startOffset)) else null

        override fun getIcon(): Icon = CellIcons.RunCell
        override fun getTooltipText() = "Run cell (Ctrl+Enter). Right-click for more."
        override fun isNavigateAction() = true
        // Rider draws its intention bulb over the left icon slot of the caret line; stay out of its way.
        override fun getAlignment() = Alignment.RIGHT

        override fun getClickAction(): AnAction = action("Run Cell") { cell()?.let(commands::run) }

        override fun getPopupMenuActions(): ActionGroup = DefaultActionGroup(
            action("Run Cell", CellIcons.RunCell) { cell()?.let(commands::run) },
            action("Run Cells Up to Here") { cell()?.let(commands::runUpTo) },
            action("Run All Cells") { commands.runAll() },
            Separator.getInstance(),
            action("Add Cell Above", AllIcons.General.Add) { cell()?.let(commands::addCellAbove) },
            action("Add Cell Below", AllIcons.General.Add) { commands.addCellBelow(cell()) },
            Separator.getInstance(),
            action("Open Output in Tab") {
                cell()?.let { c -> CellOutputs.of(editor).let { o -> o.outputAt(c)?.let(o::openInTab) } }
            },
        )

        override fun equals(other: Any?) = other is RunCellGutterIcon && other.highlighter == highlighter
        override fun hashCode() = highlighter.hashCode()
    }

    companion object {
        private val SEPARATOR = JBColor(Color(0xC8CDD6), Color(0x43454A))

        private fun action(text: String, icon: Icon? = null, perform: () -> Unit): AnAction =
            object : DumbAwareAction(text, null, icon) {
                override fun actionPerformed(e: AnActionEvent) = perform()
            }
    }
}

/** Attaches [CellDecorations] to every .fsx editor, and stops a file's session when its last editor closes. */
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
        val editor = event.editor
        val disposable = editor.getUserData(DISPOSABLE) ?: return
        Disposer.dispose(disposable)
        editor.putUserData(DISPOSABLE, null)

        // Last editor for this file closed: stop its F# session so it doesn't hold memory until the project closes.
        val project = editor.project ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val others = EditorFactory.getInstance().getEditors(editor.document, project).any { it !== editor }
        if (!others && !project.isDisposed) HostSessionManager.getInstance(project).stop(file.path)
    }

    companion object {
        private val DISPOSABLE = Key.create<Disposable>("fsxcells.editorDisposable")
    }
}
