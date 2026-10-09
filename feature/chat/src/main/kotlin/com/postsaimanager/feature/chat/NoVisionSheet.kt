package com.postsaimanager.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

const val NO_VISION_SHEET_TAG = "no-vision-sheet"
const val NO_VISION_SWITCH_TAG = "no-vision-switch"

/** Shown when the attach button is tapped while the chat model cannot look at pictures: why, and a way to switch model. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoVisionSheet(onSwitchModel: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(NO_VISION_SHEET_TAG)) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(stringResource(R.string.chat_no_vision_message), style = MaterialTheme.typography.bodyLarge)
            FilledTonalButton(onClick = onSwitchModel, modifier = Modifier.padding(top = 16.dp).testTag(NO_VISION_SWITCH_TAG)) {
                Text(stringResource(R.string.chat_no_vision_switch))
            }
        }
    }
}
