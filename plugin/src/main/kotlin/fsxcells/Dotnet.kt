package fsxcells

import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Cells can't run until the user changes something. [title] is a short summary for a notification; the message says
 * what was found and what to do.
 */
open class SetupException(val title: String, message: String) : Exception(message)

/** The machine isn't set up to run cells: no `dotnet`, no .NET SDK, or one that's too old. */
class DotnetSetupException(title: String, message: String) : SetupException(title, message)

/** The project isn't trusted, so its scripts aren't run. */
class UntrustedProjectException : SetupException(
    "The project isn't trusted",
    "Rider hasn't been told to trust this project, so FSX Cells doesn't run code from it. " +
        "Trust the project to run cells.",
)

/** Finds the `dotnet` to run the host with, and checks that it has a .NET SDK that can run cells. */
object Dotnet {
    /** The oldest .NET SDK whose F# compiler the host works with (see host/README.md). */
    const val MIN_SDK_MAJOR = 10
    const val DOWNLOAD_URL = "https://dotnet.microsoft.com/download"
    private const val SETTINGS_PATH = "Settings | Tools | FSX Cells"

    /**
     * The `dotnet` to use for a script in [scriptDir], after checking that the SDK it picks there (global.json
     * applies) is new enough. Starts a process: don't call on the UI thread.
     */
    fun resolve(scriptDir: File): File {
        val env = environment()
        val dotnet = locate(FsxCellsSettings.getInstance().dotnetPath, env)
        checkSdk(dotnet, scriptDir, environment(dotnet, env))
        return dotnet
    }

    /** The IDE's environment; on macOS this includes what the login shell sets (PATH), unlike System.getenv(). */
    fun environment(): Map<String, String> = EnvironmentUtil.getEnvironmentMap()

    /** [base] with [dotnet]'s folder first on PATH, so the script and the processes it starts use the same dotnet. */
    fun environment(dotnet: File, base: Map<String, String>): Map<String, String> {
        val key = base.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        val dir = dotnet.absoluteFile.parent ?: return base
        val path = base[key].orEmpty()
        val entries = path.split(File.pathSeparatorChar).filter { it.isNotEmpty() }
        if (entries.firstOrNull() == dir) return base
        return base + (key to (listOf(dir) + entries).joinToString(File.pathSeparator))
    }

    /** [configured] (from settings) if set, else the first `dotnet` found by [DotnetLocator]. */
    fun locate(configured: String?, env: Map<String, String>): File {
        if (!configured.isNullOrBlank()) {
            val file = File(configured.trim())
            if (!DotnetLocator.isExecutable(file)) {
                throw DotnetSetupException(
                    "The dotnet path in settings doesn't work",
                    "$SETTINGS_PATH is set to $file, which isn't an executable file. " +
                        "Fix the path, or clear it to find dotnet automatically.",
                )
            }
            return file
        }
        val search = DotnetLocator.find(env, SystemInfo.isWindows, System.getProperty("user.home"))
        return search.found ?: throw DotnetSetupException(
            "The .NET SDK wasn't found",
            "FSX Cells needs the .NET SDK $MIN_SDK_MAJOR or newer to run F# cells, and couldn't find the dotnet " +
                "command. It looked on PATH and in:\n" + search.tried.joinToString("\n") { "  $it" } +
                "\nInstall the .NET SDK, or set the path to dotnet in $SETTINGS_PATH.",
        )
    }

    /** Runs `dotnet --version` in [scriptDir] and throws if it fails or picks an SDK older than [MIN_SDK_MAJOR]. */
    fun checkSdk(dotnet: File, scriptDir: File, env: Map<String, String>) {
        val (exitCode, output) = try {
            run(dotnet, scriptDir, env, "--version")
        } catch (e: Exception) {
            throw DotnetSetupException("Can't run dotnet", "Running $dotnet failed: ${e.message}")
        }
        sdkProblem(dotnet, scriptDir, exitCode, output, findGlobalJson(scriptDir))?.let { throw it }
    }

