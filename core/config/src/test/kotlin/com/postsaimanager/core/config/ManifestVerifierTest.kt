package com.postsaimanager.core.config

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Security tests for [ManifestVerifier].
 *
 * A real P-256 keypair is generated per test and used to sign genuine payloads, so the
 * cryptography is actually exercised rather than mocked. The manifest names model download
 * URLs — getting this wrong means shipping a path to arbitrary native binaries.
 */
class ManifestVerifierTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var keyPair: KeyPair
    private lateinit var trustedKeys: TrustedKeys
    private lateinit var verifier: ManifestVerifier

    private val keyId = "pam-2026"

    @BeforeEach
    fun setUp() {
        keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        trustedKeys = TrustedKeys().apply { trustForTesting(keyId, keyPair.public) }
        verifier = ManifestVerifier(trustedKeys, json)
    }

    private fun sign(bytes: ByteArray, key: PrivateKey = keyPair.private): String =
        Base64.getEncoder().encodeToString(
            Signature.getInstance(ManifestVerifier.ALGORITHM).apply {
                initSign(key)
                update(bytes)
            }.sign(),
        )

    private fun manifest(
        schemaVersion: Int = 1,
        issuedAt: Long = 1_000L,
        keyId: String = this.keyId,
        tamperPayload: Boolean = false,
        signWith: PrivateKey = keyPair.private,
    ): SignedManifest {
        val payloadJson = """
            {"schemaVersion":$schemaVersion,"issuedAt":$issuedAt,
             "models":[{"id":"qwen-1_5b","name":"Qwen 1.5B","family":"Qwen",
             "parameterCount":"1.5B","quantization":"Q4_K_M","sizeBytes":1100000000,
             "minAvailableRamBytes":2000000000,"contextTokens":32768,"license":"Apache-2.0",
             "downloadUrl":"https://models.example/qwen.gguf","sha256":"${"a".repeat(64)}"}],
             "providers":[]}
        """.trimIndent()

        val signedBytes = payloadJson.toByteArray()
        val signature = sign(signedBytes, signWith)

        // Tamper *after* signing — exactly what a compromised CDN would do.
        val servedBytes = if (tamperPayload) {
            payloadJson.replace("https://models.example/qwen.gguf", "https://evil.invalid/x.gguf")
                .toByteArray()
        } else {
            signedBytes
        }

        return SignedManifest(
            payload = Base64.getEncoder().encodeToString(servedBytes),
            signature = signature,
            keyId = keyId,
        )
    }

    @Nested
    @DisplayName("Accepts")
    inner class Accepts {

        @Test
        fun `a correctly signed manifest`() {
            val result = verifier.verify(manifest())

            assertThat(result).isInstanceOf(ManifestResult.Verified::class.java)
            val payload = (result as ManifestResult.Verified).payload
            assertThat(payload.models).hasSize(1)
            assertThat(payload.models.first().isInstallable).isTrue()
        }

        @Test
        fun `a re-fetch of the same manifest — equal timestamps are not a rollback`() {
            val result = verifier.verify(manifest(issuedAt = 5_000L), cachedIssuedAt = 5_000L)
            assertThat(result).isInstanceOf(ManifestResult.Verified::class.java)
        }

        @Test
        fun `unknown fields, so a newer server does not break older clients`() {
            val payloadJson =
                """{"schemaVersion":1,"issuedAt":1,"models":[],"providers":[],"futureField":"x"}"""
            val signed = SignedManifest(
                payload = Base64.getEncoder().encodeToString(payloadJson.toByteArray()),
                signature = sign(payloadJson.toByteArray()),
                keyId = keyId,
            )

            assertThat(verifier.verify(signed)).isInstanceOf(ManifestResult.Verified::class.java)
        }
    }

    @Nested
    @DisplayName("Rejects")
    inner class Rejects {

        @Test
        @DisplayName("a payload modified after signing — the CDN-compromise case")
        fun `tampered payload`() {
            val result = verifier.verify(manifest(tamperPayload = true))

            assertThat(result).isEqualTo(ManifestResult.Rejected(ManifestRejection.BadSignature))
        }

        @Test
        fun `a manifest signed by a different key`() {
            val attacker = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()

            val result = verifier.verify(manifest(signWith = attacker.private))

            assertThat(result).isEqualTo(ManifestResult.Rejected(ManifestRejection.BadSignature))
        }

        @Test
        fun `an unknown key id`() {
            val result = verifier.verify(manifest(keyId = "not-our-key"))

            assertThat(result).isEqualTo(
                ManifestResult.Rejected(ManifestRejection.UnknownKey("not-our-key")),
            )
        }

        @Test
        @DisplayName("a replayed older manifest — downgrade attack needing no key compromise")
        fun `stale manifest`() {
            val result = verifier.verify(manifest(issuedAt = 1_000L), cachedIssuedAt = 9_000L)

            assertThat(result).isEqualTo(
                ManifestResult.Rejected(ManifestRejection.Stale(1_000L, 9_000L)),
            )
        }

        @Test
        fun `a schema version this client predates`() {
            val result = verifier.verify(manifest(schemaVersion = 99))

            assertThat(result).isEqualTo(
                ManifestResult.Rejected(ManifestRejection.UnsupportedSchema(99, 1)),
            )
        }

        @Test
        fun `an unexpected signature algorithm`() {
            val result = verifier.verify(manifest().copy(algorithm = "NONE"))

            assertThat(result).isEqualTo(
                ManifestResult.Rejected(ManifestRejection.UnsupportedAlgorithm("NONE")),
            )
        }

        @Test
        fun `malformed base64`() {
            val result = verifier.verify(manifest().copy(payload = "!!!not-base64!!!"))
            assertThat(result).isInstanceOf(ManifestResult.Rejected::class.java)
        }

        @Test
        fun `a valid signature over JSON that is not a manifest`() {
            val notAManifest = """{"hello":"world"}"""
            val signed = SignedManifest(
                payload = Base64.getEncoder().encodeToString(notAManifest.toByteArray()),
                signature = sign(notAManifest.toByteArray()),
                keyId = keyId,
            )

            val result = verifier.verify(signed)
            assertThat((result as ManifestResult.Rejected).reason)
                .isInstanceOf(ManifestRejection.Malformed::class.java)
        }
    }

    @Nested
    @DisplayName("Default trust posture")
    inner class DefaultTrust {

        @Test
        @DisplayName("with no key shipped, every remote manifest is refused")
        fun `empty TrustedKeys rejects everything`() {
            // The signing key does not exist yet (task 7.4.6). Until it does, the app must
            // fall back to its bundled catalog rather than trust anything from the network.
            val emptyVerifier = ManifestVerifier(TrustedKeys(), json)

            val result = emptyVerifier.verify(manifest())

            assertThat((result as ManifestResult.Rejected).reason)
                .isInstanceOf(ManifestRejection.UnknownKey::class.java)
        }
    }
}
