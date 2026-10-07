package com.postsaimanager.core.designsystem.component

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.center
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.DocumentPreview
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.PreviewPage
import com.postsaimanager.core.model.TextBounds
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The page preview's text selection and zoom, drawn for real (Robolectric). The page image is not decoded on the JVM, so its size is
 * handed in through [LocalAssumedPageImageSize].
 *
 * Not testable on the JVM: the platform's own long-press selection itself. Robolectric has no magnifier surface, so a real selection
 * throws inside Compose; that is checked on the device. What is checked here is everything this code owns around it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PageSelectionUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val line = OcrBlock("Musterfirma Rechnung", TextBounds(0.1f, 0.45f, 0.9f, 0.55f), 0.9f)
    private val page = PreviewPage(pageNumber = 1, imagePath = "/none.png", textBlocks = listOf(line))
    private val imageSize = Size(1000f, 1000f)

    // ---- zoom: a held finger must reach the text layer ----

    private var longPresses = 0

    private fun showGesturePage(viewport: PageViewport) {
        compose.setContent {
            Box(Modifier.size(400.dp, 600.dp)) {
                Box(Modifier.fillMaxSize().onSizeChanged { viewport.box = it }.testTag("page").pageGestures(viewport)) {
                    // Stands in for the text layer: a child that wants the long press.
                    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onLongPress = { longPresses++ }) })
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a held finger that drifts a little while zoomed still long-presses the text layer, and does not pan`() {
        val viewport = PageViewport()
        showGesturePage(viewport)
        viewport.scale = 2.5f
        compose.waitForIdle()
        compose.onNodeWithTag("page").performTouchInput {
            down(center)
            moveBy(Offset(2f, 2f))
            advanceEventTime(1_000)
            up()
        }
        compose.waitForIdle()
        assertThat(longPresses).isEqualTo(1)
        assertThat(viewport.offset).isEqualTo(Offset.Zero)
    }

    @Test
    fun `a one-finger drag pans a zoomed page`() {
        val viewport = PageViewport()
        showGesturePage(viewport)
        viewport.scale = 2.5f
        compose.waitForIdle()
        compose.onNodeWithTag("page").performTouchInput { swipe(center, center + Offset(-120f, 0f), durationMillis = 200) }
        compose.waitForIdle()
        assertThat(viewport.offset.x).isLessThan(-50f)
        assertThat(longPresses).isEqualTo(0)
    }

    @Test
    fun `a one-finger drag on an unzoomed page is left to the pager`() {
        val viewport = PageViewport()
        showGesturePage(viewport)
        compose.onNodeWithTag("page").performTouchInput { swipe(center, center + Offset(-120f, 0f), durationMillis = 200) }
        compose.waitForIdle()
        assertThat(viewport.offset).isEqualTo(Offset.Zero)
        assertThat(viewport.scale).isEqualTo(1f)
    }

    // ---- Back and the selection's state ----

    private var closes = 0

    private fun showPageWithBackFallback(selection: PageSelection) {
        // What Back does when the page does not take it: the preview closing. Registered first, so the page's own handler is above it.
        compose.activity.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                closes++
            }
        })
        compose.setContent {
            CompositionLocalProvider(LocalAssumedPageImageSize provides imageSize) {
                Box(Modifier.size(400.dp, 600.dp)) { ZoomablePage(page = page, description = "page", selection = selection) }
            }
        }
        compose.waitForIdle()
    }

    private fun pressActivityBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun `back with a selection clears it and does not close, back again closes`() {
        val selection = PageSelection()
        showPageWithBackFallback(selection)
        compose.runOnIdle { selection.active = true }
        compose.waitForIdle()

        pressActivityBack()
        assertThat(selection.active).isFalse()
        assertThat(closes).isEqualTo(0)

        pressActivityBack()
        assertThat(closes).isEqualTo(1)
    }

    @Test
    fun `clearing replaces the selection container, so a new selection starts empty`() {
        val selection = PageSelection()
        val before = selection.generation
        selection.clear()
        assertThat(selection.generation).isEqualTo(before + 1)
        assertThat(selection.active).isFalse()
    }

    @Test
    fun `the toolbar showing means a selection exists, hiding means none`() {
        val selection = PageSelection()
        val delegate = object : androidx.compose.ui.platform.TextToolbar {
            override val status = androidx.compose.ui.platform.TextToolbarStatus.Hidden
            override fun showMenu(
                rect: Rect,
                onCopyRequested: (() -> Unit)?,
                onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?,
                onSelectAllRequested: (() -> Unit)?,
            ) = Unit

            override fun hide() = Unit
        }
        val toolbar = TrackingTextToolbar(delegate, selection)
        toolbar.showMenu(Rect.Zero, onCopyRequested = {}, onPasteRequested = null, onCutRequested = null, onSelectAllRequested = null)
        assertThat(selection.active).isTrue()
        toolbar.hide()
        assertThat(selection.active).isFalse()
    }

    // ---- the dialog ----

    private fun showDialog() {
        compose.setContent {
            CompositionLocalProvider(LocalAssumedPageImageSize provides imageSize) {
                PagePreviewDialog(
                    title = "Rechnung",
                    preview = DocumentPreview("d", "Musterfirma", listOf(page)),
                    loading = false,
                    initialPageIndex = 0,
                    onClose = { closes++ },
                    onOpenDocument = null,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `back without a selection closes the dialog`() {
        showDialog()
        val dialog = ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog
        compose.runOnUiThread { dialog.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertThat(closes).isEqualTo(1)
    }

    @Test
    fun `the header has no Select text button`() {
        showDialog()
        compose.onNodeWithText("Select text").assertDoesNotExist()
    }
}
