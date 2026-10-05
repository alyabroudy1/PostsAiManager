package com.postsaimanager.core.designsystem.component

import android.text.format.Formatter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.postsaimanager.core.designsystem.R
import com.postsaimanager.core.model.ChatModelFit
import com.postsaimanager.core.model.NotRecommendedReason
import com.postsaimanager.core.model.SpeedHint
import java.util.Locale

/**
 * The line that tells how a chat model suits this phone: "Recommended for this phone", or "Not recommended: " and the reason. A model
 * that merely works (suitable) gets no line. Shared by first-run setup and the Models screen, so both say the same thing.
 */
@Composable
fun ChatModelFitBadge(fit: ChatModelFit, modifier: Modifier = Modifier) {
    when (fit) {
        ChatModelFit.Recommended -> Text(
            text = stringResource(R.string.model_fit_recommended),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
        ChatModelFit.Suitable -> Unit
        is ChatModelFit.NotRecommended -> Text(
            text = notRecommendedText(fit),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
    }
}

/** The speed note of a model that answers slower than the reader (nothing for a normal-speed one). From the catalog's data. */
@Composable
fun ModelSpeedHint(hint: SpeedHint, modifier: Modifier = Modifier) {
    val text = when (hint) {
        SpeedHint.NORMAL -> return
        SpeedHint.SLOWER -> stringResource(R.string.model_speed_slower)
        SpeedHint.MUCH_SLOWER -> stringResource(R.string.model_speed_much_slower)
    }
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun notRecommendedText(fit: ChatModelFit.NotRecommended): String = when (fit.reason) {
    NotRecommendedReason.MEMORY -> stringResource(R.string.model_fit_not_memory, formatGb(fit.requiredRamGb ?: 0.0))
    NotRecommendedReason.STORAGE ->
        stringResource(R.string.model_fit_not_storage, Formatter.formatShortFileSize(LocalContext.current, fit.requiredStorageBytes ?: 0L))
    NotRecommendedReason.UNSUPPORTED_32_BIT -> stringResource(R.string.model_fit_not_32bit)
}

/** 8.0 reads "8", 4.5 reads "4.5". */
private fun formatGb(gb: Double): String =
    if (gb % 1.0 == 0.0) gb.toInt().toString() else String.format(Locale.getDefault(), "%.1f", gb)
