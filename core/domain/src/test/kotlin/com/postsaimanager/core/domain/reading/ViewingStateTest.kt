package com.postsaimanager.core.domain.reading

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ViewingStateTest {

    private val state = ViewingState()

    @Test
    fun `a letter is being viewed only while the app is in front on that letter's screen`() {
        state.documentOpened("a")
        assertThat(state.isViewing("a")).isFalse() // the app is in the background

        state.setAppInForeground(true)
        assertThat(state.isViewing("a")).isTrue()
        assertThat(state.isViewing("b")).isFalse()

        state.setAppInForeground(false)
        assertThat(state.isViewing("a")).isFalse()
    }

    @Test
    fun `leaving the screen ends the viewing, and nested screens of one letter are balanced`() {
        state.setAppInForeground(true)
        state.documentOpened("a")
        state.documentOpened("a")
        state.documentClosed("a")
        assertThat(state.isViewing("a")).isTrue()
        state.documentClosed("a")
        assertThat(state.isViewing("a")).isFalse()
        // An unbalanced close never makes a later open invisible.
        state.documentClosed("a")
        state.documentOpened("a")
        assertThat(state.isViewing("a")).isTrue()
    }
}
