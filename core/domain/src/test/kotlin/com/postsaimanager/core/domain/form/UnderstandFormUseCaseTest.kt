package com.postsaimanager.core.domain.form

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.FormRole
import com.postsaimanager.core.model.FormValueSource
import com.postsaimanager.core.model.Relationship
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

class UnderstandFormUseCaseTest {

    private val today = LocalDate.of(2026, 10, 1)
    private val nowMs = ZonedDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private val recent = ZonedDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private val ahmadProfile = SubjectCandidate("ahmad", "Ahmad", Relationship.CHILD, birthDate = LocalDate.of(2019, 3, 12))
    private val meProfile = SubjectCandidate("me", "Me", isSelf = true, birthDate = LocalDate.of(1985, 6, 1))

    // ── What the scripted model "knows" about the German swim-course form ──

    private val germanKeys = mapOf(
        "Name des Kindes" to "full_name",
        "Geburtsdatum" to "birth_date",
        "Geburtsort" to "birth_place",
        "Anschrift" to "address",
        "Hat Ihr Kind das Seepferdchen bereits?" to "swim_level",
        "Allergien / Hinweise zur Gesundheit" to "allergies",
        "Name der Erziehungsberechtigten" to "full_name",
        "Telefon (Notfall)" to "phone",
        "E-Mail" to "email",
        "Kontoinhaber" to "account_holder",
        "IBAN" to "iban",
        "Name der Bank" to "bank_name",
        "Ort, Datum" to "today_place",
        "Unterschrift" to "signature",
    )

    private val germanScript = FormScript(
        notFields = setOf("Kinder 6–10", "Seite", "Kursgebühr"),
        keyOf = germanKeys,
        sectionRoles = mapOf(
            "Angaben zum Kind" to FormRole.SUBJECT,
            "Erziehungsberechtigte" to FormRole.GUARDIAN,
            "Zahlung per Lastschrift" to FormRole.GUARDIAN,
            "Einverständnis" to FormRole.GUARDIAN,
        ),
        fieldRoles = mapOf("Kontoinhaber" to FormRole.PAYER, "IBAN" to FormRole.PAYER, "Name der Bank" to FormRole.PAYER, "Unterschrift" to FormRole.SIGNER),
        subjects = mapOf("Ahmad" to 3.0, "Me" to -2.0),
        reasonLine = "Kinder 6–10 Jahre · Kursbeginn im Herbst",
    )

    private val germanEmbedder = FakeEmbedder(intent = { label -> germanKeys[label]?.let(::listOf).orEmpty() })

    private suspend fun session(script: FormScript = germanScript) = FakePromptSession().apply { scorer = script::score }

    private fun useCase(session: FakePromptSession, embedder: FakeEmbedder = germanEmbedder) =
        UnderstandFormUseCase(session, { system, user -> "<s>$system|$user<u>" to "<a>" }, embedder)

    private fun request(key: String = FormFixtures.GERMAN, subjects: List<SubjectCandidate> = listOf(meProfile, ahmadProfile), locale: Locale = Locale.GERMANY) =
        UnderstandFormRequest("doc", "fill", FormFixtures.pages(key), subjects, today, nowMs, locale)

    private suspend fun understand(session: FakePromptSession, request: UnderstandFormRequest = request()): FormUnderstanding =
        (useCase(session)(request) as PamResult.Success).data

    private fun FormUnderstanding.field(label: String) = fields.single { it.labelText == label }

