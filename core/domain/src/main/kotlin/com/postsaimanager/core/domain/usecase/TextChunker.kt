package com.postsaimanager.core.domain.usecase

/** A slice of a document, ready to embed. */
data class TextChunk(
    val ordinal: Int,
    val text: String,
)

/**
 * Splits OCR text into overlapping passages for retrieval.
 *
 * ### Why chunk at all
 *
 * Embedding a whole letter yields one vector averaging everything in it, so a question
 * about a deadline competes with the address block and the legal footer. Passages give the
 * retriever something specific to match.
 *
 * ### Why overlap
 *
 * A fact that straddles a boundary is otherwise split across two chunks and fully present
 * in neither — "Die Frist endet am | 31.01.2026" matches poorly in both halves. Overlap
 * costs a little storage and removes the failure mode entirely.
 *
 * ### Why paragraphs first
 *
 * German business letters are structured: address block, reference line, subject, body,
 * closing. Splitting on blank lines keeps those units intact, and a unit that is already
 * coherent embeds better than an arbitrary character window. Fixed-size splitting is only
 * the fallback for a paragraph too long to stand alone — which OCR of a dense page does
 * produce.
 */
object TextChunker {

    fun chunk(
        text: String,
        targetChars: Int = DEFAULT_TARGET_CHARS,
        overlapChars: Int = DEFAULT_OVERLAP_CHARS,
    ): List<TextChunk> {
        val normalised = text.replace("\r\n", "\n").trim()
        if (normalised.isBlank()) return emptyList()

        // Short enough to stand alone: splitting would only lose context.
        if (normalised.length <= targetChars) {
            return listOf(TextChunk(0, normalised))
        }

        val paragraphs = normalised.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        for (paragraph in paragraphs) {
            if (paragraph.length > targetChars) {
                // Too long to keep whole — flush, then window it.
                if (current.isNotBlank()) {
                    chunks += current.toString().trim()
                    current.clear()
                }
                chunks += splitLongText(paragraph, targetChars, overlapChars)
                continue
            }

            if (current.length + paragraph.length + 2 > targetChars && current.isNotBlank()) {
                chunks += current.toString().trim()
                // Carry the tail forward so a fact at the seam appears in both chunks.
                val tail = current.toString().takeLast(overlapChars)
                current.clear()
                current.append(tail).append("\n\n")
            }
            current.append(paragraph).append("\n\n")
        }

        if (current.isNotBlank()) chunks += current.toString().trim()

        return chunks
            .filter { it.length >= MIN_CHUNK_CHARS }
            .mapIndexed { index, content -> TextChunk(index, content) }
    }

    /** Fixed-size windows with overlap, for a paragraph that cannot stand alone. */
    private fun splitLongText(
        text: String,
        targetChars: Int,
        overlapChars: Int,
    ): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        val stride = (targetChars - overlapChars).coerceAtLeast(1)

        while (start < text.length) {
            val end = (start + targetChars).coerceAtMost(text.length)

            // Prefer a sentence boundary near the end — cutting mid-sentence produces a
            // fragment that embeds poorly and reads badly if ever shown to the user.
            val cut = if (end < text.length) {
                text.lastIndexOf('.', end).takeIf { it > start + stride / 2 }?.plus(1) ?: end
            } else {
                end
            }

            result += text.substring(start, cut).trim()
            if (cut >= text.length) break
            start = (cut - overlapChars).coerceAtLeast(start + 1)
        }
        return result.filter { it.isNotBlank() }
    }

    /**
     * ~700 characters is roughly 230 tokens of German — large enough to carry a whole
     * paragraph, small enough that several fit a 4 k context alongside the question.
     *
     * **The upper bound is not a matter of taste.** A chunk longer than the embedding
     * encoder's window is truncated when it is embedded, and nothing reports it: the
     * stored text keeps its tail, so keyword search still finds it, while its vector
     * represents only the beginning. The passage then fails to match questions about its
     * own second half, and looks merely irrelevant rather than broken.
     *
     * The encoder currently accepts 256 tokens, and German runs about three characters per
     * token, leaving ~768. This sits under that with room for the tokenizer being less
     * generous than the estimate on compound words. Raising it means raising the encoder's
     * window first — attention cost is quadratic in length, so that is not free.
     */
    const val DEFAULT_TARGET_CHARS = 700
    const val DEFAULT_OVERLAP_CHARS = 120
    private const val MIN_CHUNK_CHARS = 40
}
