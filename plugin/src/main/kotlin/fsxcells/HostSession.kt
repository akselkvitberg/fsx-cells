package fsxcells

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/** A compiler diagnostic. Lines are 1-based script lines, columns 0-based (see host/README.md). */
data class Diagnostic(
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
    val severity: String,
    val message: String,
    val fileName: String,
)

/** Result of one cell evaluation, as sent by the host (see host/README.md). */
data class EvalResult(
    val ok: Boolean,
    val stdout: String,
    val valueText: String?,
    val typeName: String?,
    val error: String?,
    val elapsedMs: Long,
    val diagnostics: List<Diagnostic> = emptyList(),
    /** The value as HTML, when it has a table or object layout. */
    val valueHtml: String? = null,
    /** True when the cell was stopped by [HostSession.interrupt]. */
    val interrupted: Boolean = false,
    /** A note from the plugin shown above the output, e.g. that the cell ran in a new session. */
    val notice: String? = null,
)

/** One `FsxCells.Host` process, bound to one .fsx file. */
class HostSession private constructor(private val scriptPath: String, launch: () -> Pair<List<String>, Map<String, String>>) {
    /**
     * Finds dotnet and checks its SDK before starting the host; throws [DotnetSetupException] if it can't run cells.
     * Starts processes: don't create one on the UI thread.
     */
    constructor(scriptPath: String) : this(scriptPath, {
        val dotnet = Dotnet.resolve(File(scriptPath).absoluteFile.parentFile)
        listOf(dotnet.path, hostDll().absolutePath) to Dotnet.environment(dotnet, Dotnet.environment())
    })

    /** Runs the host with [command] and [environment] as they are (for tests). */
    internal constructor(scriptPath: String, command: List<String>, environment: Map<String, String>) :
        this(scriptPath, { command to environment })

    private val log = logger<HostSession>()
    private val gson = Gson()
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<EvalResult>>()

    /** Called with console output of a running evaluation, by id (on the stdout reader thread). */
    private val outputListeners = ConcurrentHashMap<Int, (String) -> Unit>()

    private val process: Process
    private val stdin: OutputStreamWriter

    /**
     * Requests waiting to be written to the host. A writer thread drains it, so the caller (often the UI thread)
     * never blocks on a full pipe.
     */
    private val outbox = LinkedBlockingQueue<Pair<Int?, String>>()

    /** [System.nanoTime] of the last eval sent or answered, for [idleNanos]. */
    @Volatile
    private var lastActive = System.nanoTime()

    /** The host's last stderr lines, shown when it exits unexpectedly (an unhandled exception's message and stack). */
    private val stderrTail = ArrayDeque<String>()

    init {
        val scriptDir = File(scriptPath).absoluteFile.parentFile
        val (command, environment) = launch()
        log.info("Starting fsx-cells host: ${command.joinToString(" ")} for $scriptPath")
        process = ProcessBuilder(command)
            .directory(scriptDir)
            .apply { environment().putAll(environment) }
            .start()
        stdin = OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8)

        daemon("fsx-cells host stdin") { writeRequests() }
        daemon("fsx-cells host stdout") { readResponses() }
        daemon("fsx-cells host stderr") {
            BufferedReader(InputStreamReader(process.errorStream, StandardCharsets.UTF_8))
                .forEachLine { line ->
                    log.info("host: $line")
                    synchronized(stderrTail) {
                        stderrTail.addLast(line)
                        while (stderrTail.size > STDERR_TAIL_LINES) stderrTail.removeFirst()
                    }
                }
        }
        process.onExit().thenRun {
            val message = exitMessage()
            if (process.exitValue() == HOST_SDK_EXIT_CODE) {
                // The host couldn't load the SDK's F# compiler (see Program.fs): a setup problem, not a crash.
                failPending(DotnetSetupException("Can't load the .NET SDK's F# compiler", message))
            } else {
                failPending(message)
            }
        }

