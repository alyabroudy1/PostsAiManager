package com.postsaimanager.core.domain.form.fill

/**
 * Where the form conversation reports what it did, so a failure on a device is visible in a log (debug builds only; see the app's
 * module). A line is an [event] name and `key=value` details of ids, counts and states: never a field value, a label or anything
 * about a person.
 */
fun interface FormFillTrace {

    fun event(name: String, details: String)

    companion object {
        /** Reports nothing (release builds and tests). */
        val NONE = FormFillTrace { _, _ -> }
    }
}
