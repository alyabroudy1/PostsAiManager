package com.postsaimanager.feature.documents

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.actions.ActionKinds
import org.junit.Test

class ActionTemplatesTest {

    private fun pick(kind: String, amount: String? = null, party: String? = null, date: String? = null) = ActionTemplates.pick(kind, amount, party, date)

    @Test
    fun `payment uses the most specific template its values allow, arguments in the order amount party date`() {
        val full = pick("pay", "64,98 €", "Nordlicht", "15 Oct")!!
        assertThat(full.template).isEqualTo(R.string.action_pay_apd)
        assertThat(full.args).containsExactly("64,98 €", "Nordlicht", "15 Oct").inOrder()

        assertThat(pick("pay", "64,98 €", null, "15 Oct")!!.template).isEqualTo(R.string.action_pay_ad)
        assertThat(pick("pay", "64,98 €", "Nordlicht", null)!!.template).isEqualTo(R.string.action_pay_ap)
        assertThat(pick("pay", null, "Nordlicht", "15 Oct")!!.template).isEqualTo(R.string.action_pay_pd)
        assertThat(pick("pay", "64,98 €")!!.template).isEqualTo(R.string.action_pay_a)
        assertThat(pick("pay", date = "15 Oct")!!.template).isEqualTo(R.string.action_pay_d)
        assertThat(pick("pay", party = "Nordlicht")!!.template).isEqualTo(R.string.action_pay_p)
        assertThat(pick("pay")!!.template).isEqualTo(R.string.action_pay)
        assertThat(pick("pay")!!.args).isEmpty()
    }

    @Test
    fun `a value the kind has no template for is left out, the party first`() {
        // Object or cancel states only a date: a party and an amount are dropped, never put in the wrong place.
        val p = pick("object_cancel", "1 €", "Finanzamt", "30 Nov")!!
        assertThat(p.template).isEqualTo(R.string.action_object_cancel_d)
        assertThat(p.args).containsExactly("30 Nov")
        // A reply has no amount template: the party and the date remain.
        val r = pick("reply", "1 €", "Finanzamt", "30 Nov")!!
        assertThat(r.template).isEqualTo(R.string.action_reply_pd)
        assertThat(r.args).containsExactly("Finanzamt", "30 Nov").inOrder()
    }

    @Test
    fun `every kind of the catalogue has at least its plain template, and a kind this build does not know has none`() {
        ActionKinds.ALL.forEach { assertThat(pick(it.id)).isNotNull() }
        assertThat(pick("teleport")).isNull()
    }
}
