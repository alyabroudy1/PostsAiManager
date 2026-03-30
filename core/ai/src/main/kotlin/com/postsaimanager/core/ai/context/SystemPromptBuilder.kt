package com.postsaimanager.core.ai.context

import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.Profile
import javax.inject.Inject

/**
 * Builds the system prompt and context for AI conversations.
 * Assembles document text, extraction results, and profile data
 * into a coherent context that the LLM can reason about.
 */
class SystemPromptBuilder @Inject constructor() {

    fun buildDocumentContext(
        document: Document,
        pages: List<DocumentPage>,
        extractedData: List<ExtractedData>,
        profiles: List<Profile>,
    ): String = buildString {
        appendLine("You are a helpful assistant specializing in German postal correspondence management.")
        appendLine("You help users understand, organize, and respond to official letters and documents.")
        appendLine()
        appendLine("## Current Document")
        appendLine("Title: ${document.title}")
        document.documentType?.let { appendLine("Type: $it") }
        document.language?.let { appendLine("Language: $it") }
        appendLine()

        if (extractedData.isNotEmpty()) {
            appendLine("## Extracted Information")
            extractedData.forEach { field ->
                appendLine("- ${field.fieldName}: ${field.fieldValue} (${field.fieldType}, confidence: ${(field.confidence * 100).toInt()}%)")
            }
            appendLine()
        }

        if (profiles.isNotEmpty()) {
            appendLine("## Related Profiles")
            profiles.forEach { profile ->
                appendLine("- ${profile.name} (${profile.type})")
                profile.organization?.let { appendLine("  Organization: $it") }
                profile.reference?.let { appendLine("  Reference: $it") }
            }
            appendLine()
        }

        val fullText = pages.mapNotNull { it.ocrText }.joinToString("\n\n--- Page Break ---\n\n")
        if (fullText.isNotBlank()) {
            appendLine("## Document Full Text")
            appendLine(fullText)
        }

        appendLine()
        appendLine("## Instructions")
        appendLine("- Respond in the same language as the document (German or Arabic).")
        appendLine("- When asked to draft a reply, use formal German letter format.")
        appendLine("- Be concise and helpful.")
        appendLine("- If you're unsure about something, say so rather than guessing.")
    }

    fun buildStandaloneContext(): String = buildString {
        appendLine("You are a helpful assistant specializing in German postal correspondence management.")
        appendLine("You help users understand, organize, and respond to official letters and documents.")
        appendLine("Respond in the user's language.")
    }
}