    /** What's wrong with the result of `dotnet --version` in [scriptDir], or null if its SDK can run cells. */
    fun sdkProblem(dotnet: File, scriptDir: File, exitCode: Int?, output: String, globalJson: File?): DotnetSetupException? {
        val text = output.trim().lines().take(20).joinToString("\n")
        if (exitCode == null) {
            return DotnetSetupException("dotnet didn't respond", "`$dotnet --version` didn't finish within 30 seconds in $scriptDir.")
        }
        if (exitCode != 0) {
            return DotnetSetupException(
                "No usable .NET SDK",
                "`dotnet --version` failed in $scriptDir (using $dotnet):\n$text\n" +
                    "FSX Cells needs the .NET SDK $MIN_SDK_MAJOR or newer.",
            )
        }
        val version = text.lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        val major = sdkMajor(version)
            ?: return DotnetSetupException("Unexpected dotnet output", "`dotnet --version` in $scriptDir printed:\n$text")
        if (major >= MIN_SDK_MAJOR) return null
        val cause = if (globalJson != null) " It's selected by $globalJson." else ""
        val fix = if (globalJson != null) {
            "Install the .NET SDK $MIN_SDK_MAJOR or newer and update the SDK version in global.json (or allow it to " +
                "roll forward to a newer major version)."
        } else {
            "Install the .NET SDK $MIN_SDK_MAJOR or newer."
        }
        return DotnetSetupException(
            ".NET SDK $MIN_SDK_MAJOR or newer is needed",
            "In $scriptDir, dotnet uses the .NET SDK $version.$cause FSX Cells compiles cells with the SDK's F# " +
                "compiler, which needs .NET SDK $MIN_SDK_MAJOR or newer. $fix",
        )
    }

    /** 10 for "10.0.100" or "10.0.100-rc.2.25502.107"; null if it doesn't look like a version. */
    fun sdkMajor(version: String): Int? = version.trim().substringBefore('.').toIntOrNull()

    /** The global.json that applies to [dir]: the nearest one in it or a parent folder. */
    fun findGlobalJson(dir: File): File? =
        generateSequence(dir.absoluteFile) { it.parentFile }
            .map { File(it, "global.json") }
            .firstOrNull { it.isFile }

    /** Exit code (null on timeout) and combined output of `dotnet <args>` in [dir]. */
    private fun run(dotnet: File, dir: File, env: Map<String, String>, vararg args: String): Pair<Int?, String> {
        val process = ProcessBuilder(listOf(dotnet.path) + args)
            .directory(dir)
            .redirectErrorStream(true)
            .apply { environment().putAll(env) }
            .start()
        process.outputStream.close()
        // Read on another thread, so a process that writes a lot can't block on a full pipe.
        val output = StringBuilder()
        val reader = Thread({
            output.append(process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8))
        }, "fsx-cells dotnet output").apply { isDaemon = true; start() }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null to ""
        }
        reader.join(5000)
        return process.exitValue() to synchronized(output) { output.toString() }
    }
}

/**
 * Where to look for `dotnet` when no path is set: PATH (as `dotnet fsi` in a terminal would), DOTNET_ROOT, then the
 * folders the installers and package managers use. Pure, so it can be tested with a fake file system.
 */
object DotnetLocator {
    data class Search(val found: File?, val tried: List<String>)

    fun isExecutable(file: File) = file.isFile && file.canExecute()

    fun find(
        env: Map<String, String>,
        isWindows: Boolean,
        home: String?,
        isExecutable: (File) -> Boolean = ::isExecutable,
    ): Search {
        val exe = if (isWindows) "dotnet.exe" else "dotnet"
        val separator = if (isWindows) ';' else ':'
        fun variable(name: String) = env.entries.firstOrNull { it.key.equals(name, ignoreCase = isWindows) }?.value
            ?.takeIf { it.isNotBlank() }

        val dirs = mutableListOf<String>()
        variable("PATH")?.split(separator)?.map { it.trim().removeSurrounding("\"") }?.filter { it.isNotEmpty() }
            ?.let(dirs::addAll)
        val pathCount = dirs.size
        listOfNotNull(variable("DOTNET_ROOT"), variable(if (isWindows) "DOTNET_ROOT(x86)" else "DOTNET_ROOT_X64"))
            .forEach(dirs::add)
        if (isWindows) {
            listOf("ProgramFiles", "ProgramW6432", "ProgramFiles(x86)").mapNotNull(::variable)
                .forEach { dirs += File(it, "dotnet").path }
            variable("LOCALAPPDATA")?.let { dirs += File(File(it, "Microsoft"), "dotnet").path }
        } else {
            dirs += listOf(
                "/usr/local/share/dotnet", // macOS installer
                "/opt/homebrew/bin",
                "/usr/local/bin",
                "/usr/share/dotnet", // Linux packages
                "/usr/lib/dotnet",
                "/usr/lib64/dotnet",
                "/opt/dotnet",
                "/snap/bin",
            )
        }
        if (home != null) dirs += File(home, ".dotnet").path // dotnet-install scripts

        val unique = dirs.distinct()
        val found = unique.asSequence().map { File(it, exe) }.firstOrNull(isExecutable)
        // PATH folders aren't listed in messages: there can be many, and "on PATH" says it.
        val tried = dirs.drop(pathCount).distinct().map { File(it, exe).path }
        return Search(found, tried)
    }
}
