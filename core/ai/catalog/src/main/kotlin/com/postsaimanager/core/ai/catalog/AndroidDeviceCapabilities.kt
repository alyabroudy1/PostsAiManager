package com.postsaimanager.core.ai.catalog

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.domain.setup.DeviceCapabilities
import com.postsaimanager.core.model.DeviceProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * [DeviceCapabilities] from the system: total memory from [ActivityManager.MemoryInfo], free space where the models live (the app's
 * files directory), the fast cores from the CPU frequencies ([CpuTopology]) and the 64-bit ABIs.
 */
class AndroidDeviceCapabilities @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cpuTopology: CpuTopology,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : DeviceCapabilities {

    override suspend fun current(): DeviceProfile {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        val frequencies = cpuTopology.coreMaxFreqsKHz(ioDispatcher)
        return DeviceProfile(
            totalRamGb = marketedRamGb(memory.totalMem / BYTES_PER_GB),
            availableStorageBytes = withContext(ioDispatcher) {
                runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
            },
            bigCoreCount = bigCoreCount(frequencies, Runtime.getRuntime().availableProcessors()),
            is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty(),
        )
    }

    internal companion object {
        private const val BYTES_PER_GB = 1_000_000_000.0

        /** The RAM sizes phones are sold with, in GB. */
        private val MARKETED_RAM_GB = listOf(4.0, 6.0, 8.0, 12.0, 16.0, 24.0)

        /**
         * Android reports less than the RAM a phone is sold with (an 8 GB phone shows about 7.4 GB): [reportedGb] rounded up to the next
         * standard size, which is what a model's requirement (Google's figure) is written against. Above the largest size it is unchanged.
         */
        internal fun marketedRamGb(reportedGb: Double): Double = MARKETED_RAM_GB.firstOrNull { reportedGb <= it } ?: reportedGb

        /** A core is "big" when its top frequency is within this share of the fastest core's. */
        private const val BIG_CORE_SHARE = 0.75

        /**
         * Cores in the fast clusters: those whose maximum frequency is at least [BIG_CORE_SHARE] of the fastest. When the frequencies
         * could not be read (empty), [processorCount] stands in.
         */
        internal fun bigCoreCount(maxFrequenciesKHz: List<Long>, processorCount: Int): Int {
            val fastest = maxFrequenciesKHz.maxOrNull() ?: return processorCount
            return maxFrequenciesKHz.count { it >= fastest * BIG_CORE_SHARE }
        }
    }
}
