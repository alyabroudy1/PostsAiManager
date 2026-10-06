package com.postsaimanager.core.designsystem.component

import com.postsaimanager.core.model.PersonTag

/** The person chips a row shows and how many more it hides behind "+n". */
data class PersonChipSplit(val shown: List<PersonTag>, val hidden: Int) {

    companion object {
        /** At most this many person chips are shown on a row. */
        const val MAX_SHOWN = 2

        fun of(people: List<PersonTag>, max: Int = MAX_SHOWN): PersonChipSplit =
            PersonChipSplit(people.take(max), (people.size - max).coerceAtLeast(0))
    }
}
