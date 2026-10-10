package com.postsaimanager.core.data.backup

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.postsaimanager.core.common.backup.ConsentRequest
import com.postsaimanager.core.domain.backup.DriveAccount
import com.postsaimanager.core.domain.backup.DriveAuthorization
import com.postsaimanager.core.domain.backup.DriveConnection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A fresh access token for Drive, or the reason there is none. Internal to the data layer: no screen sees a token. */
sealed interface DriveToken {
    data class Token(val value: String) : DriveToken
    data object NeedsConsent : DriveToken
    data class Failed(val message: String) : DriveToken
}

interface DriveTokenSource {
    suspend fun accessToken(): DriveToken
}

/**
 * The Google account link, on Google's Authorization API (Google Identity Services): it asks for the `drive.appdata` scope only (the
 * hidden app folder; the app can see no other file in Drive) and hands out an access token on demand, from the signed-in account on this
 * phone, silently once the person has agreed. Only the fact that the person connected and the account's e-mail (for display) are kept,
 * in private preferences; no token is stored, so there is nothing to leak or to expire.
 */
@Singleton
class GoogleDriveConnection @Inject constructor(
    @ApplicationContext private val context: Context,
) : DriveConnection, DriveTokenSource {

    private val preferences get() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(readAccount())

    override val account: Flow<DriveAccount> = state.asStateFlow()

    override suspend fun isConnected(): Boolean = state.value is DriveAccount.Connected

    override suspend fun authorize(): DriveAuthorization = when (val result = request()) {
        is Requested.Done -> {
            val email = result.result.toGoogleSignInAccount()?.email ?: (state.value as? DriveAccount.Connected)?.email
            remember(email)
            DriveAuthorization.Granted(email)
        }
        is Requested.Consent -> DriveAuthorization.NeedsConsent(result.request)
        is Requested.Error -> DriveAuthorization.Failed(result.message)
    }

    override suspend fun accessToken(): DriveToken {
        if (!isConnected()) return DriveToken.NeedsConsent
        return when (val result = request()) {
            is Requested.Done -> result.result.accessToken?.let { DriveToken.Token(it) } ?: DriveToken.Failed("Google gave no access token.")
            is Requested.Consent -> DriveToken.NeedsConsent
            is Requested.Error -> DriveToken.Failed(result.message)
        }
    }

    override suspend fun disconnect() {
        // Best effort: when Google cannot be reached the local link is still dropped, and the person can remove access in their Google account.
        // Revoking the access token withdraws the whole grant (Google's OAuth revoke endpoint), so the next connect asks for consent again.
        runCatching {
            val token = (request() as? Requested.Done)?.result?.accessToken
            if (token != null) revoke(token)
        }
        runCatching { preferences.edit().clear().apply() }
        state.value = DriveAccount.Disconnected
    }

    private sealed interface Requested {
        data class Done(val result: AuthorizationResult) : Requested
        data class Consent(val request: ConsentRequest) : Requested
        data class Error(val message: String) : Requested
    }

    private suspend fun request(): Requested = try {
        val authorization = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE_APPDATA))).build()
        val result = Identity.getAuthorizationClient(context).authorize(authorization).await()
        val pending = result.pendingIntent
        if (result.hasResolution() && pending != null) Requested.Consent(ConsentRequest(pending.intentSender)) else Requested.Done(result)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Requested.Error(e.message ?: "Google sign-in failed.")
    }

    private suspend fun revoke(token: String) = withContext(Dispatchers.IO) {
        val connection = URL(REVOKE_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write("token=${URLEncoder.encode(token, "UTF-8")}".toByteArray()) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun remember(email: String?) {
        runCatching { preferences.edit().putBoolean(KEY_CONNECTED, true).putString(KEY_EMAIL, email).apply() }
        state.value = DriveAccount.Connected(email)
    }

    private fun readAccount(): DriveAccount = runCatching {
        if (preferences.getBoolean(KEY_CONNECTED, false)) DriveAccount.Connected(preferences.getString(KEY_EMAIL, null)) else DriveAccount.Disconnected
    }.getOrDefault(DriveAccount.Disconnected)

    companion object {
        const val SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        private const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
        private const val TIMEOUT_MS = 15_000
        private const val FILE = "drive_connection"
        private const val KEY_CONNECTED = "connected"
        private const val KEY_EMAIL = "email"
    }
}

/** A Play services [Task] as a suspending call (the small adapter instead of a whole coroutines-play-services dependency). */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
