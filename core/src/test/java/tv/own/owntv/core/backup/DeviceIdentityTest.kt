package tv.own.owntv.core.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a backup counts as this device's own — the only case its hardware settings restore unasked. */
class DeviceIdentityTest {

    private val here = DeviceIdentity(DeviceIdentity.hash("abc123"), "TCL G10")

    @Test
    fun `the same device is recognised through the file`() {
        val file = DeviceIdentity.fromJson(JSONObject(here.toJson().toString()))
        assertTrue(DeviceIdentity.sameDevice(file, here))
    }

    @Test
    fun `another device, an old file, or an unreadable id all count as elsewhere`() {
        assertFalse(DeviceIdentity.sameDevice(DeviceIdentity(DeviceIdentity.hash("zzz"), "Pixel"), here))
        assertFalse(DeviceIdentity.sameDevice(DeviceIdentity.fromJson(null), here))
        assertFalse(DeviceIdentity.sameDevice(DeviceIdentity("", "x"), DeviceIdentity("", "y")))
    }

    @Test
    fun `the raw id never reaches the file`() {
        assertEquals(16, here.id.length)
        assertNotEquals("abc123", here.id)
        assertEquals("", DeviceIdentity.hash(""))
    }
}
