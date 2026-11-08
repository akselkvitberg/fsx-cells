package fsxcells

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

private fun isFsx(file: VirtualFile?) = file != null && file.extension.equals("fsx", ignoreCase = true)

private fun AnActionEvent.fsxEditor(): Pair<Editor, VirtualFile>? {
    val editor = getData(CommonDataKeys.EDITOR) ?: return null
    val file = getData(CommonDataKeys.VIRTUAL_FILE) ?: FileDocumentManager.getInstance().getFile(editor.document)
    return if (file != null && isFsx(file)) editor to file else null
}

/** Base for actions that only apply in .fsx editors. Elsewhere they are disabled, so their keys keep their normal meaning. */
abstract class FsxAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.fsxEditor() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (editor, file) = e.fsxEditor() ?: return
        perform(project, editor, file)
    }

    abstract fun perform(project: Project, editor: Editor, file: VirtualFile)
}

object CellRunner {
    private fun startOffset(editor: Editor, cell: Cell) =
        if (cell.markerLine >= 0) editor.document.getLineStartOffset(cell.markerLine) else 0

    /** Runs [cell] in the file's session and shows the result under the cell. */
    fun run(project: Project, editor: Editor, file: VirtualFile, cell: Cell) {
        if (cell.isEmpty) return
        val document = editor.document
        val cellStart = startOffset(editor, cell)
        val lastLineEnd = document.getLineEndOffset(cell.lastLine)
        val code = document.charsSequence.subSequence(document.getLineStartOffset(cell.firstLine), lastLineEnd).toString()

        val outputs = CellOutputs.of(editor)
        outputs.show(cellStart, lastLineEnd, OutputFormatter.running())

        // Track the cell's start through edits made while the code runs.
        val anchor = document.createRangeMarker(cellStart, cellStart)

        val future = try {
            HostSessionManager.getInstance(project).sessionFor(file.path).eval(code, cell.firstLine + 1)
        } catch (e: Exception) {
            anchor.dispose()
            outputs.show(cellStart, lastLineEnd, listOf(OutputLine(e.message ?: e.toString(), OutputLineKind.Error)))
            return
        }

        future.whenComplete { result, error ->
            ApplicationManager.getApplication().invokeLater({
                try {
                    if (editor.isDisposed || !anchor.isValid) return@invokeLater
                    val cells = CellParser.parse(document.charsSequence)
                    val current = CellParser.cellAt(cells, document.getLineNumber(anchor.startOffset)) ?: return@invokeLater
                    val endLine = if (current.isEmpty) maxOf(current.markerLine, 0) else current.lastLine
                    val lines =
                        if (error != null) listOf(OutputLine(error.message ?: error.toString(), OutputLineKind.Error))
                        else OutputFormatter.format(result)
                    outputs.show(startOffset(editor, current), document.getLineEndOffset(endLine), lines)
                } finally {
                    anchor.dispose()
                }
            }, ModalityState.any())
        }
    }
}

/** Ctrl+Enter: run the cell under the caret. */
class RunCellAction : FsxAction() {
    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        val cells = CellParser.parse(editor.document.charsSequence)
        val cell = CellParser.cellAt(cells, editor.caretModel.logicalPosition.line) ?: return
        CellRunner.run(project, editor, file, cell)
    }
}

/** Run every cell from the top of the file up to and including the one under the caret. */
class RunCellsUpToHereAction : FsxAction() {
    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        val cells = CellParser.parse(editor.document.charsSequence)
        val caretLine = editor.caretModel.logicalPosition.line
        cells.filter { (if (it.markerLine >= 0) it.markerLine else it.firstLine) <= caretLine }
            .forEach { CellRunner.run(project, editor, file, it) }
    }
}

/** Stop this file's F# session (losing its state) and clear its output blocks. */
class RestartSessionAction : FsxAction() {
    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        HostSessionManager.getInstance(project).restart(file.path)
        CellOutputs.of(editor).clearAll()
    }
}

class ClearOutputsAction : FsxAction() {
    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        CellOutputs.of(editor).clearAll()
    }
}
