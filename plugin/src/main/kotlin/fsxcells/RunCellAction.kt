package fsxcells

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.awt.event.KeyEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

private fun isFsx(file: VirtualFile?) = file != null && file.extension.equals("fsx", ignoreCase = true)

/**
 * The main .fsx editor this event is for (not a diff or preview editor). For keyboard shortcuts the focus must be in
 * the editor itself: in its find/replace field the data context still has the host editor, but Enter keys belong
 * to the field. Menus, toolbars and gutter popups are not checked, their focus may be elsewhere.
 */
private fun AnActionEvent.fsxEditor(): Pair<Editor, VirtualFile>? {
    val editor = getData(CommonDataKeys.EDITOR) ?: return null
    if (editor.editorKind != EditorKind.MAIN_EDITOR) return null
    if (inputEvent is KeyEvent) {
        val focused = getData(PlatformDataKeys.CONTEXT_COMPONENT)
        if (focused != null && !SwingUtilities.isDescendingFrom(focused, editor.contentComponent)) return null
    }
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
        // Always on the UI thread (actions, toolbar links and chained runs). The host reads #load-ed files from disk,
        // so unsaved edits in them would be ignored. Saved before the code is read, in case saving reformats.
        FileDocumentManager.getInstance().saveAllDocuments()
        val document = editor.document
        val codeStart = document.getLineStartOffset(cell.firstLine)
        val code = document.charsSequence.subSequence(codeStart, document.getLineEndOffset(cell.lastLine)).toString()

        val outputs = CellOutputs.of(editor)
        var runningBlock = outputs.markRunning(cell)
        // Follows the cell through edits made while it runs. The code marker tells whether the cell's own text is
        // unchanged (edits elsewhere, like a cell added below, don't matter) and where its lines are now.
        val key = CellKey.of(document, cell)
        val codeMarker = document.createRangeMarker(codeStart, codeStart + code.length)

        // Output written while the cell runs, shown as it comes. Both this and the result are handled on the UI
        // thread in the order they arrived, so live output never replaces the result.
        var finished = false
        val written = StringBuilder()
        val updateQueued = AtomicBoolean(false)
        val onOutput: (String) -> Unit = { text ->
            synchronized(written) { written.append(text) }
            if (updateQueued.compareAndSet(false, true)) {
                ApplicationManager.getApplication().invokeLater({
                    updateQueued.set(false)
                    if (finished || editor.isDisposed) return@invokeLater
                    val current = key.resolve(document) ?: return@invokeLater
                    val soFar = synchronized(written) { written.toString() }
                    outputs.showLive(current, OutputFormatter.live(soFar))?.let { runningBlock = it }
                }, ModalityState.any())
            }
        }

        val future = HostSessionManager.getInstance(project).eval(file.path, code, cell.firstLine + 1, cell.display, onOutput)

        future.whenComplete { result, error ->
            ApplicationManager.getApplication().invokeLater({
                var ok = false
                finished = true
                try {
                    if (editor.isDisposed) return@invokeLater
                    val current = key.resolve(document)
                    if (current == null) {
                        // The cell is gone; don't leave its old block saying "running…".
                        runningBlock?.running = false
                        return@invokeLater
                    }
                    if (error != null) {
                        val cause = if (error is java.util.concurrent.CompletionException) error.cause ?: error else error
                        outputs.show(current, OutputFormatter.failure(cause.message ?: cause.toString()))
                    } else {
                        ok = result.ok
                        val unchanged = codeMarker.isValid && codeMarker.endOffset - codeMarker.startOffset == code.length &&
                            document.charsSequence.subSequence(codeMarker.startOffset, codeMarker.endOffset).toString() == code
                        val lineShift = if (unchanged) document.getLineNumber(codeMarker.startOffset) - cell.firstLine else 0
                        outputs.show(current, OutputFormatter.format(result), if (unchanged) result else null, lineShift)
                    }
                } finally {
                    key.dispose()
                    codeMarker.dispose()
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

/** Run the cell under the caret and every cell after it, stopping at the first failure. */
class RunCellsBelowAction : FsxAction() {
    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        val cells = CellParser.parse(editor.document.charsSequence)
        val from = CellNavigation.runBelowStart(cells, editor.caretModel.logicalPosition.line)
        CellRunner.runFirst(project, editor, file, cells.size, from)
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
            moveCaretToLine(editor, next.firstLine)
        }
    }
}

private fun moveCaretToLine(editor: Editor, line: Int) {
    val document = editor.document
    val target = line.coerceAtMost(document.lineCount - 1)
    editor.caretModel.moveToOffset(document.getLineStartOffset(target))
    editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
}

/** Move the caret to the first line of the next cell. */
class NextCellAction : FsxAction() {
    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        val cells = CellParser.parse(editor.document.charsSequence)
        CellNavigation.neighbourLine(cells, editor.caretModel.logicalPosition.line, true, editor.document.lineCount)
            ?.let { moveCaretToLine(editor, it) }
    }
}

/** Move the caret to the first line of the previous cell. */
class PreviousCellAction : FsxAction() {
    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        val cells = CellParser.parse(editor.document.charsSequence)
        CellNavigation.neighbourLine(cells, editor.caretModel.logicalPosition.line, false, editor.document.lineCount)
            ?.let { moveCaretToLine(editor, it) }
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

/** Stop the cell running in this file, and the ones waiting to run after it. The session keeps its values. */
class InterruptCellAction : FsxAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project ?: return
        val (_, file) = e.fsxEditor() ?: return
        // Disabled when nothing runs, so the shortcut keeps its usual meaning (Stop) then.
        e.presentation.isEnabled = HostSessionManager.getInstance(project).isBusy(file.path)
    }

    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        HostSessionManager.getInstance(project).interrupt(file.path)
    }
}

/** Stop this file's F# session (losing its state) and clear its output blocks. */
class RestartSessionAction : FsxAction() {
    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        HostSessionManager.getInstance(project).stop(file.path)
        CellOutputs.of(editor).clearAll()
    }
}

/** Restart this file's F# session, clear its output, then run every cell from the top. */
class RestartAndRunAllAction : FsxAction() {
    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        // stop() removes the old session before returning; evaluations are queued after this point and start a new one.
        CellCommands(project, editor, file).restartAndRunAll()
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
