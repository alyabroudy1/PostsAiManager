package com.postsaimanager.core.domain.benchmark

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.zones.DecoderSpec
import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The per-slot argmax decoder (the default of a [com.postsaimanager.core.domain.extraction.zones.ScoringProfile]) gives, on the
 * 16 recorded letters, exactly the readings it gave before decoders existed: the golden file is the fingerprint of those
 * readings under the 0.8B model's thresholds and cuts. `$DECODER_GOLDEN_OUT`
 * rewrites it (only ever on purpose: a change to it is a change of behaviour).
 */
class DecoderGoldenTest {

    private val docs = BenchmarkFixtures.load().docs
    private val recordings = Recordings.load(File("src/test/resources/benchmark/recordings"))
    private val golden = File("src/test/resources/benchmark/decoder-argmax.golden.txt")

    @Test
    fun `the default decoder reproduces the recorded readings`() {
        val now = DecoderGolden.fingerprint(docs, recordings, ModelProfiles.QWEN35_08B.scoring.copy(decoder = DecoderSpec()))
        System.getenv("DECODER_GOLDEN_OUT")?.let { File(it).writeText(now); return }
        assertThat(golden.exists()).isTrue()
        assertThat(now).isEqualTo(golden.readText())
    }
}
