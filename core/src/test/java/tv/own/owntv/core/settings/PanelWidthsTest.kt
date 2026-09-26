package tv.own.owntv.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelWidthsTest {

    @Test
    fun `two panels let the category go down to the minimum`() {
        assertTrue(PanelShares(10, 90, 0).isValid)
        assertTrue(PanelShares(15, 85, 0).isValid)
        assertEquals(PanelShares(10, 90, 0), balanceToTotal(PanelShares(10, 90, 0)))
    }

    @Test
    fun `three panels keep the list at the usual ceiling`() {
        assertFalse(PanelShares(10, 85, 5).isValid)
        assertEquals(PanelWidthLimits.MAX, PanelWidthLimits.listMax(preview = 10))
        assertEquals(PanelShares(10, 80, 10), balanceToTotal(PanelShares(10, 90, 10)))
    }

    @Test
    fun `the category itself never passes the usual ceiling`() {
        assertFalse(PanelShares(90, 10, 0).isValid)
        assertEquals(PanelShares(80, 20, 0), balanceToTotal(PanelShares(90, 10, 0)))
    }
}
