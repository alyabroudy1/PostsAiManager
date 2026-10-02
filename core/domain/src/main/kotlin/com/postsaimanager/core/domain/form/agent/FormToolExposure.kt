package com.postsaimanager.core.domain.form.agent

import com.postsaimanager.core.domain.agent.AgentContext
import com.postsaimanager.core.domain.agent.AgentEntry
import com.postsaimanager.core.model.FormValueSource

/**
 * Where the fill stands as far as the choice of tool goes. Computed by code from the stored fill and the run (never by reading the
 * meaning of what anybody wrote): see [FormToolExposure.stage].
 */
enum class FormStage {
    /** The form has not been read yet. */
    NOT_READ,

    /** Nobody is known as the subject (whom the form is for). */
    SUBJECT_UNKNOWN,

    /** A person is known for the first role without one: its fields can be filled from the stored details. */
    ROLE_READY,

    /** A role has open fields and no person: the user has to say who it is (or leave it). */
    ROLE_NEEDS_PERSON,

    /** The user typed who has the role (somebody who is not stored): the typed text goes into its name fields. */
    ROLE_TYPED,

    /** Every role is settled and fields still wait for an answer. */
    OPEN_FIELDS,

    /** Nothing is open. */
    NOTHING_OPEN,
}

/**
 * Which tools the model sees and the grammar allows in each [FormStage] (data, so a later stage or tool is one more row). The AI still
 * chooses among them and writes all the wording; the table only keeps a small model from calling what makes no sense yet (a fill
 * before the form was read, a question about fields while a role still has nobody).
 *
 * `get_person_details` is not exposed in any stage: the stored values reach the form through `fill_from_profile`, and a small model
 * does better with fewer choices.
 */
class ToolPolicy(private val table: Map<FormStage, List<String>> = DEFAULT) {

    /** The tool names for [stage]; [rememberPending] adds `remember_detail` (a user's answer may be the yes to "remember it?"). */
    fun allowed(stage: FormStage, rememberPending: Boolean): List<String> {
        val base = table.getValue(stage)
        val remembering = rememberPending && stage in REMEMBER_STAGES && REMEMBER !in base
        return if (remembering) base + REMEMBER else base
    }

    companion object {
        private val REMEMBER = RememberDetailTool.NAME

        /** The stages in which a typed answer can be the user's yes to a remember question. */
        private val REMEMBER_STAGES = setOf(FormStage.OPEN_FIELDS, FormStage.NOTHING_OPEN)

        val DEFAULT: Map<FormStage, List<String>> = mapOf(
            FormStage.NOT_READ to listOf(ReadFormTool.NAME),
            FormStage.SUBJECT_UNKNOWN to listOf(ListPeopleTool.NAME, AskUserTool.NAME),
            FormStage.ROLE_READY to listOf(FillFromProfileTool.NAME, AskUserTool.NAME),
            FormStage.ROLE_NEEDS_PERSON to listOf(AskUserTool.NAME, SkipFieldTool.NAME),
            FormStage.ROLE_TYPED to listOf(FillFieldTool.NAME, SkipFieldTool.NAME, AskUserTool.NAME),
            FormStage.OPEN_FIELDS to listOf(AskUserTool.NAME, FillFieldTool.NAME, SkipFieldTool.NAME, ShowOnPageTool.NAME),
            FormStage.NOTHING_OPEN to listOf(ShowFillCardTool.NAME, ShowOnPageTool.NAME, FinishTool.NAME),
        )
    }
}

/** Reads the [FormStage] of the stored fill and gives the tools the [ToolPolicy] allows for it. */
class FormToolExposure(private val env: FormToolEnv, private val guidance: FormGuidance, private val policy: ToolPolicy = ToolPolicy()) {

    suspend fun allowed(entries: List<AgentEntry>): List<String> {
        val context = AgentContext.of(entries)
        return policy.allowed(stage(context), rememberPending(context))
    }

    suspend fun stage(context: AgentContext): FormStage {
        val fields = env.fields()
        if (fields.isEmpty()) return FormStage.NOT_READ
        return when (guidance.roleSituation(fields, UserReply.of(context))) {
            is RoleSituation.Ready -> FormStage.ROLE_READY
            RoleSituation.SubjectUnknown -> FormStage.SUBJECT_UNKNOWN
            is RoleSituation.NeedsPerson -> FormStage.ROLE_NEEDS_PERSON
            is RoleSituation.Typed -> FormStage.ROLE_TYPED
            RoleSituation.None -> if (FormRefs.open(fields).isEmpty()) FormStage.NOTHING_OPEN else FormStage.OPEN_FIELDS
        }
    }

    /**
     * The turn began with the user's answer to a question and the fill holds something the user typed: the answer may be the yes to
     * "remember it?", so `remember_detail` is offered (the tool itself still checks the user agreed and what the value is).
     */
    private suspend fun rememberPending(context: AgentContext): Boolean =
        context.turnStartedByUser && context.previousTurnEnd?.name == AskUserTool.NAME &&
            env.fields().any { it.value != null && it.valueSource == FormValueSource.USER }
}
