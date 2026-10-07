package com.postsaimanager.core.data.repository

import android.content.Context
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.data.database.PamDatabase
import com.postsaimanager.core.data.database.entity.DocumentEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The duplicate lookups on the real schema: what a stored file hash finds, live or in the trash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentSourceHashRoomTest {

    private lateinit var db: PamDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication() as Context, PamDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    private fun doc(id: String, hash: String?, createdAt: Long = 1, deletedAt: Long? = null) = DocumentEntity(
        id = id, title = id, status = "NEW", documentType = null, language = null, sourceType = "PDF_IMPORT",
        thumbnailPath = null, pageCount = 1, createdAt = createdAt, modifiedAt = createdAt, deletedAt = deletedAt, sourceHash = hash,
    )

    @Test
    fun `a live document is found by its hash`() = runBlocking {
        db.documentDao().insert(doc("a", "h"))
        assertThat(db.documentDao().findBySourceHash("h")?.id).isEqualTo("a")
    }

    @Test
    fun `a trashed document is found when no live one has the hash, and says it is trashed`() = runBlocking {
        db.documentDao().insert(doc("a", "h", deletedAt = 5))
        db.documentDao().insert(doc("c", "other", createdAt = 0))
        val found = db.documentDao().findBySourceHash("h")
        assertThat(found?.id).isEqualTo("a")
        assertThat(found?.deletedAt).isEqualTo(5L)
    }

    @Test
    fun `a live document wins over an older trashed one`() = runBlocking {
        db.documentDao().insert(doc("old-trashed", "h", createdAt = 1, deletedAt = 5))
        db.documentDao().insert(doc("live", "h", createdAt = 2))
        assertThat(db.documentDao().findBySourceHash("h")?.id).isEqualTo("live")
    }

    @Test
    fun `a file nobody imported is not found`() = runBlocking {
        db.documentDao().insert(doc("a", "h"))
        assertThat(db.documentDao().findBySourceHash("nope")).isNull()
    }
}
