package com.postsaimanager.core.domain.importing

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.SourceType
import com.postsaimanager.core.testing.stagedFile
import org.junit.jupiter.api.Test

/** The grouping rules: one PDF is one document; images together are one document unless the switch splits them; a mix keeps both. */
class ImportGroupingTest {

    private val pdfA = stagedFile("a", ImportedKind.PDF, pages = 3)
    private val pdfB = stagedFile("b", ImportedKind.PDF, pages = 2)
    private val img1 = stagedFile("i1")
    private val img2 = stagedFile("i2")
    private val img3 = stagedFile("i3")

    private fun ids(groups: List<ImportGroup>) = groups.map { g -> g.files.map { it.id } }

    @Test
    fun `one PDF is one document of the PDF source type`() {
        val groups = ImportGrouping.group(listOf(pdfA), eachImageSeparate = false)
        assertThat(ids(groups)).containsExactly(listOf("a"))
        assertThat(groups.single().sourceType).isEqualTo(SourceType.PDF_IMPORT)
        assertThat(groups.single().pageCount).isEqualTo(3)
    }

    @Test
    fun `two PDFs are two documents`() {
        assertThat(ids(ImportGrouping.group(listOf(pdfA, pdfB), false))).containsExactly(listOf("a"), listOf("b")).inOrder()
    }

    @Test
    fun `images shared together are one document in the shared order`() {
        val groups = ImportGrouping.group(listOf(img2, img1, img3), eachImageSeparate = false)
        assertThat(ids(groups)).containsExactly(listOf("i2", "i1", "i3"))
        assertThat(groups.single().sourceType).isEqualTo(SourceType.UPLOAD)
    }

    @Test
    fun `the switch makes each image its own document`() {
        val groups = ImportGrouping.group(listOf(img1, img2, img3), eachImageSeparate = true)
        assertThat(ids(groups)).containsExactly(listOf("i1"), listOf("i2"), listOf("i3")).inOrder()
        assertThat(groups.map { it.sourceType }.toSet()).containsExactly(SourceType.UPLOAD)
    }

    @Test
    fun `in a mix each PDF is its own document and the images go together where the first one was`() {
        val groups = ImportGrouping.group(listOf(pdfA, img1, pdfB, img2), eachImageSeparate = false)
        assertThat(ids(groups)).containsExactly(listOf("a"), listOf("i1", "i2"), listOf("b")).inOrder()
    }

    @Test
    fun `in a mix with the switch the images are split and the PDFs stay`() {
        val groups = ImportGrouping.group(listOf(img1, pdfA, img2), eachImageSeparate = true)
        assertThat(ids(groups)).containsExactly(listOf("i1"), listOf("a"), listOf("i2")).inOrder()
    }

    @Test
    fun `nothing gives no documents`() {
        assertThat(ImportGrouping.group(emptyList(), false)).isEmpty()
    }

    @Test
    fun `a single file's hash is its own and several images hash their hashes in order`() {
        val single = ImportGroup(listOf(pdfA))
        assertThat(single.sourceHash).isEqualTo("sha-a")

        val ab = ImportGroup(listOf(img1, img2)).sourceHash
        val ba = ImportGroup(listOf(img2, img1)).sourceHash
        assertThat(ab).hasLength(64)
        assertThat(ab).isNotEqualTo(ba)
        assertThat(ImportGroup(listOf(img1, img2)).sourceHash).isEqualTo(ab)
    }
}
