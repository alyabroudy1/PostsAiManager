package com.postsaimanager.core.domain.extraction.gemma

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** How Gemma reads a letter: the constrained-JSON reader (the default) or the "Questions" reader, which asks as the chat does. */
enum class ReaderStyle { JSON, QUESTIONS }

/** The debug switch "Reader style: JSON / Questions" (Settings, Debug; a release build always reads in the default style). */
interface GemmaReaderStyle {

    val style: Flow<ReaderStyle>

    suspend fun current(): ReaderStyle

    suspend fun set(style: ReaderStyle)
}

/**
 * The [GemmaDocumentReader] the app reads with: the chosen style's reader. A letter with no text lines is always read by the JSON reader
 * (the Questions reader has nothing to ask about).
 */
class StyleSwitchedGemmaReader @Inject constructor(
    private val json: ChatEngineGemmaReader,
    private val questions: QuestionAnswerGemmaReader,
    private val style: GemmaReaderStyle,
) : GemmaDocumentReader {

    override suspend fun read(request: GemmaReaderRequest): GemmaReaderOutcome =
        if (!request.letter.isImageOnly && style.current() == ReaderStyle.QUESTIONS) questions.read(request) else json.read(request)
}
