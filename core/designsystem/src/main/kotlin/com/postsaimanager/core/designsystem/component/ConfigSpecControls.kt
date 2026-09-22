package com.postsaimanager.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.model.ConfigSpec
import com.postsaimanager.core.model.InferenceOverrides
import com.postsaimanager.core.model.ReloadScope
import com.postsaimanager.core.model.effectiveValue

/**
 * Renders one [ConfigSpec] with the control its type calls for — one composable per type, so
 * a new [ConfigSpec] variant fails to compile here rather than silently not rendering.
 *
 * Shared between `feature:settings` (the "On-device AI" section) and `feature:chat` (the
 * chat header's model sheet) — both render the *same* schema the domain layer hands them,
 * and duplicating this per feature would let the two drift on what a slider step or a
 * disabled choice looks like. Lives in `:core:designsystem` rather than either feature
 * because neither may depend on the other (architecture rule 1).
 */
@Composable
fun ConfigSpecItem(
    spec: ConfigSpec,
    overrides: InferenceOverrides,
    onValueChange: (Any) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (spec) {
        is ConfigSpec.Slider -> SliderSpecItem(spec, spec.effectiveValue(overrides), onValueChange, modifier)
        is ConfigSpec.Switch -> SwitchSpecItem(spec, spec.effectiveValue(overrides), onValueChange, modifier)
        is ConfigSpec.Choice -> ChoiceSpecItem(spec, spec.effectiveValue(overrides), onValueChange, modifier)
    }
}

@Composable
fun SliderSpecItem(
    spec: ConfigSpec.Slider,
    value: Float,
    onValueChange: (Any) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = spec.label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatSliderValue(value, spec.step),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ReloadHint(spec.reloadScope)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = spec.min..spec.max,
            steps = stepCountFor(spec),
        )
    }
}

@Composable
fun SwitchSpecItem(
    spec: ConfigSpec.Switch,
    value: Boolean,
    onValueChange: (Any) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onValueChange(!value) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = spec.label, style = MaterialTheme.typography.bodyLarge)
            ReloadHint(spec.reloadScope)
        }
        Switch(checked = value, onCheckedChange = onValueChange)
    }
}

@Composable
fun ChoiceSpecItem(
    spec: ConfigSpec.Choice,
    value: String,
    onValueChange: (Any) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showDialog = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = spec.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ReloadHint(spec.reloadScope, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
    }

    if (showDialog) {
        ConfigChoiceDialog(
            title = spec.label,
            options = spec.options,
            selected = value,
            disabledOptions = spec.disabledOptions,
            disabledReason = spec.disabledReason,
            onSelect = { option ->
                onValueChange(option)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

/**
 * A subtle hint shown under any control whose change only takes effect on the next model
 * load — see [ConfigSpec.reloadScope].
 */
@Composable
fun ReloadHint(reloadScope: ReloadScope, modifier: Modifier = Modifier) {
    if (reloadScope == ReloadScope.NONE) return
    Text(
        text = "Applies on next model load",
        style = MaterialTheme.typography.labelSmall,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 4.dp),
    )
}

/**
 * A single-choice dialog that still shows [disabledOptions] — greyed out with
 * [disabledReason] underneath — rather than hiding them, so the user learns *why* an option
 * such as GPU is off instead of wondering why it is missing.
 */
@Composable
private fun ConfigChoiceDialog(
    title: String,
    options: List<String>,
    selected: String,
    disabledOptions: Set<String>,
    disabledReason: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    val disabled = option in disabledOptions
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !disabled) { onSelect(option) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == selected,
                            onClick = { onSelect(option) },
                            enabled = !disabled,
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                text = option,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (disabled) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            if (disabled && disabledReason != null) {
                                Text(
                                    text = disabledReason,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun formatSliderValue(value: Float, step: Float): String =
    if (step >= 1f) value.toInt().toString() else "%.2f".format(value)

private fun stepCountFor(spec: ConfigSpec.Slider): Int {
    if (spec.step <= 0f) return 0
    val intervals = ((spec.max - spec.min) / spec.step).toInt()
    return (intervals - 1).coerceAtLeast(0)
}
