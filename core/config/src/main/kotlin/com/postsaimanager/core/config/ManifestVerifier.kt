package com.postsaimanager.core.config

import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verifies the remote configuration manifest before any of it is trusted.
 *
 * The manifest names model download URLs, which makes it a supply-chain surface: a
 * compromised CDN that could rewrite it could point the app at a malicious GGUF. HTTPS
 * alone does not cover that — it protects the transport, not the origin. Two independent
 * checks are used instead:
 *
 * 1. **This signature** — proves the manifest came from the project's signing key.
 * 2. **SHA-256 on the downloaded artefact**, with the hash taken from this signed
 *    manifest — proves the bytes match what was signed for.
 *
 * ### Why ECDSA P-256 rather than Ed25519
 *
 * The plan specified Ed25519. `java.security.Signature` only supports it from **API 33**,
 * and `minSdk` here is 26, so it would mean bundling BouncyCastle or Tink — several MB of
 * dependency for a single signature check. `SHA256withECDSA` over P-256 is available from
 * API 23, needs no dependency, and is equally suitable for signing a config blob.
 * Recorded as a deliberate deviation, not an oversight.
 */
@Singleton
class ManifestVerifier @Inject constructor(
    private val trustedKeys: TrustedKeys,
    private val json: Json,
) {

    /**
     * Verifies and decodes a manifest.
     *
     * @param cachedIssuedAt `issuedAt` of the manifest already held, for rollback
     *   protection. Pass `null` when there is nothing cached.
     */
    fun verify(
        signed: SignedManifest,
        cachedIssuedAt: Long? = null,
    ): ManifestResult {
        if (signed.algorithm != ALGORITHM) {
            return ManifestResult.Rejected(
                ManifestRejection.UnsupportedAlgorithm(signed.algorithm),
            )
        }

        val publicKey = trustedKeys.publicKeyFor(signed.keyId)
            ?: return ManifestResult.Rejected(ManifestRejection.UnknownKey(signed.keyId))

        val payloadBytes = decodeBase64(signed.payload)
            ?: return ManifestResult.Rejected(
                ManifestRejection.Malformed("payload is not valid base64"),
            )
        val signatureBytes = decodeBase64(signed.signature)
            ?: return ManifestResult.Rejected(
                ManifestRejection.Malformed("signature is not valid base64"),
            )

        // Verify the exact bytes that were signed, before parsing them. Parsing first and
        // re-serialising would make the check depend on JSON canonicalisation.
        val signatureValid = runCatching {
            Signature.getInstance(ALGORITHM).apply {
                initVerify(publicKey)
                update(payloadBytes)
            }.verify(signatureBytes)
        }.getOrDefault(false)

        if (!signatureValid) {
            return ManifestResult.Rejected(ManifestRejection.BadSignature)
        }

        val payload = runCatching {
            json.decodeFromString<ManifestPayload>(payloadBytes.decodeToString())
        }.getOrElse {
            return ManifestResult.Rejected(
                ManifestRejection.Malformed(it.message ?: "payload is not a manifest"),
            )
        }

        if (payload.schemaVersion > ManifestPayload.SUPPORTED_SCHEMA_VERSION) {
            return ManifestResult.Rejected(
                ManifestRejection.UnsupportedSchema(
                    found = payload.schemaVersion,
                    supported = ManifestPayload.SUPPORTED_SCHEMA_VERSION,
                ),
            )
        }

        // Rollback protection: a validly signed but superseded manifest must not replace a
        // newer one. Equal timestamps are accepted so a re-fetch of the same manifest is
        // not treated as an attack.
        if (cachedIssuedAt != null && payload.issuedAt < cachedIssuedAt) {
            return ManifestResult.Rejected(
                ManifestRejection.Stale(payload.issuedAt, cachedIssuedAt),
            )
        }

        return ManifestResult.Verified(payload)
    }

    private fun decodeBase64(value: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(value) }.getOrNull()

    companion object {
        const val ALGORITHM = "SHA256withECDSA"
        const val KEY_ALGORITHM = "EC"
    }
}

sealed interface ManifestResult {
    data class Verified(val payload: ManifestPayload) : ManifestResult
    data class Rejected(val reason: ManifestRejection) : ManifestResult
}

/**
 * Public keys this build trusts, pinned at compile time.
 *
 * Keyed by id so a key can be rotated: ship the new key alongside the old, switch the
 * server to sign with the new one, then drop the old in a later release. Without the id
 * indirection, rotation requires every client to update simultaneously.
 */
@Singleton
class TrustedKeys @Inject constructor() {

    /**
     * Base64 X.509 SubjectPublicKeyInfo, by key id.
     *
     * **Not yet populated** — the signing key does not exist. Until it does,
     * [publicKeyFor] returns null and every remote manifest is rejected as
     * [ManifestRejection.UnknownKey], leaving the app on its bundled catalog. That is the
     * correct failure mode: no key, no remote trust. Tracked as task 7.4.6.
     */
    private val keys: Map<String, String> = emptyMap()

    private val cache = mutableMapOf<String, PublicKey>()

    fun publicKeyFor(keyId: String): PublicKey? {
        cache[keyId]?.let { return it }
        val encoded = keys[keyId] ?: return null
        return runCatching {
            val spec = X509EncodedKeySpec(Base64.getDecoder().decode(encoded))
            KeyFactory.getInstance(ManifestVerifier.KEY_ALGORITHM).generatePublic(spec)
        }.getOrNull()?.also { cache[keyId] = it }
    }

    /** Test seam — lets a suite install a generated key without shipping one. */
    fun trustForTesting(keyId: String, publicKey: PublicKey) {
        cache[keyId] = publicKey
    }
}
