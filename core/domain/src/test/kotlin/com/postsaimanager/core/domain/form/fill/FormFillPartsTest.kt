package com.postsaimanager.core.domain.form.fill

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.agent.obj
import com.postsaimanager.core.domain.agent.str
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.FormChip
import com.postsaimanager.core.model.FormChipAction
import com.postsaimanager.core.model.FormChipLabel
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.FormMessage
import com.postsaimanager.core.model.FormMessageKind
import com.postsaimanager.core.model.FormText
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.ReviewState
import org.junit.jupiter.api.Test

class FormFillPartsTest {

    private fun field(
        id: String,
        kind: FormFieldKind = FormFieldKind.TEXT,
        value: String? = null,
        page: Int = 1,
        order: Int = 0,
        reconfirm: Boolean = false,
        skipped: Boolean = false,
        alreadyFilled: String? = null,
        review: ReviewState = ReviewState.UNREVIEWED,
    ) = FormField(
        id = id, formFillId = "fill", documentId = "doc", page = page, labelText = id, labelBox = null, fillBox = null, kind = kind,
        value = value, reconfirm = reconfirm, skipped = skipped, alreadyFilled = alreadyFilled, reviewState = review, orderIndex = order,
    )

    // ── Progress and what is still open ──

    @Test
    fun `progress counts ready, need you and signatures`() {
        val fields = listOf(
            field("a", value = "x"), field("b", value = "y"), field("c"), field("d"),
            field("e", alreadyFilled = "by hand"), field("sign", kind = FormFieldKind.SIGNATURE, page = 2),
        )

        val p = FillProgress.of(fields)

        assertThat(p).isEqualTo(FillProgress(total = 6, ready = 3, needYou = 2, signatures = 1, firstSignaturePage = 2))
    }

    @Test
    fun `open fields run by page and position, skip what needs no answer and never include the signature`() {
        val fields = listOf(
            field("p2-b", page = 2, order = 5),
            field("sign", kind = FormFieldKind.SIGNATURE, page = 1, order = 9),
            field("p1-b", page = 1, order = 3),
            field("done", value = "x", page = 1, order = 1),
            field("skipped", skipped = true, page = 1, order = 2),
            field("hand", alreadyFilled = "by hand", page = 1, order = 0),
            field("p1-a", page = 1, order = 2),
            field("still", value = "old", reconfirm = true, page = 1, order = 4),
            field("confirmed", value = "old", reconfirm = true, review = ReviewState.CONFIRMED, page = 1, order = 6),
            field("p2-a", page = 2, order = 1),
        )

        assertThat(FillProgress.openFields(fields).map { it.id }).containsExactly("p1-a", "p1-b", "still", "p2-a", "p2-b").inOrder()
    }

    @Test
    fun `a secret is masked to its last four characters`() {
        assertThat(FormMask.of("DE89 3704 0044 0532 0130 00")).isEqualTo("••••3000")
    }

    // ── Stored messages ──

    @Test
    fun `a status line survives storage and an ordinary message is not one`() {
        val form = FormMessage(
            FormMessageKind.STATUS, FormText.AGENT_PAUSED,
            chips = listOf(FormChip(FormChipAction.CONTINUE, labelCode = FormChipLabel.CONTINUE)),
        )

        val stored = FormMessageCodec.toMessage("m1", "conv", 5, form)

        assertThat(stored.role).isEqualTo(MessageRole.TOOL_RESULT) // never part of the model's history
        assertThat(FormMessageCodec.parse(stored)).isEqualTo(form)
        assertThat(FormMessageCodec.isAgentStep(stored)).isFalse()
        assertThat(FormMessageCodec.parse(stored.copy(toolName = null))).isNull()
        assertThat(FormMessageCodec.parse(stored.copy(toolArgs = "{not json"))).isNull()
    }

    private fun call(name: String, args: String, content: String = "") = AiMessage(
        id = "m", conversationId = "conv", role = MessageRole.TOOL_CALL, content = content, toolCallId = "c1", toolName = name, toolArgs = args, createdAt = 1,
    )

    @Test
    fun `an ask_user call renders as the question with its chips as answers, other calls render nothing or their own thing`() {
        val ask = call("ask_user", obj("question" to str("Wer?"), "chips" to kotlinx.serialization.json.JsonArray(listOf(str("Ahmad"), str("Ich")))).toString(), "Wer?")

        val form = FormMessageCodec.parse(ask)!!

        assertThat(form.kind).isEqualTo(FormMessageKind.QUESTION)
        assertThat(form.chips).containsExactly(
            FormChip(FormChipAction.ANSWER, label = "Ahmad", arg = "Ahmad"),
            FormChip(FormChipAction.ANSWER, label = "Ich", arg = "Ich"),
        ).inOrder()
        assertThat(FormMessageCodec.parse(call("ask_user", "{}"))!!.chips).isEmpty()
        assertThat(FormMessageCodec.parse(call("finish", obj("summary" to str("Fertig.")).toString()))!!.kind).isEqualTo(FormMessageKind.QUESTION)
        assertThat(FormMessageCodec.parse(call("show_fill_card", "{}"))!!.kind).isEqualTo(FormMessageKind.CARD)
        val page = FormMessageCodec.parse(call("show_on_page", obj("field_id" to str("f3")).toString()))!!
        assertThat(page.kind).isEqualTo(FormMessageKind.PAGE)
        assertThat(page.fieldId).isEqualTo("f3")
        assertThat(FormMessageCodec.parse(call("read_form", "{}"))).isNull()
        assertThat(FormMessageCodec.isAgentStep(call("read_form", "{}"))).isTrue()
        // An ordinary assistant message and a tool call without an id are no form message.
        assertThat(FormMessageCodec.parse(ask.copy(toolCallId = null))).isNull()
        assertThat(FormMessageCodec.parse(ask.copy(role = MessageRole.ASSISTANT))).isNull()
    }
}
