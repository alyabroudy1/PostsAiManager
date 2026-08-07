package com.postsaimanager.core.config

import com.postsaimanager.core.model.AiModelDescriptor
import kotlinx.serialization.Serializable

/**
 * The signature envelope as served.
 *
 * [payload] is base64 of the exact JSON bytes that were signed. Verifying the raw bytes —
 * rather than re-serialising a parsed object — sidesteps JSON canonicalisation entirely:
 * key order, whitespace and number formatting cannot change what was verified.
 */
@Serializable
data class SignedManifest(
    val payload: String,
    val signature: String,
    val keyId: String,
    val algorithm: String = ManifestVerifier.ALGORITHM,
)

/**
 * The verified contents: everything that must be changeable without an app update.
 *
 * Unknown fields are ignored on decode, so a newer server can add fields without breaking
 * older clients. [schemaVersion] guards the reverse case — a breaking change bumps it and
 * old clients decline rather than misinterpret.
 */
@Serializable
data class ManifestPayload(
    val schemaVersion: Int,
    /** Epoch millis. Used for rollback protection — see [ManifestVerifier]. */
    val issuedAt: Long,
    val models: List<AiModelDescriptor> = emptyList(),
    val providers: List<ProviderDescriptor> = emptyList(),
) {
    companion object {
        /** Highest schema this client understands. */
        const val SUPPORTED_SCHEMA_VERSION = 1
    }
}

/**
 * An online AI provider, declared as data.
 *
 * Groq, NVIDIA Build, Ollama and most emerging free platforms speak an OpenAI-compatible
 * `/v1/chat/completions`, so adding one is a descriptor entry rather than new code. Only
 * genuinely different APIs (Gemini, Anthropic) need a bespoke adapter.
 *
 * Lives here — not in `:core:ai:online` — so the online module can stay free of any
 * dependency that would let it reach user data. See documentation/02-architecture.md §5.6.
 */
@Serializable
data class ProviderDescriptor(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val adapter: String = ADAPTER_OPENAI_COMPATIBLE,
    val authStyle: String = AUTH_BEARER,
    val models: List<String> = emptyList(),
    val requiresKey: Boolean = true,
    val freeTier: Boolean = false,
    val privacyPolicyUrl: String? = null,
    /** Host is user-supplied (self-hosted Ollama), so the descriptor cannot pin a URL. */
    val userSuppliedHost: Boolean = false,
) {
    companion object {
        const val ADAPTER_OPENAI_COMPATIBLE = "openai-compatible"
        const val ADAPTER_GEMINI = "gemini"
        const val AUTH_BEARER = "bearer"
        const val AUTH_HEADER = "header"
        const val AUTH_NONE = "none"
    }
}

/** Why a manifest was refused. Each maps to a distinct operational problem. */
sealed interface ManifestRejection {
    /** The signing key is not one this build trusts. */
    data class UnknownKey(val keyId: String) : ManifestRejection

    /** Signature did not verify — tampering, corruption, or the wrong key. */
    data object BadSignature : ManifestRejection

    /** Payload is not valid JSON, or not a manifest. */
    data class Malformed(val reason: String) : ManifestRejection

    /** Server speaks a schema this client predates. Decline rather than misread. */
    data class UnsupportedSchema(val found: Int, val supported: Int) : ManifestRejection

    /**
     * Older than what is already cached.
     *
     * Without this check, replaying an old *validly signed* manifest would be enough to
     * roll a client back to superseded model URLs — a downgrade attack that needs no key
     * compromise at all.
     */
    data class Stale(val issuedAt: Long, val cachedIssuedAt: Long) : ManifestRejection

    data class UnsupportedAlgorithm(val algorithm: String) : ManifestRejection
}
