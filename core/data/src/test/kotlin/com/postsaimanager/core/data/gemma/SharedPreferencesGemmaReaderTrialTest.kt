package com.postsaimanager.core.data.gemma

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The trial's switch: off by default (debug and release alike), kept on the app's private preferences, one-off requests are spent. */
class SharedPreferencesGemmaReaderTrialTest {

    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val stored = mutableMapOf<String, Boolean>()
    private val preferences = mockk<SharedPreferences> {
        every { getBoolean(any(), any()) } answers { stored[firstArg<String>()] ?: secondArg<Boolean>() }
        every { edit() } returns editor
    }
    private val context = mockk<Context> { every { getSharedPreferences("gemma_reader_trial", Context.MODE_PRIVATE) } returns preferences }

    @Test
    @DisplayName("the switch is off until it is turned on")
    fun `off by default`() = runBlocking {
        val trial = SharedPreferencesGemmaReaderTrial(context)

        assertThat(trial.isEnabled()).isFalse()
        assertThat(trial.enabled.first()).isFalse()
    }

    @Test
    @DisplayName("turning it on is stored and seen at once; a new instance starts from what was stored")
    fun `on is stored`() = runBlocking {
        every { editor.putBoolean("enabled", true) } answers { stored["enabled"] = true; editor }
        val trial = SharedPreferencesGemmaReaderTrial(context)

        trial.setEnabled(true)

        assertThat(trial.isEnabled()).isTrue()
        assertThat(trial.enabled.first()).isTrue()
        verify { editor.putBoolean("enabled", true) }
        assertThat(SharedPreferencesGemmaReaderTrial(context).isEnabled()).isTrue()
    }

    @Test
    @DisplayName("preferences that cannot be read leave the trial off")
    fun `unreadable is off`() = runBlocking {
        every { context.getSharedPreferences(any(), any()) } throws IllegalStateException("storage unavailable")

        assertThat(SharedPreferencesGemmaReaderTrial(context).isEnabled()).isFalse()
    }

    @Test
    @DisplayName("a one-off request is for one document and is spent by the first reading that takes it")
    fun `requests are spent`() {
        val trial = SharedPreferencesGemmaReaderTrial(context)

        trial.requestOnce("d1")

        assertThat(trial.takeRequest("d2")).isFalse()
        assertThat(trial.takeRequest("d1")).isTrue()
        assertThat(trial.takeRequest("d1")).isFalse()
    }
}
