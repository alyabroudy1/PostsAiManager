package com.postsaimanager.core.common.extensions

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult

/**
 * Wraps a Flow emission into PamResult.Success, catching errors as PamResult.Error.
 */
fun <T> Flow<T>.asPamResult(): Flow<PamResult<T>> =
    this.map<T, PamResult<T>> { PamResult.Success(it) }
        .catch { emit(PamResult.Error(PamError.Unknown(cause = it))) }
