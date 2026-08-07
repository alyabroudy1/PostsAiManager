package com.postsaimanager.core.ai.catalog

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.postsaimanager.core.model.DeviceCapability
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Measures what the device can currently afford.
 *
 * **Read this fresh at every decision point.** A snapshot taken at app start is stale by
 * the time the user taps Install, and stale again by the time the engine loads the file.
 * Measured on the project's test device, `MemTotal` was 11.3 GB while `MemAvailable` was
 * 2.5 GB — the gap is where OOM kills come from (task 7.10.6).
 */
@Singleton
class DeviceCapabilityChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun current(): DeviceCapability {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)

        return DeviceCapability(
            totalRamBytes = memoryInfo.totalMem,
            availableRamBytes = memoryInfo.availMem,
            freeStorageBytes = freeStorageBytes(),
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            isLowMemory = memoryInfo.lowMemory,
        )
    }

    /**
     * Free space in the app's own storage — models live in `filesDir`, not on shared
     * storage, so this is the number that matters rather than total disk free.
     */
    private fun freeStorageBytes(): Long =
        runCatching { StatFs(context.filesDir.absolutePath).availableBytes }
            .getOrDefault(0L)
}
