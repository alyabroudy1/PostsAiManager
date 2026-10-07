package com.postsaimanager.core.testing

import com.postsaimanager.core.domain.ai.ChatImageStore

/** A [ChatImageStore] that keeps its pictures as names in memory. A source that [unreadable] lists is "not a picture". */
class FakeChatImageStore(private val unreadable: Set<String> = emptySet()) : ChatImageStore {

    /** The stored pictures of each conversation, in the order they were imported. */
    val stored = mutableMapOf<String, MutableList<String>>()

    override suspend fun import(conversationId: String, source: String): String? {
        if (source in unreadable) return null
        val list = stored.getOrPut(conversationId) { mutableListOf() }
        return "/chat-attachments/$conversationId/${list.size + 1}.png".also { list += it }
    }

    override suspend fun deleteAll(conversationId: String) {
        stored.remove(conversationId)
    }
}
