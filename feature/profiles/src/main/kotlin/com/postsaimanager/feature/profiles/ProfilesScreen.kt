package com.postsaimanager.feature.profiles

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.postsaimanager.core.designsystem.component.PamEmptyState
import com.postsaimanager.core.designsystem.component.PamErrorState
import com.postsaimanager.core.designsystem.component.PamLoadingState
import com.postsaimanager.core.designsystem.component.PamTopAppBar
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.model.Profile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    onProfileClick: (String) -> Unit,
    onAddPerson: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfilesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val pendingDeletion by viewModel.pendingDeletion.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = { PamTopAppBar(title = stringResource(R.string.profiles_title)) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddPerson,
                icon = { Icon(PamIcons.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.profiles_add_person)) },
                modifier = Modifier.testTag("add_person"),
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::onSearchQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.profiles_search_placeholder)) },
                leadingIcon = { Icon(PamIcons.Search, contentDescription = stringResource(R.string.profiles_search_description)) },
                trailingIcon = {
                    AnimatedVisibility(visible = searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(PamIcons.Close, contentDescription = stringResource(R.string.profiles_search_clear))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )

            AnimatedContent(
                targetState = uiState,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "profiles_content",
            ) { state ->
                when (state) {
                    is ProfilesUiState.Loading -> PamLoadingState()
                    is ProfilesUiState.Empty -> PamEmptyState(
                        icon = PamIcons.Profiles,
                        title = stringResource(
                            if (searchQuery.isNotEmpty()) R.string.profiles_empty_no_results_title else R.string.profiles_empty_title,
                        ),
                        subtitle = stringResource(
                            if (searchQuery.isNotEmpty()) R.string.profiles_empty_no_results_subtitle else R.string.profiles_empty_subtitle,
                        ),
                    )
                    is ProfilesUiState.Error -> PamErrorState(
                        message = state.message ?: stringResource(R.string.profiles_error_unknown),
                        icon = PamIcons.Error,
                    )
                    is ProfilesUiState.Success -> ProfilesList(
                        state = state,
                        onProfileClick = onProfileClick,
                        onDelete = viewModel::requestDelete,
                    )
                }
            }
        }
    }

    pendingDeletion?.let { profile ->
        DeleteProfileDialog(
            profile = profile,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

/** The profiles in three groups: my household first, then organisations, then other people. Stateless, so it can be tested alone. */
@Composable
internal fun ProfilesList(
    state: ProfilesUiState.Success,
    onProfileClick: (String) -> Unit,
    onDelete: (Profile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = state.sections
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        profileSection(R.string.profiles_section_household, "household", sections.household, state.contactCounts, onProfileClick, onDelete)
        profileSection(R.string.profiles_section_organisations, "organisations", sections.organisations, state.contactCounts, onProfileClick, onDelete)
        profileSection(R.string.profiles_section_people, "people", sections.people, state.contactCounts, onProfileClick, onDelete)
    }
}

/** One titled group of the list; nothing at all when the group is empty. Keys are prefixed so a profile id cannot clash with a header. */
private fun LazyListScope.profileSection(
    title: Int,
    tag: String,
    profiles: List<Profile>,
    contactCounts: Map<String, Int>,
    onProfileClick: (String) -> Unit,
    onDelete: (Profile) -> Unit,
) {
    if (profiles.isEmpty()) return
    item(key = "header_$tag") {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp).testTag("section_$tag"),
        )
    }
    items(profiles, key = { it.id }) { profile ->
        ProfileListItem(
            profile = profile,
            contactCount = contactCounts[profile.id] ?: 0,
            onClick = { onProfileClick(profile.id) },
            onDeleteClick = { onDelete(profile) },
        )
    }
}

/**
 * Confirms a profile delete before it happens — the operation is destructive and, unlike
 * unlinking a document, not obviously undoable from this screen.
 *
 * The wording is deliberately different for a machine-created profile: the tombstone
 * `ProfileRepository.deleteProfile` writes for it (so a reprocessed document does not silently
 * recreate what the user just removed) is otherwise an implementation detail nobody asked for —
 * surfaced here as a plain reassurance, not a technical aside.
 */
@Composable
private fun DeleteProfileDialog(
    profile: Profile,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isMachineCreated = profile.sourceDocumentId != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_delete_title, profile.name)) },
        text = {
            Text(
                stringResource(
                    if (isMachineCreated) R.string.profiles_delete_body_auto else R.string.profiles_delete_body,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.profiles_delete_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.profiles_delete_cancel)) } },
    )
}

@Composable
private fun ProfileListItem(
    profile: Profile,
    contactCount: Int,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar placeholder
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = profile.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                val typeText = stringResource(profileLabel(profile))
                val relationshipText = profile.relationship?.let { stringResource(relationshipLabel(it)) }
                val contactsText = contactCount.takeIf { it > 0 }
                    ?.let { pluralStringResource(R.plurals.profile_contacts_count, it, it) }
                val subtitle = listOfNotNull(typeText, relationshipText, profile.organization, contactsText).joinToString(" · ")
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDeleteClick) {
                Icon(
                    PamIcons.Delete,
                    contentDescription = stringResource(R.string.profiles_delete_description),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
