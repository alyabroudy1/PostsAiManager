package com.postsaimanager

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pins H1: [PostsAiManagerApp] must recognise `:inference` as *not* the main process, so
 * `onCreate` skips main-process-only start-up work there (see the class KDoc) — the actual
 * bug this hotfix exists for was that `DocumentProcessingRecovery` ran unconditionally in
 * every process and crashed `:inference` by constructing `OcrService` before ML Kit's
 * `ContentProvider`-based init had run.
 */
class PostsAiManagerAppTest {

    private val packageName = "com.postsaimanager.debug"

    @Test
    @DisplayName("the app's own process name is the main process")
    fun mainProcess() {
        assertThat(isMainProcess(packageName, packageName)).isTrue()
    }

    @Test
    @DisplayName("the :inference suffix is not the main process")
    fun inferenceProcess() {
        assertThat(isMainProcess("$packageName:inference", packageName)).isFalse()
    }

    @Test
    @DisplayName("a null process name (lookup failed) defaults to 'assume main' — the safe default")
    fun nullProcessNameDefaultsToMain() {
        assertThat(isMainProcess(null, packageName)).isTrue()
    }

    @Test
    @DisplayName("the background reprocess is injected lazily: field injection alone must not build it in :inference")
    fun reprocessIsLazy() {
        val field = PostsAiManagerApp::class.java.getDeclaredField("reprocessOutdatedDocuments")

        assertThat(field.type).isEqualTo(dagger.Lazy::class.java)
    }

    @Test
    @DisplayName("onCreate leaves before any start-up work when this is not the main process")
    fun onCreateGatesOnMainProcessBeforeReprocess() {
        // Source order is the wiring: the early `return` on !isMainProcess() comes before the launch.
        val source = java.io.File("src/main/kotlin/com/postsaimanager/PostsAiManagerApp.kt").readText()
        val gate = source.indexOf("if (!isMainProcess()) return")
        val launch = source.indexOf("reprocessOutdatedDocuments.get()")

        assertThat(gate).isGreaterThan(-1)
        assertThat(launch).isGreaterThan(gate)
    }

    @Test
    @DisplayName("an unrelated process name is not the main process")
    fun unrelatedProcessName() {
        assertThat(isMainProcess("com.some.other.app", packageName)).isFalse()
    }
}
