package com.postsaimanager.core.data.repository

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.result.getOrNull
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.model.DocumentType
import com.postsaimanager.core.model.ExtractedContact
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ExtractionResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rule-based entity extraction from OCR text.
 * Extracts dates, addresses, reference numbers, contacts, etc.
 * using regex patterns common in German official letters.
 *
 * This runs without AI — providing immediate results.
 * AI-enhanced extraction runs later in the pipeline for refinement.
 */
@Singleton
class EntityExtractor @Inject constructor() {

    fun extract(documentId: String, ocrText: String, language: String?): ExtractionResult {
        val fields = mutableListOf<ExtractedData>()

        // ── Dates ──
        extractDates(ocrText).forEach { (date, confidence) ->
            fields.add(
                ExtractedData(
                    id = UuidGenerator.generate(),
                    documentId = documentId,
                    fieldName = "Date",
                    fieldValue = date,
                    fieldType = ExtractedFieldType.DATE,
                    confidence = confidence,
                )
            )
        }

        // ── Reference Numbers ──
        extractReferenceNumbers(ocrText).forEach { ref ->
            fields.add(
                ExtractedData(
                    id = UuidGenerator.generate(),
                    documentId = documentId,
                    fieldName = "Reference",
                    fieldValue = ref,
                    fieldType = ExtractedFieldType.REFERENCE_NUMBER,
                    confidence = 0.8f,
                )
            )
        }

        // ── Emails ──
        extractEmails(ocrText).forEach { email ->
            fields.add(
                ExtractedData(
                    id = UuidGenerator.generate(),
                    documentId = documentId,
                    fieldName = "Email",
                    fieldValue = email,
                    fieldType = ExtractedFieldType.EMAIL,
                    confidence = 0.95f,
                )
            )
        }

        // ── Phone Numbers ──
        extractPhoneNumbers(ocrText).forEach { phone ->
            fields.add(
                ExtractedData(
                    id = UuidGenerator.generate(),
                    documentId = documentId,
                    fieldName = "Phone",
                    fieldValue = phone,
                    fieldType = ExtractedFieldType.PHONE,
                    confidence = 0.85f,
                )
            )
        }

        // ── IBAN ──
        extractIbans(ocrText).forEach { iban ->
            fields.add(
                ExtractedData(
                    id = UuidGenerator.generate(),
                    documentId = documentId,
                    fieldName = "IBAN",
                    fieldValue = iban,
                    fieldType = ExtractedFieldType.IBAN,
                    confidence = 0.9f,
                )
            )
        }

        // ── Subject line (German: "Betreff") ──
        extractSubject(ocrText)?.let { subject ->
            fields.add(
                ExtractedData(
                    id = UuidGenerator.generate(),
                    documentId = documentId,
                    fieldName = "Subject",
                    fieldValue = subject,
                    fieldType = ExtractedFieldType.SUBJECT,
                    confidence = 0.85f,
                )
            )
        }

        // ── Deadlines (German: "Frist", "bis zum") ──
        extractDeadline(ocrText)?.let { deadline ->
            fields.add(
                ExtractedData(
                    id = UuidGenerator.generate(),
                    documentId = documentId,
                    fieldName = "Deadline",
                    fieldValue = deadline,
                    fieldType = ExtractedFieldType.DEADLINE,
                    confidence = 0.75f,
                )
            )
        }

        return ExtractionResult(
            documentId = documentId,
            language = language,
            subject = fields.find { it.fieldType == ExtractedFieldType.SUBJECT }?.fieldValue,
            date = fields.find { it.fieldType == ExtractedFieldType.DATE }?.fieldValue,
            referenceNumbers = fields.filter { it.fieldType == ExtractedFieldType.REFERENCE_NUMBER }
                .map { it.fieldValue },
            deadline = fields.find { it.fieldType == ExtractedFieldType.DEADLINE }?.fieldValue,
            documentType = guessDocumentType(ocrText),
            fields = fields,
        )
    }

