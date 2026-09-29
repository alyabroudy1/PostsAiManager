package com.postsaimanager.core.ai.catalog

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Each CPU core's max frequency, read from sysfs once per process, off the caller's thread.
 *
 * The cpufreq files (`/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq`, kHz) never
 * change while the process runs and are often unreadable for apps, so a single cached read
 * is enough. Unreadable entries are skipped; an empty or uniform result means "unknown" to
 * `InferenceConfig.performanceCoreThreadCount`, which falls back safely.
 */
@Singleton
class CpuTopology @Inject constructor() {
    private val mutex = Mutex()
    private var cached: List<Long>? = null

    suspend fun coreMaxFreqsKHz(dispatcher: CoroutineDispatcher = Dispatchers.IO): List<Long> {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: withContext(dispatcher) { read() }.also { cached = it }
        }
    }

    private fun read(): List<Long> {
        val cpuDirs = File("/sys/devices/system/cpu")
            .listFiles { file -> file.name.matches(CPU_DIR_REGEX) } ?: return emptyList()
        return cpuDirs.mapNotNull { dir ->
            File(dir, "cpufreq/cpuinfo_max_freq").takeIf { it.canRead() }
                ?.let { runCatching { it.readText().trim().toLong() }.getOrNull() }
        }
    }

    private companion object {
        val CPU_DIR_REGEX = Regex("cpu[0-9]+")
    }
}
