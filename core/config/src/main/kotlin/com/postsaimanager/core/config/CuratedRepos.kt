package com.postsaimanager.core.config

import kotlinx.serialization.Serializable

/**
 * A model repository offered for browsing, in the spirit of Google's AI Edge Gallery.
 *
 * The user picks a repo, [HuggingFaceCatalogSource] lists its GGUF quantisations with real
 * SHA-256 hashes from the Hub API, and any of them can be installed.
 */
@Serializable
data class CuratedRepo(
    val repoId: String,
    val displayName: String,
    val family: String,
    val license: String,
    /** Gated repos need the licence accepted on the Hub plus an access token. */
    val gated: Boolean = false,
    val recommendedQuant: String = "Q4_K_M",
    val notes: String? = null,
)

/**
 * Starter list shipped with the app.
 *
 * ### Why these repos rather than Google's own
 *
 * `google/gemma-*` is **gated** — it requires accepting the licence on huggingface.co and
 * supplying an access token, which is a poor first-run experience. The community GGUF
 * conversions below were each checked against the Hub API on 2026-08-07 and are
 * **ungated**, so they install with no account at all.
 *
 * They are also the right *format*: AI Edge Gallery ships LiteRT `.task` files for
 * MediaPipe, whereas this app runs GGUF through llama.cpp (chosen for arbitrary
 * custom-model import and GBNF grammar constraints — see documentation/06-llama-spike.md).
 * Gallery's list is therefore not reusable, but the Hub covers the same models in a format
 * we can actually run.
 *
 * This list is a **fallback**. The authoritative one arrives in the signed manifest, so new
 * models can be added without an app update — which is exactly why the version numbers here
 * are not hard-coded anywhere else in the codebase.
 */
object CuratedRepos {

    val all: List<CuratedRepo> = listOf(
        // ── Gemma ────────────────────────────────────────────────────────────
        CuratedRepo(
            repoId = "unsloth/gemma-3-1b-it-GGUF",
            displayName = "Gemma 3 1B Instruct",
            family = "Gemma",
            license = "Gemma Terms of Use",
            notes = "Smallest Gemma. Fits low-memory devices; no native tool calling, " +
                "so tool use relies on grammar-constrained decoding.",
        ),
        CuratedRepo(
            repoId = "ggml-org/gemma-3-4b-it-GGUF",
            displayName = "Gemma 3 4B Instruct",
            family = "Gemma",
            license = "Gemma Terms of Use",
            notes = "Official llama.cpp org conversion. Strong quality; needs a mid-range " +
                "or better device.",
        ),
        CuratedRepo(
            repoId = "unsloth/gemma-3-4b-it-GGUF",
            displayName = "Gemma 3 4B Instruct (Unsloth)",
            family = "Gemma",
            license = "Gemma Terms of Use",
            notes = "Alternative conversion with a wider spread of quantisations.",
        ),

        // ── Qwen ─────────────────────────────────────────────────────────────
        CuratedRepo(
            repoId = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            displayName = "Qwen2.5 1.5B Instruct",
            family = "Qwen",
            license = "Apache-2.0",
            notes = "Published by the Qwen team. Apache-2.0 and good at tool use — the " +
                "safest default.",
        ),
        CuratedRepo(
            repoId = "Qwen/Qwen3-1.7B-GGUF",
            displayName = "Qwen3 1.7B",
            family = "Qwen",
            license = "Apache-2.0",
            notes = "Newer generation, similar footprint.",
        ),
        CuratedRepo(
            repoId = "ggml-org/Qwen3-1.7B-GGUF",
            displayName = "Qwen3 1.7B (llama.cpp)",
            family = "Qwen",
            license = "Apache-2.0",
            notes = "Conversion maintained alongside llama.cpp itself.",
        ),
    )

    fun byFamily(): Map<String, List<CuratedRepo>> = all.groupBy { it.family }

    /**
     * Quantisations worth surfacing first, best trade-off first.
     *
     * A repo commonly publishes 15+ variants; showing all of them is noise. `Q4_K_M` is the
     * usual quality/size sweet spot, with smaller options for constrained devices.
     */
    val preferredQuantOrder: List<String> =
        listOf("Q4_K_M", "Q4_0", "IQ4_XS", "Q5_K_M", "Q3_K_M", "Q8_0")
}
