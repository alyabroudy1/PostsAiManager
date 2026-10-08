package com.postsaimanager.core.domain.ai

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.TimingLog
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelLoadState
import kotlinx.coroutines.flow.Flow

/** Who the resident model is loaded for: a document reading, or the chat (and everything else that uses the chat's own config). */
enum class ModelUse { READING, CHAT }

/**
 * The "Reading: CPU / GPU" setting (Settings, Debug): which accelerator the chat model runs on while it READS documents, apart from the
 * accelerator the chat uses. CPU by default, which for the chat model is its own default, so by default no reload ever happens.
 */
interface ReadingAcceleratorSetting {
    val accelerator: Flow<Accelerator>

    suspend fun current(): Accelerator

    suspend fun set(accelerator: Accelerator)
}

/** What loading a model for a use does to the engine. */
sealed interface BackendPlan {

    /** The same model is resident on the wanted accelerator: nothing is reloaded (reading after reading, reply after reply). */
    data object Keep : BackendPlan

    /** The model is not resident (or another one is): a plain load. */
    data class Load(val to: Accelerator) : BackendPlan

    /** The model is resident on the other accelerator: the engine is reloaded (the weights are read again, the chat session is lost). */
    data class Reload(val from: Accelerator, val to: Accelerator) : BackendPlan
}

/**
 * The switching policy, pure over the engine's state. There is one model and one engine at a time (the one-model rule), so a backend
 * switch is a reload, and it is paid only when the wanted accelerator differs from the resident one. Consequences, by construction:
 * - readings load with the reading config, so a batch of queued readings on GPU is ONE reload, however long the queue is;
 * - nothing reloads the chat backend when the readings end: the chat's own next load does (lazy switch back), so no ping-pong;
 * - a chat reply loads the chat config, so a chat always wins its backend; a reading never loads while the chat is active, because the
 *   workers wait for [ChatActivityGate] before they read at all (R3 unifies those clocks).
 */
fun planBackend(state: ModelLoadState, modelPath: String, wanted: Accelerator): BackendPlan {
    val resident = (state as? ModelLoadState.Ready)?.takeIf { it.modelId == modelPath }?.config?.accelerator
        ?: return BackendPlan.Load(wanted)
    return if (resident == wanted) BackendPlan.Keep else BackendPlan.Reload(resident, wanted)
}

/**
 * Loads [path] for [use] with [config] (the reading config for a reading, the chat's for the chat) and logs every load that is not a
 * no-op to the timing log, so a trial shows how often the backend switched and how long it took.
 */
suspend fun ChatEngine.loadForUse(use: ModelUse, path: String, config: InferenceConfig): PamResult<AiCapabilities> {
    val plan = planBackend(state.value, path, config.accelerator)
    val started = System.nanoTime()
    val result = load(path, config)
    if (plan !is BackendPlan.Keep) {
        val ms = (System.nanoTime() - started) / NANOS_PER_MS
        val what = when (plan) {
            is BackendPlan.Reload -> "reload ${plan.from} -> ${plan.to}"
            is BackendPlan.Load -> "load on ${plan.to}"
            BackendPlan.Keep -> ""
        }
        TimingLog.log("backend: $what for $use took ${ms}ms (${if (result is PamResult.Success) "ok" else "failed"})")
    }
    return result
}

private const val NANOS_PER_MS = 1_000_000L
