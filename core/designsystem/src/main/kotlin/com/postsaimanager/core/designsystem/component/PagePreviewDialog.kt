package com.postsaimanager.core.designsystem.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.postsaimanager.core.designsystem.R
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.model.DocumentPreview
import kotlinx.coroutines.delay

/** How long the header's "Select text" hint keeps the recognised text tinted. */
private const val HINT_MILLIS = 1500L

/**
 * A full-screen, in-place preview of a document's pages, opened on one of them with the passage or field marked.
 * Shared by the chat (a cited passage) and the Extracted tab (the box a field was read from).
 *
 * A [Dialog] rather than a bottom sheet: back closes it natively, it draws edge to edge, and
 * — the deciding reason — a sheet's own vertical drag would fight panning a zoomed page.
 * It sits on top of the screen rather than replacing it, so what is underneath keeps its scroll position untouched.
 *
 * The recognised text of a page is selected with the platform's own text selection (long-press a word, drag the native handles, copy
 * or share from the system toolbar); see [ZoomablePage] and [OcrTextLayer].
 *
 * @param title shown while [preview] is not there (loading, or unavailable); the loaded document's own title replaces it
 * @param preview the pages, or null while [loading] or when the document can't be previewed
 * @param initialPageIndex index into [DocumentPreview.pages] to open on
 * @param onOpenDocument called with the page number being viewed when "Open document" is tapped; null hides the button
 * @param onPageShown called with the page number now in view (also on open), so the caller can land on it when the preview closes
 */
