package com.postsaimanager.core.domain.form.fill

/**
 * The numbers of the request detector as data (the same idea as `FormScoringProfile`): the threshold on the model's log-odds
 * score and the closeness the embedding model must find first. Defaults are the model's own Yes/No boundary; tune them on recordings.
 */
data class FormFillProfile(
    /** "Does the user ask for help filling in this form?" is Yes above this. */
    val fillRequestThreshold: Double = 0.0,
    /** On a document that is not a form, the embedding model must find a message this close to a fill request before the model is asked. */
    val fillRequestEmbeddingGate: Float = 0.30f,
)
