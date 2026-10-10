package com.postsaimanager.core.data.settings

import android.content.Context
import android.content.res.Resources
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.postsaimanager.core.domain.settings.AppLanguageProvider
import com.postsaimanager.core.domain.settings.AppLanguageSettings
import com.postsaimanager.core.model.AppLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app language through the platform's per-app language setting.
 *
 * - API 33+: [AppCompatDelegate.setApplicationLocales] hands the choice to the system `LocaleManager`, which stores it, shows it in
 *   the system's own per-app language screen, and recreates the app's activities.
 * - API 26-32: AppCompat applies it to every `AppCompatActivity` and stores it through its `autoStoreLocales` service (declared in
 *   the manifest), so it survives a restart. AppCompat reads that store only when the first activity starts, so a background worker that
 *   runs before any screen would see "System"; the pick is therefore also kept in a small preferences file there, and used when
 *   AppCompat has nothing yet.
 */
@Singleton
class AppCompatAppLanguage @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppLanguageSettings, AppLanguageProvider {

    override fun current(): AppLanguage {
        val fromAppCompat = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (fromAppCompat.isNotEmpty() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return AppLanguage.fromTags(fromAppCompat)
        return AppLanguage.fromTags(preferences().getString(KEY, "").orEmpty())
    }

    override fun select(language: AppLanguage) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) preferences().edit().putString(KEY, language.tag).apply()
        AppCompatDelegate.setApplicationLocales(
            if (language == AppLanguage.SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(language.tag),
        )
    }

    override fun aiLanguageCode(): String {
        val system = Resources.getSystem().configuration.locales[0]?.toLanguageTag().orEmpty()
        return AppLanguage.effective(current(), system).tag
    }

    private fun preferences() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private companion object {
        const val FILE = "pam_app_language"
        const val KEY = "language"
    }
}
