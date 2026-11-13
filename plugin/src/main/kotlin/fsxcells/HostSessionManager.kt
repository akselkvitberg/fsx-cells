package fsxcells

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Keeps one [HostSession] per .fsx file in the project, so each file has its own F# state. */
@Service(Service.Level.PROJECT)
class HostSessionManager(private val project: Project) : Disposable {
    private val sessions = ConcurrentHashMap<String, HostSession>()

    /**
     * Starts sessions and sends evaluations off the UI thread (starting one runs `dotnet --version` first), one at
     * a time, so cells run in the order they were asked for.
     */
    private val starter = AppExecutorUtil.createBoundedApplicationPoolExecutor("FSX Cells session start", 1)

    @Volatile
    private var disposed = false

    /** The setup problem last shown as a notification, so running cell after cell doesn't repeat it. */
    @Volatile
    private var lastNotified: String? = null

    /**
     * Evaluates [code] in [scriptPath]'s session, starting one if needed. Fails with a [SetupException] when the
     * project isn't trusted or dotnet or its SDK can't run cells; that is also shown once as a notification.
     */
    fun eval(scriptPath: String, code: String, startLine: Int, display: CellDisplay?): CompletableFuture<EvalResult> {
        val result = CompletableFuture<EvalResult>()
        starter.execute {
            try {
                check(!disposed) { "The project is closed." }
                // Running a cell runs the script's code: not in a project the user hasn't trusted.
                if (!TrustedProjects.isProjectTrusted(project)) throw UntrustedProjectException()
                sessionFor(scriptPath).eval(code, startLine, display).whenComplete { r, e ->
                    if (e != null) result.completeExceptionally(e) else result.complete(r)
                }
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return result.whenComplete { _, e ->
            val problem = generateSequence(e) { it.cause }.filterIsInstance<SetupException>().firstOrNull()
            if (problem == null) {
                if (e == null) lastNotified = null
            } else if (problem.message != lastNotified && !disposed) {
                lastNotified = problem.message
                SetupNotifier.notify(project, problem)
            }
        }
    }

    /** The running session for [scriptPath], starting (or replacing a dead) one if needed. */
    private fun sessionFor(scriptPath: String): HostSession =
        sessions.compute(scriptPath) { path, existing ->
            if (existing != null && existing.isAlive) existing else HostSession(path)
        }!!

    /** Stops the file's session (its state is lost); the next run starts a new one. */
    fun stop(scriptPath: String) {
        sessions.remove(scriptPath)?.dispose()
    }

    override fun dispose() {
        disposed = true
        // A session being started now is stopped too: dispose() runs after the starter task, in its queue.
        starter.execute { stopAll() }
        stopAll()
    }

    private fun stopAll() {
        sessions.values.forEach { it.dispose() }
        sessions.clear()
    }

    companion object {
        fun getInstance(project: Project): HostSessionManager = project.service()
    }
}
