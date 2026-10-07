package com.postsaimanager.core.domain.ai

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class MessageImagesTest {

    @Test
    @DisplayName("one picture is stored as its path, several as a JSON array, none as null: the existing column is enough")
    fun `encode and decode round trip`() {
        assertThat(MessageImages.encode(emptyList())).isNull()
        assertThat(MessageImages.encode(listOf("/a/1.png"))).isEqualTo("/a/1.png")
        val many = listOf("/a/1.png", "/a/2.png", "/a/with \"quote\".png")
        assertThat(MessageImages.decode(MessageImages.encode(many))).isEqualTo(many)
        assertThat(MessageImages.decode("/a/1.png")).containsExactly("/a/1.png")
        assertThat(MessageImages.decode(null)).isEmpty()
        assertThat(MessageImages.decode("  ")).isEmpty()
    }

    @Test
    fun `a rebuilt conversation names the pictures with a text marker`() {
        assertThat(MessageImages.marker(1)).isEqualTo("[image: photo 1]")
        assertThat(MessageImages.marker(3)).isEqualTo("[image: photo 1] [image: photo 2] [image: photo 3]")
    }

    @Test
    fun `the limit is the Gallery's`() {
        assertThat(MessageImages.MAX_PER_MESSAGE).isEqualTo(10)
    }
}
