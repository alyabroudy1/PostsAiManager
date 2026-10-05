package com.postsaimanager.core.data.worker

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reprocess requests' constraints on a real API level: WorkManager's builder only records the
 * device-idle requirement on API 23+, which a JVM unit test cannot see.
 */
@RunWith(AndroidJUnit4::class)
class ReprocessConstraintsTest {

    @Test
    fun chargingOrIdle_eachWithBatteryNotLow() {
        val (charging, idle) = ReprocessDocumentWorker.requests("d1").map { it.second.workSpec.constraints }

        assertTrue(charging.requiresCharging())
        assertFalse(charging.requiresDeviceIdle())
        assertTrue(charging.requiresBatteryNotLow())

        assertTrue(idle.requiresDeviceIdle())
        assertFalse(idle.requiresCharging())
        assertTrue(idle.requiresBatteryNotLow())
    }
}
