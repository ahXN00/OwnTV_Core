package tv.own.owntv.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.sports.model.Sport

class SportsCatalogTest {

    @Test
    fun `every league key is unique and resolvable`() {
        val keys = SportsCatalog.leagues.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        keys.forEach { assertEquals(it, SportsCatalog.league(it)!!.key) }
    }

    @Test
    fun `volleyball and lacrosse have leagues`() {
        assertTrue(SportsCatalog.sports.containsAll(listOf(Sport.VOLLEYBALL, Sport.LACROSSE)))
    }

    @Test
    fun `cups cost no table fetch, leagues still do`() {
        assertFalse(SportsWindow.hasTable(SportsCatalog.league("soccer/eng.fa")!!))
        assertFalse(SportsWindow.hasTable(SportsCatalog.league("rugby/289234")!!))
        assertTrue(SportsWindow.hasTable(SportsCatalog.league("soccer/nor.1")!!))
        assertFalse(SportsWindow.hasTable(SportsCatalog.league("racing/nascar-truck")!!))
    }

    @Test
    fun `sports come in the hub's chip order`() {
        assertEquals(
            listOf(Sport.SOCCER, Sport.BASKETBALL, Sport.TENNIS, Sport.CRICKET, Sport.RACING, Sport.HOCKEY, Sport.FOOTBALL, Sport.MMA),
            SportsCatalog.sports.take(8),
        )
    }
}
