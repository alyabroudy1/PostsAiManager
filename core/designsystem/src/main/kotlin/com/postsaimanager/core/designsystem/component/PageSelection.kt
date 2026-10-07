package com.postsaimanager.core.designsystem.component

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * Whether the page's text has a selection, and the way to clear it.
 *
 * Compose gives a [SelectionContainer] no public selection state, so this observes the one thing it does publish: the floating toolbar
 * ([TrackingTextToolbar]) is shown while a selection exists and hidden when it is cleared. Clearing is the documented counterpart: the
 * container is re-created under a new [generation] key, which drops its selection.
 */
@Stable
internal class PageSelection {
    /** True while the text has a selection (the toolbar is up). */
    var active by mutableStateOf(false)
        internal set

    /** Changes on every [clear]; keys the [SelectionContainer] so that a new one replaces the selected one. */
    var generation by mutableIntStateOf(0)
        private set

    fun clear() {
        active = false
        generation++
    }
}

/**
 * Passes everything to the platform's [delegate] toolbar and tells [selection] whether a selection is showing.
 *
 * The rect Compose hands to [showMenu] is the selection in the text layer's own layout coordinates, moved by the layer's (transformed)
 * origin only: it knows nothing of the page's zoom, so at 2.5x the bar would sit where the text is at fit size. [mapRect] puts it where
 * the zoomed text is on screen ([PageViewport.toolbarRect]); the handles are drawn by Compose inside the transformed layer and need nothing.
 */
internal class TrackingTextToolbar(
    private val delegate: TextToolbar,
    private val selection: PageSelection,
    private val mapRect: (Rect) -> Rect = { it },
) : TextToolbar {
    override val status: TextToolbarStatus get() = delegate.status

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        selection.active = true
        delegate.showMenu(mapRect(rect), onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
    }

    override fun hide() {
        selection.active = false
        delegate.hide()
    }
}
