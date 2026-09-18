package com.postsaimanager.architecturetest

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Rule 1 — task 7.15.1, enforcing documentation/02-architecture.md §10 rule 1 and the
 * "Guiding principles" section of documentation/README.md: presentation code talks to
 * `:core:domain` only.
 *
 * Why this matters in the running app, not in the abstract: everything else this project
 * relies on to be private and safe — the consent gate before a document ever leaves the
 * device, `PamResult` error handling instead of a leaked exception, a use case validating
 * a write before it touches Room — lives *inside* `:core:domain` and its implementations.
 * None of it is enforced again at the UI layer. If a screen or ViewModel under `feature/` can
 * import `:core:data`, `:core:ai:*` or `:core:config` directly, it can read a document
 * straight out of Room, drive the local model, or hit the network from a Composable,
 * silently stepping around every one of those guarantees. The compiler cannot catch that
 * mistake unless the dependency line is absent — which is what this test polices.
 *
 * Two files break this today, on purpose, each with a name and a reason rather than a
 * blanket skip:
 *
 * - [TEMPORARY_EXCEPTIONS] — `feature:documents` reaching into `:core:data`. This is a
 *   real violation, tracked as task 7.15.2, being fixed next. It is listed here so the
 *   fix is verifiable: once 7.15.2 lands, the entry becomes a set that allows nothing and
 *   can be deleted.
 * - [SANCTIONED_EXCEPTIONS] — `feature:models` reaching into `:core:ai:catalog` and
 *   `:core:ai:embed`. This is not a bug: model management *is* that subsystem's UI, and a
 *   domain port here would be a pure pass-through with no logic of its own. See
 *   documentation/02-architecture.md §2.3 and the comment in feature/models/build.gradle.kts.
 *
 * Both lists name the exact imports allowed, not just the file — so a new, different
 * violation landing in an already-excepted file still fails this test.
 */
class FeatureBoundaryKonsistTest {

    /** A feature file is allowed to import exactly these — and only these — forbidden names. */
    private data class NamedException(
        val filePathSuffix: String,
        val allowedImportNames: Set<String>,
        val note: String,
    )

    private val forbiddenPackagePrefixes = listOf(
        "com.postsaimanager.core.data.",
        "com.postsaimanager.core.ai.",
        "com.postsaimanager.core.config.",
    )

    // ─────────────────────────────────────────────────────────────────────────────
    // TEMPORARY — must shrink to nothing. Tracked as task 7.15.2.
    // ─────────────────────────────────────────────────────────────────────────────
    private val TEMPORARY_EXCEPTIONS = listOf(
        NamedException(
            filePathSuffix = "feature/documents/src/main/kotlin/com/postsaimanager/feature/documents/" +
                "DocumentDetailViewModel.kt",
            allowedImportNames = setOf(
                "com.postsaimanager.core.data.repository.DocumentProcessingPipeline",
                "com.postsaimanager.core.data.repository.MatchType",
                "com.postsaimanager.core.data.repository.ProcessingState",
                "com.postsaimanager.core.data.repository.ProfileMatcher",
                "com.postsaimanager.core.data.repository.ProfileSuggestion",
                "com.postsaimanager.core.data.util.PdfGenerator",
            ),
            note = "TEMPORARY — task 7.15.2 removes this. The ViewModel drives Room-backed " +
                "pipeline types and a PDF utility directly instead of through a use case.",
        ),
        NamedException(
            filePathSuffix = "feature/documents/src/main/kotlin/com/postsaimanager/feature/documents/" +
                "DocumentDetailScreen.kt",
            allowedImportNames = setOf(
                "com.postsaimanager.core.data.repository.MatchType",
                "com.postsaimanager.core.data.repository.ProcessingState",
                "com.postsaimanager.core.data.repository.ProfileSuggestion",
            ),
            note = "TEMPORARY — task 7.15.2 removes this. The same :core:data types leak one " +
                "layer further, into the Composable itself.",
        ),
    )