@Composable
fun PagePreviewDialog(
    title: String?,
    preview: DocumentPreview?,
    loading: Boolean,
    initialPageIndex: Int,
    onClose: () -> Unit,
    onOpenDocument: ((pageNumber: Int) -> Unit)?,
    onPageShown: ((pageNumber: Int) -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            // Top and sides here; the bottom is cleared where the pages are drawn (see [dialogBottomInsetPx]), because a Dialog window is
            // not always handed the navigation bar's insets.
            val topAndSides = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal).union(WindowInsets.displayCutout)
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(topAndSides)) {
                if (preview == null || preview.pages.isEmpty()) {
                    PreviewHeader(title = title ?: stringResource(R.string.page_preview_default_title), onClose = onClose)
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (loading) {
                            CircularProgressIndicator()
                        } else {
                            Text(
                                stringResource(R.string.page_preview_unavailable),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    PreviewPager(preview, initialPageIndex, onClose, onOpenDocument, onPageShown)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PreviewPager(
    preview: DocumentPreview,
    initialPageIndex: Int,
    onClose: () -> Unit,
    onOpenDocument: ((pageNumber: Int) -> Unit)?,
    onPageShown: ((pageNumber: Int) -> Unit)?,
) {
    val pages = preview.pages
    val pagerState = rememberPagerState(
        initialPage = initialPageIndex.coerceIn(0, pages.lastIndex),
        pageCount = { pages.size },
    )
    val current = pages[pagerState.currentPage.coerceIn(0, pages.lastIndex)]
    LaunchedEffect(pagerState, onPageShown) {
        snapshotFlow { pagerState.currentPage }.collect { index ->
            onPageShown?.invoke(pages[index.coerceIn(0, pages.lastIndex)].pageNumber)
        }
    }

    val canSelect = current.textBlocks.isNotEmpty()
    // The hint: a tap on "Select text" briefly tints all recognised text. Selecting itself never depends on it.
    var hintCount by remember { mutableIntStateOf(0) }
    var showAllText by remember { mutableStateOf(false) }
    LaunchedEffect(hintCount) {
        if (hintCount > 0) {
            showAllText = true
            delay(HINT_MILLIS)
            showAllText = false
        }
    }
    PreviewHeader(
        title = stringResource(R.string.page_preview_title_page, preview.title, pagerState.currentPage + 1, pages.size),
        onClose = onClose,
        selectTextEnabled = canSelect,
        onSelectTextHint = { hintCount++ },
    )
    if (!canSelect) {
        Text(
            stringResource(R.string.page_preview_select_text_unavailable),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    // Above the pager, not below it: a Dialog window does not always get navigation-bar
    // insets, and a bottom button would sit under the 3-button bar.
    if (onOpenDocument != null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Start,
        ) {
            OutlinedButton(onClick = { onOpenDocument(current.pageNumber) }) {
                Text(stringResource(R.string.page_preview_open_document))
            }
        }
    }
    val density = LocalDensity.current
    val context = LocalContext.current
    // What is under the system's bar is measured on the screen: how far this window reaches below where an app window may draw.
    val view = LocalView.current
    var measuredPx by remember { mutableIntStateOf(0) }
    val bottomPx = maxOf(dialogBottomInsetPx(context), measuredPx)
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { measuredPx = windowOverlapUnderBarPx(view, context) },
    ) { index ->
        ZoomablePage(
            page = pages[index],
            description = stringResource(R.string.page_preview_page_description, index + 1, pages.size, preview.title),
            showAllText = showAllText && index == pagerState.currentPage,
        )
    }
    Spacer(Modifier.height(with(density) { bottomPx.toDp() }))
}

/**
 * How far up from the bottom of the dialog the pages must stop so they clear the navigation bar. A Dialog window on a 3-button bar
 * (Android 15 and later edge to edge) is not reliably handed the bar's insets, so the largest of what the dialog was handed (the bar, the
 * tappable area, the system bars) and what the hosting activity's own window reports (the bar's size whether or not it is shown)
 * is used: whichever source knows the bar, the pages clear it, and none is a hard-coded size.
 */
@Composable
private fun dialogBottomInsetPx(context: Context): Int {
    val density = LocalDensity.current
    val handed = maxOf(
        WindowInsets.navigationBars.getBottom(density),
        WindowInsets.tappableElement.getBottom(density),
        WindowInsets.systemBars.getBottom(density),
    )
    return maxOf(handed, activityNavigationBarPx(context))
}

/**
 * How many pixels of this dialog's window lie below the bottom of the area an app window can use (so under the navigation bar), by the
 * windows' real positions on the screen: the one number that holds whatever the insets say.
 */
private fun windowOverlapUnderBarPx(view: View, context: Context): Int {
    var c: Context? = context
    while (c is ContextWrapper) {
        if (c is Activity) {
            val visible = android.graphics.Rect().also { c.window.decorView.getWindowVisibleDisplayFrame(it) }
            val at = IntArray(2).also { view.rootView.getLocationOnScreen(it) }
            return (at[1] + view.rootView.height - visible.bottom).coerceAtLeast(0)
        }
        c = c.baseContext
    }
    return 0
}

/** The navigation bar's height in pixels as the hosting activity's window reports it (0 when there is no activity or no bar). */
private fun activityNavigationBarPx(context: Context): Int {
    var c: Context? = context
    while (c is ContextWrapper) {
        if (c is Activity) {
            val decor = c.window?.decorView ?: return 0
            val fromInsets = ViewCompat.getRootWindowInsets(decor)
                ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.tappableElement())?.bottom ?: 0
            // Where the system's bar actually starts: the display's full height less the bottom of the area an app window can use. This
            // knows the bar even where the insets API reports a smaller number (the 3-button bar of some devices).
            val visible = android.graphics.Rect().also { decor.getWindowVisibleDisplayFrame(it) }
            val full = if (android.os.Build.VERSION.SDK_INT >= 30) c.windowManager.maximumWindowMetrics.bounds.height() else c.resources.displayMetrics.heightPixels
            val fromFrame = (full - visible.bottom).coerceAtLeast(0)
            android.util.Log.d("PreviewInsets", "navigation bar px: insets=$fromInsets frame=$fromFrame (display $full, visible bottom ${visible.bottom})")
            return maxOf(fromInsets, fromFrame)
        }
        c = c.baseContext
    }
    return 0
}

@Composable
private fun PreviewHeader(
    title: String,
    onClose: () -> Unit,
    selectTextEnabled: Boolean? = null,
    onSelectTextHint: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Null while there are no pages to select on (loading, unavailable): no action at all.
        if (selectTextEnabled != null) {
            TextButton(onClick = onSelectTextHint, enabled = selectTextEnabled) {
                Text(stringResource(R.string.page_preview_select_text))
            }
        }
        IconButton(onClick = onClose) {
            Icon(PamIcons.Close, contentDescription = stringResource(R.string.page_preview_close))
        }
    }
}
