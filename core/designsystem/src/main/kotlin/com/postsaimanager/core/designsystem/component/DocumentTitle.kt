package com.postsaimanager.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.postsaimanager.core.designsystem.R
import com.postsaimanager.core.model.Document

/**
 * The title of [document] in the user's language: the localised sentence for a default title the
 * app wrote ("Scanned 3 pages"), otherwise the stored title (the model's or the person's words).
 */
@Composable
fun documentDisplayTitle(document: Document): String {
    val resources = LocalContext.current.resources
    return document.displayTitle { count ->
        resources.getQuantityString(R.plurals.document_title_scanned_pages, count, count)
    }
}
