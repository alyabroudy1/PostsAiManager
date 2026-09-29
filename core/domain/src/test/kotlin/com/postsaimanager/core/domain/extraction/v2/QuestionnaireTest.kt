package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Answers the questionnaire the way a correct model would, from the benchmark oracle's single-call answer. */
internal object OracleQuestionnaire {

    fun responder(letter: Letter, p: Prepared): (String, String) -> String? {
        val raw = (InterpretationParser.parse(Oracle.structured(letter, p).json) as InterpretationParser.Parsed.Ok).value
        val text = (InterpretationParser.parseText(Oracle.text(letter)) as InterpretationParser.Parsed.Ok).value
        val slots = ExtractionSchema.DEFAULT.allSlots

        fun id(v: String) = if (v == "NONE" || p.offered.get(v) != null) v else "\"$v\""
        fun party(x: RawParty, withRelation: Boolean) =
            "${id(x.id)} ${x.kind} ${if (withRelation) x.relation + " " else ""}\"${x.name}\" ${x.confidence}"
        fun parties(role: PartyRole) = raw.parties.filter { it.role == role.name }

        return { question, _ ->
            when {
                question.contains("What kind of document") -> "${raw.type} ${raw.language} ${raw.typeConfidence}"
                question.contains("Who wrote and sent") -> parties(PartyRole.SENDER).firstOrNull()?.let { party(it, false) } ?: "NONE"
                question.contains("To whom is the letter addressed") -> {
                    val all = parties(PartyRole.ADDRESSEE) + parties(PartyRole.CO_ADDRESSEE)
                    if (all.isEmpty()) "NONE" else all.joinToString("; ") { party(it, true) }
                }
                question.contains("Who is the letter about") -> {
                    val all = parties(PartyRole.SUBJECT_PERSON)
                    if (all.isEmpty()) "NONE" else all.joinToString("; ") { party(it, false) }
                }
                question.contains("contact person") -> parties(PartyRole.ROUTING).firstOrNull()?.let { party(it, false) } ?: "NONE"
                question.contains("sent in care of") -> parties(PartyRole.CARE_OF).firstOrNull()?.let { party(it, false) } ?: "NONE"
                question.contains("other important facts") ->
                    if (raw.extras.isEmpty()) {
                        "NONE"
                    } else {
                        raw.extras.joinToString("; ") { "${id(it.id)} \"${it.label}\" ${it.key} \"${it.value}\" ${it.confidence}" }
                    }
                question.contains("title of at most 8 words") -> "\"${text.title}\""
                question.contains("Copy the subject line") -> "\"${text.subject}\""
                question.contains("one or two sentences") -> "\"${text.summary}\""
                question.contains("three short questions") -> text.questions.joinToString(" ") { "\"$it\"" }
                else -> {
                    val slot = slots.firstOrNull { question.contains(it.question) }
                    val answer = slot?.let { raw.slots[it.json] }
                    if (slot == null || answer == null) {
                        "NONE"
                    } else {
                        when (slot.kind) {
                            SlotKind.AMOUNT, SlotKind.DATE -> "${answer.id} ${answer.role} ${answer.confidence}"
                            SlotKind.DEADLINE ->
                                if (answer.rule != null) "RULE \"${answer.rule}\" ${answer.role} ${answer.confidence}"
                                else "${answer.id} ${answer.role} ${answer.confidence}"
                            SlotKind.REFERENCE_LIST -> answer.ids.joinToString(" ") + " " + answer.confidence
                            else -> "${id(answer.id.orEmpty())} ${answer.confidence}"
                        }
                    }
                }
            }
        }
    }
}

class QuestionnaireTest {

    private val schema = ExtractionSchema.DEFAULT

