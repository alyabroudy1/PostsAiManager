package com.postsaimanager.core.domain.benchmark

/** The families a reader would accept for each benchmark letter (several where the letter is honestly either); shared by the accuracy test and the threshold fit. */
internal object FamilyExpectations {
    val BY_KEY: Map<String, Set<String>> = mapOf(
        "N1-mahnung-telco-qr-1p" to setOf("invoice_bill"),
        "N2-kfz-verlaengerung-2p" to setOf("contract_policy"),
        "N3-schule-familie-2p" to setOf("official_letter"),
        "N4-zhd-firma-1p" to setOf("contract_policy", "free_form"),
        "N5-co-familie-1p" to setOf("invoice_bill"),
        "N6-nebenkosten-3p" to setOf("invoice_bill"),
        "N7-beitragsservice-1p" to setOf("invoice_bill", "official_letter"),
        "N8-info-bank-noaction-1p" to setOf("official_letter"),
        "N9-fuzzy-name-1p" to setOf("invoice_bill"),
        "N10-kinderarzt-termin-1p" to setOf("medical"),
        "invoice-2p" to setOf("invoice_bill"),
        "tax-long-7p" to setOf("official_letter"),
        "receipt-noise-1p" to setOf("receipt"),
    )
}
