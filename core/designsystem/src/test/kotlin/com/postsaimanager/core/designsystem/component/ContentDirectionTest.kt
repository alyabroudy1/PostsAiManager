package com.postsaimanager.core.designsystem.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The style choice that makes an Arabic answer right-to-left in a German UI, and keeps the rest of a style as it was. */
class ContentDirectionTest {

    @Test
    fun `direction follows the paragraph's own script, not the app's locale, and alignment is the start of that direction`() {
        val style = TextStyle().byContentDirection()

        assertThat(style.textDirection).isEqualTo(TextDirection.Content)
        assertThat(style.textAlign).isEqualTo(TextAlign.Start)
    }

    @Test
    fun `colour and size are kept`() {
        val style = TextStyle(color = Color.Red).byContentDirection()

        assertThat(style.color).isEqualTo(Color.Red)
    }
}