    @Test
    fun `the German form is understood into its fields with keys, roles and kinds`() = runTest {
        val understanding = understand(session())

        assertThat(understanding.fields.map { it.labelText }).containsExactly(
            "Name des Kindes", "Geburtsdatum", "Geburtsort", "Anschrift", "Hat Ihr Kind das Seepferdchen bereits?", "Kurstermin",
            "Allergien / Hinweise zur Gesundheit", "Name der Erziehungsberechtigten", "Telefon (Notfall)", "E-Mail",
            "Kontoinhaber", "IBAN", "Name der Bank", "Ich willige in die Veröffentlichung von Fotos ein", "Ort, Datum", "Unterschrift",
        ).inOrder()

        assertThat(understanding.field("Name des Kindes").dataKey).isEqualTo("full_name")
        assertThat(understanding.field("Name des Kindes").role).isEqualTo(FormRole.SUBJECT)
        assertThat(understanding.field("Geburtsdatum").kind).isEqualTo(FormFieldKind.DATE)
        assertThat(understanding.field("Name der Erziehungsberechtigten").role).isEqualTo(FormRole.GUARDIAN)
        assertThat(understanding.field("Telefon (Notfall)").role).isEqualTo(FormRole.GUARDIAN)
        assertThat(understanding.field("Kontoinhaber").role).isEqualTo(FormRole.PAYER)
        assertThat(understanding.field("IBAN").dataKey).isEqualTo("iban")
        assertThat(understanding.field("Unterschrift").kind).isEqualTo(FormFieldKind.SIGNATURE)
        assertThat(understanding.field("Unterschrift").role).isEqualTo(FormRole.SIGNER)
        assertThat(understanding.field("Kurstermin").dataKey).isNull()
        assertThat(understanding.field("Kurstermin").options).hasSize(3)
        assertThat(understanding.field("Allergien / Hinweise zur Gesundheit").page).isEqualTo(1)
        assertThat(understanding.field("IBAN").page).isEqualTo(2)
        assertThat(understanding.fields.map { it.orderIndex }).isEqualTo(understanding.fields.indices.toList())
        assertThat(understanding.fields.map { it.id }.toSet()).hasSize(understanding.fields.size)
        assertThat(understanding.fields.all { it.formFillId == "fill" && it.documentId == "doc" && it.updatedAt == nowMs }).isTrue()
        assertThat(understanding.sectionRoles.map { it.role }).containsExactly(FormRole.SUBJECT, FormRole.GUARDIAN, FormRole.GUARDIAN, FormRole.GUARDIAN)
        assertThat(understanding.locale.language).isEqualTo("de")
    }

    @Test
    fun `the child is suggested as the subject with the quoted reason`() = runTest {
        val understanding = understand(session())
        assertThat(understanding.subjectRanking.map { it.profileId }).containsExactly("ahmad", "me").inOrder()
        assertThat(understanding.subjectRanking[0].reasonLine).isEqualTo("Kinder 6–10 Jahre · Kursbeginn im Herbst")
    }

    @Test
    fun `the understanding stays within the scoring budget and only ever scores`() = runTest {
        val session = session()
        val understanding = understand(session)

        val scored = session.scored.flatten().size
        assertThat(understanding.scoresSpent).isEqualTo(scored)
        // 2 pages, 16 fields: 5 confirms + 16 x 4 key scores + 4 sections x 6 + 4 bank/signature fields x 6 + 2 subjects + 6 reason lines.
        assertThat(scored).isAtMost(130)
        assertThat(session.asks).isEmpty()
        assertThat(understanding.fields.all { it.value == null && it.valueSource == FormValueSource.NONE }).isTrue()
    }

    @Test
    fun `progress is reported step by step and the session is opened once and closed`() = runTest {
        val session = session()
        val progress = mutableListOf<FormProgress>()

        useCase(session)(request()) { progress += it }

        assertThat(progress.map { it.step }).containsExactly(FormStep.FIND, FormStep.CONFIRM, FormStep.CLASSIFY, FormStep.ROLES, FormStep.SUBJECT).inOrder()
        assertThat(progress.map { it.done }).containsExactly(1, 2, 3, 4, 5).inOrder()
        assertThat(progress.last().total).isEqualTo(5)
        assertThat(session.opens).hasSize(1)
        assertThat(session.opens.single()).contains("Anmeldung Schwimmkurs Seepferdchen")
        assertThat(session.closes).isEqualTo(1)
    }

    @Test
    fun `a model that cannot open is an error and nothing is scored`() = runTest {
        val session = session().apply { openFailsWith = PamError.ModelNotLoaded("none") }
        val result = useCase(session)(request())
        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        assertThat(session.scored).isEmpty()
    }

