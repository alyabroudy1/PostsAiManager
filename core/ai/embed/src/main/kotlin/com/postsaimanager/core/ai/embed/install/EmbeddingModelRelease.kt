package com.postsaimanager.core.ai.embed.install

/**
 * The exact embedding model this build expects, pinned by revision and content hash.
 *
 * ### Why this is pinned in the APK, unlike the chat models
 *
 * `BundledCatalog` deliberately ships **no** URLs or hashes: the chat catalog is
 * open-ended and user-driven, it gains entries after release, and a bad entry has to be
 * revocable without an app update. That is what the signed remote manifest is for.
 *
 * The embedding model is a different kind of thing. There is exactly one, the app requires
 * it, the user never chooses it, and changing it is not a config change — a new model
 * produces vectors in a different space, so every stored embedding has to be discarded and
 * recomputed. That only happens in a build. Since replacing it already requires shipping an
 * app version, routing it through a revocable manifest would add a moving part that can
 * fail — a network fetch, a signature check, an empty key list — in front of an asset whose
 * correct value is known at compile time.
 *
 * Pinning is also the stronger guarantee here. [sha256] is checked before the file is moved
 * into place, so a compromised CDN, a mirror serving the wrong file, or a truncated
 * transfer all fail closed. The URL names an immutable **revision**, not a branch, so it
 * cannot change under us even if the repository's `main` moves.
 *
 * The trade-off accepted: this cannot be corrected without an app update. For an asset that
 * cannot change without an app update anyway, that costs nothing.
 */
object EmbeddingModelRelease {

    /**
     * Immutable commit in the source repository. Never `main` — a branch would let the
     * bytes behind these URLs change, at which point the pinned hashes below would start
     * rejecting a download that had done nothing wrong.
     */
    const val REVISION = "cad454171d918d9873a2701ba245054b6c1760dd"

    private const val BASE =
        "https://huggingface.co/Xenova/distiluse-base-multilingual-cased-v2/resolve/$REVISION"

    /**
     * The `fp16` export, not `fp32` (539 MB) or `int8` (135 MB).
     *
     * fp32 doubles the download for accuracy nobody can perceive in a retrieval ranking.
     * int8 halves it again, and is worth revisiting — but quantisation error lands directly
     * on cosine similarity, and the measured margin between a correct paragraph and its
     * neighbour is already only a few hundredths (see `SimilarityCalibrationTest`). Not a
     * trade to make without re-running that calibration.
     */
    val model = Asset(
        url = "$BASE/onnx/model_fp16.onnx",
        sha256 = "3bab79112030135aa92ea795cb696a4254717934652390c56c3e094a9c142774",
        sizeBytes = 269_617_227L,
    )

    /**
     * The WordPiece vocabulary. 119,547 entries, and the reason this checkpoint was chosen
     * over the `e5` / `bge` family, which ship SentencePiece instead.
     *
     * Not optional and not a detail: the vocabulary defines the token ids the graph was
     * trained against. A mismatched vocab still produces plausible vectors that mean
     * nothing, so it is pinned as strictly as the weights.
     */
    val vocabulary = Asset(
        url = "$BASE/vocab.txt",
        sha256 = "fe0fda7c425b48c516fc8f160d594c8022a0808447475c1a7c6d6479763f310c",
        sizeBytes = 995_526L,
    )

    val all: List<Asset> = listOf(model, vocabulary)

    val totalBytes: Long = all.sumOf { it.sizeBytes }

    data class Asset(
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
    )
}
