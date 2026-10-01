package com.postsaimanager.core.domain.form.fill

/**
 * The numbers of the form conversation as data (the same idea as `FormScoringProfile`): thresholds on the model's log-odds
 * scores, how many things are asked or shown at once. Defaults are the model's own Yes/No boundary; tune them on recordings.
 */
data class FormFillProfile(
    /** A message is read as an intent other than "answer" only when that intent's score is above this and the best. */
    val intentThreshold: Double = 0.0,
    /** ...and beats the "gives the answer" score by this much: a plain answer is by far the most likely thing to be typed, so it has a head start. */
    val answerPrior: Double = 1.5,
    /** A reply of at most this many characters and words is a candidate for being taken as the answer without asking the model. */
    val shortAnswerChars: Int = 80,
    val shortAnswerWords: Int = 8,
    /** A free answer names an option (or yes/no, or a person, or a field) only when its score is above this... */
    val choiceThreshold: Double = 0.0,
    /** ...and beats the runner-up by at least this. */
    val choiceMargin: Double = 1.0,
    /** "Does the user ask for help filling in this form?" is Yes above this. */
    val fillRequestThreshold: Double = 0.0,
    /** On a document that is not a form, the embedding model must find a message this close to a fill request before the model is asked. */
    val fillRequestEmbeddingGate: Float = 0.30f,
    /** At most this many questions per round, then the user decides to continue. */
    val roundSize: Int = 5,
    /** At most this many answer chips under a question (plus Skip). */
    val maxChips: Int = 6,
    /** The token budget of a question the model writes. */
    val questionTokens: Int = 120,
    /** At most this many fields are scored when the user asks to change a value. */
    val maxFieldScores: Int = 16,
)
