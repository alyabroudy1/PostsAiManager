package com.postsaimanager.core.data.gemma

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.Accelerator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The "Reading: CPU / GPU" setting: CPU by default, stored on the app's private preferences. */
class SharedPreferencesReadingAcceleratorSettingTest {

    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val stored = mutableMapOf<String, String>()
    private val preferences = mockk<SharedPreferences> {
        every { getString(any(), any()) } answers { stored[firstArg<String>()] ?: secondArg<String?>() }
        every { edit() } returns editor
    }
    private val context = mockk<Context> { every { getSharedPreferences("reading_accelerator", Context.MODE_PRIVATE) } returns preferences }

    @Test
    @DisplayName("reading is on the CPU until the GPU is chosen")
    fun `cpu by default`() = runBlocking {
        val setting = SharedPreferencesReadingAcceleratorSetting(context)

        assertThat(setting.current()).isEqualTo(Accelerator.CPU)
        assertThat(setting.accelerator.first()).isEqualTo(Accelerator.CPU)
    }

    @Test
    @DisplayName("choosing the GPU is stored and seen at once; a new instance starts from what was stored")
    fun `gpu is stored`() = runBlocking {
        every { editor.putString("reading_accelerator", any()) } answers { stored["reading_accelerator"] = secondArg(); editor }
        val setting = SharedPreferencesReadingAcceleratorSetting(context)

        setting.set(Accelerator.GPU)

        assertThat(setting.current()).isEqualTo(Accelerator.GPU)
        assertThat(SharedPreferencesReadingAcceleratorSetting(context).current()).isEqualTo(Accelerator.GPU)
    }
}
