package fsxcells

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputFormatterTest {
    private fun diagnostic(severity: String, line: Int, col: Int, message: String) =
        Diagnostic(line, col, line, col + 1, severity, message, "a.fsx")

    @Test
    fun `warnings are shown dimmed after the value and error`() {
        val result = EvalResult(
            true, "out\n", "42", "int", "(2,5): boom", 3,
            listOf(
                diagnostic("error", 2, 4, "boom"),
                diagnostic("warning", 7, 0, "careful"),
                diagnostic("info", 8, 0, "fyi"),
            ),
        )
        assertEquals(
            listOf(
                OutputLine("out"),
                OutputLine("42"),
                OutputLine("(2,5): boom", OutputLineKind.Error),
                OutputLine("(7,1): careful", OutputLineKind.Dim),
            ),
            OutputFormatter.format(result).lines,
        )
    }
}
