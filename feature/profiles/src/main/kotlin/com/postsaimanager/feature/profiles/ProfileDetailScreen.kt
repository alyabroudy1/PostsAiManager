package com.postsaimanager.feature.profiles

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.roundToInt
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.postsaimanager.core.designsystem.component.MemoryCard
import com.postsaimanager.core.designsystem.component.MemorySubject
import com.postsaimanager.core.designsystem.component.NoteActions
import com.postsaimanager.core.designsystem.component.PamLoadingState
import com.postsaimanager.core.designsystem.component.PamTopAppBar
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.domain.memory.DocumentNoteText
import com.postsaimanager.core.model.DocumentNote
import com.postsaimanager.core.model.FormDataKey
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileFact
import com.postsaimanager.core.model.HouseholdRole
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.Relationship
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.DateTimeParseException

@Composable
fun ProfileDetailScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileDetailViewModel = hiltViewModel(),
    notesViewModel: ProfileNotesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val notes by notesViewModel.notes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val message by viewModel.message.collectAsStateWithLifecycle()
    val removed by viewModel.removed.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val removedText = stringResource(R.string.saved_details_deleted)
    val undoText = stringResource(R.string.saved_details_undo)

    LaunchedEffect(state.finished) { if (state.finished) onNavigateBack() }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(removed) {
        if (removed != null) {
            val result = snackbarHostState.showSnackbar(removedText, undoText, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete() else viewModel.consumeRemoved()
        }
    }

    ProfileDetailContent(
        state = state,
        availableKeys = viewModel.availableKeys(state.facts),
        snackbarHostState = snackbarHostState,
        onNavigateBack = onNavigateBack,
        onUpdate = viewModel::update,
        onKind = viewModel::setKind,
        onRole = viewModel::setRole,
        onRelationship = viewModel::setRelationship,
        onSave = viewModel::save,
        detailActions = SavedDetailActions(save = viewModel::saveDetail, delete = viewModel::deleteDetail),
        notes = notes,
        noteActions = notesViewModel.actions,
        contactActions = ContactActions(
            save = viewModel::saveContact,
            setActive = viewModel::setContactActive,
            merge = viewModel::mergeContact,
            move = viewModel::moveContactTo,
            delete = viewModel::removeContact,
            confirm = viewModel::confirmContact,
            call = { phone -> launch(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' })), "dial", viewModel) },
            email = { address -> launch(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(address))), "write-email", viewModel) },
        ),
        modifier = modifier,
    )
}

/** Opens the dialer or the mail app (nothing is dialled or sent by the app itself); the app lock is told the trip is on purpose. */
private fun launch(context: Context, intent: Intent, reason: String, viewModel: ProfileDetailViewModel) {
    viewModel.onExternalLaunching(reason)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        viewModel.onExternalLaunchFinished()
        Toast.makeText(context, context.getString(R.string.contact_no_app), Toast.LENGTH_SHORT).show()
    }
}

