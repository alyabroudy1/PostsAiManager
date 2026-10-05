package com.postsaimanager.core.domain.form

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.FormRole
import com.postsaimanager.core.model.FormValueSource
import com.postsaimanager.core.model.ReviewState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

class FillValuesTest {

    private val now = ZonedDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private val recent = ZonedDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private val old = ZonedDateTime.of(2024, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun p(value: String, at: Long = recent, sensitive: Boolean = false, source: FormValueSource = FormValueSource.PROFILE) =
        FakePersonDataSource.profile(value, at, sensitive, source)

    private val ahmad = mapOf(
        "full_name" to p("Ahmad Mustermann"),
        "birth_date" to p("2019-03-12", old),
        "street" to p("Musterstraße 12"),
        "postcode" to p("54321"),
        "city" to p("Beispieldorf"),
        "swim_level" to p("Nein", source = FormValueSource.FACT),
    )
    private val dad = mapOf(
        "full_name" to p("Mohammad Mustermann"),
        "phone" to p("0151 2345678", old),
        "iban" to p("DE89370400440532013000", sensitive = true, source = FormValueSource.FACT),
        "allergies" to p("Nuss", sensitive = true, source = FormValueSource.FACT),
    )
    private val source = FakePersonDataSource(mapOf("ahmad" to ahmad, "dad" to dad))

    private fun context(confirmed: Set<FormRole> = setOf(FormRole.SUBJECT), locale: Locale = Locale.GERMANY, country: String? = null) = FillContext(
        roleProfiles = mapOf(FormRole.SUBJECT to "ahmad", FormRole.GUARDIAN to "dad", FormRole.PAYER to "dad"),
        confirmedRoles = confirmed, locale = locale, countryIso2 = country, nowMs = now, zone = ZoneOffset.UTC,
    )

    private fun field(
        id: String,
        key: String?,
        role: FormRole?,
        kind: FormFieldKind = FormFieldKind.TEXT,
        options: List<String> = emptyList(),
        alreadyFilled: String? = null,
        review: ReviewState = ReviewState.UNREVIEWED,
        value: String? = null,
    ) = FormField(
        id = id, formFillId = "fill", documentId = "doc", page = 1, labelText = id, labelBox = null, fillBox = null, kind = kind,
        options = options, dataKey = key, role = role, alreadyFilled = alreadyFilled, reviewState = review, value = value,
    )

    private suspend fun fill(vararg fields: FormField, ctx: FillContext = context()) = FillValues(source).fill(fields.toList(), ctx)

    @Test
    fun `a field is filled from the profile of its role, with the source of the value`() = runTest {
        val result = fill(
            field("name", "full_name", FormRole.SUBJECT),
            field("guardian", "full_name", FormRole.GUARDIAN),
            field("swim", "swim_level", FormRole.SUBJECT, FormFieldKind.CHOICE, options = listOf("Ja", "Nein")),
        )
        val byId = result.fields.associateBy { it.id }
        assertThat(byId.getValue("name").value).isEqualTo("Ahmad Mustermann")
        assertThat(byId.getValue("name").valueSource).isEqualTo(FormValueSource.PROFILE)
        assertThat(byId.getValue("name").profileId).isEqualTo("ahmad")
        assertThat(byId.getValue("guardian").value).isEqualTo("Mohammad Mustermann")
        assertThat(byId.getValue("guardian").profileId).isEqualTo("dad")
        assertThat(byId.getValue("swim").value).isEqualTo("Nein")
        assertThat(byId.getValue("swim").valueSource).isEqualTo(FormValueSource.FACT)
    }

    @Test
    fun `a date is written in the form's locale with a four-digit year`() = runTest {
        val f = field("dob", "birth_date", FormRole.SUBJECT, FormFieldKind.DATE)
        assertThat(fill(f).fields.single().value).isEqualTo("12.03.2019")
        assertThat(fill(f, ctx = context(locale = Locale.UK)).fields.single().value).isEqualTo("12/03/2019")
        assertThat(fill(f, ctx = context(locale = Locale.US)).fields.single().value).isEqualTo("3/12/2019")
    }

    @Test
    fun `the address is one line in the order of the form's country`() = runTest {
        val f = field("addr", "address", FormRole.SUBJECT)
        assertThat(fill(f).fields.single().value).isEqualTo("Musterstraße 12, 54321 Beispieldorf")
        val gb = FakePersonDataSource(mapOf("ahmad" to mapOf("street" to p("10 Main Street"), "city" to p("Leeds"), "postcode" to p("LS1 4AB"))))
        val uk = FillValues(gb).fill(listOf(f), context(locale = Locale.UK, country = "GB"))
        assertThat(uk.fields.single().value).isEqualTo("10 Main Street, Leeds LS1 4AB")
    }

    @Test
    fun `half an address is never written`() = runTest {
        val half = FakePersonDataSource(mapOf("ahmad" to mapOf("street" to p("Musterstraße 12"))))
        val result = FillValues(half).fill(listOf(field("addr", "address", FormRole.SUBJECT)), context())
        assertThat(result.fields.single().value).isNull()
    }

    @Test
    fun `a value older than the key allows is filled and flagged for reconfirmation`() = runTest {
        val result = fill(
            field("phone", "phone", FormRole.GUARDIAN),
            field("addr", "address", FormRole.SUBJECT),
            field("dob", "birth_date", FormRole.SUBJECT, FormFieldKind.DATE),
        )
        assertThat(result.fields.map { it.value }.all { it != null }).isTrue()
        assertThat(result.needsReconfirm).containsExactly("phone")
    }

    @Test
    fun `a sensitive value is filled only for a role the user confirmed`() = runTest {
        val iban = field("iban", "iban", FormRole.PAYER)
        val allergies = field("allergies", "allergies", FormRole.GUARDIAN)

        val unconfirmed = fill(iban, allergies)
        assertThat(unconfirmed.fields.map { it.value }).containsExactly(null, null)

        val confirmed = fill(iban, allergies, ctx = context(confirmed = setOf(FormRole.SUBJECT, FormRole.PAYER, FormRole.GUARDIAN)))
        assertThat(confirmed.fields.map { it.value }).containsExactly("DE89 3704 0044 0532 0130 00", "Nuss").inOrder()
    }

    @Test
    fun `a field without a key, a role or a chosen profile is not filled`() = runTest {
        val result = fill(
            field("nokey", null, FormRole.SUBJECT),
            field("norole", "full_name", null),
            field("other", "full_name", FormRole.EMERGENCY_CONTACT),
            field("unknown", "no_such_key", FormRole.SUBJECT),
        )
        assertThat(result.fields.all { it.value == null }).isTrue()
    }

    @Test
    fun `signatures, bare boxes, hand-filled and reviewed fields are left alone`() = runTest {
        val result = fill(
            field("sign", "full_name", FormRole.SUBJECT, FormFieldKind.SIGNATURE),
            field("box", "full_name", FormRole.SUBJECT, FormFieldKind.CHECKBOX),
            field("hand", "full_name", FormRole.SUBJECT, alreadyFilled = "Ahmed"),
            field("edited", "full_name", FormRole.SUBJECT, review = ReviewState.EDITED, value = "Typed"),
            field("typed", "full_name", FormRole.SUBJECT, value = "Answer"),
        )
        assertThat(result.fields.map { it.value }).containsExactly(null, null, null, "Typed", "Answer").inOrder()
    }

    @Test
    fun `a choice is filled only when the stored value is one of the printed options`() = runTest {
        val f = field("swim", "swim_level", FormRole.SUBJECT, FormFieldKind.CHOICE, options = listOf("Seepferdchen", "Bronze"))
        assertThat(fill(f).fields.single().value).isNull()
    }

    @Test
    fun `a missing stored value leaves the field for the user`() = runTest {
        assertThat(fill(field("school", "school", FormRole.SUBJECT)).fields.single().value).isNull()
    }

    @Test
    fun `no model can be reached from FillValues, so no value can come from one`() {
        val reachable = FillValues::class.java.declaredConstructors.flatMap { it.parameterTypes.toList() } +
            FillValues::class.java.declaredFields.map { it.type }
        assertThat(reachable.none { PromptSession::class.java.isAssignableFrom(it) || it.name.startsWith("com.postsaimanager.core.domain.ai") }).isTrue()
        assertThat(reachable.map { it.simpleName }).containsAtLeast("PersonDataSource", "AddressComposer")
    }

    @Test
    fun `every filled value is a stored value, copied and formatted`() = runTest {
        val stored = (ahmad.values + dad.values).map { it.value }.toSet() + setOf("12.03.2019", "Musterstraße 12, 54321 Beispieldorf", "DE89 3704 0044 0532 0130 00")
        val fields = listOf(
            field("a", "full_name", FormRole.SUBJECT), field("b", "birth_date", FormRole.SUBJECT, FormFieldKind.DATE),
            field("c", "address", FormRole.SUBJECT), field("d", "iban", FormRole.PAYER), field("e", "phone", FormRole.GUARDIAN),
        )
        val result = FillValues(source).fill(fields, context(confirmed = setOf(FormRole.PAYER)))
        val values = result.fields.mapNotNull { it.value }
        assertThat(values).isNotEmpty()
        assertThat(stored).containsAtLeastElementsIn(values)
    }

    @Test
    fun `today's date is written in the form's format and the place is the Me city, both from today`() = runTest {
        val result = fill(
            field("date", "today_date", null, FormFieldKind.DATE),
            field("place", "today_place", null),
            ctx = context().copy(todayPlaceProfileId = "ahmad"),
        )
        val byId = result.fields.associateBy { it.id }
        assertThat(byId.getValue("date").value).isEqualTo("01.10.2026")
        assertThat(byId.getValue("date").valueSource).isEqualTo(FormValueSource.TODAY)
        assertThat(byId.getValue("place").value).isEqualTo("Beispieldorf")
        assertThat(byId.getValue("place").valueSource).isEqualTo(FormValueSource.TODAY)
    }

    @Test
    fun `the place of signing stays empty when the Me city is unknown, and a typed value is never replaced`() = runTest {
        val unknown = fill(field("place", "today_place", null), ctx = context().copy(todayPlaceProfileId = "dad"))
        assertThat(unknown.fields.single().value).isNull()

        val typed = fill(field("date", "today_date", null, FormFieldKind.DATE, review = ReviewState.EDITED, value = "1.1.2026"))
        assertThat(typed.fields.single().value).isEqualTo("1.1.2026")
    }

    @Test
    fun `the given and family name come from the stored full name`() = runTest {
        val result = fill(
            field("given", "given_name", FormRole.SUBJECT),
            field("family", "family_name", FormRole.SUBJECT),
        )
        assertThat(result.fields.map { it.value }).containsExactly("Ahmad", "Mustermann").inOrder()
    }

    @Test
    fun `a child with no address of their own takes the guardian's, then Me's, whole`() = runTest {
        val kind = mapOf("full_name" to p("Test Kind"), "birth_date" to p("2019-03-12"))
        val me = mapOf("street" to p("Hauptweg 3"), "postcode" to p("10115"), "city" to p("Berlin"))
        val people = FakePersonDataSource(mapOf("kind" to kind, "dad" to dad, "me" to me))
        val ctx = FillContext(
            roleProfiles = mapOf(FormRole.SUBJECT to "kind", FormRole.GUARDIAN to "dad"), confirmedRoles = setOf(FormRole.SUBJECT),
            locale = Locale.GERMANY, addressFallbacks = listOf("dad", "me"), nowMs = now, zone = ZoneOffset.UTC,
        )
        val result = FillValues(people).fill(
            listOf(field("addr", "address", FormRole.SUBJECT), field("city", "city", FormRole.SUBJECT), field("birth", "birth_date", FormRole.SUBJECT, FormFieldKind.DATE)),
            ctx,
        )
        val byId = result.fields.associateBy { it.id }
        assertThat(byId.getValue("addr").value).isEqualTo("Hauptweg 3, 10115 Berlin")
        assertThat(byId.getValue("addr").profileId).isEqualTo("me")
        assertThat(byId.getValue("city").value).isEqualTo("Berlin")
        assertThat(byId.getValue("birth").value).isEqualTo("12.03.2019")
    }

    @Test
    fun `an own address is not replaced by the household's`() = runTest {
        val ctx = context().copy(addressFallbacks = listOf("dad"))
        val result = fill(field("city", "city", FormRole.SUBJECT), ctx = ctx)
        assertThat(result.fields.single().value).isEqualTo("Beispieldorf")
        assertThat(result.fields.single().profileId).isEqualTo("ahmad")
    }
}
