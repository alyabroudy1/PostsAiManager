package com.postsaimanager.feature.setup

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.model.SetupOffer
import com.postsaimanager.core.model.SetupPartStatus
import com.postsaimanager.core.model.SetupProgress

/** First-run AI model setup. [onDone] runs once the user is finished (models installed) or chose "Skip for now". */
@Composable
fun SetupScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.exit) {
        if (state.exit) onDone()
    }
    SetupContent(
        state = state,
        onDownload = viewModel::download,
        onUseMobileData = viewModel::useMobileData,
        onWaitForWifi = viewModel::waitForWifi,
        onDismissMobileDataQuestion = viewModel::dismissMobileDataQuestion,
        onCancel = viewModel::cancel,
        onSkip = viewModel::skip,
        onContinueWithoutSearch = viewModel::continueWithoutSearch,
        modifier = modifier,
    )
}

@Composable
internal fun SetupContent(
    state: SetupUiState,
    onDownload: () -> Unit,
    onUseMobileData: () -> Unit,
    onWaitForWifi: () -> Unit,
    onDismissMobileDataQuestion: () -> Unit,
    onCancel: () -> Unit,
    onSkip: () -> Unit,
    onContinueWithoutSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = PamIcons.AiModel,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.setup_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.setup_summary),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.setup_privacy),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))

        val offer = state.offer
        when (state.stage) {
            SetupStage.LOADING -> Unit
            SetupStage.INTRO -> if (offer != null) Intro(offer, onDownload, onSkip)
            SetupStage.ASK_MOBILE_DATA -> if (offer != null) {
                MobileDataQuestion(offer, onUseMobileData, onWaitForWifi, onDismissMobileDataQuestion)
            }
            SetupStage.DOWNLOADING -> if (offer != null) {
                Downloading(offer, state.progress, onCancel)
            }
            SetupStage.FAILED -> if (offer != null) {
                Failed(offer, state, onDownload, onContinueWithoutSearch, onSkip)
            }
            SetupStage.FINISHED -> Text(
                text = stringResource(R.string.setup_finished),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun Intro(offer: SetupOffer, onDownload: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    if (offer.canInstallChatModel) {
        Text(
            text = stringResource(
                R.string.setup_download_info,
                offer.chatModelName,
                Formatter.formatShortFileSize(context, offer.chatModelBytes),
                Formatter.formatShortFileSize(context, offer.searchModelBytes),
            ),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    } else {
        Text(
            text = stringResource(R.string.setup_unsupported),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
    Button(
        onClick = onDownload,
        enabled = offer.canInstallChatModel,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.setup_download_button))
    }
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_skip))
    }
    Text(
        text = stringResource(R.string.setup_skip_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun MobileDataQuestion(
    offer: SetupOffer,
    onUseMobileData: () -> Unit,
    onWaitForWifi: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    Text(
        text = stringResource(R.string.setup_mobile_data_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = stringResource(
            R.string.setup_mobile_data_body,
            Formatter.formatShortFileSize(context, offer.chatModelBytes + offer.searchModelBytes),
        ),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Button(onClick = onUseMobileData, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_use_mobile_data))
    }
    OutlinedButton(onClick = onWaitForWifi, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_wait_wifi))
    }
    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_back))
    }
}

@Composable
private fun Downloading(offer: SetupOffer, progress: SetupProgress, onCancel: () -> Unit) {
    Text(
        text = stringResource(R.string.setup_downloading_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    PartRows(offer, progress)
    Text(
        text = stringResource(R.string.setup_downloading_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_cancel))
    }
}

@Composable
private fun Failed(
    offer: SetupOffer,
    state: SetupUiState,
    onRetry: () -> Unit,
    onContinueWithoutSearch: () -> Unit,
    onSkip: () -> Unit,
) {
    Text(
        text = stringResource(R.string.setup_failed_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = stringResource(R.string.setup_failed_body),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    PartRows(offer, state.progress)
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_retry))
    }
    if (state.canContinueWithoutSearch) {
        Text(
            text = stringResource(R.string.setup_search_failed_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onContinueWithoutSearch, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.setup_continue_without_search))
        }
    } else {
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.setup_skip))
        }
    }
}

@Composable
private fun PartRows(offer: SetupOffer, progress: SetupProgress) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PartRow(
            label = stringResource(R.string.setup_part_chat, offer.chatModelName),
            status = progress.chat,
        )
        PartRow(
            label = stringResource(R.string.setup_part_search),
            status = progress.search,
        )
    }
}

/** One model: its name, a progress bar and a status line, read as a single item by a screen reader. */
@Composable
private fun PartRow(label: String, status: SetupPartStatus) {
    val context = LocalContext.current
    val statusText = when (status) {
        SetupPartStatus.NotStarted -> stringResource(R.string.setup_status_not_started)
        SetupPartStatus.Waiting -> stringResource(R.string.setup_status_waiting)
        is SetupPartStatus.Downloading -> status.totalBytes?.let { total ->
            stringResource(
                R.string.setup_status_progress,
                Formatter.formatShortFileSize(context, status.bytesDone),
                Formatter.formatShortFileSize(context, total),
            )
        } ?: Formatter.formatShortFileSize(context, status.bytesDone)
        SetupPartStatus.Done -> stringResource(R.string.setup_status_done)
        SetupPartStatus.Failed -> stringResource(R.string.setup_status_failed)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.titleSmall)
        val fraction = when (status) {
            is SetupPartStatus.Downloading -> status.fraction
            SetupPartStatus.Done -> 1f
            else -> 0f
        }
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = if (status is SetupPartStatus.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
