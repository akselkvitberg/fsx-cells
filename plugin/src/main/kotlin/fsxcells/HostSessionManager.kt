package fsxcells

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/** Keeps one [HostSession] per .fsx file in the project, so each file has its own F# state. */
@Service(Service.Level.PROJECT)
class HostSessionManager : Disposable {
    private val sessions = ConcurrentHashMap<String, HostSession>()

    /** The running session for [scriptPath], starting (or replacing a dead) one if needed. */
    fun sessionFor(scriptPath: String): HostSession =
        sessions.compute(scriptPath) { path, existing ->
            if (existing != null && existing.isAlive) existing else HostSession(path)
        }!!

    /** Stops the file's session (its state is lost); the next run starts a new one. */
    fun stop(scriptPath: String) {
        sessions.remove(scriptPath)?.dispose()
    }

    override fun dispose() {
        sessions.values.forEach { it.dispose() }
        sessions.clear()
    }

    companion object {
        fun getInstance(project: Project): HostSessionManager = project.service()
    }
}
