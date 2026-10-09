package com.postsaimanager.feature.setup

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.postsaimanager.core.model.AiModelDescriptor
import com.postsaimanager.core.model.ChatModelFit
import com.postsaimanager.core.model.ChatModelOption
import com.postsaimanager.core.model.ChatModelRecommendation
import com.postsaimanager.core.model.ModelRole
import com.postsaimanager.core.model.NotRecommendedReason
import com.postsaimanager.core.model.SetupOffer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The first-run model list, drawn for real: Gemma is preselected and carries the "Recommended" badge; a phone below it shows the reason. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupModelChoiceUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun descriptor(id: String, name: String, sizeBytes: Long, role: ModelRole = ModelRole.CHAT) = AiModelDescriptor(
        id = id, name = name, family = "f", parameterCount = "p", quantization = "q", sizeBytes = sizeBytes,
        minAvailableRamBytes = 0, contextTokens = 4096, license = "l", role = role,
    )

    private val reader = descriptor("reader", "Reader 0.8B", 500_000_000L, ModelRole.READER_AND_CHAT)
    private val two = descriptor("two", "Qwen 2B", 1_300_000_000L)
    private val gemma = descriptor("gemma", "Gemma 4 E2B · Chat", 2_600_000_000L).copy(setupRank = 100, readsDocuments = true)
    private val search = 270_000_000L

    private fun show(options: List<ChatModelOption>, preselected: String) = compose.setContent {
        MaterialTheme {
            SetupContent(
                state = SetupUiState(
                    offer = SetupOffer(ChatModelRecommendation(options, preselected, reader), canInstallChatModel = true),
                    stage = SetupStage.INTRO,
                    selectedId = preselected,
                ),
                onDownload = {},
                onUseMobileData = {},
                onWaitForWifi = {},
                onDismissMobileDataQuestion = {},
                onCancel = {},
                onSkip = {},
                onContinueWithoutSearch = {},
                onContinueInBackground = {},
                onSelectModel = {},
                onConfirmModel = {},
                onDismissConfirmation = {},
            )
        }
    }

    @Test
    fun `on a phone with the memory Gemma is listed first, selected, and badged Recommended`() {
        show(
            listOf(
                ChatModelOption(gemma, ChatModelFit.Recommended, gemma.sizeBytes + search),
                ChatModelOption(reader, ChatModelFit.Suitable, reader.sizeBytes + search),
                ChatModelOption(two, ChatModelFit.Suitable, reader.sizeBytes + two.sizeBytes + search),
            ),
            preselected = "gemma",
        )

        // One badge, and it is on the selected Gemma row.
        compose.onAllNodesWithText("Recommended for this phone").assertCountEquals(1)
        compose.onNode(isSelected()).assertTextContains("Gemma 4 E2B · Chat", substring = true)
        compose.onNode(isSelected()).assertTextContains("Recommended for this phone", substring = true)
        // No second model is announced for the download.
        compose.onAllNodesWithText("Gemma 4 E2B · Chat chats and reads your letters too", substring = true).assertCountEquals(1)
    }

    @Test
    fun `on a phone below Gemma's memory the recommended Qwen is selected and Gemma shows why not`() {
        show(
            listOf(
                ChatModelOption(two, ChatModelFit.Recommended, reader.sizeBytes + two.sizeBytes + search),
                ChatModelOption(reader, ChatModelFit.Suitable, reader.sizeBytes + search),
                ChatModelOption(gemma, ChatModelFit.NotRecommended(NotRecommendedReason.MEMORY, requiredRamGb = 8.0), gemma.sizeBytes + search),
            ),
            preselected = "two",
        )

        compose.onNode(isSelected()).assertTextContains("Qwen 2B", substring = true)
        compose.onAllNodesWithText("Recommended for this phone").assertCountEquals(1)
        compose.onAllNodesWithText("Not recommended: needs ≥ 8 GB memory").assertCountEquals(1)
        // The Qwen reader is announced as before.
        compose.onAllNodesWithText("Letters are read with Reader 0.8B (always included)").assertCountEquals(1)
    }
}
