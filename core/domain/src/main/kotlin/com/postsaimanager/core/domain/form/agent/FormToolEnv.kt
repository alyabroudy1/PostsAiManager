package com.postsaimanager.core.domain.form.agent

import com.postsaimanager.core.domain.agent.AgentTool
import com.postsaimanager.core.domain.agent.ToolRegistry
import com.postsaimanager.core.domain.form.FillValues
import com.postsaimanager.core.domain.form.GuardiansOfUseCase
import com.postsaimanager.core.domain.form.PersonDataSource
import com.postsaimanager.core.domain.form.RememberDetailUseCase
import com.postsaimanager.core.domain.repository.FormFillRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFill
import com.postsaimanager.core.model.Profile
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.Locale

/**
 * What the form tools of ONE document work on: the stored fill and its fields, the managed people, the reading of the form, the
 * guard that checks every value and the one place a detail is remembered. Tools hold no state of their own: everything they know
 * is read from here (stored data) or from the call's context, so a run resumes from storage.
 */
class FormToolEnv(
    val documentId: String,
    val fills: FormFillRepository,
    private val profiles: ProfileRepository,
    val people: PersonDataSource,
    val guard: FieldValueGuard,
    val reader: FormReader,
    val remember: RememberDetailUseCase,
    val fillValues: FillValues,
    val clock: () -> Long = System::currentTimeMillis,
    val today: () -> LocalDate = LocalDate::now,
    private val fallbackLocale: () -> Locale = Locale::getDefault,
    val wording: FormWording = FormWording.English,
) {
    val fillId: String = FormChatLog.fillId(documentId)

    /** What the roles are called in this form and who can have them. */
    val roles = RoleWording(this)

    private val guardians = GuardiansOfUseCase(profiles)

    /** The people a child's forms are signed by (Me, and the partner if there is one); none for anyone else. */
    suspend fun guardiansOf(person: Profile): List<Profile> = guardians(person.id)

    suspend fun fill(): FormFill? = fills.getFill(fillId)

    suspend fun fields(): List<FormField> = fills.fields(fillId)

    /** The managed people in the order their `p1`, `p2`... names are given. */
    suspend fun managed(): List<Profile> = FormRefs.orderedPeople(profiles.getProfiles().first())

    suspend fun person(ref: String): Profile? = FormRefs.findPerson(managed(), ref)

    /** The language values are written in: the fill's (the form's), else the document's, else the phone's. */
    suspend fun locale(): Locale =
        fill()?.localeTag?.let(Locale::forLanguageTag) ?: reader.documentLanguage(documentId) ?: fallbackLocale()

    /** The language the form is in: the document's stored language, else [locale]. The agent writes its questions in it. */
    suspend fun formLanguage(): Locale = reader.documentLanguage(documentId) ?: locale()

    private var ocrWordsCache: Set<String>? = null

    /**
     * The words of the document's stored OCR text (folded, three letters or more), read once per run: the vocabulary of the form's own
     * language, which a question written in that language shares words with.
     */
    suspend fun ocrWords(): Set<String> = ocrWordsCache ?: Regex("[\\p{L}\\p{N}]{3,}")
        .findAll(FormRefs.fold(reader.ocrText(documentId))).map { it.value }.toSet().also { ocrWordsCache = it }

    /** The managed person who is the user ("Me"), if the profiles have one. */
    suspend fun selfPerson(): Profile? = managed().firstOrNull { it.isSelf }

    /** "Me": whose city answers a "place of signing" field. */
    suspend fun selfId(): String? = profiles.getProfiles().first().firstOrNull { it.isSelf }?.id
}

/** Every tool of the form agent for one document, in the order the model is told about them, as the agent spec. */
class FormAgentTools(
    /** Dynamic tool exposure ([FormToolExposure]); false lets the model use every tool at every step (tests of the tools alone). */
    private val dynamicTools: Boolean = true,
    private val envFor: (String) -> FormToolEnv,
) {

    fun specFor(documentId: String): FormAgentSpec {
        val env = envFor(documentId)
        val guidance = FormGuidance(env)
        val tools: List<AgentTool> = listOf(
            ReadFormTool(env),
            ListPeopleTool(env),
            GetPersonDetailsTool(env),
            FillFromProfileTool(env),
            FillFieldTool(env),
            AskUserTool(env, guidance, QuestionGuard(env, guidance)),
            RememberDetailTool(env),
            SkipFieldTool(env),
            ShowFillCardTool(env),
            ShowOnPageTool(env),
            FinishTool(env),
        )
        return FormAgentSpec(env, guidance, ToolRegistry(tools), if (dynamicTools) FormToolExposure(env, guidance) else null)
    }
}