    // ─────────────────────────────────────────────────────────────────────────────
    // SANCTIONED — a permanent, deliberate exception. Not tracked for removal.
    // ─────────────────────────────────────────────────────────────────────────────
    private val SANCTIONED_EXCEPTIONS = listOf(
        NamedException(
            filePathSuffix = "feature/models/src/main/kotlin/com/postsaimanager/feature/models/" +
                "ModelsViewModel.kt",
            allowedImportNames = setOf(
                "com.postsaimanager.core.ai.catalog.CatalogEntry",
                "com.postsaimanager.core.ai.catalog.ModelCatalogRepository",
                "com.postsaimanager.core.ai.catalog.gguf.ModelImporter",
                "com.postsaimanager.core.ai.catalog.ModelCatalogState",
                "com.postsaimanager.core.ai.catalog.download.ModelDownloadStatus",
                "com.postsaimanager.core.ai.embed.install.EmbeddingModelManager",
                "com.postsaimanager.core.ai.embed.install.InstallStatus",
            ),
            note = "SANCTIONED, not temporary — model management IS the catalog/embedding " +
                "subsystem's UI; a domain port would be a pass-through over files and " +
                "downloads, not documents. Revisit only if a second consumer appears.",
        ),
        NamedException(
            filePathSuffix = "feature/models/src/main/kotlin/com/postsaimanager/feature/models/" +
                "ModelsScreen.kt",
            allowedImportNames = setOf(
                "com.postsaimanager.core.ai.catalog.CatalogEntry",
                "com.postsaimanager.core.ai.catalog.download.ModelDownloadStatus",
                "com.postsaimanager.core.ai.embed.install.InstallStatus",
            ),
            note = "SANCTIONED, same reasoning as ModelsViewModel above.",
        ),
    )

    /** Everything under `feature/<module>/src/main` for any module — presentation source, not its tests. */
    private fun KoFileDeclaration.isFeatureMainSource(): Boolean {
        val segments = projectPath.replace('\\', '/').split('/')
        val featureIndex = segments.indexOf("feature")
        return featureIndex >= 0 &&
            segments.getOrNull(featureIndex + 2) == "src" &&
            segments.getOrNull(featureIndex + 3) == "main"
    }

    private fun KoFileDeclaration.normalizedPath(): String = projectPath.replace('\\', '/')

    private fun allowedImportNamesFor(file: KoFileDeclaration, exceptions: List<NamedException>): Set<String> =
        exceptions.firstOrNull { file.normalizedPath().endsWith(it.filePathSuffix) }?.allowedImportNames.orEmpty()

    @Test
    fun `a feature screen importing core-data, core-ai or core-config directly could read the user's documents or drive the model straight from Compose, bypassing the domain layer that the consent gate, use-case validation and PamResult error handling all live behind`() {
        val allExceptions = TEMPORARY_EXCEPTIONS + SANCTIONED_EXCEPTIONS

        val violations: List<Pair<String, String>> = Konsist.scopeFromProject()
            .files
            .filter { it.isFeatureMainSource() }
            .flatMap { file ->
                val allowed = allowedImportNamesFor(file, allExceptions)
                file.imports
                    .map { it.name }
                    .filter { name -> forbiddenPackagePrefixes.any(name::startsWith) }
                    .filterNot { it in allowed }
                    .map { file.normalizedPath() to it }
            }

        assertTrue(violations.isEmpty()) {
            val details = violations.joinToString(separator = "\n") { (path, import) -> "  $path -> import $import" }
            "The following feature files import :core:data, :core:ai:* or :core:config " +
                "directly, which would let a screen or ViewModel reach infrastructure without " +
                "going through :core:domain — no use case, no consent gate, no PamResult error " +
                "handling in the way. If this is a genuine, reasoned exception, add a named " +
                "entry to TEMPORARY_EXCEPTIONS or SANCTIONED_EXCEPTIONS in " +
                "FeatureBoundaryKonsistTest.kt explaining why; do not widen the rule.\n$details"
        }
    }
}
