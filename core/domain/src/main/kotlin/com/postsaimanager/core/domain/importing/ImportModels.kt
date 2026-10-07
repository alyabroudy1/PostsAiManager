package com.postsaimanager.core.domain.importing

import com.postsaimanager.core.model.SourceType
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** The limits of importing, in one place: the port's implementation enforces them and the UI words them. */
object ImportLimits {
    const val MAX_FILE_BYTES: Long = 50L * 1024 * 1024
    const val MAX_FILE_MEGABYTES: Int = 50
    const val MAX_PDF_PAGES: Int = 50

    /** The longest side of a rendered page, in pixels: A4 at 300 dpi, what a scan looks like to the pipeline. */
    const val PAGE_LONGEST_SIDE_PX: Int = 2480

    /** The longest side of a confirm-sheet thumbnail, in pixels. */
    const val THUMBNAIL_LONGEST_SIDE_PX: Int = 360

    /** Why a PDF with [pages] pages cannot be imported (none at all means damaged, too many is refused whole), or null when it can. */
    fun pageCountProblem(fileName: String, pages: Int): ImportProblem? = when {
        pages <= 0 -> ImportProblem.Broken(fileName)
        pages > MAX_PDF_PAGES -> ImportProblem.TooManyPages(fileName, pages)
        else -> null
    }
}

/** What a file really is, decided from its first bytes (never from its name or the type the sender claimed). */
enum class ImportedKind { PDF, IMAGE }

/**
 * A file that has been copied into the app (a `content://` grant can expire) and checked.
 *
 * @property id unique within its batch; names the copy on disk.
 * @property path where the private copy is.
 * @property pageCount the PDF's page count (always within [ImportLimits.MAX_PDF_PAGES]); 1 for an image; 0 while a password-protected
 *   PDF is still locked.
 * @property sha256 SHA-256 of the file's bytes, lower-case hex.
 * @property passwordRequired a PDF that cannot be read without its password ([pageCount] is 0 until it is unlocked).
 */
@Serializable
data class StagedFile(
    val id: String,
    val path: String,
    val kind: ImportedKind,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String,
    val pageCount: Int,
    val passwordRequired: Boolean = false,
)

/** Why a file cannot be imported. The UI words each one in the user's language; nothing here is user-facing text. */
sealed interface ImportProblem {
    val fileName: String

    /** Neither a PDF nor an image the platform decodes. */
    data class NotSupported(override val fileName: String) : ImportProblem

    data class TooLarge(override val fileName: String) : ImportProblem

    data class TooManyPages(override val fileName: String, val pages: Int) : ImportProblem

    /** Password-protected, and this Android version cannot ask for the password. */
    data class PasswordUnsupported(override val fileName: String) : ImportProblem

    /** Password-protected; the caller may ask the user and try again. */
    data class PasswordRequired(override val fileName: String) : ImportProblem

    data class WrongPassword(override val fileName: String) : ImportProblem

    /** Damaged or unreadable content. */
    data class Broken(override val fileName: String) : ImportProblem

    /** The file could not be opened or copied (grant expired, storage full). */
    data class Unreadable(override val fileName: String) : ImportProblem
}

sealed interface StageResult {
    data class Staged(val file: StagedFile) : StageResult
    data class Rejected(val problem: ImportProblem) : StageResult
}

sealed interface RenderResult {
    /** [pagePaths] are `file://` URIs of the page JPEGs in order. */
    data class Rendered(val pagePaths: List<String>) : RenderResult
    data class Failed(val problem: ImportProblem) : RenderResult
}

/** One document to create: the files whose pages make it, in order. */
@Serializable
data class ImportGroup(val files: List<StagedFile>) {
    init {
        require(files.isNotEmpty()) { "a group needs a file" }
    }

    val isPdf: Boolean get() = files.singleOrNull()?.kind == ImportedKind.PDF

    /** One PDF is imported as [SourceType.PDF_IMPORT]; images as [SourceType.UPLOAD]. */
    val sourceType: SourceType get() = if (isPdf) SourceType.PDF_IMPORT else SourceType.UPLOAD

    /** The file's own hash for a single file; for several images the hash of their hashes, in order. */
    val sourceHash: String
        get() = files.singleOrNull()?.sha256 ?: sha256Hex(files.joinToString(separator = "\n") { it.sha256 }.toByteArray())

    /** The page count the document will have (unlocked PDFs and images; an unknown count is 0). */
    val pageCount: Int get() = files.sumOf { it.pageCount }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * Everything the background import needs, so only [batchId] has to be handed to WorkManager (its input data is small and stored).
 * [passwords] maps a staged file's id to the password the user typed for that PDF; it lives only in the batch's private folder
 * and is deleted with it.
 */
@Serializable
data class ImportRequest(
    val batchId: String,
    val groups: List<ImportGroup>,
    val passwords: Map<String, String> = emptyMap(),
)

/** What an import run produced: the new documents' ids and the groups that could not be imported. */
data class ImportOutcome(
    val documentIds: List<String>,
    val problems: List<ImportProblem>,
) {
    val isComplete: Boolean get() = problems.isEmpty()
}

/** What a finished import job made, as the background job reports it: the new documents and how many groups failed. */
data class ImportResult(val documentIds: List<String>, val failedGroups: Int)

/** How many import jobs are running and how many ended with a problem that the user has not dismissed. */
data class ImportStatus(val running: Int = 0, val failed: Int = 0) {
    val isIdle: Boolean get() = running == 0 && failed == 0
}