        send(null, JsonObject().apply {
            addProperty("type", "init")
            addProperty("scriptPath", scriptPath)
        })
    }

    val isAlive: Boolean get() = process.isAlive

    /**
     * Evaluates [code]; [startLine] is the 1-based script line of the first line of code. [display] asks the host to
     * show the value as an image or HTML. [onOutput] gets what the code writes to the console while it runs, from a
     * background thread; the result's stdout has all of it, and [onOutput] isn't called after the result.
     */
    fun eval(
        code: String,
        startLine: Int,
        display: CellDisplay? = null,
        onOutput: ((String) -> Unit)? = null,
    ): CompletableFuture<EvalResult> {
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<EvalResult>()
        if (onOutput != null) outputListeners[id] = onOutput
        pending[id] = future
        lastActive = System.nanoTime()
        send(id, JsonObject().apply {
            addProperty("type", "eval")
            addProperty("id", id)
            addProperty("code", code)
            addProperty("startLine", startLine)
            if (display != null) addProperty("display", display.wireName)
        })
        return future
    }

    /**
     * Asks the host to stop the running evaluation and the queued ones; they answer with an interrupted result.
     * Returns the evaluations that were waiting for an answer, for [isWaitingFor].
     */
    fun interrupt(): Set<Int> {
        val waiting = pending.keys.toSet()
        if (waiting.isNotEmpty()) send(null, JsonObject().apply { addProperty("type", "interrupt") })
        return waiting
    }

    /** True if any of [ids] has no answer yet. */
    fun isWaitingFor(ids: Set<Int>): Boolean = ids.any(pending::containsKey)

    /** True while an evaluation has no answer yet. */
    val isBusy: Boolean get() = pending.isNotEmpty()

    /**
     * How long the session has been unused at [now] (a [System.nanoTime] value): the time since the last cell was
     * sent or answered. 0 while a cell runs or waits to run.
     */
    fun idleNanos(now: Long = System.nanoTime()): Long {
        // isBusy first: an answer updates lastActive before it leaves [pending], so once nothing is pending,
        // lastActive is at least as new as the last answer.
        if (isBusy) return 0
        return (now - lastActive).coerceAtLeast(0)
    }

    /** Stops the host; evaluations still waiting get [message] as their error. */
    fun dispose(message: String = "The F# session was stopped.") {
        outbox.put(STOP)
        failPending(message)
        process.toHandle().descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    /** Queues a request; never blocks. [id] is the eval id to fail if the write fails. */
    private fun send(id: Int?, json: JsonObject) {
        outbox.put(id to gson.toJson(json))
    }

    private fun writeRequests() {
        while (true) {
            val item = try { outbox.take() } catch (_: InterruptedException) { return }
            if (item === STOP) return
            val (id, line) = item
            try {
                stdin.write(line)
                stdin.write("\n")
                stdin.flush()
            } catch (e: Exception) {
                if (id != null) pending.remove(id)?.completeExceptionally(e)
                if (!process.isAlive) return
            }
        }
    }

    private fun readResponses() {
        BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).forEachLine { line ->
            try {
                val obj = JsonParser.parseString(line).asJsonObject
                val idElement = obj.get("id")
                if (idElement == null || idElement.isJsonNull) {
                    log.warn("Host message without id: $line")
                    return@forEachLine
                }
                val id = idElement.asInt
                if (obj.string("type") == "output") {
                    outputListeners[id]?.invoke(obj.string("text") ?: "")
                    return@forEachLine
                }
                outputListeners.remove(id)
                // Before the eval leaves [pending], so the session never looks idle since an earlier eval.
                lastActive = System.nanoTime()
                val future = pending.remove(id) ?: return@forEachLine
                future.complete(
                    EvalResult(
                        ok = obj.get("ok")?.asBoolean ?: false,
                        stdout = obj.string("stdout") ?: "",
                        valueText = obj.string("valueText"),
                        typeName = obj.string("typeName"),
                        error = obj.string("error"),
                        elapsedMs = obj.get("elapsedMs")?.asLong ?: 0,
                        diagnostics = obj.getAsJsonArray("diagnostics")?.map { el ->
                            val d = el.asJsonObject
                            Diagnostic(
                                startLine = d.get("startLine").asInt,
                                startCol = d.get("startCol").asInt,
                                endLine = d.get("endLine").asInt,
                                endCol = d.get("endCol").asInt,
                                severity = d.string("severity") ?: "error",
                                message = d.string("message") ?: "",
                                fileName = d.string("fileName") ?: "",
                            )
                        } ?: emptyList(),
                        valueHtml = obj.string("valueHtml"),
                        interrupted = obj.get("interrupted")?.asBoolean ?: false,
                    )
                )
            } catch (e: Exception) {
                log.warn("Could not read host response: $line", e)
            }
        }
    }

    /** "The F# host process exited (code N)." plus what it last wrote to stderr, if anything. */
    private fun exitMessage(): String {
        // Give the stderr reader thread a moment to read what the process wrote just before it exited.
        Thread.sleep(200)
        val tail = synchronized(stderrTail) { stderrTail.toList() }
        val head = "The F# host process exited (code ${process.exitValue()})."
        return if (tail.isEmpty()) head else head + "\n" + tail.joinToString("\n")
    }

    private fun failPending(error: Throwable) {
        outputListeners.clear()
        pending.keys.toList().forEach { id -> pending.remove(id)?.completeExceptionally(error) }
    }

    private fun failPending(message: String) {
        outputListeners.clear()
        val result = EvalResult(false, "", null, null, message, 0)
        pending.keys.toList().forEach { id -> pending.remove(id)?.complete(result) }
    }

    private fun JsonObject.string(name: String): String? {
        val el = get(name) ?: return null
        return if (el.isJsonNull) null else el.asString
    }

    private fun daemon(name: String, body: () -> Unit) {
        val t = Thread(body, name)
        t.isDaemon = true
        t.start()
    }

    companion object {
        const val HOST_ENV = "FSXCELLS_HOST"
        private val STOP: Pair<Int?, String> = Pair(null, "")
        private const val STDERR_TAIL_LINES = 30

        /** The host's exit code when the SDK's F# compiler can't be loaded (see host Program.fs). */
        private const val HOST_SDK_EXIT_CODE = 2

        /**
         * FsxCells.Host.dll, bundled in the plugin under `<plugin>/host`; FSXCELLS_HOST overrides it for development.
         * The plugin folder is found from this class's jar, which is in `<plugin>/lib`: the plugin manager's lookup by id
         * is internal API.
         */
        fun hostDll(): File {
            val override = System.getenv(HOST_ENV)?.takeIf { it.isNotBlank() }
            val dll = override?.let { File(it) }
                ?: PathManager.getJarPathForClass(HostSession::class.java)?.let { File(it).absoluteFile.parentFile?.parentFile }
                    ?.resolve("host")?.resolve("FsxCells.Host.dll")
                ?: error("Cannot locate the fsx-cells plugin folder.")
            if (!dll.exists()) error("F# host not found at $dll. Set $HOST_ENV to the path of FsxCells.Host.dll.")
            return dll
        }
    }
}
