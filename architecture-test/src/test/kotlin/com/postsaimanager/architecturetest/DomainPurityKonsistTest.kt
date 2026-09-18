package com.postsaimanager.architecturetest

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Rule 4 — task 7.15.1, enforcing documentation/02-architecture.md §10 rule 7 ("`core:domain`
 * … contains no Android imports") and §11.7 ("Direct `android.util.Log` calls make
 * `:core:domain` untestable and risk leaking document content into logcat").
 *
 * This is not a theoretical rule: it has broken the build twice. `android.util.Log` is a
 * stub outside an instrumented environment, so a single import makes every JVM unit test
 * that touches the affected class throw `RuntimeException: Method e in android.util.Log
 * not mocked` — not just for that class, for the whole test run. Both previous times this
 * was caught by a failing test after the fact, not by review before it landed. This test
 * makes the catch immediate and named instead of accidental.
 */
class DomainPurityKonsistTest {

    /** Everything under `core/domain/src/main` — the domain module's production source, not its tests. */
    private fun KoFileDeclaration.isDomainMainSource(): Boolean =
        projectPath.replace('\\', '/').contains("core/domain/src/main/")

    @Test
    fun `an android import in core-domain throws 'not mocked' under every plain JUnit test that touches it, silently turning the domain layer untestable off-device`() {
        val violations = Konsist.scopeFromProject()
            .files
            .filter { it.isDomainMainSource() }
            .flatMap { file ->
                file.imports
                    .map { it.name }
                    .filter { it.startsWith("android.") }
                    .map { file.projectPath.replace('\\', '/') to it }
            }

        assertTrue(violations.isEmpty()) {
            val details = violations.joinToString(separator = "\n") { (path, import) -> "  $path -> import $import" }
            "A file under core/domain imports an android.* class. :core:domain must be plain " +
                "Kotlin: an Android class (most commonly android.util.Log) is a stub outside " +
                "an instrumented test, so any JVM unit test that exercises the importing class " +
                "throws \"Method ... not mocked\" — this has broken the build twice already. " +
                "§11.7 prescribes a PamLogger in :core:common, but it is not built yet " +
                "(task 7.15.4), so today the answer is not to log from the domain at all: " +
                "carry the diagnostic in the PamError you return and let the adapter that " +
                "owns a Context decide whether to log it. AiExtractionUseCase does exactly " +
                "that, after hitting this.\n$details"
        }
    }
}
