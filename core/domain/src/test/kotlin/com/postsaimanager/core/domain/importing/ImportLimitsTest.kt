package com.postsaimanager.core.domain.importing

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.testing.stagedFile
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/** The page cap (a PDF over 50 pages is refused whole), the limits' values, and the request the background job reads back. */
class ImportLimitsTest {

    @Test
    fun `the limits are the agreed ones`() {
        assertThat(ImportLimits.MAX_FILE_BYTES).isEqualTo(50L * 1024 * 1024)
        assertThat(ImportLimits.MAX_PDF_PAGES).isEqualTo(50)
        assertThat(ImportLimits.PAGE_LONGEST_SIDE_PX).isEqualTo(2480)
    }

    @Test
    fun `up to 50 pages is fine`() {
        assertThat(ImportLimits.pageCountProblem("a.pdf", 1)).isNull()
        assertThat(ImportLimits.pageCountProblem("a.pdf", 50)).isNull()
    }

    @Test
    fun `51 pages is refused whole with the count`() {
        assertThat(ImportLimits.pageCountProblem("a.pdf", 51)).isEqualTo(ImportProblem.TooManyPages("a.pdf", 51))
        assertThat(ImportLimits.pageCountProblem("a.pdf", 60)).isEqualTo(ImportProblem.TooManyPages("a.pdf", 60))
    }

    @Test
    fun `a PDF with no pages is damaged`() {
        assertThat(ImportLimits.pageCountProblem("a.pdf", 0)).isEqualTo(ImportProblem.Broken("a.pdf"))
    }

    @Test
    fun `a request survives the trip through its stored form`() {
        val request = ImportRequest(
            batchId = "b1",
            groups = listOf(
                ImportGroup(listOf(stagedFile("a", ImportedKind.PDF, pages = 3))),
                ImportGroup(listOf(stagedFile("i1"), stagedFile("i2"))),
            ),
            passwords = mapOf("a" to "secret"),
        )

        val json = Json.encodeToString(ImportRequest.serializer(), request)

        assertThat(Json.decodeFromString(ImportRequest.serializer(), json)).isEqualTo(request)
    }
}
