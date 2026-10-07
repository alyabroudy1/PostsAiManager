package com.postsaimanager.importing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.postsaimanager.R
import com.postsaimanager.core.designsystem.component.FriendlyDate
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.domain.importing.ImportLimits
import com.postsaimanager.core.domain.importing.ImportProblem
import com.postsaimanager.core.domain.importing.StagedFile
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * The confirm sheet: what will be added (a card per document with its page thumbnails), why a file is left out, the passwords a
 * locked PDF asks for, the one switch of the grouping, and the two buttons. It shows what the view model decided and decides nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(
    state: ImportUiState,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onSeparateChange: (Boolean) -> Unit,
    onAddAgainChange: (ImportRow, Boolean) -> Unit,
    onUnlock: (fileId: String, password: String) -> Unit,
    onHide: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = if (state.stage == ImportStage.ADDING || state.target == ImportTarget.Failed) onClose else onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        when {
            state.target == ImportTarget.Failed -> Message(stringResource(R.string.import_failed_all), onClose)
            state.stage == ImportStage.READING -> Working(stringResource(R.string.import_reading), null)
            state.stage == ImportStage.ADDING -> Working(
                stringResource(R.string.import_adding),
                if (state.submitted) onHide else null,
            )
            else -> Review(state, onCancel, onConfirm, onSeparateChange, onAddAgainChange, onUnlock)
        }
    }
}

@Composable
private fun Working(text: String, onHide: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.bodyLarge)
        if (onHide != null) TextButton(onClick = onHide) { Text(stringResource(R.string.import_continue_background)) }
    }
}

@Composable
private fun Message(text: String, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.import_close)) }
    }
}

@Composable
private fun Review(
    state: ImportUiState,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onSeparateChange: (Boolean) -> Unit,
    onAddAgainChange: (ImportRow, Boolean) -> Unit,
    onUnlock: (String, String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.problems) { problem ->
            Text(problemText(problem), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        items(state.lockedFiles, key = { it.id }) { file ->
            PasswordCard(file, wrong = file.id in state.wrongPasswords, onUnlock = { onUnlock(file.id, it) })
        }
        // No key: the same file shared twice makes two rows with one hash.
        items(state.rows) { row ->
            RowCard(row, onAddAgainChange = { onAddAgainChange(row, it) })
        }
        if (state.showSeparateSwitch) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.import_each_image_separate),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = state.eachImageSeparate, onCheckedChange = onSeparateChange)
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.import_cancel)) }
                Button(onClick = onConfirm, enabled = state.canAdd) { Text(stringResource(R.string.import_add)) }
            }
        }
    }
}

@Composable
private fun RowCard(row: ImportRow, onAddAgainChange: (Boolean) -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // A file whose thumbnail is not ready (or cannot be made) shows its placeholder; the card never waits for it.
                items(row.group.files) { file -> Thumbnail(row.thumbnails[file.id]) }
            }
            val files = row.group.files
            Text(
                text = if (files.size == 1) files.single().displayName else pluralStringResource(R.plurals.import_group_images, files.size, files.size),
                style = MaterialTheme.typography.titleSmall,
            )
            val pages = row.group.pageCount
            if (pages > 0) {
                Text(
                    pluralStringResource(R.plurals.import_pages, pages, pages),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            row.duplicate?.let { duplicate ->
                Text(
                    stringResource(
                        if (files.size == 1) R.string.import_duplicate else R.string.import_duplicate_group,
                        friendlyDate(duplicate.addedOn),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.import_add_again), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = row.included, onCheckedChange = onAddAgainChange)
                }
            }
        }
    }
}

/** The first page of a file; the placeholder icon stays behind it while it loads and when there is none. */
@Composable
private fun Thumbnail(path: String?) {
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 90.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = PamIcons.Documents,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(24.dp),
        )
        if (path != null) {
            AsyncImage(
                model = File(path.removePrefix("file://")),
                contentDescription = stringResource(R.string.import_thumbnail_description),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.size(width = 64.dp, height = 90.dp),
            )
        }
    }
}

@Composable
private fun PasswordCard(file: StagedFile, wrong: Boolean, onUnlock: (String) -> Unit) {
    var password by remember(file.id) { mutableStateOf("") }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.import_password_title, file.displayName), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.import_password_label)) },
                singleLine = true,
                isError = wrong,
                supportingText = if (wrong) ({ Text(stringResource(R.string.import_wrong_password)) }) else null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onUnlock(password) }, enabled = password.isNotEmpty(), modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.import_unlock))
            }
        }
    }
}

@Composable
private fun problemText(problem: ImportProblem): String = when (problem) {
    is ImportProblem.NotSupported -> stringResource(R.string.import_problem_not_supported, problem.fileName)
    is ImportProblem.TooLarge -> stringResource(R.string.import_problem_too_large, problem.fileName, ImportLimits.MAX_FILE_MEGABYTES)
    is ImportProblem.TooManyPages ->
        stringResource(R.string.import_problem_too_many_pages, problem.fileName, problem.pages, ImportLimits.MAX_PDF_PAGES)
    is ImportProblem.PasswordUnsupported -> stringResource(R.string.import_problem_password_unsupported, problem.fileName)
    is ImportProblem.PasswordRequired -> stringResource(R.string.import_problem_password_required, problem.fileName)
    is ImportProblem.WrongPassword -> stringResource(R.string.import_problem_wrong_password, problem.fileName)
    is ImportProblem.Broken -> stringResource(R.string.import_problem_broken, problem.fileName)
    is ImportProblem.Unreadable -> stringResource(R.string.import_problem_unreadable, problem.fileName)
}

@Composable
private fun friendlyDate(date: LocalDate): String {
    val locale: Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val today = remember { LocalDate.now() }
    return FriendlyDate.format(date, showYear = date.year != today.year, locale = locale)
}
