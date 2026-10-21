package me.tarry.app.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/** Hex colour literals belong in ui/theme/ only (docs/design/tokens.md, Do / Don't). */
class NoColorLiteralsTest {
    private fun sourceRoot(): File {
        val rel = "src/main/java"
        // Gradle runs unit tests with the module dir as the working directory; fall back to the repo root.
        return listOf(File(rel), File("app/$rel")).firstOrNull { it.isDirectory }
            ?: error("app/src/main/java not found from ${File(".").absoluteFile}")
    }

    @Test
    fun colorLiteralsOnlyAppearUnderUiTheme() {
        val root = sourceRoot()
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { !it.invariantSeparatorsPath.contains("/ui/theme/") }
            .flatMap { f ->
                f.readLines().withIndex()
                    .filter { it.value.contains("Color(0x") }
                    .map { "${f.relativeTo(root).invariantSeparatorsPath}:${it.index + 1}" }
            }
            .toList()
        assertThat(offenders).isEmpty()
    }
}
