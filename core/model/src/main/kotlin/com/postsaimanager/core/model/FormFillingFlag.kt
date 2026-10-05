package com.postsaimanager.core.model

/**
 * Whether the form-filling feature is offered. The one owner of that decision: the app module binds it from the build type (on in
 * debug builds, off in release builds) and every entry point (the Extracted-tab card, the document menu item, the chat's request
 * detection, the Models-screen note) reads it from here. Profiles, saved details and the profile page do not depend on it.
 */
interface FormFillingFlag {

    val enabled: Boolean

    companion object {
        val ON: FormFillingFlag = Fixed(true)
        val OFF: FormFillingFlag = Fixed(false)
    }

    private class Fixed(override val enabled: Boolean) : FormFillingFlag
}
