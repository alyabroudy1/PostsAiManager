package com.postsaimanager.core.domain.timeline

import com.postsaimanager.core.model.CaseStatus
import com.postsaimanager.core.model.ProfileEvent

/**
 * Where a matter stands, derived from its events as a pure function: the status of the latest event whose kind says where a matter
 * stands ([EventKind.status]: approval, rejection, cancellation, application, objection), so a rejection followed by an objection is open
 * again and an approval followed by information stays approved. A matter with no such event is open. "Latest" is the event date, then
 * the day it was recorded, so letters read out of order still land in the order they happened.
 */
object CaseStatusDeriver {

    fun derive(events: List<ProfileEvent>, kinds: EventKinds = EventKinds.DEFAULT): CaseStatus =
        events.sortedWith(compareByDescending<ProfileEvent> { it.eventDate }.thenByDescending { it.recordedAt })
            .firstNotNullOfOrNull { kinds.byId(it.kind).status }
            ?: CaseStatus.OPEN
}

/**
 * Whether the status derived from a matter's events may be stored, the one place that decides it. A status the user set
 * ([com.postsaimanager.core.model.CaseStatusSource.USER]) is never replaced by the derived one, however the events change, until the
 * user hands it back to automatic.
 */
object CaseStatusPolicy {

    fun mayDerive(case: com.postsaimanager.core.model.Case?): Boolean = case?.statusSource != com.postsaimanager.core.model.CaseStatusSource.USER
}
