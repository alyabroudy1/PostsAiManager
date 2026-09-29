package com.postsaimanager.core.config

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.AiModelDescriptor
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class HfTreeEntry(
    val type: String = "file",
    val path: String = "",
    val size: Long = 0,
    val lfs: HfLfs? = null,
)

@Serializable
private data class HfLfs(
    /** For LFS-tracked files this is the **SHA-256** of the content. */
    val oid: String = "",
    val size: Long = 0,
)

/**
 * Resolves GGUF models directly from the Hugging Face Hub.
 *
 * ### Why this exists
 *
 * Google's AI Edge Gallery popularised the pattern of a browsable list of open models that
 * download on demand. Its list cannot be reused here: it ships **LiteRT `.task`** files for
 * MediaPipe, whereas this app runs **GGUF** through llama.cpp (chosen because it is the
 * only runtime supporting both arbitrary custom models and GBNF grammar constraints, which
 * the Phase 8 tool layer depends on).
 *
 * The Hub is the equivalent source for GGUF, and it offers something better for our
 * purposes: the tree API returns each LFS file's `oid`, **which is its SHA-256**. That
 * supplies the integrity hash the downloader requires without anyone having to hash
 * multi-gigabyte artefacts by hand — the gap currently blocking task 7.4.6.
 *
 * ### Trust — read before enabling
 *
 * This is a **weaker trust tier** than the signed manifest, and deliberately a separate one:
 *
 * | Source | Integrity | Authenticity |
 * |---|---|---|
 * | Signed manifest | SHA-256 from a payload signed by our key | Our signature |
 * | This (Hub direct) | SHA-256 from the Hub API over TLS | Hub + TLS only |
 *
 * The hash still prevents a corrupted or man-in-the-middled *download*, because it is
 * fetched separately from the artefact. What it does not do is prove that *we* vetted the
 * repository. A user pointing the app at an arbitrary repo is making the same trust
 * decision they make downloading a model on a desktop — which is reasonable, as long as it
 * is theirs to make rather than something the app does silently.
 *
 * Curated repos therefore still belong in the signed manifest; this path is for models the
 * user explicitly chooses.
 */
@Singleton
class HuggingFaceCatalogSource @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
) {

    /**
     * Lists the GGUF quantisations available in a repo, as installable descriptors.
     *
     * @param repoId e.g. `"Qwen/Qwen2.5-1.5B-Instruct-GGUF"`
     * @param accessToken required for gated repos — Gemma, for instance, requires accepting
     *   its licence on the Hub first, and returns 401/403 without one.
     */
    suspend fun listGgufModels(
        repoId: String,
        accessToken: String? = null,
    ): PamResult<List<AiModelDescriptor>> = try {
        val response = httpClient.get("$API_BASE/models/$repoId/tree/main") {
            accessToken?.let { header("Authorization", "Bearer $it") }
        }

        when (response.status.value) {
            200 -> {
                val entries = json.decodeFromString<List<HfTreeEntry>>(response.bodyAsText())
                PamResult.Success(entries.mapNotNull { it.toDescriptor(repoId) })
            }
            401, 403 -> PamResult.Error(
                PamError.InferenceError(
                    "This model is gated. Accept its licence on huggingface.co and add an " +
                        "access token to download it.",
                ),
            )
            404 -> PamResult.Error(
                PamError.FileNotFound("No such repository: $repoId"),
            )
            else -> PamResult.Error(
                PamError.InferenceError("Hugging Face returned ${response.status.value}."),
            )
        }
    } catch (e: Exception) {
        PamResult.Error(PamError.InferenceError("Could not reach Hugging Face: ${e.message}", e))
    }

    private fun HfTreeEntry.toDescriptor(repoId: String): AiModelDescriptor? {
        if (type != "file" || !path.endsWith(".gguf", ignoreCase = true)) return null

        // Only LFS-tracked files carry a content hash. Without one the descriptor would be
        // `isInstallable == false` anyway, so skip it rather than offer something unusable.
        val sha256 = lfs?.oid?.takeIf { it.length == SHA256_HEX_LENGTH } ?: return null
        val actualSize = lfs.size.takeIf { it > 0 } ?: size

        val fileName = path.substringAfterLast('/')
        val quantisation = QUANT_PATTERN.find(fileName)?.value?.uppercase() ?: "unknown"

        return AiModelDescriptor(
            id = "hf:$repoId:$fileName",
            name = "${repoId.substringAfterLast('/')} · $quantisation",
            family = repoId.substringBefore('/'),
            parameterCount = PARAM_PATTERN.find(repoId)?.value?.uppercase() ?: "unknown",
            quantization = quantisation,
            sizeBytes = actualSize,
            // Working set runs meaningfully above file size once loaded; a conservative
            // multiplier keeps the fit check from being over-optimistic.
            minAvailableRamBytes = (actualSize * RAM_MULTIPLIER).toLong(),
            contextTokens = DEFAULT_CONTEXT_TOKENS,
            license = "See $repoId on Hugging Face",
            downloadUrl = "$HUB_BASE/$repoId/resolve/main/$path",
            sha256 = sha256,
            supportsTools = false,
            description = "From huggingface.co/$repoId",
        )
    }

    private companion object {
        const val API_BASE = "https://huggingface.co/api"
        const val HUB_BASE = "https://huggingface.co"
        const val SHA256_HEX_LENGTH = 64
        const val RAM_MULTIPLIER = 1.4
        const val DEFAULT_CONTEXT_TOKENS = 4096
        val QUANT_PATTERN = Regex("""(?i)\bQ\d+_[A-Z0-9_]+|\bF16\b|\bBF16\b""")
        val PARAM_PATTERN = Regex("""(?i)\b\d+(\.\d+)?[BM]\b""")
    }
}
