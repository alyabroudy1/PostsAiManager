package com.postsaimanager.core.domain.setup

import com.postsaimanager.core.model.DeviceProfile

/** What this phone offers (memory, free storage, fast cores, 64-bit), read by the data layer. */
fun interface DeviceCapabilities {
    suspend fun current(): DeviceProfile
}