    @Nested
    inner class QuestionsFromTheRegistry {

        @Test
        fun `every slot of every type carries its own question and every type its description`() {
            for (type in schema.types) {
                assertThat(type.description).isNotEmpty()
                for (slot in type.slots) assertThat(slot.question).isNotEmpty()
            }
            // No two slots share a question, or a model could not tell them apart.
            val questions = schema.allSlots.map { it.question }
            assertThat(questions.toSet()).hasSize(questions.size)
        }

        @Test
        fun `the type question offers every type of the registry with its description`() {
            val q = QuestionnairePrompt.type(schema)
            for (type in schema.types) {
                assertThat(q.text).contains("- ${type.id}: ${type.description}")
            }
            val matcher = GbnfMatcher(q.grammar)
            for (type in schema.types) assertThat(matcher.accepts("${type.id} de HIGH")).isTrue()
            assertThat(matcher.accepts("invoice de HIGH")).isFalse()
        }

        @Test
        fun `a question is generated for each slot of each type that has something to choose from`() {
            for (letter in Letters.all) {
                val prepared = Prepared(letter.pages)
                for (slot in letter.type.slots) {
                    val q = QuestionnairePrompt.slot(slot, prepared.offered)
                    val hasIds = prepared.offered.idsOf(*slot.kind.candidates).isNotEmpty()
                    val canQuote = slot.kind == SlotKind.DEADLINE || slot.kind == SlotKind.NAME || slot.kind == SlotKind.ACTION
                    if (hasIds || canQuote) {
                        assertThat(q).isNotNull()
                        assertThat(q!!.text).contains(slot.question)
                        assertThat(q.name).isEqualTo("slot:${slot.json}")
                    } else {
                        assertThat(q).isNull()
                    }
                }
            }
        }

        @Test
        fun `every type's questions can be asked of a letter with no candidates at all`() {
            val none = OfferedCandidates(emptyList())
            for (type in schema.types) {
                for (slot in type.slots) {
                    val q = QuestionnairePrompt.slot(slot, none)
                    if (q != null) GbnfMatcher(q.grammar).accepts("NONE") // the grammar parses and takes NONE
                }
            }
        }
    }

