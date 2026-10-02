package com.postsaimanager.applock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.postsaimanager.R

/**
 * Shows [content] only while the app is unlocked; otherwise a neutral lock screen.
 *
 * While locked, [content] is not in the composition at all — not hidden, not behind a scrim —
 * so no document title, thumbnail or text exists in any composable, in the semantics tree or in
 * an accessibility service's view of the window. Its `rememberSaveable` state (navigation back
 * stack, scroll positions) is parked by the [rememberSaveableStateHolder] and restored on
 * unlock, so the user lands where they were.
 *
 * Anything that should open a document — a share-in intent, a notification tap — is applied by
 * [content] once it is composed, that is, after unlock. Such a handler must therefore live
 * inside [content] and read the intent when it first composes; an `onNewIntent` that arrives
 * while locked must be kept by the activity until then, not dropped.
 */
@Composable
fun AppLockGate(
    modifier: Modifier = Modifier,
    viewModel: AppLockViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val holder = rememberSaveableStateHolder()

    if (state.locked) {
        LockScreen(onUnlock = viewModel::unlock, modifier = modifier)
    } else {
        holder.SaveableStateProvider(AppContentKey) { content() }
    }
}

private const val AppContentKey = "app-content"

/**
 * App name and one button. Asks for authentication by itself every time the screen comes to the
 * foreground; the button is for the user who dismissed it.
 */
@Composable
private fun LockScreen(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    LifecycleResumeEffect(Unit) {
        onUnlock()
        onPauseOrDispose { }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.applock_locked_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlock) { Text(stringResource(R.string.applock_unlock)) }
    }
}
