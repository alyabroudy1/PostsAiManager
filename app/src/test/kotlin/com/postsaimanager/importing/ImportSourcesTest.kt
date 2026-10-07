package com.postsaimanager.importing

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Which files an incoming intent asks for, and which of them are read at all. */
class ImportSourcesTest {

    private val a = "content://provider/a"
    private val b = "content://provider/b"
    private val c = "content://provider/c"

    @Test
    fun `a single share is its one stream`() {
        assertThat(ImportSources.from(ImportSources.ACTION_SEND, stream = a, streams = emptyList(), clip = emptyList(), data = null))
            .containsExactly(a)
    }

    @Test
    fun `a multiple share keeps the sender's order`() {
        assertThat(ImportSources.from(ImportSources.ACTION_SEND_MULTIPLE, null, listOf(c, a, b), emptyList(), null))
            .containsExactly(c, a, b).inOrder()
    }

    @Test
    fun `open with is the intent's data`() {
        assertThat(ImportSources.from(ImportSources.ACTION_VIEW, null, emptyList(), emptyList(), a)).containsExactly(a)
    }

    @Test
    fun `a share without a stream falls back to its clip`() {
        assertThat(ImportSources.from(ImportSources.ACTION_SEND, null, emptyList(), listOf(a), null)).containsExactly(a)
        assertThat(ImportSources.from(ImportSources.ACTION_SEND_MULTIPLE, null, emptyList(), listOf(a, b), null)).containsExactly(a, b).inOrder()
    }

    @Test
    fun `the same file named twice is read once`() {
        assertThat(ImportSources.from(ImportSources.ACTION_SEND_MULTIPLE, null, listOf(a, a, b), emptyList(), null))
            .containsExactly(a, b).inOrder()
    }

    @Test
    fun `an unknown action with nothing attached asks for nothing`() {
        assertThat(ImportSources.from("android.intent.action.MAIN", null, emptyList(), emptyList(), null)).isEmpty()
        assertThat(ImportSources.from(null, null, emptyList(), emptyList(), null)).isEmpty()
    }

    @Test
    fun `only content URIs are read, a file URI from another app could name this app's own files`() {
        val (readable, refused) = ImportSources.split(listOf(a, "file:///data/data/com.postsaimanager/databases/pam.db", b, "http://example.org/x.pdf"))

        assertThat(readable).containsExactly(a, b).inOrder()
        assertThat(refused).containsExactly("file:///data/data/com.postsaimanager/databases/pam.db", "http://example.org/x.pdf").inOrder()
    }
}
