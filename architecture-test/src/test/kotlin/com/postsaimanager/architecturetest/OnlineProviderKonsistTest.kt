package com.postsaimanager.architecturetest

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Rules 2 and 3 — task 7.15.1, enforcing documentation/02-architecture.md §2.3 and §10
 * rules 2–3: `:core:ai:online` is the module that talks to a remote model over HTTP, and
 * the product's whole privacy thesis (documentation/README.md's "Online models exist only
 * as a deliberate, per-query escalation … Remote models may propose actions but never
 * execute them") is a Gradle fact enforced here, not a habit.
 *
 * There are currently zero known violations of either rule — the module was built clean.
 * These tests exist so the *next* dependency line someone adds to
 * `core/ai/online/build.gradle.kts` fails a test instead of shipping silently.
 */
class OnlineProviderKonsistTest {

    /** Everything under `core/ai/online/src/main` — the module's production source, not its tests. */
    private fun KoFileDeclaration.isOnlineProviderMainSource(): Boolean =
        projectPath.replace('\\', '/').contains("core/ai/online/src/main/")

    @Test
    fun `an online provider importing core-data would let a remote HTTP call read the user's documents, which is exactly the leak the whole on-device-first architecture exists to prevent`() {
        val violations = Konsist.scopeFromProject()
            .files
            .filter { it.isOnlineProviderMainSource() }
            .flatMap { file ->
                file.imports
                    .map { it.name }
                    .filter { it.startsWith("com.postsaimanager.core.data.") }
                    .map { file.projectPath.replace('\\', '/') to it }
            }

        assertTrue(violations.isEmpty()) {
            val details = violations.joinToString(separator = "\n") { (path, import) -> "  $path -> import $import" }
            "A file under core/ai/online imports :core:data. That puts a DAO, DataStore or " +
                "file handle on a remote provider's classpath, which means a document could " +
                "be read and sent to a third-party API without the consent gate ever running. " +
                "This dependency must never exist — see documentation/README.md, \"The two " +
                "structural guarantees\", guarantee 1.\n$details"
        }
    }

    @Test
    fun `an online provider importing core-domain would put the executable AiTool type on a remote HTTP client's classpath, which is the difference between a model proposing an action and a model executing one unsupervised`() {
        val violations = Konsist.scopeFromProject()
            .files
            .filter { it.isOnlineProviderMainSource() }
            .flatMap { file ->
                file.imports
                    .map { it.name }
                    .filter { it.startsWith("com.postsaimanager.core.domain.") }
                    .map { file.projectPath.replace('\\', '/') to it }
            }

        assertTrue(violations.isEmpty()) {
            val details = violations.joinToString(separator = "\n") { (path, import) -> "  $path -> import $import" }
            "A file under core/ai/online imports :core:domain. ToolSpec (inert, describable) " +
                "lives in :core:model on purpose so a remote model can be told what tools " +
                "exist; AiTool.execute lives in :core:domain so that a remote provider " +
                "physically cannot call it. Adding this dependency would turn \"remote models " +
                "propose, the user confirms, the app executes locally\" from a compiler fact " +
                "into a suggestion. See documentation/README.md, \"The two structural " +
                "guarantees\", guarantee 2.\n$details"
        }
    }
}
