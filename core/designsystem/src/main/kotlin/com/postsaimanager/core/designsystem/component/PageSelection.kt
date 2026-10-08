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
 * The rect Compose hands to [showMenu] is already where the text is on screen: the page is zoomed by layout, so the text layer's
 * coordinates are the window's and nothing needs mapping.
 */
internal class TrackingTextToolbar(
    private val delegate: TextToolbar,
    private val selection: PageSelection,
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
        delegate.showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
    }

    override fun hide() {
        selection.active = false
        delegate.hide()
    }
}
