package com.postsaimanager.core.domain.document.list

import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.ExtractedData

/**
 * The one owner of "which stored field is the sender / the addressee of a document": the slot the
 * reading filled, then (for documents an older extractor read) the legacy field names. The document
 * list rows and the detail screen's summary card both ask here, so they cannot disagree.
 */
object PartyFields {

    /** The sender's field among [fields], or null when none was read. */
    fun sender(fields: List<ExtractedData>): ExtractedData? =
        fields.firstOrNull { it.slotKey == UnderstandingToFields.SLOT_SENDER }
            ?: fields.firstOrNull { it.slotKey == null && it.fieldName == UnderstandingToFields.SENDER_ORGANISATION }
            ?: fields.firstOrNull { it.slotKey == null && it.fieldName == UnderstandingToFields.SENDER_NAME }

    /** The addressee's field among [fields], or null when none was read. */
    fun addressee(fields: List<ExtractedData>): ExtractedData? =
        fields.firstOrNull { it.slotKey == UnderstandingToFields.SLOT_ADDRESSEE }
            ?: fields.firstOrNull { it.slotKey == null && it.fieldName == UnderstandingToFields.RECEIVER_NAME }

    fun of(party: DocumentParty, fields: List<ExtractedData>): ExtractedData? = when (party) {
        DocumentParty.SENDER -> sender(fields)
        DocumentParty.ADDRESSEE -> addressee(fields)
    }
}
