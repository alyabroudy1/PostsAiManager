package com.postsaimanager.feature.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonRowPlanTest {
    @Test
    fun equalHalvesWhenEachFitsInHalf() {
        val plan = planButtonRow(listOf(200, 300), available = 1000, gap = 20)
        assertTrue(plan.sideBySide)
        assertEquals(listOf(490, 490), plan.widths)
    }

    @Test
    fun naturalPlusEqualShareWhenOneIsLongerThanHalf() {
        val plan = planButtonRow(listOf(300, 600), available = 1000, gap = 20)
        assertTrue(plan.sideBySide)
        assertEquals(listOf(340, 640), plan.widths)
        assertEquals(980, plan.widths.sum())
    }

    @Test
    fun exactFitIsSideBySide() {
        val plan = planButtonRow(listOf(400, 580), available = 1000, gap = 20)
        assertTrue(plan.sideBySide)
        assertEquals(listOf(400, 580), plan.widths)
    }

    @Test
    fun stackedFullWidthWhenSumDoesNotFit() {
        val plan = planButtonRow(listOf(500, 600), available = 1000, gap = 20)
        assertFalse(plan.sideBySide)
        assertEquals(listOf(1000, 1000), plan.widths)
    }
}
