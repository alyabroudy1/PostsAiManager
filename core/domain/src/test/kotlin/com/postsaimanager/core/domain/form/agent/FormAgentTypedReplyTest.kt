package com.postsaimanager.core.domain.form.agent

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.agent.AgentContext
import com.postsaimanager.core.domain.agent.AgentEntry
import com.postsaimanager.core.domain.agent.AgentStepTrace
import com.postsaimanager.core.domain.agent.AgentTrace
import com.postsaimanager.core.model.FormMessageKind
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.model.Relationship
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * The device loop where the user TYPED a stored person's name instead of tapping its chip: the typed name registers like the tap, the
 * guidance never names a tool that is not exposed, a chip is a label (never `p1`), and a question in the wrong language is turned back.
 */
class FormAgentTypedReplyTest {

    private val start = AgentEntry.UserText("Help me fill in this form.", isStart = true)

    private fun askCall(question: String, vararg chips: String) = AgentEntry.Call(
        "ask1", "ask_user",
        JsonObject(mapOf("question" to JsonPrimitive(question), "chips" to JsonArray(chips.map(::JsonPrimitive)))),
    )

    // ── The device loop ──

    @Test
    fun `a typed stored name registers like a chip tap and the model fills from the profile instead of looping on ask_user`() = runTest {
        val h = AgentHarness(dynamicTools = true)
        h.model.reply(h.call("read_form"))
        h.model.reply(h.call("list_people"))
        h.model.reply(h.ask("Für wen ist das Formular?", "Ahmad", "Me"))
        h.agent.start("doc")

        // The user types the name (any case and spacing) instead of tapping a chip. The model still asks first, as on the device...
        h.model.reply(h.ask("Für wen ist das Formular?", "Ahmad", "Me"))
        // ...is told the only function now is fill_from_profile, and the state's suggestion names that one too.
        h.model.reply { message ->
            assertThat(message).contains("ask_user is not available now. Available now: fill_from_profile")
            assertThat(message).contains("suggested next: fill_from_profile(person_id=p2, role=subject)")
            h.call("fill_from_profile", "person_id" to "p2", "role" to "subject")
        }
        h.model.reply(h.call("fill_from_profile", "person_id" to "p1", "role" to "guardian"))
        h.model.reply(h.ask("Hat Ahmad das Seepferdchen schon?", "Ja", "Nein"))
        h.agent.route("doc", "  AHMAD ")

        assertThat(h.results().none { it.contains("already answered") }).isTrue()
        assertThat(h.field("Name des Kindes").value).isEqualTo("Ahmad Mustermann")
        assertThat(h.model.sent.any { it.contains("\"answer\":\"  AHMAD \"") && it.contains("\"matched_person\":\"p2\"") }).isTrue()
        assertThat(h.shown().last { it.first.kind == FormMessageKind.QUESTION }.second).isEqualTo("Hat Ahmad das Seepferdchen schon?")
    }

    @Test
    fun `the repeat block's error names only exposed tools`() = runTest {
        val h = AgentHarness(dynamicTools = true)
        h.model.reply(h.call("read_form"))
        h.model.reply(h.call("fill_from_profile", "person_id" to "p2", "role" to "subject"))
        h.model.reply(h.call("fill_from_profile", "person_id" to "p1", "role" to "guardian"))
        h.model.reply(h.call("fill_from_profile", "person_id" to "p1", "role" to "payer"))
        h.model.reply(h.ask("Hat Ahmad das Seepferdchen schon?", "Ja", "Nein"))
        h.agent.start("doc")

        h.model.reply(h.ask("Hat Ahmad das Seepferdchen schon?", "Ja", "Nein"))
        h.model.reply { message ->
            assertThat(message).contains("already answered: Nein")
            val suggestion = message.substringAfter("suggested next: ").substringBefore("\"")
            val allowed = message.substringAfter("\"tools_now\":\"").substringBefore("\"").split(", ")
            assertThat(allowed).containsAtLeastElementsIn(ToolPolicy().named(suggestion))
            h.ask("Wie lautet das Geburtsdatum des Kindes?")
        }
        h.agent.route("doc", "Nein")
    }

