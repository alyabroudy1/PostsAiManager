package com.postsaimanager.core.domain.extraction.v2

/**
 * The type ids stored by extractors before extraction-v2-2, mapped onto a document family and topics.
 * The one owner of that mapping: the database migration and the benchmark's legacy view both read it.
 */
object LegacyTypes {
    /** The family id and topic ids a legacy type id stands for. */
    data class Mapping(val family: String, val topics: List<String> = emptyList())

    val BY_TYPE: Map<String, Mapping> = mapOf(
        "bill" to Mapping("invoice_bill"),
        "reminder_dunning" to Mapping("invoice_bill"),
        "authority_tax" to Mapping("official_letter", listOf("government")),
        "health" to Mapping("medical", listOf("health")),
        "insurance_contract" to Mapping("contract_policy", listOf("insurance")),
        "school" to Mapping("official_letter", listOf("school_education")),
        "receipt" to Mapping("receipt"),
        "info_no_action" to Mapping("official_letter"),
        "other" to Mapping("free_form"),
    )

    /** The mapping of [typeId], or null when it is already a family id or unknown. */
    fun of(typeId: String?): Mapping? = typeId?.trim()?.lowercase()?.let(BY_TYPE::get)
}
