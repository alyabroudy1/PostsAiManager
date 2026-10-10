package com.postsaimanager.core.data.repository

import android.content.Context
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.PamDatabase
import com.postsaimanager.core.data.database.entity.DocumentEntity
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Deleting a profile whose source letter is already gone for good (real schema, foreign keys on). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileDeleteAfterDocumentGoneRoomTest {

    private lateinit var db: PamDatabase
    private lateinit var profiles: ProfileRepositoryImpl

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication() as Context, PamDatabase::class.java)
            .allowMainThreadQueries().build()
        profiles = ProfileRepositoryImpl(db.profileDao(), db.dismissedEntityDao(), db.documentDao(), Dispatchers.Unconfined)
    }

    @After
    fun close() = db.close()

    private fun document(id: String) = DocumentEntity(
        id = id, title = "t", status = "EXTRACTED", documentType = null, language = null, sourceType = "CAMERA",
        thumbnailPath = null, pageCount = 1, createdAt = 1, modifiedAt = 1,
    )

    private fun fromLetter(documentId: String) = Profile(
        id = "p1", kind = ProfileKind.PERSON, name = "Testperson Beispiel", createdAt = 1, modifiedAt = 1,
        sourceDocumentId = documentId, sourceEntityName = "testperson beispiel",
    )

    @Test
    fun `an AI-created profile can be deleted after its letter was permanently deleted`(): Unit = runBlocking {
        db.documentDao().insert(document("d1"))
        profiles.createProfile(fromLetter("d1"))
        db.documentDao().deleteById("d1")

        val result = profiles.deleteProfile("p1")

        assertThat(result).isEqualTo(PamResult.Success(Unit))
        assertThat(db.profileDao().getById("p1")).isNull()
    }

    @Test
    fun `deleting an AI-created profile whose letter still exists still tombstones the entity`(): Unit = runBlocking {
        db.documentDao().insert(document("d1"))
        profiles.createProfile(fromLetter("d1"))

        val result = profiles.deleteProfile("p1")

        assertThat(result).isEqualTo(PamResult.Success(Unit))
        assertThat(db.dismissedEntityDao().isDismissed("d1", "testperson beispiel")).isTrue()
    }
}