    @Test
    fun `an engine failure during scoring is an error and the session is still closed`() = runTest {
        val session = session()
        var calls = 0
        val failing = object : com.postsaimanager.core.domain.ai.PromptSession by session {
            override suspend fun score(continuations: List<String>, yes: String, no: String, shared: String): PamResult<List<Double>> =
                if (++calls > 1) PamResult.Error(PamError.InferenceError("boom")) else session.score(continuations, yes, no, shared)
        }
        val result = UnderstandFormUseCase(failing, { _, _ -> "p" to "" }, germanEmbedder)(request())
        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        assertThat(session.closes).isEqualTo(1)
    }

    @Test
    fun `without managed profiles no subject is scored`() = runTest {
        val understanding = understand(session(), request(subjects = emptyList()))
        assertThat(understanding.subjectRanking).isEmpty()
    }

    @Test
    fun `the English consent form is understood with its checkboxes and table cells`() = runTest {
        val keys = mapOf("Pupil's full name" to "full_name", "Date of birth" to "birth_date", "Mobile phone" to "mobile", "Email" to "email", "Signed" to "signature")
        val script = FormScript(
            notFields = setOf("Trip date"), keyOf = keys,
            sectionRoles = mapOf("Pupil details" to FormRole.SUBJECT, "Parent or guardian" to FormRole.GUARDIAN, "Consent" to FormRole.GUARDIAN),
            fieldRoles = mapOf("Signed" to FormRole.SIGNER),
            subjects = mapOf("Ahmad" to 3.0),
        )
        val understanding = (UnderstandFormUseCase(session(script), { _, _ -> "p" to "" }, FakeEmbedder(intent = { keys[it]?.let(::listOf).orEmpty() }))(
            request(FormFixtures.ENGLISH, locale = Locale.UK),
        ) as PamResult.Success).data

        assertThat(understanding.field("Pupil's full name").role).isEqualTo(FormRole.SUBJECT)
        assertThat(understanding.field("Mobile phone").role).isEqualTo(FormRole.GUARDIAN)
        assertThat(understanding.field("Signed").kind).isEqualTo(FormFieldKind.SIGNATURE)
        assertThat(understanding.fields.count { it.kind == FormFieldKind.CHECKBOX }).isEqualTo(2)
        assertThat(understanding.fields.count { it.kind == FormFieldKind.TABLE_CELL }).isEqualTo(3)
        assertThat(understanding.locale.language).isEqualTo("en")
    }

    @Test
    fun `the Arabic form is understood right to left`() = runTest {
        val keys = mapOf("الاسم الكامل" to "full_name", "تاريخ الميلاد" to "birth_date", "رقم الآيبان" to "iban")
        val script = FormScript(
            notFields = setOf("يرجى"), keyOf = keys,
            sectionRoles = mapOf("بيانات العضو" to FormRole.SUBJECT, "الدفع" to FormRole.PAYER),
            subjects = mapOf("Ahmad" to 3.0),
        )
        val understanding = (UnderstandFormUseCase(session(script), { _, _ -> "p" to "" }, FakeEmbedder(intent = { keys[it]?.let(::listOf).orEmpty() }))(
            request(FormFixtures.ARABIC, locale = Locale.forLanguageTag("ar")),
        ) as PamResult.Success).data

        assertThat(understanding.field("الاسم الكامل").role).isEqualTo(FormRole.SUBJECT)
        assertThat(understanding.field("تاريخ الميلاد").kind).isEqualTo(FormFieldKind.DATE)
        assertThat(understanding.field("رقم الآيبان").role).isEqualTo(FormRole.PAYER)
        assertThat(understanding.field("نوع العضوية").options).containsExactly("شهرية", "سنوية").inOrder()
        assertThat(understanding.locale.language).isEqualTo("ar")
    }

    // ── End to end: understand the German form, then fill it for Ahmad and his father ──

    private fun p(value: String, sensitive: Boolean = false, source: FormValueSource = FormValueSource.PROFILE) =
        FakePersonDataSource.profile(value, recent, sensitive, source)