    @Nested
    inner class TinyGrammars {

        private val prepared = Prepared(Letters.n1.pages)
        private val offered = prepared.offered

        private fun idOf(vararg kinds: CandidateKind) = offered.idsOf(*kinds).first()

        @Test
        fun `an amount slot accepts an offered amount id with a role and a confidence, and NONE`() {
            val q = QuestionnairePrompt.slot(Slots.TOTAL, offered)!!
            val m = GbnfMatcher(q.grammar)
            val amount = idOf(CandidateKind.AMOUNT)
            assertThat(m.accepts("$amount TOTAL_DUE HIGH")).isTrue()
            assertThat(m.accepts("NONE")).isTrue()
        }

        @Test
        fun `an amount slot rejects ids of other kinds, ids that were not offered, and unknown roles`() {
            val m = GbnfMatcher(QuestionnairePrompt.slot(Slots.TOTAL, offered)!!.grammar)
            val date = idOf(CandidateKind.DATE)
            assertThat(m.accepts("$date TOTAL_DUE HIGH")).isFalse()
            assertThat(m.accepts("A999 TOTAL_DUE HIGH")).isFalse()
            assertThat(m.accepts("${idOf(CandidateKind.AMOUNT)} SOMETHING HIGH")).isFalse()
            assertThat(m.accepts("${idOf(CandidateKind.AMOUNT)} TOTAL_DUE MAYBE")).isFalse()
        }

        @Test
        fun `a date slot takes date ids only`() {
            val m = GbnfMatcher(QuestionnairePrompt.slot(Slots.LETTER_DATE, offered)!!.grammar)
            assertThat(m.accepts("${idOf(CandidateKind.DATE)} LETTER_DATE HIGH")).isTrue()
            assertThat(m.accepts("${idOf(CandidateKind.AMOUNT)} LETTER_DATE HIGH")).isFalse()
        }

        @Test
        fun `a deadline slot also takes a quoted period, and a plain date slot does not`() {
            val due = GbnfMatcher(QuestionnairePrompt.slot(Slots.DUE_DATE, offered)!!.grammar)
            assertThat(due.accepts("RULE \"within 14 days\" DEADLINE MEDIUM")).isTrue()
            assertThat(due.accepts("${idOf(CandidateKind.DATE)} DEADLINE HIGH")).isTrue()
            val date = GbnfMatcher(QuestionnairePrompt.slot(Slots.LETTER_DATE, offered)!!.grammar)
            assertThat(date.accepts("RULE \"within 14 days\" LETTER_DATE MEDIUM")).isFalse()
        }

        @Test
        fun `a deadline slot with no date candidates still takes a rule or NONE`() {
            val none = OfferedCandidates(emptyList())
            val q = QuestionnairePrompt.slot(Slots.DUE_DATE, none)!!
            val m = GbnfMatcher(q.grammar)
            assertThat(m.accepts("NONE")).isTrue()
            assertThat(m.accepts("RULE \"within one month\" DEADLINE LOW")).isTrue()
            assertThat(m.accepts("D1 DEADLINE HIGH")).isFalse()
        }

        @Test
        fun `a slot with no candidate of its kind is not asked at all`() {
            val amountsOnly = OfferedCandidates(offered.rows.filter { it.candidate.kind == CandidateKind.AMOUNT })
            assertThat(QuestionnairePrompt.slot(Slots.IBAN, amountsOnly)).isNull()
            assertThat(QuestionnairePrompt.slot(Slots.REFERENCE, amountsOnly)).isNull()
            assertThat(QuestionnairePrompt.slot(Slots.LETTER_DATE, amountsOnly)).isNull()
        }

        @Test
        fun `party questions take name ids or a quoted name`() {
            val names = offered.idsOf(CandidateKind.NAME)
            val m = GbnfMatcher(QuestionnairePrompt.sender(offered).grammar)
            assertThat(m.accepts("${names.first()} COMPANY \"Some Company\" HIGH")).isTrue()
            assertThat(m.accepts("\"Copied Name\" PERSON \"Copied Name\" MEDIUM")).isTrue()
            assertThat(m.accepts("NONE")).isTrue()
            assertThat(m.accepts("A1 COMPANY \"x\" HIGH")).isFalse()
            assertThat(m.accepts("${names.first()} ROBOT \"x\" HIGH")).isFalse()
        }

        @Test
        fun `the addressee question leaves out the sender's id and takes up to a list of co-addressees`() {
            val names = offered.idsOf(CandidateKind.NAME)
            check(names.size >= 2) { "the fixture must offer two names" }
            val sender = names[0]
            val other = names[1]
            val m = GbnfMatcher(QuestionnairePrompt.addressee(offered, sender).grammar)
            assertThat(m.accepts("$other PERSON HOUSEHOLD \"Familie X\" HIGH")).isTrue()
            assertThat(m.accepts("$sender PERSON NONE \"Sender\" HIGH")).isFalse()
            assertThat(m.accepts("$other PERSON NONE \"A\" HIGH; \"B\" PERSON NONE \"B\" MEDIUM")).isTrue()
            val without = GbnfMatcher(QuestionnairePrompt.addressee(offered, null).grammar)
            assertThat(without.accepts("$sender PERSON NONE \"Sender\" HIGH")).isTrue()
        }

        @Test
        fun `the extras question offers only the ids nothing else took`() {
            val ids = offered.idsOf(*CandidateKind.entries.toTypedArray())
            val taken = ids.take(2).toSet()
            val free = ids[2]
            val m = GbnfMatcher(QuestionnairePrompt.extras(offered, taken).grammar)
            assertThat(m.accepts("$free \"Label\" some_key \"\" MEDIUM")).isTrue()
            assertThat(m.accepts("NONE \"Klasse\" school_class \"2a\" LOW; $free \"L\" key \"\" HIGH")).isTrue()
            assertThat(m.accepts("${taken.first()} \"Label\" some_key \"\" MEDIUM")).isFalse()
            assertThat(m.accepts("NONE")).isTrue()
        }

        @Test
        fun `a reference list takes several reference ids`() {
            val slot = Slots.CITED_REFERENCES
            val refs = offered.idsOf(CandidateKind.REFERENCE)
            val q = QuestionnairePrompt.slot(slot, offered)
            if (refs.size >= 2) {
                val m = GbnfMatcher(q!!.grammar)
                assertThat(m.accepts("${refs[0]} ${refs[1]} MEDIUM")).isTrue()
                assertThat(m.accepts("${refs[0]} ${idOf(CandidateKind.DATE)} MEDIUM")).isFalse()
            }
        }
    }

