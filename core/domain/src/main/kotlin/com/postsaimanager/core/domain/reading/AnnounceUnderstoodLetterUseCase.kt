package com.postsaimanager.core.domain.reading

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.first
import java.time.Clock
import javax.inject.Inject

/**
 * Tells the person that a letter has been understood, when they are not already looking at it: the one owner of "may this
 * notification be posted, and what may it say".
 *
 * Posts nothing when the "Reading finished" switch is off, when the app is in the foreground on that letter's own screen, or when the
 * letter is gone. What it says is [ReadingFinishedContent]: nothing of a private letter, nothing at all with the app lock on.
 * Letters finishing close together are grouped ([ReadingFinishedBatch]).
 *
 * The caller (the pipeline) calls this only for a reading a person started (a scan, an import, "Reprocess"); a quiet re-read after an
 * extractor version bump never reaches it.
 */
class AnnounceUnderstoodLetterUseCase @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val documents: DocumentRepository,
    private val profiles: ProfileRepository,
    private val viewing: ViewingState,
    private val batch: ReadingFinishedBatch,
    private val notifier: ReadingFinishedNotifier,
    private val clock: Clock,
) {

    /** Returns whether a notification was posted. */
    suspend operator fun invoke(documentId: String): Boolean {
        val prefs = preferences.getUserPreferences().first()
        if (!prefs.readingFinishedNotifications) return false
        if (viewing.isViewing(documentId)) return false
        val document = (documents.getDocumentById(documentId) as? PamResult.Success)?.data?.takeIf { !it.isTrashed } ?: return false

        val fields = documents.observeExtractedData(documentId).first().filter { !it.deletedByUser }
        val people = profiles.getProfiles().first()
        val letter = UnderstoodLetter.of(document, fields, people)

        val letters = batch.add(letter, clock.millis(), notifier.isShowing())
        return notifier.show(ReadingFinishedContent.of(letters, hideContent = prefs.biometricEnabled))
    }
}
