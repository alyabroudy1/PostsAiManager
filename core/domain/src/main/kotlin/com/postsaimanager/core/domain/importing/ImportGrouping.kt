package com.postsaimanager.core.domain.importing

/**
 * Which files become which documents. Decided from the kinds of the files and one switch the user controls, never from content:
 * one PDF is one document; images shared together are one document in the shared order (or one each when
 * [eachImageSeparate]); in a mix each PDF is its own document and the images go together.
 *
 * A document sits where its first file sat, so the list on the sheet reads in the shared order.
 */
object ImportGrouping {

    fun group(files: List<StagedFile>, eachImageSeparate: Boolean): List<ImportGroup> {
        val groups = mutableListOf<ImportGroup>()
        val images = files.filter { it.kind == ImportedKind.IMAGE }
        var imagesPlaced = false
        for (file in files) {
            when {
                file.kind == ImportedKind.PDF -> groups += ImportGroup(listOf(file))
                eachImageSeparate -> groups += ImportGroup(listOf(file))
                !imagesPlaced -> {
                    groups += ImportGroup(images)
                    imagesPlaced = true
                }
            }
        }
        return groups
    }
}
