package com.postsaimanager.core.data.repository

import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.model.DocumentType
import com.postsaimanager.core.model.ExtractedContact
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ExtractionResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Structural document extraction engine.
 *
 * Instead of just running regex on raw text, this:
 * 1. Detects the document language from text features
 * 2. Splits the document into structural zones (letterhead, address block, subject, body, footer)
 * 3. Extracts sender profile from letterhead/footer
 * 4. Extracts receiver profile from address block
 * 5. Extracts metadata (date, reference, subject)
 * 6. Extracts structured data (IBANs, amounts, deadlines)
 * 7. Extracts body content and summary
 */
@Singleton
class EntityExtractor @Inject constructor() {

    fun extract(documentId: String, ocrText: String, ocrLanguage: String?): ExtractionResult {
        if (ocrText.isBlank()) return ExtractionResult(
            documentId = documentId, language = ocrLanguage, fields = emptyList()
        )

        val fields = mutableListOf<ExtractedData>()
        val lines = ocrText.lines()

        // ── Step 1: Detect language ──
        val detectedLanguage = detectLanguage(ocrText)

        // ── Step 2: Parse document zones ──
        val zones = parseDocumentZones(lines)

        // ── Step 3: Extract sender (from letterhead / footer) ──
        val sender = extractSenderContact(zones.letterhead, zones.footer, ocrText)
        sender?.let { contact ->
            contact.name?.let { name ->
                fields.add(makeField(documentId, "Sender Name", name, ExtractedFieldType.PERSON_NAME, 0.85f))
            }
            contact.organization?.let { org ->
                fields.add(makeField(documentId, "Sender Organization", org, ExtractedFieldType.ORGANIZATION, 0.80f))
            }
            contact.street?.let { street ->
                fields.add(makeField(documentId, "Sender Address", buildAddress(contact), ExtractedFieldType.ADDRESS, 0.75f))
            }
            contact.phone?.let { phone ->
                fields.add(makeField(documentId, "Sender Phone", phone, ExtractedFieldType.PHONE, 0.85f))
            }
            contact.email?.let { email ->
                fields.add(makeField(documentId, "Sender Email", email, ExtractedFieldType.EMAIL, 0.95f))
            }
        }

        // ── Step 4: Extract receiver (from address block) ──
        val receiver = extractReceiverContact(zones.addressBlock)
        receiver?.let { contact ->
            contact.name?.let { name ->
                fields.add(makeField(documentId, "Receiver Name", name, ExtractedFieldType.PERSON_NAME, 0.80f))
            }
            contact.organization?.let { org ->
                fields.add(makeField(documentId, "Receiver Organization", org, ExtractedFieldType.ORGANIZATION, 0.75f))
            }
            if (contact.street != null) {
                fields.add(makeField(documentId, "Receiver Address", buildAddress(contact), ExtractedFieldType.ADDRESS, 0.70f))
            }
        }

        // ── Step 5: Extract date (prefer near-top, labeled dates) ──
        val documentDate = extractDocumentDate(zones.metadataBlock, ocrText)
        documentDate?.let { date ->
            fields.add(makeField(documentId, "Document Date", date, ExtractedFieldType.DATE, 0.90f))
        }

        // ── Step 6: Extract subject ──
        val subject = extractSubject(zones.subjectLine, ocrText)
        subject?.let { subj ->
            fields.add(makeField(documentId, "Subject", subj, ExtractedFieldType.SUBJECT, 0.90f))
        }

        // ── Step 7: Extract reference numbers ──
        extractReferenceNumbers(ocrText).forEach { (label, value) ->
            fields.add(makeField(documentId, label, value, ExtractedFieldType.REFERENCE_NUMBER, 0.80f))
        }

        // ── Step 8: Extract financial data ──
        extractIbans(ocrText).forEach { iban ->
            fields.add(makeField(documentId, "IBAN", iban, ExtractedFieldType.IBAN, 0.92f))
        }
        extractAmounts(ocrText).forEach { amount ->
            fields.add(makeField(documentId, "Amount", amount, ExtractedFieldType.OTHER, 0.80f))
        }

        // ── Step 9: Extract deadlines ──
        extractDeadlines(ocrText).forEach { deadline ->
            fields.add(makeField(documentId, "Deadline", deadline, ExtractedFieldType.DEADLINE, 0.80f))
        }

        // ── Step 10: Extract body content summary ──
        val bodySummary = extractBodySummary(zones.body)
        if (bodySummary.isNotBlank()) {
            fields.add(makeField(documentId, "Content Preview", bodySummary, ExtractedFieldType.TEXT, 0.70f))
        }

        // ── Step 11: Extract all emails and phones from full text ──
        extractEmails(ocrText).forEach { email ->
            if (fields.none { it.fieldValue == email }) {
                fields.add(makeField(documentId, "Email", email, ExtractedFieldType.EMAIL, 0.95f))
            }
        }
        extractPhoneNumbers(ocrText).forEach { phone ->
            if (fields.none { it.fieldValue == phone }) {
                fields.add(makeField(documentId, "Phone", phone, ExtractedFieldType.PHONE, 0.85f))
            }
        }

        // ── Step 12: Classify document type ──
        val docType = classifyDocumentType(ocrText, fields)

        return ExtractionResult(
            documentId = documentId,
            language = detectedLanguage,
            sender = sender,
            receiver = receiver,
            subject = subject,
            date = documentDate,
            referenceNumbers = fields.filter { it.fieldType == ExtractedFieldType.REFERENCE_NUMBER }.map { it.fieldValue },
            deadline = fields.find { it.fieldType == ExtractedFieldType.DEADLINE }?.fieldValue,
            documentType = docType,
            fields = fields,
        )
    }

