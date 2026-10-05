package com.postsaimanager.core.domain.extraction.v2

/**
 * Makes the [DocumentInterpreter] for one extraction, sized to the window the model was loaded with and
 * chosen for the model that will read the letter ([modelId] is its catalogue id, null when unknown).
 *
 * The app binds `ProfileInterpreterFactory` (`extraction/zones/ModelProfile.kt`), which picks the strategy
 * from the model's registered profile.
 */
fun interface InterpreterFactory {
    fun create(contextTokens: Int, modelId: String?): DocumentInterpreter
}