    @Nested
    inner class AnswersAreRead {

        @Test
        fun `a slot answer becomes the raw slot the verifier already takes`() {
            val amount = AnswerReader.slot(Slots.TOTAL, "A3 TOTAL_DUE HIGH")!!
            assertThat(amount.id).isEqualTo("A3")
            assertThat(amount.role).isEqualTo("TOTAL_DUE")
            assertThat(amount.confidence).isEqualTo("HIGH")
            val rule = AnswerReader.slot(Slots.DUE_DATE, "RULE \"innerhalb von 14 Tagen\" DEADLINE MEDIUM")!!
            assertThat(rule.rule).isEqualTo("innerhalb von 14 Tagen")
            assertThat(rule.id).isNull()
            assertThat(AnswerReader.slot(Slots.IBAN, "NONE")).isNull()
            assertThat(AnswerReader.slot(Slots.CITED_REFERENCES, "R1 R2 R3 HIGH")!!.ids).containsExactly("R1", "R2", "R3").inOrder()
        }

        @Test
        fun `a cut-off answer is not an answer`() {
            assertThat(AnswerReader.slot(Slots.TOTAL, "A3 TOTAL_DUE")).isNull()
            assertThat(AnswerReader.parties("M1 COMPANY \"Stadtwerke Bei", withRelation = false)).isEmpty()
            val extras = AnswerReader.extras("N4 \"Zählernummer\" meter_number \"\" MEDIUM; NONE \"Kla")
            assertThat(extras.map { it.label }).containsExactly("Zählernummer")
        }

        @Test
        fun `parties keep their kind, relation and name`() {
            val list = AnswerReader.parties("M2 PERSON HOUSEHOLD \"Familie Beispiel\" HIGH; M3 PERSON NONE \"Max B\" LOW", withRelation = true)
            assertThat(list.map { it.id }).containsExactly("M2", "M3").inOrder()
            assertThat(list[0].relation).isEqualTo("HOUSEHOLD")
            assertThat(list[0].name).isEqualTo("Familie Beispiel")
            assertThat(AnswerReader.parties("NONE", withRelation = true)).isEmpty()
        }

        @Test
        fun `a name in Arabic script survives the quotes`() {
            val list = AnswerReader.parties("M1 COMPANY \"شركة الكهرباء\" HIGH", withRelation = false)
            assertThat(list.single().name).isEqualTo("شركة الكهرباء")
        }
    }

