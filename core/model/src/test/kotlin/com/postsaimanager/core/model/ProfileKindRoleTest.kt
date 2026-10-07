package com.postsaimanager.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ProfileKindRoleTest {

    private fun profile(kind: ProfileKind, role: HouseholdRole?) =
        Profile(id = "p", kind = kind, householdRole = role, name = "n", createdAt = 0, modifiedAt = 0)

    @Test
    fun `the legacy type maps 1 to 1 to kind and role`() {
        assertThat(ProfileType.AUTHORITY.kind).isEqualTo(ProfileKind.ORGANISATION)
        assertThat(ProfileType.AUTHORITY.householdRole).isNull()
        assertThat(ProfileType.PERSON.kind).isEqualTo(ProfileKind.PERSON)
        assertThat(ProfileType.PERSON.householdRole).isNull()
        assertThat(ProfileType.FAMILY_MEMBER.kind).isEqualTo(ProfileKind.PERSON)
        assertThat(ProfileType.FAMILY_MEMBER.householdRole).isEqualTo(HouseholdRole.MEMBER)
        assertThat(ProfileType.USER_SELF.kind).isEqualTo(ProfileKind.PERSON)
        assertThat(ProfileType.USER_SELF.householdRole).isEqualTo(HouseholdRole.SELF)
    }

    @Test
    fun `kind and role map back to the same legacy type`() {
        ProfileType.entries.forEach { type ->
            assertThat(ProfileType.of(type.kind, type.householdRole)).isEqualTo(type)
        }
        assertThat(profile(ProfileKind.PERSON, HouseholdRole.SELF).type).isEqualTo(ProfileType.USER_SELF)
        assertThat(profile(ProfileKind.PERSON, null).type).isEqualTo(ProfileType.PERSON)
    }

    @Test
    fun `managed means a household role, self means the SELF role`() {
        assertThat(profile(ProfileKind.PERSON, null).isManaged).isFalse()
        assertThat(profile(ProfileKind.ORGANISATION, null).isManaged).isFalse()
        assertThat(profile(ProfileKind.PERSON, HouseholdRole.MEMBER).isManaged).isTrue()
        assertThat(profile(ProfileKind.PERSON, HouseholdRole.MEMBER).isSelf).isFalse()
        assertThat(profile(ProfileKind.PERSON, HouseholdRole.SELF).isManaged).isTrue()
        assertThat(profile(ProfileKind.PERSON, HouseholdRole.SELF).isSelf).isTrue()
    }
}