    private val people = FakePersonDataSource(
        mapOf(
            "ahmad" to mapOf(
                "full_name" to p("Ahmad Mustermann"), "birth_date" to p("2019-03-12"), "birth_place" to p("Beispieldorf"),
                "street" to p("Musterstraße 12"), "postcode" to p("54321"), "city" to p("Beispieldorf"),
            ),
            "me" to mapOf(
                "full_name" to p("Mohammad Mustermann"), "phone" to p("0151 2345678"), "email" to p("mohammad@example.org"),
                "iban" to p("DE89370400440532013000", sensitive = true, source = FormValueSource.FACT),
                "account_holder" to p("Mohammad Mustermann"),
            ),
        ),
    )

    private fun fillContext(confirmed: Set<FormRole>, locale: Locale) = FillContext(
        roleProfiles = mapOf(FormRole.SUBJECT to "ahmad", FormRole.GUARDIAN to "me", FormRole.PAYER to "me", FormRole.SIGNER to "me"),
        confirmedRoles = confirmed, locale = locale, nowMs = nowMs, zone = ZoneOffset.UTC,
    )

    @Test
    fun `the German form is filled for Ahmad and his father from their profiles`() = runTest {
        val session = session()
        val understanding = understand(session)

        val filled = FillValues(people).fill(understanding.fields, fillContext(setOf(FormRole.SUBJECT), understanding.locale))
        val values = filled.fields.associate { it.labelText to it.value }

        assertThat(values["Name des Kindes"]).isEqualTo("Ahmad Mustermann")
        assertThat(values["Geburtsdatum"]).isEqualTo("12.03.2019")
        assertThat(values["Geburtsort"]).isEqualTo("Beispieldorf")
        assertThat(values["Anschrift"]).isEqualTo("Musterstraße 12, 54321 Beispieldorf")
        assertThat(values["Name der Erziehungsberechtigten"]).isEqualTo("Mohammad Mustermann")
        assertThat(values["Telefon (Notfall)"]).isEqualTo("0151 2345678")
        assertThat(values["E-Mail"]).isEqualTo("mohammad@example.org")
        assertThat(values["Kontoinhaber"]).isEqualTo("Mohammad Mustermann")
        // The IBAN is sensitive and the payer was not confirmed: it waits for the user.
        assertThat(values["IBAN"]).isNull()
        // What no profile holds, a choice, a checkbox and the signature are left for the user.
        assertThat(values["Allergien / Hinweise zur Gesundheit"]).isNull()
        assertThat(values["Kurstermin"]).isNull()
        assertThat(values["Unterschrift"]).isNull()
        assertThat(values["Name der Bank"]).isNull()

        val withPayer = FillValues(people).fill(understanding.fields, fillContext(setOf(FormRole.SUBJECT, FormRole.PAYER), understanding.locale))
        assertThat(withPayer.fields.single { it.labelText == "IBAN" }.value).isEqualTo("DE89 3704 0044 0532 0130 00")
        assertThat(withPayer.fields.single { it.labelText == "IBAN" }.valueSource).isEqualTo(FormValueSource.FACT)
        assertThat(withPayer.fields.single { it.labelText == "Name des Kindes" }.profileId).isEqualTo("ahmad")
        assertThat(withPayer.fields.single { it.labelText == "Telefon (Notfall)" }.profileId).isEqualTo("me")
    }

    @Test
    fun `no value in the whole flow comes from the model`() = runTest {
        val session = session()
        val understanding = understand(session)
        val filled = FillValues(people).fill(understanding.fields, fillContext(setOf(FormRole.SUBJECT, FormRole.PAYER), understanding.locale))

        // The model was only ever asked to score, never to write: no generation call was made, and understanding left every value empty.
        assertThat(session.asks).isEmpty()
        assertThat(understanding.fields.all { it.value == null }).isTrue()

        val stored = setOf(
            "Ahmad Mustermann", "12.03.2019", "Beispieldorf", "Musterstraße 12, 54321 Beispieldorf", "Mohammad Mustermann",
            "0151 2345678", "mohammad@example.org", "DE89 3704 0044 0532 0130 00",
        )
        val values: List<String> = filled.fields.mapNotNull(FormField::value)
        assertThat(values).isNotEmpty()
        assertThat(stored).containsAtLeastElementsIn(values.toSet())
    }
}
