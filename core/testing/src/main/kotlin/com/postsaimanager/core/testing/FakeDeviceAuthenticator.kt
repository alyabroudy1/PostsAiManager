package com.postsaimanager.core.testing

import com.postsaimanager.core.domain.applock.DeviceAuthAvailability
import com.postsaimanager.core.domain.applock.DeviceAuthPurpose
import com.postsaimanager.core.domain.applock.DeviceAuthResult
import com.postsaimanager.core.domain.applock.DeviceAuthenticator
import com.postsaimanager.core.domain.applock.MonotonicClock

/** Scriptable [DeviceAuthenticator]: set [availability] and [result], read [prompts]. */
class FakeDeviceAuthenticator(
    var availability: DeviceAuthAvailability = DeviceAuthAvailability.AVAILABLE,
    var result: DeviceAuthResult = DeviceAuthResult.Success,
) : DeviceAuthenticator {

    val prompts = mutableListOf<DeviceAuthPurpose>()

    override fun availability(): DeviceAuthAvailability = availability

    override suspend fun authenticate(purpose: DeviceAuthPurpose): DeviceAuthResult {
        prompts += purpose
        return result
    }
}

/** A [MonotonicClock] the test advances by hand. */
class FakeMonotonicClock(var nowMillis: Long = 0L) : MonotonicClock {
    override fun elapsedMillis(): Long = nowMillis

    fun advanceMinutes(minutes: Long) {
        nowMillis += minutes * 60_000L
    }
}
