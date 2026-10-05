package com.postsaimanager

import android.content.Context
import android.util.Log
import com.postsaimanager.core.domain.agent.AgentTrace
import com.postsaimanager.core.domain.form.fill.AllowlistedFormOcrTrace
import com.postsaimanager.core.domain.form.fill.FormFillTrace
import com.postsaimanager.core.domain.form.fill.FormOcrTrace
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * The form conversation's traces go to logcat in debug builds only; release builds report nothing. `FormFill` carries ids, counts
 * and states; `FormOcr` carries a form's OCR text and is limited to the documents listed in `files/debug-trace-docs.txt` (see
 * [FormOcrTrace] for how to enable it).
 */
@Module
@InstallIn(SingletonComponent::class)
object FormFillTraceModule {

    private const val TAG = "FormFill"
    private const val OCR_TAG = "FormOcr"
    private const val AGENT_TAG = "FormAgent"
    private const val ALLOWLIST = "debug-trace-docs.txt"

    @Provides
    @Singleton
    fun provideFormFillTrace(): FormFillTrace =
        if (BuildConfig.DEBUG) FormFillTrace { name, details -> Log.d(TAG, "$name $details") } else FormFillTrace.NONE

    /** One line per agent step in debug builds (tag `FormAgent`): tool, argument names, outcome, timings, session size. No values. */
    @Provides
    @Singleton
    fun provideAgentTrace(): AgentTrace =
        if (BuildConfig.DEBUG) AgentTrace { step -> Log.d(AGENT_TAG, step.line()) } else AgentTrace.NONE

    @Provides
    @Singleton
    fun provideFormOcrTrace(@ApplicationContext context: Context): FormOcrTrace =
        if (BuildConfig.DEBUG) {
            AllowlistedFormOcrTrace(
                enabled = { true },
                allowed = { runCatching { File(context.filesDir, ALLOWLIST).takeIf { it.isFile }?.readLines().orEmpty() }.getOrDefault(emptyList()) },
                sink = { Log.d(OCR_TAG, it) },
            )
        } else {
            FormOcrTrace.NONE
        }
}