    @Test
    fun `a name that fits several people is not registered, the model gets the candidates`() = runTest {
        val h = AgentHarness()
        h.profiles.seed(
            testProfile(id = "lena1", name = "Lena Alt", type = ProfileType.FAMILY_MEMBER, relationship = Relationship.CHILD),
            testProfile(id = "lena2", name = "Lena Neu", type = ProfileType.FAMILY_MEMBER, relationship = Relationship.CHILD),
        )
        val guidance = FormGuidance(h.envFor("doc"))

        assertThat(guidance.matchPerson("Lena")).isNull()
        assertThat(guidance.ambiguousPeople("lena").map { it.name }).containsExactly("Lena Alt", "Lena Neu")
        assertThat(guidance.matchPerson("Lena Neu")?.name).isEqualTo("Lena Neu")
        assertThat(guidance.matchPerson("  lena   NEU")?.name).isEqualTo("Lena Neu")
        val result = guidance.replyResult(askCall("Für wen?", "Lena Alt"), "Lena").toModelText()
        assertThat(result).contains("\"candidates\":\"Lena Alt, Lena Neu\"")
        assertThat(result).doesNotContain("matched_person")
    }

    // ── Guidance and exposure agree ──

    private suspend fun scenario(stage: FormStage): Pair<AgentHarness, List<AgentEntry>> {
        val h = AgentHarness(dynamicTools = true)
        if (stage == FormStage.NOT_READ) return h to listOf(start)
        h.exec("read_form")
        return when (stage) {
            FormStage.SUBJECT_UNKNOWN -> h to listOf(start)
            FormStage.ROLE_ANSWERED -> h to listOf(start, askCall("Für wen?", "Ahmad", "Me"), AgentEntry.UserText("Ahmad"))
            FormStage.ROLE_READY -> {
                h.exec("fill_from_profile", "person_id" to "p2", "role" to "subject")
                h to listOf(start)
            }
            FormStage.ROLE_NEEDS_PERSON -> {
                h.exec("fill_from_profile", "person_id" to "p1", "role" to "subject")
                h to listOf(start)
            }
            FormStage.ROLE_TYPED -> {
                h.exec("fill_from_profile", "person_id" to "p1", "role" to "subject")
                h to listOf(start, askCall("Wer ist die erziehungsberechtigte Person?", "Ahmad", "Someone else"), AgentEntry.UserText("Erika Test"))
            }
            FormStage.OPEN_FIELDS, FormStage.NOTHING_OPEN -> {
                listOf("subject", "guardian", "payer", "signer").forEach { h.exec("fill_from_profile", "person_id" to "p1", "role" to it) }
                if (stage == FormStage.NOTHING_OPEN) {
                    h.fills.saveFields("fill-doc", h.fields().map { it.copy(skipped = true) })
                }
                h to listOf(start)
            }
            FormStage.NOT_READ -> error("handled above")
        }
    }

    @Test
    fun `in every stage the suggested next names only tools that are exposed there`() = runTest {
        val policy = ToolPolicy()
        for (stage in FormStage.entries) {
            val (h, entries) = scenario(stage)
            val env = h.envFor("doc")
            val guidance = FormGuidance(env, policy)
            val context = AgentContext.of(entries)
            assertThat(FormToolExposure(env, guidance, policy).stage(context)).isEqualTo(stage)

            val allowed = policy.allowed(stage, rememberPending = true)
            val state = guidance.state(UserReply.of(context))
            val suggestion = state.substringAfter("suggested next: ")
            assertThat(policy.named(suggestion)).isNotEmpty()
            assertThat(allowed).containsAtLeastElementsIn(policy.named(suggestion))
        }
    }

    @Test
    fun `a suggestion that would name a tool of another stage is replaced by the exposed tools`() = runTest {
        // A policy that does not expose fill_from_profile for a named subject while the text still points at it.
        val policy = ToolPolicy(ToolPolicy.DEFAULT + (FormStage.ROLE_ANSWERED to listOf("ask_user")))
        val h = AgentHarness(dynamicTools = true)
        h.exec("read_form")
        val guidance = FormGuidance(h.envFor("doc"), policy)

        val state = guidance.state(UserReply("Ahmad", askCall("Für wen?", "Ahmad", "Me")))

        assertThat(state).contains("suggested next: call one of: ask_user")
        assertThat(state).doesNotContain("suggested next: fill_from_profile")
    }

    // ── Chips are labels ──

