package com.postsaimanager.core.testing

import com.postsaimanager.core.domain.contacts.LoadLetterContactsUseCase
import com.postsaimanager.core.domain.contacts.ObserveOrganisationContactsUseCase
import com.postsaimanager.core.domain.repository.ContactRepository
import com.postsaimanager.core.domain.repository.ProfileRepository

/** A [LoadLetterContactsUseCase] over fakes, for tests that build a chat context or grounding sources and do not care about contacts. */
fun letterContactsFor(
    profiles: ProfileRepository = FakeProfileRepository(),
    contacts: ContactRepository = FakeContactRepository(),
): LoadLetterContactsUseCase = LoadLetterContactsUseCase(contacts, profiles, ObserveOrganisationContactsUseCase(contacts))
