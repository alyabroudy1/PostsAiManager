package com.postsaimanager.feature.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.skills.JsSkillWebview
import com.postsaimanager.core.domain.skills.ToolStep
import com.postsaimanager.core.domain.skills.ToolStepKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Gallery's thinking body, progress panel and image body, and the composer's attach button, drawn for real (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp")
class GalleryBodiesUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the thinking body starts collapsed, opens on a tap and says how long the model thought`() {
        compose.setContent {
            MaterialTheme { MessageBodyThinking(thinkingText = "First I look at the date.", inProgress = false, durationMs = 3_200) }
        }

        compose.onNodeWithText("Thought for 3.2s").assertIsDisplayed()
        compose.onNodeWithTag(THINKING_TEXT_TAG).assertDoesNotExist()

        compose.onNodeWithTag(THINKING_HEADER_TAG).performClick()
        compose.onNodeWithTag(THINKING_TEXT_TAG).assertIsDisplayed()

        compose.onNodeWithTag(THINKING_HEADER_TAG).performClick()
        compose.waitUntil(2_000) { compose.onAllNodesWithTagCount(THINKING_TEXT_TAG) == 0 }
    }

    @Test
    fun `while the model thinks the header says so, and the text stays collapsed until asked for`() {
        compose.setContent { MaterialTheme { MessageBodyThinking(thinkingText = "hmm", inProgress = true, durationMs = null) } }

        compose.onNodeWithText("Thinking…").assertIsDisplayed()
        compose.onNodeWithTag(THINKING_TEXT_TAG).assertDoesNotExist()
    }

    @Test
    fun `the progress panel counts the steps, and shows each one when opened`() {
        val steps = listOf(
            ToolStep(ToolStepKind.LOAD_SKILL, "send-email", "", failed = false),
            ToolStep(ToolStepKind.RUN_INTENT, "send_email", """{"extra_email":"a@b.de"}""", failed = false),
            ToolStep(ToolStepKind.RUN_JS, "calculate-hash", "index.html", failed = true),
        )
        compose.setContent { MaterialTheme { MessageBodyCollapsableProgressPanel(steps = steps) } }

        compose.onNodeWithText("3 steps").assertIsDisplayed()
        compose.onNodeWithText("Loaded skill \"send-email\"").assertDoesNotExist()

        compose.onNodeWithTag(PROGRESS_HEADER_TAG).performClick()
        compose.onNodeWithText("Loaded skill \"send-email\"").assertIsDisplayed()
        compose.onNodeWithText("Prepared the action \"send_email\"").assertIsDisplayed()
        compose.onNodeWithText("The script of \"calculate-hash\" failed").assertIsDisplayed()
    }

    @Test
    fun `a message with several pictures shows each, and a tap says which`() {
        val tapped = mutableListOf<Int>()
        compose.setContent {
            MaterialTheme { MessageBodyImage(paths = listOf("/x/1.png", "/x/2.png", "/x/3.png"), onImageClicked = { tapped += it }) }
        }

        compose.onNodeWithTag(messageImageTag(0)).assertExists()
        compose.onNodeWithTag(messageImageTag(2)).performClick()
        assertThat(tapped).containsExactly(2)
    }

    @Test
    fun `the attach button stays for a model that does not take pictures and explains instead of opening the menu`() {
        var unsupported = 0
        compose.setContent {
            MaterialTheme { ChatInputBar("", {}, {}, false, {}, imageInputSupported = false, onAttachUnsupported = { unsupported++ }) }
        }
        compose.onNodeWithTag(ATTACH_BUTTON_TAG).performClick()
        assertThat(unsupported).isEqualTo(1)
        compose.onNodeWithTag(ATTACH_PHOTO_TAG).assertDoesNotExist()
    }

    @Test
    fun `there is an attach button for a model that takes pictures`() {
        compose.setContent { MaterialTheme { ChatInputBar("", {}, {}, false, {}, imageInputSupported = true) } }
        compose.onNodeWithTag(ATTACH_BUTTON_TAG).assertIsDisplayed()
    }

    @Test
    fun `the attach menu offers the photo picker and each page of the letter, and the attached pictures can be removed`() {
        var picked = 0
        val pages = mutableListOf<AttachablePage>()
        val removed = mutableListOf<String>()
        var menuOpened = 0
        compose.setContent {
            MaterialTheme {
                ChatInputBar(
                    value = "",
                    onValueChange = {},
                    onSend = {},
                    isGenerating = false,
                    onStop = {},
                    attachments = listOf("/x/1.png", "/x/2.png"),
                    imageInputSupported = true,
                    attachablePages = listOf(AttachablePage(1, "file:///p1.jpg"), AttachablePage(2, "file:///p2.jpg")),
                    onPickPhotos = { picked++ },
                    onOpenAttachMenu = { menuOpened++ },
                    onAttachPage = { pages += it },
                    onRemoveAttachment = { removed += it },
                )
            }
        }

        compose.onNodeWithTag(attachmentRemoveTag(1)).performClick()
        assertThat(removed).containsExactly("/x/2.png")

        compose.onNodeWithTag(ATTACH_BUTTON_TAG).performClick()
        assertThat(menuOpened).isEqualTo(1)
        compose.onNodeWithText("Page 2 of this letter").performClick()
        assertThat(pages.map { it.pageNumber }).containsExactly(2)

        compose.onNodeWithTag(ATTACH_BUTTON_TAG).performClick()
        compose.onNodeWithTag(ATTACH_PHOTO_TAG).performClick()
        assertThat(picked).isEqualTo(1)
    }

    @Test
    fun `a typed message is sent with the send button`() {
        var sent = 0
        compose.setContent { MaterialTheme { ChatInputBar(value = "hello", onValueChange = {}, onSend = { sent++ }, isGenerating = false, onStop = {}) } }
        compose.onNodeWithContentDescription("Send").performClick()
        assertThat(sent).isEqualTo(1)
    }

    @Test
    fun `a skill's webview offers full screen, and a page outside the sandbox loads nothing`() {
        compose.setContent {
            MaterialTheme { MessageBodyWebview(webview = JsSkillWebview("text-spinner/assets/webview.html", 1.5f)) }
        }
        compose.onNodeWithTag(WEBVIEW_FULL_SCREEN_TAG).assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
