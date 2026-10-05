package com.postsaimanager.core.designsystem.component

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The report draft: a plain `mailto:` SENDTO, the fixed subject, and the answer text only when asked for. */
@RunWith(RobolectricTestRunner::class)
class ReportAnswerTest {

    private val intro = "What was wrong?"
    private val heading = "The answer:"
    private val answer = "Your IBAN is DE00 1234"

    @Test
    fun `the draft is a mailto SENDTO to the contact address with the fixed subject`() {
        val intent = ReportAnswer.intent("body")

        assertThat(intent.action).isEqualTo(Intent.ACTION_SENDTO)
        assertThat(intent.data.toString()).isEqualTo("mailto:alyabroudy1@gmail.com")
        assertThat(intent.getStringArrayExtra(Intent.EXTRA_EMAIL)).asList().containsExactly("alyabroudy1@gmail.com")
        assertThat(intent.getStringExtra(Intent.EXTRA_SUBJECT)).isEqualTo("PostsAiManager: AI answer report")
        assertThat(intent.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("body")
    }

    @Test
    fun `by default the answer text is not in the body`() {
        val body = ReportAnswer.body(intro, heading, answer, includeAnswer = false)

        assertThat(body).isEqualTo(intro)
        assertThat(body).doesNotContain(answer)
        assertThat(body).doesNotContain(heading)
    }

    @Test
    fun `ticking the option adds the answer under its heading`() {
        val body = ReportAnswer.body(intro, heading, answer, includeAnswer = true)

        assertThat(body).startsWith(intro)
        assertThat(body).contains(heading)
        assertThat(body).contains(answer)
    }

    @Test
    fun `a blank answer adds nothing even when ticked`() {
        assertThat(ReportAnswer.body(intro, heading, " ", includeAnswer = true)).isEqualTo(intro)
        assertThat(ReportAnswer.body(intro, heading, null, includeAnswer = true)).isEqualTo(intro)
    }
}
