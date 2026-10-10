package com.postsaimanager.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.postsaimanager.core.designsystem.icon.PamIcons
import java.io.File

/** The tags of the attach button, the "photo" menu entry and an attachment's remove button (`attachment-remove-<index>`), for tests. */
const val ATTACH_BUTTON_TAG = "attach-button"
const val ATTACH_PHOTO_TAG = "attach-photo"
const val ATTACH_CAMERA_TAG = "attach-camera"
const val ATTACH_PAGE_TAG_PREFIX = "attach-page-"
fun attachmentRemoveTag(index: Int) = "attachment-remove-$index"

/**
 * The composer: the text field, Send (Stop while a reply streams), and, for a model that can look at pictures, the Gallery's
 * attach button with the pictures already attached shown above the field. The attach menu offers a photo from the picker and, in
 * a letter's chat, each page of the letter.
 */
@Composable
internal fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isGenerating: Boolean,
    onStop: () -> Unit,
    attachments: List<String> = emptyList(),
    imageInputSupported: Boolean = false,
    attachablePages: List<AttachablePage> = emptyList(),
    onPickPhotos: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onAttachUnsupported: () -> Unit = {},
    onOpenAttachMenu: () -> Unit = {},
    onAttachPage: (AttachablePage) -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
) {
    Surface(tonalElevation = 3.dp) {
        Column {
            if (attachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(attachments.size) { index ->
                        val path = attachments[index]
                        Box {
                            AsyncImage(
                                model = File(path),
                                contentDescription = stringResource(R.string.chat_image_in_group_description, index + 1, attachments.size),
                                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            IconButton(
                                onClick = { onRemoveAttachment(path) },
                                modifier = Modifier.align(Alignment.TopEnd).size(24.dp).testTag(attachmentRemoveTag(index)),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.chat_attachment_remove),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                run {
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = {
                                // A model that cannot look at pictures explains itself instead of hiding the button.
                                if (imageInputSupported) {
                                    onOpenAttachMenu()
                                    menuOpen = true
                                } else {
                                    onAttachUnsupported()
                                }
                            },
                            modifier = Modifier.testTag(ATTACH_BUTTON_TAG),
                        ) {
                            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = stringResource(R.string.chat_attach))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_attach_photo)) },
                                onClick = {
                                    menuOpen = false
                                    onPickPhotos()
                                },
                                modifier = Modifier.testTag(ATTACH_PHOTO_TAG),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_attach_camera)) },
                                onClick = {
                                    menuOpen = false
                                    onTakePhoto()
                                },
                                modifier = Modifier.testTag(ATTACH_CAMERA_TAG),
                            )
                            if (attachablePages.isNotEmpty()) HorizontalDivider()
                            attachablePages.forEach { page ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_attach_page, page.pageNumber)) },
                                    onClick = {
                                        menuOpen = false
                                        onAttachPage(page)
                                    },
                                    modifier = Modifier.testTag(ATTACH_PAGE_TAG_PREFIX + page.pageNumber),
                                )
                            }
                        }
                    }
                }
                // Stays enabled and editable even while generating — the user can queue up
                // their next thought, or just cancel via the button on the right.
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    maxLines = 4,
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (isGenerating) {
                    // Send → Stop while a reply streams, rather than disabling the button —
                    // cancelling is always one tap away, never a dead end.
                    IconButton(onClick = onStop) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.chat_input_stop),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    IconButton(onClick = onSend, enabled = value.isNotBlank()) {
                        Icon(
                            imageVector = PamIcons.Send,
                            contentDescription = stringResource(R.string.chat_input_send),
                            tint = if (value.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
