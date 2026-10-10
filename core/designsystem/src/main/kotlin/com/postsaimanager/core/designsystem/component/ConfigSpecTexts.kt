package com.postsaimanager.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.postsaimanager.core.designsystem.R
import com.postsaimanager.core.model.ConfigSpec
import com.postsaimanager.core.model.isGpuBlockedByDriver

/**
 * Localized text for a [ConfigSpec] — the domain carries English fallbacks and stable keys;
 * the UI edge maps those keys to string resources. Unknown keys fall back to the domain text.
 */
@Composable
fun configSpecLabel(spec: ConfigSpec): String = when (spec.key) {
    "threads" -> stringResource(R.string.ds_cfg_threads)
    "contextTokens" -> stringResource(R.string.ds_cfg_context)
    "accelerator" -> stringResource(R.string.ds_cfg_accelerator)
    "temperature" -> stringResource(R.string.ds_cfg_temperature)
    "topK" -> stringResource(R.string.ds_cfg_top_k)
    "topP" -> stringResource(R.string.ds_cfg_top_p)
    "flashAttention" -> stringResource(R.string.ds_cfg_flash_attention)
    "thinkingEffort" -> stringResource(R.string.ds_cfg_thinking)
    else -> spec.label
}

/** The display text of one option of a [ConfigSpec.Choice] (the option value itself stays the stored key). */
@Composable
fun configOptionLabel(spec: ConfigSpec.Choice, option: String): String = when (spec.key) {
    "accelerator" -> when (option) {
        "CPU" -> stringResource(R.string.ds_cfg_accel_cpu)
        "GPU" -> stringResource(R.string.ds_cfg_accel_gpu)
        else -> option
    }
    "thinkingEffort" -> when (option) {
        "OFF" -> stringResource(R.string.ds_cfg_think_off)
        "LOW" -> stringResource(R.string.ds_cfg_think_low)
        "HIGH" -> stringResource(R.string.ds_cfg_think_high)
        else -> option
    }
    else -> option
}

/** The reason shown under a disabled option, localized for the two reasons the domain produces. */
@Composable
fun configDisabledReason(spec: ConfigSpec.Choice): String? {
    val reason = spec.disabledReason ?: return null
    return when {
        spec.isGpuBlockedByDriver -> stringResource(R.string.ds_cfg_gpu_blocked)
        reason == "Not available in this build" -> stringResource(R.string.ds_cfg_unavailable_build)
        else -> reason
    }
}
