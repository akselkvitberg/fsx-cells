package fsxcells

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
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
)

/** One `FsxCells.Host` process, bound to one .fsx file. */
class HostSession(private val scriptPath: String) {
    private val log = logger<HostSession>()
    private val gson = Gson()
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<EvalResult>>()

    private val process: Process
    private val stdin: OutputStreamWriter

    /**
     * Requests waiting to be written to the host. A writer thread drains it: the host only reads stdin between
     * evaluations, so writing from the caller (the UI thread) would block it once the pipe buffer is full.
     */
    private val outbox = LinkedBlockingQueue<Pair<Int?, String>>()

    init {
        val command = hostCommand()
        log.info("Starting fsx-cells host: ${command.joinToString(" ")} for $scriptPath")
        process = ProcessBuilder(command)
            .directory(File(scriptPath).parentFile)
            .start()
        stdin = OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8)

        daemon("fsx-cells host stdin") { writeRequests() }
        daemon("fsx-cells host stdout") { readResponses() }
        daemon("fsx-cells host stderr") {
            BufferedReader(InputStreamReader(process.errorStream, StandardCharsets.UTF_8))
                .forEachLine { log.info("host: $it") }
        }
        process.onExit().thenRun { failPending("The F# host process exited (code ${process.exitValue()}).") }

        send(null, JsonObject().apply {
            addProperty("type", "init")
            addProperty("scriptPath", scriptPath)
        })
    }

    val isAlive: Boolean get() = process.isAlive

    /** Evaluates [code]; [startLine] is the 1-based script line of the first line of code. */
    fun eval(code: String, startLine: Int): CompletableFuture<EvalResult> {
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<EvalResult>()
        pending[id] = future
        send(id, JsonObject().apply {
            addProperty("type", "eval")
            addProperty("id", id)
            addProperty("code", code)
            addProperty("startLine", startLine)
        })
        return future
    }

    fun dispose() {
        outbox.put(STOP)
        failPending("The F# session was stopped.")
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
                val future = pending.remove(idElement.asInt) ?: return@forEachLine
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
                    )
                )
            } catch (e: Exception) {
                log.warn("Could not read host response: $line", e)
            }
        }
    }

    private fun failPending(message: String) {
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
        const val PLUGIN_ID = "fsxcells"
        const val HOST_ENV = "FSXCELLS_HOST"
        private val STOP: Pair<Int?, String> = Pair(null, "")

        /** `dotnet <FsxCells.Host.dll>`. The DLL is bundled in the plugin; FSXCELLS_HOST overrides it for development. */
        fun hostCommand(): List<String> {
            val override = System.getenv(HOST_ENV)?.takeIf { it.isNotBlank() }
            val dll = override?.let { File(it) }
                ?: PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath
                    ?.resolve("host")?.resolve("FsxCells.Host.dll")?.toFile()
                ?: error("Cannot locate the fsx-cells plugin folder.")
            if (!dll.exists()) error("F# host not found at $dll. Set $HOST_ENV to the path of FsxCells.Host.dll.")
            return listOf("dotnet", dll.absolutePath)
        }
    }
}
