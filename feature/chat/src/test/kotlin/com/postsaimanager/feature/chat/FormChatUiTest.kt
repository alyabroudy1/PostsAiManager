package com.postsaimanager.feature.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.CheckboxValue
import com.postsaimanager.core.model.FormChip
import com.postsaimanager.core.model.FormChipAction
import com.postsaimanager.core.model.FormChipLabel
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.FormFill
import com.postsaimanager.core.model.FormFillStatus
import com.postsaimanager.core.model.FormMessage
import com.postsaimanager.core.model.FormMessageKind
import com.postsaimanager.core.model.FormText
import com.postsaimanager.core.model.FormValueSource
import com.postsaimanager.core.model.NormBox
import com.postsaimanager.core.model.ReviewState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The fill card and the answer chips drawn for real (Robolectric, real string resources). */
@RunWith(RobolectricTestRunner::class)
// A tall window: the card is one item of the chat's scrolling list, which gives it all the height it needs.
@Config(sdk = [34], qualifiers = "w411dp-h3000dp")
class FormChatUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val chips = mutableListOf<Pair<FormChip, String>>()
    private val shownOnPage = mutableListOf<FormField>()
    private val copied = mutableListOf<String>()

    private fun field(
        id: String,
        label: String,
        page: Int = 1,
        order: Int = 0,
        value: String? = null,
        key: String? = null,
        kind: FormFieldKind = FormFieldKind.TEXT,
        source: FormValueSource = FormValueSource.PROFILE,
        reconfirm: Boolean = false,
        skipped: Boolean = false,
    ) = FormField(
        id = id, formFillId = "fill", documentId = "doc", page = page, labelText = label, labelBox = null,
        fillBox = NormBox(0.1f, 0.2f, 0.6f, 0.3f), kind = kind, dataKey = key, value = value,
        valueSource = if (value == null) FormValueSource.NONE else source, reconfirm = reconfirm, skipped = skipped, orderIndex = order,
        reviewState = if (source == FormValueSource.USER) ReviewState.EDITED else ReviewState.UNREVIEWED,
    )

    private val fields = listOf(
        field("name", "Name des Kindes", value = "Ahmad Mustermann", key = "full_name"),
        field("phone", "Telefon (Notfall)", order = 1, value = "0151 2345678", key = "phone", reconfirm = true),
        field("allergies", "Allergien", order = 2),
        field("photo", "Fotos erlaubt", page = 2, value = CheckboxValue.YES, kind = FormFieldKind.CHECKBOX, source = FormValueSource.USER),
        field("iban", "IBAN", page = 2, order = 1, value = "DE89 3704 0044 0532 0130 00", key = "iban", source = FormValueSource.FACT),
        field("hand", "Kurstermin", page = 2, order = 2, skipped = true),
        field("sign", "Unterschrift", page = 2, order = 3, kind = FormFieldKind.SIGNATURE),
    )

    private fun state(list: List<FormField> = fields, open: String? = "allergies") = FillCardState(
        fill = FormFill(
            "fill", "doc", FormFillStatus.ASKING, currentFieldId = open,
            awaiting = open?.let { com.postsaimanager.core.model.FormAwaiting(com.postsaimanager.core.model.FormAwaitKind.ANSWER, fieldId = it) },
            createdAt = 0, updatedAt = 0,
        ),
        fields = list,
    )

    private fun showCard(state: FillCardState = state(), expanded: Boolean = true) = compose.setContent {
        MaterialTheme {
            FillCard(state, expanded = expanded, onShowOnPage = { shownOnPage += it }, onCopy = { copied += it })
        }
    }

    @Test
    fun `the card shows the progress header, the rows with their values and source badges`() {
        showCard()

        // 4 of 6 non-signature fields have a value (the skipped and the empty one do not), 1 signature.
        compose.onNodeWithText("4 of 7 ready · 2 need you · 1 signature").assertIsDisplayed()
        compose.onNodeWithText("Name des Kindes").assertIsDisplayed()
        compose.onNodeWithText("Ahmad Mustermann").assertIsDisplayed()
        compose.onNodeWithText("Needs your input").assertIsDisplayed() // the allergies the conversation asks about
        compose.onNodeWithText("To fill in by hand").assertIsDisplayed()
        compose.onNodeWithText("Sign here").assertIsDisplayed()
        compose.onNodeWithText("To confirm").assertIsDisplayed()
        compose.onNodeWithText("Yes").assertIsDisplayed() // a ticked box, from resources, not the stored "yes"
        compose.onNodeWithText("Page 1").assertIsDisplayed()
        compose.onNodeWithText("Page 2").assertIsDisplayed()
    }

    @Test
    fun `a sensitive value is masked until tapped and hidden again with another tap`() {
        showCard()

        compose.onNodeWithText("DE89 3704 0044 0532 0130 00").assertDoesNotExist()
        compose.onNodeWithText("••••3000").assertIsDisplayed().performClick()
        compose.onNodeWithText("DE89 3704 0044 0532 0130 00").assertIsDisplayed().performClick()
        compose.onNodeWithText("••••3000").assertIsDisplayed()
    }

    @Test
    fun `copy copies a row's value, copy all copies every value`() {
        showCard()

        compose.onNodeWithContentDescription("Copy Name des Kindes").performClick()
        assertThat(copied.last()).isEqualTo("Ahmad Mustermann")

        compose.onNodeWithText("Copy all").performClick()
        val all = copied.last().lines()
        assertThat(all).containsAtLeast("Name des Kindes: Ahmad Mustermann", "Fotos erlaubt: Yes", "IBAN: DE89 3704 0044 0532 0130 00")
        assertThat(all.none { it.startsWith("Allergien") || it.startsWith("Unterschrift") }).isTrue()
    }

    @Test
    fun `the page chip opens the page of that field`() {
        showCard()

        compose.onNodeWithContentDescription("Show IBAN on page 2").performClick()

        assertThat(shownOnPage.single().id).isEqualTo("iban")
    }

    @Test
    fun `an earlier card folds to its header`() {
        showCard(expanded = false)

        compose.onNodeWithText("4 of 7 ready · 2 need you · 1 signature").assertIsDisplayed()
        compose.onNodeWithText("Earlier fill card. The latest one is below.").assertIsDisplayed()
        compose.onNodeWithText("Name des Kindes").assertDoesNotExist()
    }

    @Test
    fun `the card re-renders when a field is answered`() {
        val current = androidx.compose.runtime.mutableStateOf(state())
        compose.setContent { MaterialTheme { FillCard(current.value, expanded = true, onShowOnPage = {}, onCopy = {}) } }
        compose.onNodeWithText("Needs your input").assertIsDisplayed()

        current.value = state(fields.map { if (it.id == "allergies") it.copy(value = "Nussallergie", valueSource = FormValueSource.USER) else it }, open = null)
        compose.waitForIdle()

        compose.onNodeWithText("Nussallergie").assertIsDisplayed()
        compose.onNodeWithText("5 of 7 ready · 1 need you · 1 signature").assertIsDisplayed()
    }

    // ── The answer chips ──

    private fun question(enabled: Boolean) = compose.setContent {
        MaterialTheme {
            FormQuestion(
                text = "Hat Ahmad das Seepferdchen schon?",
                chips = listOf(
                    FormChip(FormChipAction.ANSWER, label = "Ja", arg = "Ja"),
                    FormChip(FormChipAction.ANSWER, label = "Nein", arg = "Nein"),
                    FormChip(FormChipAction.ANSWER, label = "Weiß ich nicht", arg = "Weiß ich nicht"),
                ),
                enabled = enabled,
                onChip = { chip, shown -> chips += chip to shown },
            )
        }
    }

    @Test
    fun `tapping a chip sends it as the answer with its label`() {
        question(enabled = true)

        compose.onNodeWithText("Hat Ahmad das Seepferdchen schon?").assertIsDisplayed()
        compose.onNodeWithContentDescription("Answer: Nein").assertIsEnabled().performClick()
        compose.onNodeWithText("Weiß ich nicht").performClick()

        assertThat(chips.map { it.second }).containsExactly("Nein", "Weiß ich nicht").inOrder()
        assertThat(chips.first().first.arg).isEqualTo("Nein")
        assertThat(chips.last().first.action).isEqualTo(FormChipAction.ANSWER)
    }

    @Test
    fun `the agent's ask_user question and its chips are drawn from the stored call, no tool JSON in sight`() {
        compose.setContent {
            MaterialTheme {
                FormMessageItem(
                    message = ChatMessage(id = "m", text = "Für wen ist das Formular?", isUser = false),
                    form = FormMessage(
                        FormMessageKind.QUESTION,
                        chips = listOf(FormChip(FormChipAction.ANSWER, label = "Ahmad", arg = "Ahmad"), FormChip(FormChipAction.ANSWER, label = "Ich", arg = "Ich")),
                    ),
                    fillCard = null, isLatestCard = false, chipsEnabled = true, onChip = { c, s -> chips += c to s }, onShowOnPage = {}, onCopy = {},
                )
            }
        }

        compose.onNodeWithText("Für wen ist das Formular?").assertIsDisplayed()
        compose.onNodeWithText("Ahmad").assertIsEnabled()
        compose.onNodeWithText("Ich").performClick()
        compose.onNodeWithText("Ahmad").performClick()

        assertThat(chips.map { it.second }).containsExactly("Ich", "Ahmad").inOrder()
        assertThat(chips.all { it.first.action == FormChipAction.ANSWER }).isTrue()
    }

    @Test
    fun `a closed question (not the latest) shows its chips but they cannot be tapped`() {
        compose.setContent {
            MaterialTheme {
                FormMessageItem(
                    message = ChatMessage(id = "m", text = "Alte Frage?", isUser = false),
                    form = FormMessage(FormMessageKind.QUESTION, chips = listOf(FormChip(FormChipAction.ANSWER, label = "Ja", arg = "Ja"))),
                    fillCard = null, isLatestCard = false, chipsEnabled = false, onChip = { c, s -> chips += c to s }, onShowOnPage = {}, onCopy = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Answer: Ja").assertIsNotEnabled()
    }

    @Test
    fun `the page chip of show_on_page opens the page of the field it names`() {
        compose.setContent {
            MaterialTheme {
                FormMessageItem(
                    message = ChatMessage(id = "m", text = "", isUser = false),
                    form = FormMessage(FormMessageKind.PAGE, fieldId = "f3"), // the third field in page and reading order
                    fillCard = state(), isLatestCard = false, chipsEnabled = false, onChip = { _, _ -> }, onShowOnPage = { shownOnPage += it }, onCopy = {},
                )
            }
        }

        compose.onNodeWithTag("pageChip").assertIsDisplayed().performClick()

        assertThat(shownOnPage.single().id).isEqualTo("allergies")
    }

    @Test
    fun `a page chip for a field that is not on the form shows nothing`() {
        compose.setContent {
            MaterialTheme {
                FormMessageItem(
                    message = ChatMessage(id = "m", text = "", isUser = false),
                    form = FormMessage(FormMessageKind.PAGE, fieldId = "f99"),
                    fillCard = state(), isLatestCard = false, chipsEnabled = false, onChip = { _, _ -> }, onShowOnPage = {}, onCopy = {},
                )
            }
        }

        compose.onAllNodesWithTag("pageChip").assertCountEquals(0)
    }

    @Test
    fun `the chips of a question that is no longer open cannot be tapped`() {
        question(enabled = false)

        compose.onNodeWithContentDescription("Answer: Ja").assertIsNotEnabled()
    }

    @Test
    fun `a stored status line is rendered from its code in the user's language, its chip continues the run`() {
        compose.setContent {
            MaterialTheme {
                FormMessageItem(
                    message = ChatMessage(id = "m", text = "", isUser = false),
                    form = FormMessage(FormMessageKind.STATUS, FormText.AGENT_PAUSED, chips = listOf(FormChip(FormChipAction.CONTINUE, labelCode = FormChipLabel.CONTINUE))),
                    fillCard = null, isLatestCard = false, chipsEnabled = true, onChip = { c, s -> chips += c to s }, onShowOnPage = {}, onCopy = {},
                )
            }
        }

        compose.onNodeWithText("Paused. What is done so far is kept. Continue where I stopped?").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        assertThat(chips.single().first.action).isEqualTo(FormChipAction.CONTINUE)
        assertThat(chips.single().second).isEqualTo("Continue")
    }

    @Test
    fun `the chips of an earlier status line are disabled, and only the newest question or status with chips is pending`() {
        compose.setContent {
            MaterialTheme {
                FormMessageItem(
                    message = ChatMessage(id = "m", text = "", isUser = false),
                    form = FormMessage(FormMessageKind.STATUS, FormText.AGENT_PAUSED, chips = listOf(FormChip(FormChipAction.START_OVER, labelCode = FormChipLabel.START_OVER))),
                    fillCard = null, isLatestCard = false, chipsEnabled = false, onChip = { c, s -> chips += c to s }, onShowOnPage = {}, onCopy = {},
                )
            }
        }
        compose.onNodeWithText("Start over").assertIsNotEnabled()

        val question = ChatMessage(id = "q", text = "Wer?", isUser = false, form = FormMessage(FormMessageKind.QUESTION))
        val answer = ChatMessage(id = "a", text = "Ich", isUser = true)
        val paused = ChatMessage(id = "p", text = "", isUser = false, form = FormMessage(FormMessageKind.STATUS, FormText.AGENT_PAUSED, chips = listOf(FormChip(FormChipAction.CONTINUE, labelCode = FormChipLabel.CONTINUE))))
        val progress = ChatMessage(id = "w", text = "", isUser = false, form = FormMessage(FormMessageKind.STATUS, FormText.UNDERSTANDING))
        assertThat(listOf(question, answer, paused, progress).lastOrNull { isPendingChipsMessage(it) }).isEqualTo(paused)
        assertThat(listOf(question, answer).lastOrNull { isPendingChipsMessage(it) }).isEqualTo(answer)
    }

    @Test
    fun `the card message renders the live card`() {
        compose.setContent {
            MaterialTheme {
                FormMessageItem(
                    message = ChatMessage(id = "m", text = "", isUser = false),
                    form = FormMessage(FormMessageKind.CARD),
                    fillCard = state(), isLatestCard = true, chipsEnabled = false, onChip = { _, _ -> }, onShowOnPage = {}, onCopy = {},
                )
            }
        }

        compose.onNodeWithTag("fillCard").assertIsDisplayed()
    }

    @Test
    fun `a progress line shows a bar while the form is being read`() {
        compose.setContent {
            MaterialTheme {
                FormMessageItem(
                    message = ChatMessage(id = "m", text = "", isUser = false),
                    form = FormMessage(FormMessageKind.STATUS, FormText.UNDERSTANDING, listOf("2", "5")),
                    fillCard = null, isLatestCard = false, chipsEnabled = false, onChip = { _, _ -> }, onShowOnPage = {}, onCopy = {},
                )
            }
        }

        compose.onNodeWithText("Reading the form… (step 2 of 5)").assertIsDisplayed()
    }
}
