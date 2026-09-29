package com.postsaimanager.core.domain.applock

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.testing.FakeMonotonicClock
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** The lock's rules, driven by a fake clock — see [AppLockState] for the contract. */
class AppLockStateTest {

    private val clock = FakeMonotonicClock()
    private val lock = AppLockState(clock)

    private val locked get() = lock.snapshot.value.locked
    private val secure get() = lock.snapshot.value.secureWindow

    private fun awayFor(minutes: Long) {
        lock.onBackgrounded()
        clock.advanceMinutes(minutes)
        lock.onForegrounded()
    }

    @Nested
    @DisplayName("cold start")
    inner class ColdStart {

        @Test
        fun `is locked and secure until the settings are known, so nothing flashes`() {
            assertThat(locked).isTrue()
            assertThat(secure).isTrue()
            assertThat(lock.snapshot.value.settingsKnown).isFalse()
        }

        @Test
        fun `stays locked when the lock is enabled`() {
            lock.applySettings(enabled = true, timeoutMinutes = 1)

            assertThat(locked).isTrue()
            assertThat(secure).isTrue()
        }

        @Test
        fun `opens straight away and drops the secure flag when the lock is disabled`() {
            lock.applySettings(enabled = false, timeoutMinutes = 1)

            assertThat(locked).isFalse()
            assertThat(secure).isFalse()
        }

        @Test
        fun `an unlock before the settings arrive is ignored rather than lost to a later lock`() {
            lock.unlock()
            assertThat(locked).isTrue()
        }
    }

    @Nested
    @DisplayName("returning from the background")
    inner class Background {

        @Test
        fun `does not lock when back before the timeout`() {
            lock.applySettings(true, 5)
            lock.unlock()

            awayFor(4)

            assertThat(locked).isFalse()
        }

        @Test
        fun `locks once the timeout has elapsed`() {
            lock.applySettings(true, 5)
            lock.unlock()

            awayFor(5)

            assertThat(locked).isTrue()
        }

        @Test
        fun `a timeout of zero locks on every return`() {
            lock.applySettings(true, 0)
            lock.unlock()

            awayFor(0)

            assertThat(locked).isTrue()
        }

        @Test
        fun `uses the timeout as configured, including a later change`() {
            lock.applySettings(true, 1)
            lock.unlock()
            lock.applySettings(true, 15)

            awayFor(14)
            assertThat(locked).isFalse()

            awayFor(15)
            assertThat(locked).isTrue()
        }

        @Test
        fun `stays locked once locked, however the app is moved around`() {
            lock.applySettings(true, 1)
            awayFor(0)
            awayFor(30)

            assertThat(locked).isTrue()
        }

        @Test
        fun `never locks when the lock is disabled`() {
            lock.applySettings(false, 0)

            awayFor(600)

            assertThat(locked).isFalse()
        }

        @Test
        fun `is unlocked again by a successful authentication`() {
            lock.applySettings(true, 0)
            awayFor(1)
            assertThat(locked).isTrue()

            lock.unlock()

            assertThat(locked).isFalse()
        }

        @Test
        fun `counts from the first background, so a repeated stop event cannot extend the grace period`() {
            lock.applySettings(true, 5)
            lock.unlock()

            lock.onBackgrounded()
            clock.advanceMinutes(3)
            lock.onBackgrounded()
            clock.advanceMinutes(3)
            lock.onForegrounded()

            assertThat(locked).isTrue()
        }
    }

    @Nested
    @DisplayName("configuration changes are not backgrounding")
    inner class ConfigurationChange {

        // Rotation recreates the activity but ProcessLifecycleOwner reports no stop, so the
        // state machine only ever sees a foreground with no preceding background.

        @Test
        fun `a foreground with no background never locks, however long the app has run`() {
            lock.applySettings(true, 0)
            lock.unlock()
            clock.advanceMinutes(120)

            lock.onForegrounded()

            assertThat(locked).isFalse()
        }

        @Test
        fun `a background that is undone at once does not lock a nonzero timeout`() {
            lock.applySettings(true, 1)
            lock.unlock()

            lock.onBackgrounded()
            lock.onForegrounded()

            assertThat(locked).isFalse()
        }

        @Test
        fun `a return is judged once, the next one starts a fresh clock`() {
            lock.applySettings(true, 1)
            lock.unlock()
            awayFor(1)
            lock.unlock()

            lock.onForegrounded()

            assertThat(locked).isFalse()
        }
    }

    @Nested
    @DisplayName("toggling in Settings")
    inner class Toggling {

        @Test
        fun `enabling while running does not lock the user out of the screen they are on`() {
            lock.applySettings(false, 1)

            lock.applySettings(true, 1)

            assertThat(locked).isFalse()
            assertThat(secure).isTrue()
        }

        @Test
        fun `enabling then leaving locks after the timeout`() {
            lock.applySettings(false, 1)
            lock.applySettings(true, 1)

            awayFor(1)

            assertThat(locked).isTrue()
        }

        @Test
        fun `disabling unlocks and drops the secure flag`() {
            lock.applySettings(true, 1)
            awayFor(1)
            assertThat(locked).isTrue()

            lock.applySettings(false, 1)

            assertThat(locked).isFalse()
            assertThat(secure).isFalse()
        }

        @Test
        fun `changing only the timeout does not touch the lock`() {
            lock.applySettings(true, 1)
            assertThat(locked).isTrue()
            lock.applySettings(true, 5)
            assertThat(locked).isTrue()

            lock.unlock()
            lock.applySettings(true, 15)
            assertThat(locked).isFalse()
        }
    }
}
