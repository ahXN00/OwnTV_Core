package tv.own.owntv.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A hashed PIN opens only with the right digits; no stored PIN means no lock. Only the Customize PIN
 * still accepts a legacy plain value; profile and backup PINs reject anything that is not a hash.
 */
class PinTest {

    @Test
    fun `a hashed PIN opens with the right digits only`() {
        val stored = Pin.hash("1234")
        assertTrue(Pin.isHashed(stored))
        for (check in listOf(Pin::verify, Pin::verifyCustomize)) {
            assertTrue(check("1234", stored))
            assertFalse(check("1235", stored))
            assertFalse(check("", stored))
        }
    }

    @Test
    fun `a plain Customize PIN saved before hashing still opens the Customize check`() {
        assertFalse(Pin.isHashed("4321"))
        assertTrue(Pin.verifyCustomize("4321", "4321"))
        assertFalse(Pin.verifyCustomize("4320", "4321"))
        assertFalse(Pin.verifyCustomize("43210", "4321"))
    }

    @Test
    fun `a profile or backup PIN that is not a hash never matches`() {
        assertFalse(Pin.verify("4321", "4321"))
        assertFalse(Pin.verify("1234", "zz:abcd"))
        assertFalse(Pin.verify("a:b", "a:b"))
    }

    @Test
    fun `a wrong PIN never matches the stored hash text itself`() {
        val stored = Pin.hash("1234")
        assertFalse(Pin.verify(stored, stored))
        assertFalse(Pin.verifyCustomize(stored, stored))
    }

    @Test
    fun `only the exact hash form counts as hashed`() {
        val stored = Pin.hash("1234")
        assertFalse(Pin.isHashed("12:34"))
        assertFalse(Pin.isHashed("zz:abcd"))
        assertFalse(Pin.isHashed("$stored:00"))
        assertFalse(Pin.isHashed(stored.drop(1)))
        // A colon value that only looks split is a legacy plain value, compared as is.
        assertTrue(Pin.verifyCustomize("12:34", "12:34"))
    }

    @Test
    fun `no stored PIN means nothing to check`() {
        for (check in listOf(Pin::verify, Pin::verifyCustomize)) {
            assertTrue(check("0000", null))
            assertTrue(check("0000", ""))
        }
    }
}