    @Nested
    inner class RollbackContract {

        private val letter = Letters.n1
        private val prepared = Prepared(letter.pages)

        private fun interpreter(session: FakePromptSession) =
            QuestionnaireInterpreter(FakeAiEngine(), session, contextTokens = 4096)

        private fun request() = InterpretationRequest(prepared.layout.describe().text, prepared.offered)

        @Test
        fun `the prefix is decoded once and every question starts from exactly that prefix`() = runTest {
            val session = FakePromptSession().apply { responder = OracleQuestionnaire.responder(letter, prepared) }
            val model = interpreter(session)
            val outcome = model.interpret(request())
            assertThat(outcome).isInstanceOf(InterpretationOutcome.Answered::class.java)
            model.writeText(TextRequest("", letter.type.id))

            assertThat(session.opens).hasSize(1)
            assertThat(session.prefixDecodes).isEqualTo(1)
            assertThat(session.asks.size).isGreaterThan(10)
            // The rollback: no question ever saw an earlier question or its answer.
            assertThat(session.stateAtAsk.toSet()).containsExactly(session.opens.single())
            // The letter and the candidate table are in the prefix, the question is not.
            assertThat(session.opens.single()).contains("CANDIDATES")
            assertThat(session.opens.single()).doesNotContain(QuestionnairePrompt.type(schema).text)
            assertThat(session.asks.first().question).contains(QuestionnairePrompt.type(schema).text)
            assertThat(session.closes).isEqualTo(1)
        }

        @Test
        fun `the questions come in the documented order, the slot questions from the chosen type`() = runTest {
            val session = FakePromptSession().apply { responder = OracleQuestionnaire.responder(letter, prepared) }
            val model = interpreter(session)
            model.interpret(request())
            val names = model.transcript.map { it.name }
            assertThat(names.take(6)).containsExactly("type", "sender", "addressee", "subject_person", "contact", "care_of").inOrder()
            assertThat(names.last()).isEqualTo("extras")
            val slotNames = names.filter { it.startsWith("slot:") }
            assertThat(slotNames.size).isAtMost(letter.type.slots.size)
            assertThat(slotNames).containsNoDuplicates()
            for (n in slotNames) assertThat(letter.type.slots.map { "slot:${it.json}" }).contains(n)
        }

        @Test
        fun `a state clobbered between two questions is repaired by reading the prefix again, once`() = runTest {
            val session = FakePromptSession()
            var calls = 0
            val oracle = OracleQuestionnaire.responder(letter, prepared)
            session.responder = { q, g ->
                if (++calls == 4) session.clobber() // another engine user clears the KV after this question
                oracle(q, g)
            }
            val model = interpreter(session)
            model.interpret(request())
            assertThat(session.prefixDecodes).isEqualTo(2)
            assertThat(session.stateAtAsk.toSet()).containsExactly(session.opens.single())
        }

        @Test
        fun `a failed question is a missing answer, and three in a row end the reading`() = runTest {
            val oracle = OracleQuestionnaire.responder(letter, prepared)
            var n = 0
            val one = FakePromptSession().apply { responder = { q, g -> if (++n == 3) null else oracle(q, g) } }
            val fine = interpreter(one).interpret(request())
            assertThat(fine).isInstanceOf(InterpretationOutcome.Answered::class.java)

            val broken = FakePromptSession().apply { responder = { _, _ -> null } }
            val outcome = interpreter(broken).interpret(request())
            assertThat(outcome).isInstanceOf(InterpretationOutcome.Failed::class.java)
            assertThat(broken.closes).isEqualTo(1)
        }

        @Test
        fun `a prefix the engine cannot read is a failed outcome, not an exception`() = runTest {
            val session = FakePromptSession().apply { openFailsWith = PamError.InferenceError("too long") }
            val outcome = interpreter(session).interpret(request())
            assertThat(outcome).isInstanceOf(InterpretationOutcome.Failed::class.java)
            assertThat(session.asks).isEmpty()
        }

        @Test
        fun `the sender's id is never offered as the addressee`() = runTest {
            val session = FakePromptSession().apply { responder = OracleQuestionnaire.responder(letter, prepared) }
            val model = interpreter(session)
            model.interpret(request())
            val senderAnswer = model.transcript.first { it.name == "sender" }.answer!!
            val senderId = senderAnswer.substringBefore(' ')
            val addresseeGrammar = session.asks.first { it.question.contains("To whom is the letter addressed") }.grammar
            assertThat(prepared.offered.get(senderId)).isNotNull()
            assertThat(addresseeGrammar).doesNotContain("\"$senderId\"")
        }
    }

