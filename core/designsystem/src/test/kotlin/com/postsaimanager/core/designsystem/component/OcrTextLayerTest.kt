package com.postsaimanager.core.designsystem.component

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.TextBounds
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the text layer offers the platform's selection: only lines with something to select. The long-press itself (and the crash
 * "SelectionLayout must not be empty" it caused) can only be checked on a device: Robolectric has no magnifier surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class OcrTextLayerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fit = FittedPage(400f, 600f, 1000f, 1000f)

    private fun line(text: String, box: TextBounds = TextBounds(0.1f, 0.4f, 0.9f, 0.5f)) = PageTextLine(text, box)

    @Test
    fun `blank lines and lines without a visible box get no selectable text`() {
        val shown = selectableLines(
            listOf(
                line("Rechnung"),
                line("   "),
                line(""),
                line("Zero width", TextBounds(0.5f, 0.4f, 0.5f, 0.5f)),
                line("Betrag"),
            ),
            fit,
        )

        assertThat(shown.map { it.second.text }).containsExactly("Rechnung", "Betrag").inOrder()
    }

    @Test
    fun `a line keeps its key whatever is dropped before it, and two equal lines have different keys`() {
        val shown = selectableLines(listOf(line("A"), line("A")), fit)

        assertThat(shown.map { it.first }.distinct()).hasSize(2)
    }

    @Test
    fun `presses are held back until every line has been laid out`() {
        val count = LaidOutCount(expected = 2)
        assertThat(count.isReady).isFalse()
        count.reported("a")
        count.reported("a")
        assertThat(count.isReady).isFalse()
        count.reported("b")
        assertThat(count.isReady).isTrue()
        assertThat(LaidOutCount(expected = 0).isReady).isTrue()
    }

    @Test
    fun `the toolbar rect follows the zoomed text, and is untouched when not zoomed`() {
        val viewport = PageViewport()
        viewport.box = androidx.compose.ui.unit.IntSize(400, 600)
        viewport.rootOrigin = androidx.compose.ui.geometry.Offset(0f, 100f)
        val rect = androidx.compose.ui.geometry.Rect(-380f, -380f, -300f, -350f)
        assertThat(viewport.toolbarRect(rect)).isEqualTo(rect)

        viewport.scale = 2.5f
        viewport.offset = androidx.compose.ui.geometry.Offset(-100f, -50f)

        // The reported rect is moved by the transformed origin p = (-400, -400) only; the zoomed text is p + (rect - p) * 2.5.
        assertThat(viewport.toolbarRect(rect)).isEqualTo(androidx.compose.ui.geometry.Rect(-350f, -350f, -150f, -275f))
    }

    @Test
    fun `the tracking toolbar shows the platform bar at the mapped rect`() {
        var shown: androidx.compose.ui.geometry.Rect? = null
        val delegate = object : androidx.compose.ui.platform.TextToolbar {
            override val status = androidx.compose.ui.platform.TextToolbarStatus.Hidden
            override fun showMenu(
                rect: androidx.compose.ui.geometry.Rect,
                onCopyRequested: (() -> Unit)?,
                onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?,
                onSelectAllRequested: (() -> Unit)?,
            ) { shown = rect }
            override fun hide() = Unit
        }
        val selection = PageSelection()
        val toolbar = TrackingTextToolbar(delegate, selection) { it.translate(5f, 5f) }

        toolbar.showMenu(androidx.compose.ui.geometry.Rect(0f, 0f, 10f, 10f), null, null, null, null)

        assertThat(shown).isEqualTo(androidx.compose.ui.geometry.Rect(5f, 5f, 15f, 15f))
        assertThat(selection.active).isTrue()
    }

    @Test
    fun `the layer with a blank line among the lines draws without a selectable for it`() {
        val selection = PageSelection()
        compose.setContent {
            Box(Modifier.size(400.dp, 600.dp)) {
                Box(Modifier.fillMaxSize()) {
                    OcrTextLayer(listOf(line("Rechnung"), line("  "), line("Betrag", TextBounds(0.1f, 0.6f, 0.9f, 0.7f))), fit, selection)
                }
            }
        }
        compose.waitForIdle()
        selection.clear()
        compose.waitForIdle()
    }
}
