package com.postsaimanager.architecturetest

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `:core:ai:local` is the engine host of the `:inference` process: it may depend on `:core:domain` and `:core:model` (ports and
 * types), never on `:core:data`, which sits above it in the layering (Room, WorkManager, repositories). What the host needs from the
 * app is a port in the domain, or an adapter that lives with the engine (the bundled skills' catalogue is in `:core:ai:litert`).
 */
class AiLocalLayeringKonsistTest {

    @Test
    fun `a core-data import in core-ai-local pulls the data layer into the inference process and breaks the layering`() {
        val violations = Konsist.scopeFromProject()
            .files
            .filter { it.projectPath.replace('\\', '/').contains("core/ai/local/src/") }
            .flatMap { file ->
                file.imports
                    .map { it.name }
                    .filter { it.startsWith("com.postsaimanager.core.data.") }
                    .map { file.projectPath.replace('\\', '/') to it }
            }

        assertTrue(violations.isEmpty()) {
            "core/ai/local must not import :core:data. Move what it needs behind a port in :core:domain.\n" +
                violations.joinToString("\n") { (path, import) -> "  $path -> import $import" }
        }
    }

    @Test
    fun `a core-data dependency in the Gradle file of core-ai-local breaks the layering even when no import uses it yet`() {
        val gradleFile = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "core/ai/local/build.gradle.kts") }
            .first { it.exists() }
        val offending = gradleFile.readLines().filter { it.contains("project(\":core:data\")") }

        assertTrue(offending.isEmpty()) {
            "core/ai/local/build.gradle.kts depends on :core:data; it may depend on :core:domain and :core:model only:\n" +
                offending.joinToString("\n")
        }
    }
}
