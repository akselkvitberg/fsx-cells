package fsxcells

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DotnetTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun find(env: Map<String, String>, windows: Boolean = false, home: String? = "/home/me", vararg existing: String) =
        DotnetLocator.find(env, windows, home) { it.path in existing.map { p -> File(p).path } }

    @Test
    fun `prefers PATH over well-known folders`() {
        val search = find(
            mapOf("PATH" to "/bin:/opt/sdk"),
            existing = arrayOf("/opt/sdk/dotnet", "/usr/share/dotnet/dotnet"),
        )
        assertEquals(File("/opt/sdk/dotnet"), search.found)
    }

    @Test
    fun `falls back to DOTNET_ROOT, then installer folders, then the home folder`() {
        assertEquals(
            File("/custom/dotnet"),
            find(mapOf("PATH" to "/bin", "DOTNET_ROOT" to "/custom"), existing = arrayOf("/custom/dotnet", "/usr/local/share/dotnet/dotnet")).found,
        )
        // macOS: launched from the Dock, PATH lacks the installer's folder.
        assertEquals(
            File("/usr/local/share/dotnet/dotnet"),
            find(mapOf("PATH" to "/usr/bin:/bin"), existing = arrayOf("/usr/local/share/dotnet/dotnet")).found,
        )
        assertEquals(File("/home/me/.dotnet/dotnet"), find(emptyMap(), existing = arrayOf("/home/me/.dotnet/dotnet")).found)
    }

    @Test
    fun `reports where it looked, without the PATH folders`() {
        val search = find(mapOf("PATH" to "/bin"))
        assertNull(search.found)
        assertTrue(search.tried.contains(File("/usr/local/share/dotnet/dotnet").path))
        assertTrue(search.tried.contains(File("/home/me/.dotnet/dotnet").path))
        assertTrue(search.tried.none { it.startsWith("/bin") })
    }

    @Test
    fun `on Windows looks for dotnet exe, reads variables case-insensitively and checks Program Files`() {
        assertEquals(
            File("C:\\tools", "dotnet.exe"),
            find(mapOf("Path" to "C:\\Windows;\"C:\\tools\""), windows = true, existing = arrayOf(File("C:\\tools", "dotnet.exe").path)).found,
        )
        val programFiles = File(File("C:\\Program Files", "dotnet"), "dotnet.exe").path
        assertEquals(
            File(programFiles),
            find(mapOf("ProgramFiles" to "C:\\Program Files"), windows = true, existing = arrayOf(programFiles)).found,
        )
    }

    @Test
    fun `puts the dotnet folder first on PATH`() {
        val sep = File.pathSeparator
        val env = Dotnet.environment(File("/opt/sdk/dotnet"), mapOf("PATH" to "/bin$sep/usr/bin", "HOME" to "/home/me"))
        // absoluteFile: on Windows the folder gets the current drive.
        assertEquals("${File("/opt/sdk/dotnet").absoluteFile.parent}$sep/bin$sep/usr/bin", env["PATH"])
        assertEquals("/home/me", env["HOME"])
        assertEquals("1", env["DOTNET_NOLOGO"])
        // Already first: unchanged.
        assertEquals(env, Dotnet.environment(File("/opt/sdk/dotnet"), env))
    }

    @Test
    fun `parses SDK major versions`() {
        assertEquals(10, Dotnet.sdkMajor("10.0.100"))
        assertEquals(11, Dotnet.sdkMajor("11.0.100-preview.1.26101.1\n"))
        assertNull(Dotnet.sdkMajor("garbage"))
    }

    private val dotnet = File("/usr/bin/dotnet")
    private val dir = File("/work/aoc")

    @Test
    fun `accepts SDK 10 and newer`() {
        assertNull(Dotnet.sdkProblem(dotnet, dir, 0, "10.0.100\n", null))
        assertNull(Dotnet.sdkProblem(dotnet, dir, 0, "11.0.100-rc.1\n", null))
    }

    @Test
    fun `explains an old SDK, naming the global json that selects it`() {
        val plain = Dotnet.sdkProblem(dotnet, dir, 0, "8.0.404\n", null)
        assertNotNull(plain)
        assertTrue(plain!!.message!!.contains("8.0.404"))
        assertTrue(plain.title.contains("10"))

        val pinned = Dotnet.sdkProblem(dotnet, dir, 0, "9.0.100", File("/work/global.json"))!!
        assertTrue(pinned.message!!.contains(File("/work/global.json").path))
    }

    @Test
    fun `passes on what dotnet said when it fails`() {
        val problem = Dotnet.sdkProblem(dotnet, dir, 145, "No .NET SDKs were found.", null)!!
        assertTrue(problem.message!!.contains("No .NET SDKs were found."))
        assertNotNull(Dotnet.sdkProblem(dotnet, dir, null, "", null))
    }

    @Test
    fun `finds the nearest global json`() {
        val root = temp.newFolder("repo")
        val day = File(root, "2025/day01").apply { mkdirs() }
        assertNull(Dotnet.findGlobalJson(day)?.takeIf { it.startsWith(root) })
        File(root, "global.json").writeText("{}")
        assertEquals(File(root, "global.json"), Dotnet.findGlobalJson(day))
        File(day.parentFile, "global.json").writeText("{}")
        assertEquals(File(day.parentFile, "global.json"), Dotnet.findGlobalJson(day))
    }
}
