package fsxcells

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.CompletableFuture

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
    /**
     * Runs [cell] in the file's session and shows the result under the cell. The returned future completes on the
     * UI thread after the result is shown: true if the cell ran without errors.
     */
    fun run(project: Project, editor: Editor, file: VirtualFile, cell: Cell): CompletableFuture<Boolean> {
        val done = CompletableFuture<Boolean>()
        if (cell.isEmpty) return done.also { it.complete(true) }
        val document = editor.document
        val code = document.charsSequence
            .subSequence(document.getLineStartOffset(cell.firstLine), document.getLineEndOffset(cell.lastLine))
            .toString()

        val outputs = CellOutputs.of(editor)
        val runningBlock = outputs.markRunning(cell)
        // Follows the cell through edits made while it runs; the stamp tells whether line numbers still match.
        val key = CellKey.of(document, cell)
        val stampAtSend = document.modificationStamp

        val future = try {
            HostSessionManager.getInstance(project).sessionFor(file.path).eval(code, cell.firstLine + 1, cell.display)
        } catch (e: Exception) {
            key.dispose()
            outputs.show(cell, OutputFormatter.failure(e.message ?: e.toString()))
            return done.also { it.complete(false) }
        }

        future.whenComplete { result, error ->
            ApplicationManager.getApplication().invokeLater({
                var ok = false
                try {
                    if (editor.isDisposed) return@invokeLater
                    val current = key.resolve(document)
                    if (current == null) {
                        // The cell is gone; don't leave its old block saying "running…".
                        runningBlock?.running = false
                        return@invokeLater
                    }
                    if (error != null) {
                        outputs.show(current, OutputFormatter.failure(error.message ?: error.toString()))
                    } else {
                        ok = result.ok
                        val unchanged = document.modificationStamp == stampAtSend
                        outputs.show(current, OutputFormatter.format(result), if (unchanged) result else null)
                    }
                } finally {
                    key.dispose()
                    done.complete(ok)
                }
            }, ModalityState.any())
        }
        return done
    }

    /** Runs [count] cells from the top, one after another, stopping at the first one that fails. */
    fun runFirst(project: Project, editor: Editor, file: VirtualFile, count: Int, index: Int = 0) {
        if (index >= count || editor.isDisposed) return
        // Re-parse each step: the document may have changed while the previous cell ran.
        val cell = CellParser.parse(editor.document.charsSequence).getOrNull(index) ?: return
        run(project, editor, file, cell).thenAccept { ok ->
            if (ok) runFirst(project, editor, file, count, index + 1)
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

/** Run the cells from the top of the file up to and including the one under the caret, stopping at the first failure. */
class RunCellsUpToHereAction : FsxAction() {
    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        val cells = CellParser.parse(editor.document.charsSequence)
        val caretLine = editor.caretModel.logicalPosition.line
        val count = cells.count { (if (it.markerLine >= 0) it.markerLine else it.firstLine) <= caretLine }
        CellRunner.runFirst(project, editor, file, count)
    }
}

/**
 * Shift+Enter: run the cell under the caret, then move the caret into the next cell. On the last cell, add an empty
 * cell below instead.
 */
class RunCellAndAdvanceAction : FsxAction() {
    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        val document = editor.document
        val cells = CellParser.parse(document.charsSequence)
        val index = cells.indexOfFirst { it.contains(editor.caretModel.logicalPosition.line) }.takeIf { it >= 0 } ?: return
        val cell = cells[index]
        CellRunner.run(project, editor, file, cell)

        val next = cells.getOrNull(index + 1)
        if (next == null) {
            CellCommands(project, editor, file).addCellBelow(cell)
        } else {
            val line = next.firstLine.coerceAtMost(document.lineCount - 1)
            editor.caretModel.moveToOffset(document.getLineStartOffset(line))
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        }
    }
}

/** Ctrl+Shift+Enter: run the cell under the caret, then add an empty cell below it and put the caret there. */
class RunCellAndAddBelowAction : FsxAction() {
    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        val cells = CellParser.parse(editor.document.charsSequence)
        val cell = CellParser.cellAt(cells, editor.caretModel.logicalPosition.line) ?: return
        // Run first: the code is read before the new cell's text is inserted.
        CellRunner.run(project, editor, file, cell)
        CellCommands(project, editor, file).addCellBelow(cell)
    }
}

/** Run every cell in the file, top to bottom, stopping at the first failure. */
class RunAllCellsAction : FsxAction() {
    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        CellCommands(project, editor, file).runAll()
    }
}

/** Stop this file's F# session (losing its state) and clear its output blocks. */
class RestartSessionAction : FsxAction() {
    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        HostSessionManager.getInstance(project).stop(file.path)
        CellOutputs.of(editor).clearAll()
    }
}

/** Open the output of the cell under the caret in a read-only editor tab. */
class OpenOutputInTabAction : FsxAction() {
    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        val cells = CellParser.parse(editor.document.charsSequence)
        val cell = CellParser.cellAt(cells, editor.caretModel.logicalPosition.line) ?: return
        val outputs = CellOutputs.of(editor)
        outputs.outputAt(cell)?.let(outputs::openInTab)
    }
}

class ClearOutputsAction : FsxAction() {
    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        CellOutputs.of(editor).clearAll()
    }
}
