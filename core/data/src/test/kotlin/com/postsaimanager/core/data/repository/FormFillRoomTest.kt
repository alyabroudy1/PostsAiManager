package com.postsaimanager.core.data.repository

import android.content.Context
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.data.database.PamDatabase
import com.postsaimanager.core.data.database.entity.DocumentEntity
import com.postsaimanager.core.model.AiConversation
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.AiModelType
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.FormFill
import com.postsaimanager.core.model.FormFillStatus
import com.postsaimanager.core.model.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The form-fill data path on the real schema (in-memory Room, foreign keys on). The in-memory fakes cannot see what the database does
 * to a parent row: a REPLACE conflict is a delete plus an insert, which cascades to the children.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FormFillRoomTest {

    private lateinit var db: PamDatabase
    private lateinit var fills: FormFillRepositoryImpl
    private lateinit var conversations: ConversationRepositoryImpl

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication() as Context, PamDatabase::class.java)
            .allowMainThreadQueries().build()
        fills = FormFillRepositoryImpl(db.formFillDao(), Dispatchers.Unconfined)
        conversations = ConversationRepositoryImpl(db.conversationDao(), db.messageDao(), Dispatchers.Unconfined)
        runBlocking {
            db.documentDao().insert(
                DocumentEntity(
                    id = "d", title = "Form", status = "COMPLETED", documentType = null, language = null, sourceType = "SCAN",
                    thumbnailPath = null, pageCount = 2, createdAt = 1, modifiedAt = 1,
                ),
            )
        }
    }

    @After
    fun close() = db.close()

    private fun fill(status: FormFillStatus) = FormFill(id = "fill-d", documentId = "d", status = status, createdAt = 1, updatedAt = 1)

    private fun field(id: String) = FormField(
        id = id, formFillId = "fill-d", documentId = "d", page = 1, labelText = id, labelBox = null, fillBox = null,
        kind = FormFieldKind.TEXT, orderIndex = 0,
    )

    @Test
    fun `saving the fill again keeps the fields that were saved for it`() = runBlocking {
        fills.saveFill(fill(FormFillStatus.UNDERSTANDING))
        fills.saveFields("fill-d", listOf(field("a"), field("b"), field("c")))

        fills.saveFill(fill(FormFillStatus.ASK_SUBJECT))
        fills.saveFill(fill(FormFillStatus.ASKING))

        assertThat(fills.fields("fill-d").map { it.id }).containsExactly("a", "b", "c")
        assertThat(fills.getFill("fill-d")?.status).isEqualTo(FormFillStatus.ASKING)
    }

    @Test
    fun `a second round of messages and progress updates keeps the first round and the conversation`() = runBlocking {
        val id = "conv-d"
        conversations.createConversation(
            AiConversation(id = id, documentId = "d", aiModelId = null, modelType = AiModelType.LOCAL, title = "Form", lastMessageAt = 1, createdAt = 1),
        )
        fun message(n: Int, text: String) = AiMessage(id = "m$n", conversationId = id, role = MessageRole.ASSISTANT, content = text, createdAt = n.toLong())
        conversations.addMessage(message(1, "round 1 progress"))
        conversations.updateMessage(message(1, "round 1 done"))
        conversations.addMessage(message(2, "round 1 question"))
        fills.saveFill(fill(FormFillStatus.DONE))

        conversations.addMessage(message(3, "round 2 progress"))
        fills.saveFill(fill(FormFillStatus.UNDERSTANDING))
        conversations.updateMessage(message(3, "round 2 done"))
        conversations.addMessage(message(4, "round 2 question"))

        assertThat(conversations.getMessages(id).first().map { it.content })
            .containsExactly("round 1 done", "round 1 question", "round 2 done", "round 2 question").inOrder()
    }
}
