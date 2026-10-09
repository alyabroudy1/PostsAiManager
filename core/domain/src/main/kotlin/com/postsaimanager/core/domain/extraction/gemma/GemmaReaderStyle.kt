package com.postsaimanager.core.domain.extraction.gemma

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** How Gemma reads a letter: the "Questions" reader (the default), which asks as the chat does, or the constrained-JSON reader (the fallback). */
enum class ReaderStyle { JSON, QUESTIONS }

/** The debug switch "Reader style: JSON / Questions" (Settings, Debug; a release build always reads in the default style). */
interface GemmaReaderStyle {

    val style: Flow<ReaderStyle>

    suspend fun current(): ReaderStyle

    suspend fun set(style: ReaderStyle)

    /** The debug switch "Questions: always send the page image": off (the default), the Questions reader sends the picture only when the OCR text is weak ([QaImageDecision]). */
    val alwaysImage: Flow<Boolean>

    suspend fun alwaysSendImage(): Boolean

    suspend fun setAlwaysImage(always: Boolean)
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
