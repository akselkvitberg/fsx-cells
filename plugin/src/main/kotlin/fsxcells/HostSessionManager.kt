package fsxcells

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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

    /**
     * Files whose session was stopped for being idle, with the idle time in minutes, so the next cell can say it ran
     * in a new session.
     */
    private val stoppedIdle = ConcurrentHashMap<String, Int>()

    /** Checks for idle sessions every minute (see [stopIdleSessions]). */
    private val idleCheck: ScheduledFuture<*> = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
        { if (!disposed) starter.execute { stopIdleSessions(FsxCellsSettings.getInstance().idleTimeoutMinutes) } },
        IDLE_CHECK_SECONDS, IDLE_CHECK_SECONDS, TimeUnit.SECONDS,
    )

    /** The setup problem last shown as a notification, so running cell after cell doesn't repeat it. */
    @Volatile
    private var lastNotified: String? = null

    /**
     * Evaluates [code] in [scriptPath]'s session, starting one if needed. Fails with a [SetupException] when the
     * project isn't trusted or dotnet or its SDK can't run cells; that is also shown once as a notification.
     */
    fun eval(
        scriptPath: String,
        code: String,
        startLine: Int,
        display: CellDisplay?,
        onOutput: ((String) -> Unit)? = null,
    ): CompletableFuture<EvalResult> {
        val result = CompletableFuture<EvalResult>()
        starter.execute {
            try {
                check(!disposed) { "The project is closed." }
                // Running a cell runs the script's code: not in a project the user hasn't trusted.
                if (!TrustedProjects.isProjectTrusted(project)) throw UntrustedProjectException()
                val (session, notice) = sessionFor(scriptPath)
                session.eval(code, startLine, display, onOutput).whenComplete { r, e ->
                    if (e != null) result.completeExceptionally(e)
                    else result.complete(if (notice != null) r.copy(notice = notice) else r)
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

    /**
     * The running session for [scriptPath], starting (or replacing a dead) one if needed, and a notice for the cell
     * when the new session replaces one that was stopped for being idle.
     */
    private fun sessionFor(scriptPath: String): Pair<HostSession, String?> {
        var started = false
        val session = sessions.compute(scriptPath) { path, existing ->
            if (existing != null && existing.isAlive) existing else HostSession(path).also { started = true }
        }!!
        val idleMinutes = if (started) stoppedIdle.remove(scriptPath) else null
        return session to idleMinutes?.let(::idleRestartNotice)
    }

    /**
     * Stops the sessions that haven't run a cell for [timeoutMinutes] (0: never), to free their memory. A session
     * running a cell, or with cells waiting to run, is never stopped. Runs in [starter]'s queue, so no cell is
     * being sent to a session meanwhile.
     */
    private fun stopIdleSessions(timeoutMinutes: Int) {
        if (disposed) return
        val now = System.nanoTime()
        for ((path, session) in sessions) {
            if (!isIdleTooLong(session.idleNanos(now), timeoutMinutes)) continue
            if (sessions.remove(path, session)) {
                log.info("Stopping the F# session of $path: unused for $timeoutMinutes minutes")
                stoppedIdle[path] = timeoutMinutes
                session.dispose()
            }
        }
    }

    /** True while a cell of [scriptPath] runs (or waits to run) in its session. */
    fun isBusy(scriptPath: String): Boolean = sessions[scriptPath]?.isBusy == true

    /**
     * Stops the running cell of [scriptPath] and the ones waiting to run. If a cell doesn't stop (it is stuck in
     * native code, say), the session is restarted after [STUCK_SECONDS], losing its values.
     */
    fun interrupt(scriptPath: String) {
        val session = sessions[scriptPath] ?: return
        val waiting = session.interrupt()
        if (waiting.isEmpty()) return
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (session.isWaitingFor(waiting) && sessions.remove(scriptPath, session)) {
                session.dispose("The cell didn't stop when interrupted, so its F# session was restarted: values from earlier cells are gone.")
            }
        }, STUCK_SECONDS, TimeUnit.SECONDS)
    }

    /** Stops the file's session (its state is lost); the next run starts a new one. */
    fun stop(scriptPath: String) {
        stoppedIdle.remove(scriptPath)
        sessions.remove(scriptPath)?.dispose()
    }

    override fun dispose() {
        disposed = true
        idleCheck.cancel(false)
        // A session being started now is stopped too: dispose() runs after the starter task, in its queue.
        starter.execute { stopAll() }
        stopAll()
    }

    private fun stopAll() {
        sessions.values.forEach { it.dispose() }
        sessions.clear()
    }

    companion object {
        /** How long an interrupted cell may take to stop before its session is restarted. */
        private const val STUCK_SECONDS = 5L

        /** How often sessions are checked for being idle. */
        private const val IDLE_CHECK_SECONDS = 60L

        private val log = logger<HostSessionManager>()

        /** True if a session unused for [idleNanos] should be stopped, with a timeout of [timeoutMinutes] (0: never). */
        fun isIdleTooLong(idleNanos: Long, timeoutMinutes: Int): Boolean =
            timeoutMinutes > 0 && idleNanos >= TimeUnit.MINUTES.toNanos(timeoutMinutes.toLong())

        /** Shown above the output of the first cell run in a session that replaces one stopped for being idle. */
        fun idleRestartNotice(minutes: Int): String =
            "New F# session: the previous one was stopped after $minutes minute${if (minutes == 1) "" else "s"} " +
                "without use, so values from earlier cells are gone.\n" +
                "(Change when unused sessions are stopped in Settings | Tools | FSX Cells.)"

        fun getInstance(project: Project): HostSessionManager = project.service()
    }
}
