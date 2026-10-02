package com.postsaimanager

import android.content.Context
import android.content.res.Configuration
import com.postsaimanager.core.domain.form.agent.FormWording
import com.postsaimanager.core.model.FormRole
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** The form agent's role words and "someone else" from the string resources, read in the form's language (not the phone's). */
@Singleton
class AndroidFormWording @Inject constructor(@ApplicationContext private val context: Context) : FormWording {

    override fun roleName(role: FormRole, language: Locale): String = text(language).getString(
        when (role) {
            FormRole.SUBJECT -> R.string.form_role_subject
            FormRole.GUARDIAN -> R.string.form_role_guardian
            FormRole.PAYER -> R.string.form_role_payer
            FormRole.SIGNER -> R.string.form_role_signer
            FormRole.EMERGENCY_CONTACT -> R.string.form_role_emergency_contact
            FormRole.OTHER -> R.string.form_role_other
        },
    )

    override fun someoneElse(language: Locale): String = text(language).getString(R.string.form_someone_else)

    override fun me(language: Locale): String = text(language).getString(R.string.form_me)

    override fun personNameQuestion(language: Locale): String = text(language).getString(R.string.form_person_name_question)

    private fun text(language: Locale): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(language) })
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FormWordingModule {
    @Binds
    abstract fun bindFormWording(wording: AndroidFormWording): FormWording
}
