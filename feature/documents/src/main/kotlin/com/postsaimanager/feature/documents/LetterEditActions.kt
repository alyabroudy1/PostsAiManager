package com.postsaimanager.feature.documents

import com.postsaimanager.core.domain.contacts.UpdateContactUseCase
import com.postsaimanager.core.domain.document.SetDocumentLanguageUseCase
import com.postsaimanager.core.domain.document.SetFieldMeaningUseCase
import com.postsaimanager.core.domain.document.actions.AddActionUseCase
import com.postsaimanager.core.domain.document.actions.DeleteActionUseCase
import com.postsaimanager.core.domain.document.actions.EditActionUseCase
import com.postsaimanager.core.domain.document.people.SetConcernedPeopleUseCase
import com.postsaimanager.core.domain.timeline.AddEventUseCase
import com.postsaimanager.core.domain.timeline.DeleteEventUseCase
import com.postsaimanager.core.domain.timeline.EditEventUseCase
import com.postsaimanager.core.domain.timeline.SetCaseStatusUseCase
import com.postsaimanager.core.domain.timeline.MoveDocumentToCaseUseCase
import com.postsaimanager.core.domain.timeline.ObserveCaseChoicesUseCase
import com.postsaimanager.core.domain.timeline.RenameCaseUseCase
import javax.inject.Inject

/**
 * The ways the user changes what the AI filled in on a letter, which the detail screen offers next to the values (the actions, who the
 * letter is for, the meaning of a date or an amount, the contact person, the matter). Each is a use case of its own in the domain; this
 * only hands them to the screen's view model together.
 */
class LetterEditActions @Inject constructor(
    val editAction: EditActionUseCase,
    val deleteAction: DeleteActionUseCase,
    val addAction: AddActionUseCase,
    val setPeople: SetConcernedPeopleUseCase,
    val setMeaning: SetFieldMeaningUseCase,
    val updateContact: UpdateContactUseCase,
    val renameCase: RenameCaseUseCase,
    val moveToCase: MoveDocumentToCaseUseCase,
    val caseChoices: ObserveCaseChoicesUseCase,
    val setLanguage: SetDocumentLanguageUseCase,
    val editEvent: EditEventUseCase,
    val deleteEvent: DeleteEventUseCase,
    val addEvent: AddEventUseCase,
    val setCaseStatus: SetCaseStatusUseCase,
)
