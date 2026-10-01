package com.postsaimanager

import android.util.Log
import com.postsaimanager.core.domain.form.fill.FormFillTrace
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The form conversation's trace goes to logcat (tag `FormFill`) in debug builds only; release builds report nothing. */
@Module
@InstallIn(SingletonComponent::class)
object FormFillTraceModule {

    private const val TAG = "FormFill"

    @Provides
    @Singleton
    fun provideFormFillTrace(): FormFillTrace =
        if (BuildConfig.DEBUG) FormFillTrace { name, details -> Log.d(TAG, "$name $details") } else FormFillTrace.NONE
}
