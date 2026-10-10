package com.postsaimanager.core.common.backup

import android.content.IntentSender

/**
 * A consent screen the person has to see before Google Drive may be used (the Authorization API hands it over as a pending intent).
 * It lives in `:core:common` because `:core:domain` is plain Kotlin and cannot name an Android type, while the Settings screen has to
 * launch it. The screen launches [intentSender] with an `IntentSenderRequest`, and asks the domain to connect again when it returns:
 * the answer is then a granted access, so nothing in the result intent is read.
 */
class ConsentRequest(val intentSender: IntentSender)