    // ── Regex patterns for German official correspondence ──

    private fun extractDates(text: String): List<Pair<String, Float>> {
        val patterns = listOf(
            // DD.MM.YYYY
            Regex("""\b(\d{1,2}\.\d{1,2}\.\d{4})\b""") to 0.95f,
            // DD.MM.YY
            Regex("""\b(\d{1,2}\.\d{1,2}\.\d{2})\b""") to 0.85f,
            // DD. Month YYYY (German)
            Regex("""\b(\d{1,2}\.\s*(?:Januar|Februar|März|April|Mai|Juni|Juli|August|September|Oktober|November|Dezember)\s*\d{4})\b""") to 0.95f,
        )
        return patterns.flatMap { (regex, conf) ->
            regex.findAll(text).map { it.groupValues[1] to conf }.toList()
        }.distinctBy { it.first }
    }

    private fun extractReferenceNumbers(text: String): List<String> {
        val patterns = listOf(
            // Common German reference patterns
            Regex("""(?:Aktenzeichen|Az\.?|Geschäftszeichen|Gz\.?|Unser Zeichen|Ihr Zeichen)[\s:]+([A-Za-z0-9\-/\.]+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:Kunden-?Nr\.?|Kundennummer)[\s:]+([A-Za-z0-9\-]+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:Vertrags-?Nr\.?|Vertragsnummer)[\s:]+([A-Za-z0-9\-]+)""", RegexOption.IGNORE_CASE),
        )
        return patterns.flatMap { regex ->
            regex.findAll(text).map { it.groupValues[1].trim() }.toList()
        }.distinct()
    }

    private fun extractEmails(text: String): List<String> =
        Regex("""[\w.+-]+@[\w-]+\.[\w.]+""")
            .findAll(text).map { it.value }.toList().distinct()

    private fun extractPhoneNumbers(text: String): List<String> =
        Regex("""(?:Tel\.?|Telefon|Fon|Phone)[\s:]*([+\d\s\-/()]{8,})""", RegexOption.IGNORE_CASE)
            .findAll(text).map { it.groupValues[1].trim() }.toList().distinct()

    private fun extractIbans(text: String): List<String> =
        Regex("""[A-Z]{2}\d{2}\s?\d{4}\s?\d{4}\s?\d{4}\s?\d{4}\s?\d{0,2}""")
            .findAll(text).map { it.value.replace(" ", "") }.toList().distinct()

    private fun extractSubject(text: String): String? =
        Regex("""(?:Betreff|Betrifft|Subject)[\s:]+(.+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.trim()

    private fun extractDeadline(text: String): String? {
        val patterns = listOf(
            Regex("""(?:Frist|bis zum|spätestens|deadline)[\s:]+(\d{1,2}\.\d{1,2}\.\d{4})""", RegexOption.IGNORE_CASE),
            Regex("""(?:innerhalb von|within)\s+(\d+)\s+(?:Tagen|Wochen|days|weeks)""", RegexOption.IGNORE_CASE),
        )
        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(text)?.groupValues?.get(1)?.trim()
        }
    }

    private fun guessDocumentType(text: String): DocumentType? {
        val lower = text.lowercase()
        return when {
            lower.contains("rechnung") || lower.contains("invoice") -> DocumentType.INVOICE
            lower.contains("bescheid") || lower.contains("mitteilung") -> DocumentType.NOTICE
            lower.contains("antrag") || lower.contains("formular") -> DocumentType.FORM
            lower.contains("vertrag") || lower.contains("contract") -> DocumentType.CONTRACT
            lower.contains("zeugnis") || lower.contains("certificate") -> DocumentType.CERTIFICATE
            lower.contains("quittung") || lower.contains("receipt") -> DocumentType.RECEIPT
            lower.contains("sehr geehrte") || lower.contains("mit freundlichen") -> DocumentType.OFFICIAL_LETTER
            else -> null
        }
    }
}
