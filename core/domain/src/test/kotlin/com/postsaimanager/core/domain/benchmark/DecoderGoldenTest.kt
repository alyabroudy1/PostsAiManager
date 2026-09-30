package com.postsaimanager.core.domain.benchmark

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The default decoder (per-slot argmax) gives, on the 16 recorded letters, exactly the readings it gave before decoders existed:
 * the golden file is the fingerprint of those readings under the shipped profile of the 0.8B model. `$DECODER_GOLDEN_OUT`
 * rewrites it (only ever on purpose: a change to it is a change of behaviour).
 */
class DecoderGoldenTest {

    private val docs = BenchmarkFixtures.load().docs
    private val recordings = Recordings.load(File("src/test/resources/benchmark/recordings"))
    private val golden = File("src/test/resources/benchmark/decoder-argmax.golden.txt")

    @Test
    fun `the default decoder reproduces the recorded readings`() {
        val now = DecoderGolden.fingerprint(docs, recordings, ModelProfiles.QWEN35_08B.scoring)
        System.getenv("DECODER_GOLDEN_OUT")?.let { File(it).writeText(now); return }
        assertThat(golden.exists()).isTrue()
        assertThat(now).isEqualTo(golden.readText())
    }
}
