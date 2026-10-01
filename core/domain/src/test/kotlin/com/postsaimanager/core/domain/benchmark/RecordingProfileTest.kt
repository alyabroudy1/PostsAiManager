package com.postsaimanager.core.domain.benchmark

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
import com.postsaimanager.core.domain.extraction.zones.QuestionNames
import com.postsaimanager.core.domain.extraction.zones.ScoringDescriptions
import com.postsaimanager.core.domain.extraction.zones.ScoringProfile
import org.junit.jupiter.api.Test

/** What a device run records with: the shipped profile minus the thresholds nothing else depends on, keeping the optional people's. (File name is a leftover; the class is [RecordingProfileTest].) */
class RecordingProfileTest {

    private val shipped = ModelProfiles.QWEN35_08B.scoring
    private val recording = ModelProfiles.recordingProfile(shipped)

    @Test
    fun `the thresholds of the optional people stay, the others are dropped`() {
        assertThat(recording.thresholds.keys).containsExactly(QuestionNames.CONTACT, QuestionNames.CARE_OF, QuestionNames.SUBJECT_PERSON)
        for (k in recording.thresholds.keys) assertThat(recording.thresholds[k]).isEqualTo(shipped.thresholds[k])
        assertThat(recording.threshold(ScoringProfile.FAMILY)).isEqualTo(shipped.defaultThreshold)
        assertThat(recording.threshold(ScoringDescriptions.EXTRAS_ASK)).isEqualTo(shipped.defaultThreshold)
    }

    @Test
    fun `the decoder and the confidence cuts are the shipped ones`() {
        assertThat(recording.decoder).isEqualTo(shipped.decoder)
        assertThat(recording.cuts).isEqualTo(shipped.cuts)
    }
}
