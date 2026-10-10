package com.postsaimanager.architecturetest

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every word the user reads is a string resource (German, Arabic and English exist for each), so a screen never writes
 * `Text("literal")` or `contentDescription = "literal"` in the app or a feature module.
 */
class NoLiteralUiTextTest {

    private val literal = Regex("""(\bText\(\s*(text\s*=\s*)?|contentDescription\s*=\s*)"[^"$]*\p{L}""")

    @Test
    fun `a screen shows no hard-coded text`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val sources = listOf("app", "feature").map { File(root, it) }.filter { it.exists() }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path.replace('\\', '/') }.toList() }

        val violations = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (!line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") && literal.containsMatchIn(line)) {
                    "${file.relativeTo(root)}:${index + 1}: ${line.trim()}"
                } else {
                    null
                }
            }
        }

        assertTrue(violations.isEmpty()) {
            "Hard-coded UI text. Put it in strings.xml (values, values-de, values-ar):\n" + violations.joinToString("\n")
        }
    }
}