    // ═══════════════════════════════════════════════════════════
    // Language Detection
    // ═══════════════════════════════════════════════════════════

    fun detectLanguage(text: String): String {
        val lower = text.lowercase()
        val words = lower.split(Regex("\\s+"))

        val germanMarkers = listOf(
            "der", "die", "das", "und", "ist", "von", "mit", "für", "auf", "den",
            "des", "ein", "eine", "nicht", "sich", "auch", "werden", "haben",
            "sehr", "geehrte", "freundlichen", "grüßen", "bitte", "betreff",
            "straße", "stadt", "herrn", "frau", "gmbh", "e.v.", "bundesrepublik",
        )
        val arabicPattern = Regex("[\\u0600-\\u06FF]")
        val englishMarkers = listOf(
            "the", "and", "for", "with", "from", "this", "that", "have", "been",
            "dear", "sincerely", "regards", "please", "subject", "reference",
        )

        val arabicChars = arabicPattern.findAll(text).count()
        val germanHits = words.count { it in germanMarkers }
        val englishHits = words.count { it in englishMarkers }

        return when {
            arabicChars > text.length * 0.3 -> "ar"
            germanHits > englishHits && germanHits >= 3 -> "de"
            englishHits > germanHits && englishHits >= 3 -> "en"
            germanHits > 0 -> "de" // Default bias towards German
            else -> "de"
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Document Zone Parsing
    // ═══════════════════════════════════════════════════════════

    data class DocumentZones(
        val letterhead: List<String>,     // Top of doc: sender info, logo text
        val addressBlock: List<String>,   // Recipient address window
        val metadataBlock: List<String>,  // Date, reference numbers (right side)
        val subjectLine: String?,         // "Betreff:" line
        val body: List<String>,           // Main content
        val footer: List<String>,         // Bottom: contact info, legal
    )

    private fun parseDocumentZones(lines: List<String>): DocumentZones {
        val nonEmpty = lines.map { it.trim() }
        val totalLines = nonEmpty.size

        if (totalLines < 5) return DocumentZones(
            letterhead = nonEmpty.take(2),
            addressBlock = emptyList(),
            metadataBlock = emptyList(),
            subjectLine = null,
            body = nonEmpty,
            footer = emptyList(),
        )

        // Heuristic zone boundaries for German letters (DIN 5008 format):
        // - Lines 1-5: Letterhead (sender name, org)
        // - Lines 6-10: Address block (receiver)
        // - Lines 11-14: Metadata (date, reference)
        // - Line 15-ish: Subject (Betreff:)
        // - Rest: Body
        // - Last 5-10 lines: Footer

        val topSection = nonEmpty.take(minOf(8, totalLines))
        val footerStart = maxOf(0, totalLines - 8)

        // Find subject line
        val subjectIdx = nonEmpty.indexOfFirst { line ->
            line.matches(Regex("(?i)(betreff|betrifft|subject|re:)\\s*[:.]?\\s*.+"))
        }

        // Find address block: consecutive short lines (< 60 chars) with postal patterns
        val addressStart = findAddressBlockStart(nonEmpty)
        val addressEnd = if (addressStart >= 0) findAddressBlockEnd(nonEmpty, addressStart) else -1

        // Build zones
        val letterhead = if (addressStart > 0) nonEmpty.take(addressStart) else topSection.take(4)

        val addressBlock = if (addressStart >= 0 && addressEnd >= addressStart) {
            nonEmpty.subList(addressStart, minOf(addressEnd + 1, totalLines))
        } else emptyList()

        val metadataEnd = if (subjectIdx > 0) subjectIdx else minOf(totalLines, maxOf(addressEnd + 4, 14))
        val metadataStart = maxOf(addressEnd + 1, letterhead.size)
        val metadataBlock = if (metadataStart < metadataEnd) {
            nonEmpty.subList(metadataStart, minOf(metadataEnd, totalLines))
        } else emptyList()

        val subjectLine = if (subjectIdx >= 0) nonEmpty[subjectIdx] else null

        val bodyStart = if (subjectIdx >= 0) subjectIdx + 1 else metadataEnd
        val bodyEnd = footerStart
        val body = if (bodyStart < bodyEnd) nonEmpty.subList(bodyStart, bodyEnd) else emptyList()

        val footer = nonEmpty.subList(footerStart, totalLines)

        return DocumentZones(
            letterhead = letterhead,
            addressBlock = addressBlock,
            metadataBlock = metadataBlock,
            subjectLine = subjectLine,
            body = body,
            footer = footer,
        )
    }

    private fun findAddressBlockStart(lines: List<String>): Int {
        // Address blocks typically start after letterhead, with short lines
        // containing "Herrn", "Frau", "An", or starting a name
        for (i in 2..minOf(12, lines.size - 1)) {
            val line = lines[i]
            if (line.matches(Regex("(?i)(an\\s|herrn|frau|firma|z\\.\\s?hd\\.|familie).*")) ||
                (line.length in 5..50 && i + 1 < lines.size &&
                    lines[i + 1].matches(Regex(".*\\d{5}\\s+\\w+.*")))
            ) {
                return i
            }
        }
        return -1
    }

    private fun findAddressBlockEnd(lines: List<String>, start: Int): Int {
        // Address ends at postal code line or after 5 lines max
        for (i in start..minOf(start + 5, lines.size - 1)) {
            if (lines[i].matches(Regex(".*\\d{5}\\s+\\w+.*")) || // German PLZ
                lines[i].matches(Regex(".*\\d{4,5}\\s+[A-Z].*"))) { // Generic postal
                return i
            }
        }
        return minOf(start + 3, lines.size - 1)
    }

    // ═══════════════════════════════════════════════════════════
    // Contact Extraction
    // ═══════════════════════════════════════════════════════════

    private fun extractSenderContact(letterhead: List<String>, footer: List<String>, fullText: String): ExtractedContact? {
        if (letterhead.isEmpty() && footer.isEmpty()) return null

        val allSenderLines = letterhead + footer
        val combined = allSenderLines.joinToString("\n")

        // Organization: look for GmbH, e.V., AG, etc.
        val orgLine = allSenderLines.firstOrNull { line ->
            line.contains(Regex("(?i)(gmbh|e\\.\\s?v\\.|ag|ohg|kg|gbr|ltd|inc|co\\.)", RegexOption.IGNORE_CASE)) ||
                line.contains(Regex("(?i)(amt|behörde|finanzamt|stadt|gemeinde|kreis|land|bundes|ministerium|kammer|versicherung|kasse|anstalt)"))
        }

        // Person name from letterhead (first non-empty reasonably short line)
        val personName = letterhead.firstOrNull { it.length in 3..50 && !it.contains(Regex("[0-9]{3,}")) }

        // Address from footer/letterhead
        val streetLine = allSenderLines.firstOrNull { line ->
            line.matches(Regex(".*(?:straße|str\\.|weg|platz|allee|gasse|ring|damm|ufer).*\\d+.*", RegexOption.IGNORE_CASE)) ||
                line.matches(Regex(".*\\d+[a-z]?\\s*,?\\s*\\d{5}.*"))
        }

        val postalLine = allSenderLines.firstOrNull { line ->
            line.matches(Regex("\\d{5}\\s+\\w+.*"))
        }

        val email = extractEmails(combined).firstOrNull()
        val phone = extractPhoneNumbers(combined).firstOrNull()

        return ExtractedContact(
            name = if (orgLine != null) null else personName?.trim(),
            organization = orgLine?.trim() ?: (if (personName != letterhead.firstOrNull()) letterhead.firstOrNull()?.trim() else null),
            street = streetLine?.trim(),
            postalCode = postalLine?.let { Regex("(\\d{5})").find(it)?.value },
            city = postalLine?.let { Regex("\\d{5}\\s+(.+)").find(it)?.groupValues?.get(1)?.trim() },
            phone = phone,
            email = email,
        ).takeIf { it.name != null || it.organization != null }
    }

    private fun extractReceiverContact(addressBlock: List<String>): ExtractedContact? {
        if (addressBlock.isEmpty()) return null

        val lines = addressBlock.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        // Parse address block lines:
        // Line 1: Title/salutation or name (Herrn/Frau + Name, or direct name)
        // Line 2-3: Additional name/org lines
        // Line N-1: Street
        // Line N: PLZ + City

        var name: String? = null
        var organization: String? = null
        var street: String? = null
        var postalCode: String? = null
        var city: String? = null

        for (line in lines) {
            val trimmed = line.trim()
            when {
                // Postal code + city
                trimmed.matches(Regex("\\d{4,5}\\s+.+")) -> {
                    val match = Regex("(\\d{4,5})\\s+(.+)").find(trimmed)
                    postalCode = match?.groupValues?.get(1)
                    city = match?.groupValues?.get(2)?.trim()
                }
                // Street with number
                trimmed.matches(Regex(".*(?:straße|str\\.|weg|platz|allee|gasse|ring|damm|ufer|avenue|street|road).*\\d+.*", RegexOption.IGNORE_CASE)) ||
                    trimmed.matches(Regex(".*\\d+[a-z]?$", RegexOption.IGNORE_CASE)) -> {
                    street = trimmed
                }
                // Name with salutation
                trimmed.matches(Regex("(?i)(herrn?|frau|mr\\.?|mrs\\.?|ms\\.?)\\s+.+")) -> {
                    name = trimmed.replace(Regex("(?i)^(herrn?|frau|mr\\.?|mrs\\.?|ms\\.?)\\s+"), "").trim()
                }
                // Organization indicators
                trimmed.contains(Regex("(?i)(gmbh|e\\.v\\.|ag|ohg|firma|co\\.)")) -> {
                    organization = trimmed
                }
                // Likely a name (short, no numbers, not already assigned)
                name == null && trimmed.length in 3..50 && !trimmed.contains(Regex("\\d{3,}")) -> {
                    name = trimmed
                }
            }
        }

        return ExtractedContact(
            name = name,
            organization = organization,
            street = street,
            postalCode = postalCode,
            city = city,
        ).takeIf { it.name != null || it.organization != null }
    }

    // ═══════════════════════════════════════════════════════════
    // Metadata Extraction
    // ═══════════════════════════════════════════════════════════

    private fun extractDocumentDate(metadataBlock: List<String>, fullText: String): String? {
        // First: look for labeled date in metadata block
        val metalText = metadataBlock.joinToString(" ")
        val labeledDate = Regex("(?i)(?:datum|date|vom|berlin|münchen|hamburg|köln|frankfurt|stuttgart),?\\s*(\\d{1,2}\\.\\s*\\d{1,2}\\.\\s*\\d{4})")
            .find(metalText)?.groupValues?.get(1)?.replace(" ", "")
        if (labeledDate != null) return labeledDate

        // Also try "DD. Month YYYY" format
        val longDate = Regex("(\\d{1,2}\\.\\s*(?:Januar|Februar|März|April|Mai|Juni|Juli|August|September|Oktober|November|Dezember)\\s*\\d{4})", RegexOption.IGNORE_CASE)
            .find(metalText)?.value
        if (longDate != null) return longDate

        // Fallback: first DD.MM.YYYY in first 30% of text
        val topText = fullText.take((fullText.length * 0.3).toInt())
        return Regex("(\\d{1,2}\\.\\d{1,2}\\.\\d{4})").find(topText)?.value
    }

    private fun extractSubject(subjectLine: String?, fullText: String): String? {
        // From parsed zone
        if (subjectLine != null) {
            return subjectLine
                .replace(Regex("(?i)^(betreff|betrifft|subject|re)\\s*[:.]?\\s*"), "")
                .trim()
                .takeIf { it.isNotBlank() }
        }

        // Fallback: look for bold/emphasized text or "Betreff:" pattern
        return Regex("(?i)(?:betreff|betrifft|subject)\\s*[:.]\\s*(.+)")
            .find(fullText)?.groupValues?.get(1)?.trim()
    }

    private fun extractReferenceNumbers(text: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val patterns = listOf(
            Regex("(?i)(?:aktenzeichen|az\\.?)\\s*[:.]?\\s*([A-Za-z0-9\\-/\\.\\s]{3,30})") to "File Reference (Aktenzeichen)",
            Regex("(?i)(?:geschäftszeichen|gz\\.?)\\s*[:.]?\\s*([A-Za-z0-9\\-/\\.\\s]{3,30})") to "Business Reference",
            Regex("(?i)(?:unser zeichen|uns\\.?\\s*z(?:eichen)?)\\s*[:.]?\\s*([A-Za-z0-9\\-/\\.\\s]{3,30})") to "Our Reference",
            Regex("(?i)(?:ihr zeichen)\\s*[:.]?\\s*([A-Za-z0-9\\-/\\.\\s]{3,30})") to "Your Reference",
            Regex("(?i)(?:kunden[\\-\\s]?nr\\.?|kundennummer)\\s*[:.]?\\s*([A-Za-z0-9\\-]{3,20})") to "Customer Number",
            Regex("(?i)(?:vertrags[\\-\\s]?nr\\.?|vertragsnummer)\\s*[:.]?\\s*([A-Za-z0-9\\-]{3,20})") to "Contract Number",
            Regex("(?i)(?:rechnungs[\\-\\s]?nr\\.?|rechnungsnummer)\\s*[:.]?\\s*([A-Za-z0-9\\-]{3,20})") to "Invoice Number",
            Regex("(?i)(?:steuer[\\-\\s]?nr\\.?|steuernummer)\\s*[:.]?\\s*([0-9/\\-]{5,20})") to "Tax Number",
            Regex("(?i)(?:steuer[\\-\\s]?id|steueridentifikationsnummer)\\s*[:.]?\\s*(\\d{11})") to "Tax ID",
            Regex("(?i)(?:versicherungs[\\-\\s]?nr\\.?)\\s*[:.]?\\s*([A-Za-z0-9\\-]{3,20})") to "Insurance Number",
        )
        for ((regex, label) in patterns) {
            regex.find(text)?.let { match ->
                val value = match.groupValues[1].trim()
                if (value.isNotBlank()) results.add(label to value)
            }
        }
        return results
    }

    // ═══════════════════════════════════════════════════════════
    // Financial & Deadline Extraction
    // ═══════════════════════════════════════════════════════════

    private fun extractIbans(text: String): List<String> =
        Regex("[A-Z]{2}\\d{2}[\\s]?(?:\\d{4}[\\s]?){4}\\d{0,4}")
            .findAll(text).map { it.value.replace(Regex("\\s"), "") }
            .filter { it.length in 18..34 }
            .toList().distinct()

    private fun extractAmounts(text: String): List<String> {
        // German format: 1.234,56 € or EUR 1.234,56 or 1234,56€
        val amounts = Regex("(?:EUR|€)\\s*([\\d.]+,\\d{2})|([\\d.]+,\\d{2})\\s*(?:EUR|€)", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { match ->
                val raw = (match.groupValues[1].ifBlank { match.groupValues[2] })
                "$raw €"
            }
            .toList().distinct()
        return amounts.take(5) // Max 5 amounts
    }

    private fun extractDeadlines(text: String): List<String> {
        val results = mutableListOf<String>()
        val patterns = listOf(
            Regex("(?i)(?:frist|bis zum|spätestens|deadline|bis spätestens)\\s*[:.]?\\s*(\\d{1,2}\\.\\d{1,2}\\.\\d{4})"),
            Regex("(?i)(?:frist|bis zum|spätestens)\\s*[:.]?\\s*(\\d{1,2}\\.\\s*(?:Januar|Februar|März|April|Mai|Juni|Juli|August|September|Oktober|November|Dezember)\\s*\\d{4})"),
            Regex("(?i)(?:innerhalb von|within|binnen)\\s+(\\d+)\\s+(?:Tagen?|Wochen?|Monaten?|days?|weeks?|months?)"),
        )
        for (regex in patterns) {
            regex.findAll(text).forEach { match ->
                results.add(match.groupValues[1].trim())
            }
        }
        return results.distinct().take(3)
    }

    // ═══════════════════════════════════════════════════════════
    // Content Extraction
    // ═══════════════════════════════════════════════════════════

    private fun extractBodySummary(body: List<String>): String {
        val meaningful = body.filter { it.length > 10 && !it.matches(Regex("^[\\-_=]+$")) }
        return meaningful.take(5).joinToString(" ").take(500)
    }

    // ═══════════════════════════════════════════════════════════
    // Shared Patterns
    // ═══════════════════════════════════════════════════════════

    private fun extractEmails(text: String): List<String> =
        Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+")
            .findAll(text).map { it.value }.toList().distinct()

    private fun extractPhoneNumbers(text: String): List<String> {
        val patterns = listOf(
            Regex("(?i)(?:tel\\.?|telefon|fon|phone|mobil)\\s*[:.]?\\s*([+\\d\\s\\-/()]{8,})"),
            Regex("(?i)(?:fax)\\s*[:.]?\\s*([+\\d\\s\\-/()]{8,})"),
        )
        return patterns.flatMap { regex ->
            regex.findAll(text).map { it.groupValues[1].trim() }
        }.distinct()
    }

    // ═══════════════════════════════════════════════════════════
    // Document Classification
    // ═══════════════════════════════════════════════════════════

    private fun classifyDocumentType(text: String, fields: List<ExtractedData>): DocumentType {
        val lower = text.lowercase()
        val hasAmount = fields.any { it.fieldName == "Amount" }
        val hasIban = fields.any { it.fieldType == ExtractedFieldType.IBAN }

        return when {
            lower.contains("rechnung") || lower.contains("invoice") || (hasAmount && hasIban) -> DocumentType.INVOICE
            lower.contains("mahnung") -> DocumentType.INVOICE
            lower.contains("bescheid") || lower.contains("steuerbescheid") -> DocumentType.NOTICE
            lower.contains("mitteilung") || lower.contains("benachrichtigung") -> DocumentType.NOTICE
            lower.contains("antrag") || lower.contains("formular") || lower.contains("ausfüllen") -> DocumentType.FORM
            lower.contains("vertrag") || lower.contains("contract") || lower.contains("vereinbarung") -> DocumentType.CONTRACT
            lower.contains("kündigung") -> DocumentType.CONTRACT
            lower.contains("zeugnis") || lower.contains("certificate") || lower.contains("bescheinigung") -> DocumentType.CERTIFICATE
            lower.contains("quittung") || lower.contains("receipt") -> DocumentType.RECEIPT
            lower.contains("sehr geehrte") || lower.contains("mit freundlichen") -> DocumentType.OFFICIAL_LETTER
            else -> DocumentType.OFFICIAL_LETTER
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    private fun makeField(
        documentId: String,
        name: String,
        value: String,
        type: ExtractedFieldType,
        confidence: Float,
    ) = ExtractedData(
        id = UuidGenerator.generate(),
        documentId = documentId,
        fieldName = name,
        fieldValue = value,
        fieldType = type,
        confidence = confidence,
    )

    private fun buildAddress(contact: ExtractedContact): String = buildString {
        contact.street?.let { append(it) }
        if (contact.postalCode != null || contact.city != null) {
            if (isNotEmpty()) append(", ")
            contact.postalCode?.let { append("$it ") }
            contact.city?.let { append(it) }
        }
        contact.country?.let {
            if (isNotEmpty()) append(", ")
            append(it)
        }
    }
}