/** The profile editor, stateless so it can be drawn and tested without a ViewModel. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProfileDetailContent(
    state: ProfileDetailUiState,
    availableKeys: List<FormDataKey>,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onUpdate: ((Profile) -> Profile) -> Unit,
    onKind: (ProfileKind) -> Unit,
    onRole: (HouseholdRole?) -> Unit,
    onRelationship: (Relationship?) -> Unit,
    onSave: () -> Unit,
    detailActions: SavedDetailActions,
    modifier: Modifier = Modifier,
    contactActions: ContactActions = ContactActions(),
    /** The notes the assistant keeps about this person ("What the assistant remembers"); shown for a household person only. */
    notes: List<DocumentNote> = emptyList(),
    noteActions: NoteActions = NoteActions(),
) {
    val draft = state.draft
    val scrollState = rememberScrollState()
    // Opened from a letter's contact chip: scroll once to that contact's row (its position is reported when laid out).
    var viewportTop by remember { mutableFloatStateOf(0f) }
    var focusRowY by remember { mutableStateOf<Float?>(null) }
    var scrolledToFocus by rememberSaveable(state.focusContactId) { mutableStateOf(false) }
    LaunchedEffect(focusRowY, viewportTop) {
        val rowY = focusRowY
        if (rowY != null && !scrolledToFocus) {
            scrolledToFocus = true
            scrollState.scrollTo((scrollState.value + rowY - viewportTop).roundToInt().coerceAtLeast(0))
        }
    }
    Scaffold(
        topBar = {
            PamTopAppBar(
                title = when {
                    state.isNew -> stringResource(R.string.profile_detail_title_new)
                    else -> draft?.name.orEmpty()
                },
                onNavigateBack = onNavigateBack,
                actions = {
                    TextButton(onClick = onSave, enabled = state.canSave, modifier = Modifier.testTag("save_profile")) {
                        Text(stringResource(R.string.profile_detail_save))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { innerPadding ->
        when {
            !state.loaded -> PamLoadingState(modifier = Modifier.padding(innerPadding))
            draft == null -> Text(
                stringResource(R.string.profile_detail_not_found),
                modifier = Modifier.padding(innerPadding).padding(16.dp),
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .onGloballyPositioned { viewportTop = it.positionInRoot().y }
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { v -> onUpdate { it.copy(name = v) } },
                    label = { Text(stringResource(R.string.profile_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("field_name"),
                )

                Text(stringResource(R.string.profile_field_type), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KindChip(ProfileKind.PERSON, R.string.profile_kind_person, draft.kind, !state.selfLocked, onKind)
                    KindChip(ProfileKind.ORGANISATION, R.string.profile_type_organisation, draft.kind, !state.selfLocked, onKind)
                }

                if (draft.kind == ProfileKind.PERSON) {
                    Text(stringResource(R.string.profile_field_household), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RoleChip("NONE", R.string.profile_household_none, draft.householdRole == null, !state.selfLocked) { onRole(null) }
                        RoleChip(
                            HouseholdRole.SELF.name, R.string.profile_type_self, draft.householdRole == HouseholdRole.SELF,
                            enabled = !state.selfTaken && !state.selfLocked || draft.householdRole == HouseholdRole.SELF,
                        ) { onRole(HouseholdRole.SELF) }
                        RoleChip(HouseholdRole.MEMBER.name, R.string.profile_type_family, draft.householdRole == HouseholdRole.MEMBER, !state.selfLocked) {
                            onRole(HouseholdRole.MEMBER)
                        }
                    }
                    if (state.selfTaken && draft.householdRole != HouseholdRole.SELF) {
                        Text(
                            stringResource(R.string.profile_me_taken),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (draft.householdRole == HouseholdRole.MEMBER) {
                    Text(stringResource(R.string.profile_field_relationship), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Relationship.entries.forEach { relationship ->
                            FilterChip(
                                selected = draft.relationship == relationship,
                                onClick = { onRelationship(relationship.takeIf { draft.relationship != it }) },
                                label = { Text(stringResource(relationshipLabel(relationship))) },
                                modifier = Modifier.testTag("relationship_${relationship.name}"),
                            )
                        }
                    }
                }

                if (draft.kind != ProfileKind.ORGANISATION) {
                    BirthDateField(draft.birthDate) { iso -> onUpdate { it.copy(birthDate = iso) } }
                }

                Text(stringResource(R.string.profile_section_contact), style = MaterialTheme.typography.labelLarge)
                TextField(draft.street, R.string.profile_field_street, "field_street") { v -> onUpdate { it.copy(street = v) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(draft.postalCode, R.string.profile_field_postal_code, "field_postal_code", Modifier.weight(1f)) { v -> onUpdate { it.copy(postalCode = v) } }
                    TextField(draft.city, R.string.profile_field_city, "field_city", Modifier.weight(2f)) { v -> onUpdate { it.copy(city = v) } }
                }
                TextField(draft.phone, R.string.profile_field_phone, "field_phone", keyboard = KeyboardType.Phone) { v -> onUpdate { it.copy(phone = v) } }
                TextField(draft.email, R.string.profile_field_email, "field_email", keyboard = KeyboardType.Email) { v -> onUpdate { it.copy(email = v) } }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.profile_sensitive_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.profile_sensitive_explanation),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = draft.sensitive,
                        onCheckedChange = { v -> onUpdate { it.copy(sensitive = v) } },
                        modifier = Modifier.testTag("sensitive_switch"),
                    )
                }

                if (draft.kind == ProfileKind.ORGANISATION && !state.isNew) {
                    HorizontalDivider()
                    ContactsSection(
                        state.contacts,
                        actions = contactActions,
                        focusContactId = state.focusContactId,
                        otherOrganisations = state.otherOrganisations,
                        onFocusPlaced = { focusRowY = it },
                    )
                }

                HorizontalDivider()
                SavedDetailsSection(
                    facts = state.facts,
                    availableKeys = availableKeys,
                    canAdd = !state.isNew,
                    actions = detailActions,
                )

                // What the assistant remembers about a household person (written by the chat about all documents).
                if (draft.isManaged && !state.isNew) {
                    MemoryCard(notes, noteActions, MemorySubject.PERSON, DocumentNoteText.MAX_CHARS)
                }
            }
        }
    }
}

@Composable
private fun KindChip(
    kind: ProfileKind,
    label: Int,
    selected: ProfileKind,
    enabled: Boolean,
    onKind: (ProfileKind) -> Unit,
) {
    FilterChip(
        selected = selected == kind,
        onClick = { onKind(kind) },
        enabled = enabled,
        label = { Text(stringResource(label)) },
        modifier = Modifier.testTag("kind_${kind.name}"),
    )
}

@Composable
private fun RoleChip(name: String, label: Int, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(stringResource(label)) },
        modifier = Modifier.testTag("role_$name"),
    )
}

@Composable
private fun TextField(
    value: String?,
    label: Int,
    tag: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value.orEmpty(),
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth().testTag(tag),
    )
}

/** The birth date as a button that opens a date picker; stored as ISO yyyy-MM-dd. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthDateField(isoDate: String?, onChange: (String?) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val date = remember(isoDate) { isoDate?.let(::parseIso) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.profile_field_birth_date), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.testTag("birth_date")) {
                Text(date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: stringResource(R.string.profile_field_birth_date_none))
            }
            if (date != null) {
                IconButton(onClick = { onChange(null) }) {
                    Icon(PamIcons.Close, contentDescription = stringResource(R.string.profile_field_birth_date_clear))
                }
            }
        }
    }
    if (picking) {
        val today = remember { LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= today
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            onChange(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())
                        }
                        picking = false
                    },
                ) { Text(stringResource(R.string.date_picker_ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.date_picker_cancel)) } },
        ) { DatePicker(state = pickerState) }
    }
}

private fun parseIso(text: String): LocalDate? = try {
    LocalDate.parse(text)
} catch (_: DateTimeParseException) {
    null
}

internal fun relationshipLabel(relationship: Relationship): Int = when (relationship) {
    Relationship.PARTNER -> R.string.relationship_partner
    Relationship.CHILD -> R.string.relationship_child
    Relationship.PARENT -> R.string.relationship_parent
    Relationship.RELATIVE -> R.string.relationship_relative
    Relationship.FRIEND -> R.string.relationship_friend
    Relationship.OTHER -> R.string.relationship_other
}

/** The short label of what a profile is, for lists: "Me", "Family member", "Other person" or "Organisation". */
internal fun profileLabel(profile: Profile): Int = when {
    profile.kind == ProfileKind.ORGANISATION -> R.string.profile_type_organisation
    profile.householdRole == HouseholdRole.SELF -> R.string.profile_type_self
    profile.householdRole == HouseholdRole.MEMBER -> R.string.profile_type_family
    else -> R.string.profile_type_person
}
