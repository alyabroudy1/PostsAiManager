package com.postsaimanager.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.FactSource
import com.postsaimanager.core.model.FormValueSource
import com.postsaimanager.core.model.ProfileFact
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.testing.FakeProfileFactRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PersonDataSourceImplTest {

    private val profiles = FakeProfileRepository()
    private val facts = FakeProfileFactRepository()
    private val source = PersonDataSourceImpl(profiles, facts)

    private fun fact(key: String, value: String, updatedAt: Long = 7, sensitive: Boolean = false) =
        ProfileFact("f-$key", "ahmad", key, value, FactSource.USER, sensitive = sensitive, createdAt = 1, updatedAt = updatedAt)

    @Test
    fun `a column key reads the profile, a non-column key reads the fact`() = runTest {
        profiles.seed(testProfile(id = "ahmad", name = "Ahmad M", phone = "0151", type = ProfileType.FAMILY_MEMBER))
        facts.seed(fact("allergies", "nuts", sensitive = true))

        val phone = source.valueOf("ahmad", "phone")!!
        val allergies = source.valueOf("ahmad", "allergies")!!

        assertThat(phone.value).isEqualTo("0151")
        assertThat(phone.source).isEqualTo(FormValueSource.PROFILE)
        assertThat(allergies.value).isEqualTo("nuts")
        assertThat(allergies.source).isEqualTo(FormValueSource.FACT)
        assertThat(allergies.sensitive).isTrue()
        assertThat(allergies.updatedAt).isEqualTo(7)
    }

    @Test
    fun `a stray fact never overrides a profile column`() = runTest {
        profiles.seed(testProfile(id = "ahmad", phone = "0151"))
        facts.seed(fact("phone", "9999"))

        assertThat(source.valueOf("ahmad", "phone")!!.value).isEqualTo("0151")
        assertThat(source.allOf("ahmad")["phone"]!!.value).isEqualTo("0151")
    }

    @Test
    fun `an empty column, an unknown key and an unknown profile give nothing`() = runTest {
        profiles.seed(testProfile(id = "ahmad", phone = "  "))

        assertThat(source.valueOf("ahmad", "phone")).isNull()
        assertThat(source.valueOf("ahmad", "street")).isNull()
        assertThat(source.valueOf("ahmad", "shoe_size")).isNull()
        assertThat(source.valueOf("ghost", "phone")).isNull()
        assertThat(source.allOf("ghost")).isEmpty()
    }

    @Test
    fun `the birth date comes from the profile`() = runTest {
        profiles.seed(testProfile(id = "ahmad", birthDate = "2019-03-12"))
        facts.seed(fact("birth_date", "1999-01-01"))

        assertThat(source.valueOf("ahmad", "birth_date")!!.value).isEqualTo("2019-03-12")
        assertThat(source.allOf("ahmad")["birth_date"]!!.source).isEqualTo(FormValueSource.PROFILE)
    }

    @Test
    fun `allOf merges columns and facts`() = runTest {
        profiles.seed(testProfile(id = "ahmad", name = "Ahmad M", street = "Musterstr. 1"))
        facts.seed(fact("school", "Grundschule"))

        val all = source.allOf("ahmad")

        assertThat(all.keys).containsExactly("full_name", "street", "school")
        assertThat(all["street"]!!.source).isEqualTo(FormValueSource.PROFILE)
        assertThat(all["school"]!!.source).isEqualTo(FormValueSource.FACT)
    }
}