    @Nested
    inner class SameResultAsTheSingleCall {

        @Test
        fun `for every benchmark letter the questionnaire of the oracle gives the same verified result as the single call`() = runTest {
            val pipeline = ExtractionV2Pipeline()
            for (letter in Letters.all) {
                val prepared = Prepared(letter.pages)
                val single = pipeline.run(
                    letter.pages,
                    ScriptedInterpreter(Oracle.structured(letter, prepared).json, Oracle.text(letter)),
                    4096,
                )
                val session = FakePromptSession().apply { responder = OracleQuestionnaire.responder(letter, prepared) }
                val asked = pipeline.run(letter.pages, QuestionnaireInterpreter(FakeAiEngine(), session, contextTokens = 4096), 4096)

                fun ExtractionV2Result.shape() = listOf(
                    documentType?.id,
                    slots.map { (k, v) -> "${k.json}=${v.candidateId}/${v.normalized}/${v.role}/${v.aiConfidence}" }.sorted(),
                    parties.all.map { "${it.role}/${it.kind}/${it.relation}/${it.name}" }.sorted(),
                    extras.map { "${it.label}/${it.value.normalized}" }.sorted(),
                    freeText.title?.value, freeText.subject?.value, freeText.suggestedQuestions,
                )
                assertWithMessage(letter.id).that(asked.shape()).isEqualTo(single.shape())
                assertThat(session.prefixDecodes).isEqualTo(1)
            }
        }
    }

    @Nested
    inner class BudgetUsesTheTokenizer {

        private val letter = Letters.tax // the longest fixture
        private val prepared = Prepared(letter.pages)

        private suspend fun sentLayoutChars(session: FakePromptSession): Int {
            val model = QuestionnaireInterpreter(FakeAiEngine(), session, contextTokens = 4096)
            val result = ExtractionV2Pipeline().run(letter.pages, model, 4096)
            return result.diagnostics.layoutCharsSent
        }

        @Test
        fun `the letter is fitted by counted tokens, not by 2 and a half characters per token`() = runTest {
            // A tokenizer that finds a token in every character: the estimate would overflow the window.
            val dense = FakePromptSession().apply { tokenCounter = { it.length } }
            val sent = sentLayoutChars(dense)
            val counted = dense.countedTexts.filter { it.startsWith("=== PAGE") }
            assertThat(counted).isNotEmpty()
            val available = 4096 - QuestionnairePrompt.QUESTION_RESERVE_TOKENS - dense.tokenCounter(QuestionnairePrompt.system(true)) -
                dense.tokenCounter(SelectionPrompt.table(prepared.offered)) - 64
            assertThat(sent).isAtMost(available)
            assertThat(sent).isGreaterThan(0)
        }

        @Test
        fun `a sparse tokenizer lets more of the letter in than the estimate would`() = runTest {
            val sparse = FakePromptSession().apply { tokenCounter = { (it.length / 8) + 1 } }
            val estimated = FakePromptSession().apply { canCount = false }
            assertThat(sentLayoutChars(sparse)).isAtLeast(sentLayoutChars(estimated))
        }

        @Test
        fun `an interpreter that cannot count falls back to the estimate`() = runTest {
            val session = FakePromptSession().apply { canCount = false }
            sentLayoutChars(session)
            assertThat(session.countedTexts).isEmpty()
        }

        @Test
        fun `the single call interpreter keeps its estimate`() = runTest {
            val model = ModelDocumentInterpreter(FakeAiEngine(), contextTokens = 4096)
            assertThat(model.promptOverheadTokens(prepared.offered)).isNull()
            assertThat(model.countTokens("anything")).isNull()
        }
    }

    @Test
    fun `PamResult of the fake session is what the port promises`() = runTest {
        val session = FakePromptSession().apply { responder = { _, _ -> "NONE" } }
        assertThat(session.ask("q", "root ::= \"NONE\"", 4)).isInstanceOf(PamResult.Error::class.java) // not opened
        session.open("prefix")
        assertThat((session.ask("q", "root ::= \"NONE\"", 4) as PamResult.Success).data).isEqualTo("NONE")
    }
}