    @Test
    fun `a person id passed as a chip becomes the person's name, an unknown id is refused with a hint`() = runTest {
        val h = AgentHarness()
        h.exec("read_form")
        val ask = h.tools.specFor("doc").tools["ask_user"]!!

        val mapped = ask.normalize(JsonObject(mapOf("question" to JsonPrimitive("Q"), "chips" to JsonArray(listOf("p1", "Ahmad", "P2").map(::JsonPrimitive)))))
        assertThat((mapped["chips"] as JsonArray).map { (it as JsonPrimitive).content }).containsExactly("Me", "Ahmad").inOrder()

        val refused = h.exec("ask_user", "question" to "Für wen ist das Formular?", json = mapOf("chips" to JsonArray(listOf("p9", "Ahmad").map(::JsonPrimitive))))
        assertThat(refused.ok).isFalse()
        assertThat(refused.text("error")).contains("internal id")
        assertThat(refused.text("error")).contains("Ahmad")
        val field = h.exec("ask_user", "question" to "Für wen ist das Formular?", json = mapOf("chips" to JsonArray(listOf("f3", "Ahmad").map(::JsonPrimitive))))
        assertThat(field.ok).isFalse()
    }

    @Test
    fun `chips with ids are stored and shown as names`() = runTest {
        val h = AgentHarness()
        h.model.reply(h.call("read_form"))
        h.model.reply(h.ask("Für wen ist das Formular?", "p1", "Ahmad"))
        h.agent.start("doc")

        val stored = h.messages().first { it.toolName == "ask_user" }.toolArgs.orEmpty()
        assertThat(stored).contains("\"Me\"")
        assertThat(stored).doesNotContain("p1")
        val chips = h.shown().last { it.first.kind == FormMessageKind.QUESTION }.first.chips.map { it.label }
        assertThat(chips).containsExactly("Me", "Ahmad").inOrder()
    }

    // ── Language ──

    @Test
    fun `an English question on a German form is turned back once, and the prompt's example is language-neutral`() = runTest {
        val h = AgentHarness()
        h.model.reply(h.call("read_form"))
        h.model.reply(h.ask("Who is the pass for the child?", "Ahmad", "Me"))
        h.model.reply(h.ask("Für wen ist das Formular?", "Ahmad", "Me"))
        h.agent.start("doc")

        val system = h.model.sessions.single().system
        assertThat(system).contains("write every question in German")
        assertThat(system).doesNotContain("Who is the pass for?")
        assertThat(system).contains("<who is the pass for, in German>")
        assertThat(h.results().any { it.contains("the question is not written in German: write it in German") }).isTrue()
        assertThat(h.shown().last { it.first.kind == FormMessageKind.QUESTION }.second).isEqualTo("Für wen ist das Formular?")
    }

    @Test
    fun `a German question that shares a word with the form passes, and a refused one repeated unchanged goes through`() = runTest {
        val h = AgentHarness()
        h.model.reply(h.call("read_form"))
        h.model.reply(h.ask("Wie lautet das Geburtsdatum des Kindes?"))
        h.agent.start("doc")
        assertThat(h.results().none { it.contains("not written in") }).isTrue()

        val g = AgentHarness()
        g.model.reply(g.call("read_form"))
        g.model.reply(g.ask("Who is this for, please tell me?"))
        g.model.reply(g.ask("Who is this for, please tell me?"))
        g.agent.start("doc")
        assertThat(g.results().count { it.contains("not written in German") }).isEqualTo(1)
        assertThat(g.shown().last { it.first.kind == FormMessageKind.QUESTION }.second).isEqualTo("Who is this for, please tell me?")
    }

    // ── The trace ──

    @Test
    fun `every step line carries the tools the model could call`() = runTest {
        val h = AgentHarness(dynamicTools = true)
        val steps = mutableListOf<AgentStepTrace>()
        h.model.reply(h.call("read_form"))
        h.model.reply(h.call("list_people"))
        h.model.reply(h.ask("Für wen ist das Formular?", "Ahmad", "Me"))

        h.newAgent(AgentTrace { steps += it }).start("doc")

        assertThat(steps.map { it.toolsNow }).containsExactly(
            listOf("read_form"), listOf("list_people", "ask_user"), listOf("list_people", "ask_user"),
        ).inOrder()
        assertThat(steps.joinToString("\n") { it.line() }).contains("tools_now=[read_form] ")
        assertThat(steps.joinToString("\n") { it.line() }).contains("tools_now=[list_people|ask_user]")
    }
}
