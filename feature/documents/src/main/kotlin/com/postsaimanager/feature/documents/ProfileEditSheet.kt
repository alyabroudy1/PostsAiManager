package com.postsaimanager.feature.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileType

/**
 * Pre-filled profile information from extraction data.
 */
data class ProfileFormData(
    val name: String = "",
    val organization: String = "",
    val department: String = "",
    val street: String = "",
    val city: String = "",
    val postalCode: String = "",
    val country: String = "Germany",
    val phone: String = "",
    val email: String = "",
    val website: String = "",
    val reference: String = "",
    val notes: String = "",
    val type: ProfileType = ProfileType.AUTHORITY,
)

/**
 * Bottom sheet for creating or editing a profile with pre-filled extracted data.
 * The user can review, modify, and confirm all fields before saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditSheet(
    initialData: ProfileFormData,
    isEditing: Boolean = false,
    onSave: (ProfileFormData) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var formData by remember { mutableStateOf(initialData) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Title
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(PamIcons.Profiles, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isEditing) "Edit Profile" else "Create Profile",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = "Review and confirm the extracted information below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            // ── Profile Type ──
            Text("Profile Type", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ProfileType.entries.forEachIndexed { index, type ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index, ProfileType.entries.size),
                        onClick = { formData = formData.copy(type = type) },
                        selected = formData.type == type,
                    ) {
                        Text(
                            when (type) {
                                ProfileType.AUTHORITY -> "Authority"
                                ProfileType.PERSON -> "Person"
                                ProfileType.FAMILY_MEMBER -> "Family"
                                ProfileType.USER_SELF -> "Me"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            HorizontalDivider()

            // ── Identity ──
            Text("Identity", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

            OutlinedTextField(
                value = formData.name,
                onValueChange = { formData = formData.copy(name = it) },
                label = { Text("Name *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = formData.name.isBlank(),
            )
            OutlinedTextField(
                value = formData.organization,
                onValueChange = { formData = formData.copy(organization = it) },
                label = { Text("Organization") },
                placeholder = { Text("e.g. Jobcenter Berlin, Deutsche Bank") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = formData.department,
                onValueChange = { formData = formData.copy(department = it) },
                label = { Text("Department") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            HorizontalDivider()

            // ── Address ──
            Text("Address", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

            OutlinedTextField(
                value = formData.street,
                onValueChange = { formData = formData.copy(street = it) },
                label = { Text("Street & Number") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = formData.postalCode,
                    onValueChange = { formData = formData.copy(postalCode = it) },
                    label = { Text("PLZ") },
                    modifier = Modifier.weight(0.35f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = formData.city,
                    onValueChange = { formData = formData.copy(city = it) },
                    label = { Text("City") },
                    modifier = Modifier.weight(0.65f),
                    singleLine = true,
                )
            }
            OutlinedTextField(
                value = formData.country,
                onValueChange = { formData = formData.copy(country = it) },
                label = { Text("Country") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            HorizontalDivider()

            // ── Contact ──
            Text("Contact", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

            OutlinedTextField(
                value = formData.phone,
                onValueChange = { formData = formData.copy(phone = it) },
                label = { Text("Phone") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = formData.email,
                onValueChange = { formData = formData.copy(email = it) },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = formData.website,
                onValueChange = { formData = formData.copy(website = it) },
                label = { Text("Website") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            HorizontalDivider()

            // ── Reference ──
            Text("Additional", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

            OutlinedTextField(
                value = formData.reference,
                onValueChange = { formData = formData.copy(reference = it) },
                label = { Text("Reference / Account Number") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = formData.notes,
                onValueChange = { formData = formData.copy(notes = it) },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── Actions ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(
                    onClick = { onSave(formData) },
                    enabled = formData.name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(PamIcons.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isEditing) "Save Changes" else "Create Profile")
                }
            }
        }
    }
}
