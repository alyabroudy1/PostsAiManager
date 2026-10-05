package com.postsaimanager.feature.documents

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Pages tab's card and its collapsed recognized text, drawn for real (Robolectric, real string resources). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PagesSummaryUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the recognized text is collapsed until asked for and collapses again`() {
        compose.setContent { MaterialTheme { RecognizedTextSection(listOf(PageText(1, "raw ocr noise"))) } }

        compose.onNodeWithText("Show recognized text").assertIsDisplayed()
        compose.onNodeWithText("raw ocr noise").assertDoesNotExist()

        compose.onNodeWithText("Show recognized text").performClick()
        compose.onNodeWithText("raw ocr noise").assertIsDisplayed()

        compose.onNodeWithText("Hide recognized text").performClick()
        compose.onNodeWithText("raw ocr noise").assertDoesNotExist()
    }

    @Test
    fun `with no recognized text the section is not drawn`() {
        compose.setContent { MaterialTheme { RecognizedTextSection(emptyList()) } }

        compose.onNodeWithText("Show recognized text").assertDoesNotExist()
    }

    @Test
    fun `the card shows title, summary, sender and You`() {
        val summary = PagesSummary(
            state = PagesSummaryState.READY, summaryText = "About benefits.", from = "Jobcenter", to = PagesRecipient.You,
        )
        compose.setContent { MaterialTheme { PagesSummaryCard("My letter", summary, onInstall = {}) } }

        compose.onNodeWithText("About this document").assertIsDisplayed()
        compose.onNodeWithText("My letter").assertIsDisplayed()
        compose.onNodeWithText("About benefits.").assertIsDisplayed()
        compose.onNodeWithText("From: Jobcenter").assertIsDisplayed()
        compose.onNodeWithText("To: You").assertIsDisplayed()
    }

    @Test
    fun `the card omits lines without data`() {
        compose.setContent { MaterialTheme { PagesSummaryCard("My letter", PagesSummary(PagesSummaryState.READY), onInstall = {}) } }

        compose.onNodeWithText("From: ", substring = true).assertDoesNotExist()
        compose.onNodeWithText("To: ", substring = true).assertDoesNotExist()
    }

    @Test
    fun `reading and failed states say so, and not installed offers the install`() {
        var installs = 0
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    PagesSummaryCard("T", PagesSummary(PagesSummaryState.READING), onInstall = {})
                    PagesSummaryCard("T", PagesSummary(PagesSummaryState.AI_NOT_INSTALLED), onInstall = { installs++ })
                    PagesSummaryCard("T", PagesSummary(PagesSummaryState.FAILED), onInstall = {})
                }
            }
        }

        compose.onNodeWithText("Reading the letter…").assertExists()
        compose.onNodeWithText("AI not installed").assertExists()
        compose.onNodeWithText("This letter could not be read. The recognized text is below.").assertExists()
        compose.onNodeWithText("Install").performClick()
        assertThat(installs).isEqualTo(1)
    }
}
