package tv.own.owntv.core.sync.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rule that stops one television appearing three times in the paired list.
 *
 * The store always matched on [PairedDevice.id]; what was missing was an id that meant anything.
 * Every pairing minted a fresh random one, so the match could never hit and each pairing left another
 * identical row behind — found on the owner's own devices, three "OnePlus 13s" after three pairings.
 */
@RunWith(AndroidJUnit4::class)
class PairedDeviceStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = PairedDeviceStore(context)

    @Before
    fun setUp(): Unit = runBlocking { clear() }

    @After
    fun tearDown(): Unit = runBlocking { clear() }

    private suspend fun clear() = store.current().forEach { store.remove(it.id) }

    /** This device's own identity is minted once and then answers the same for ever. */
    @Test
    fun selfIdIsStableAcrossCalls() = runBlocking {
        val first = store.selfId()
        assertTrue("a device with no identity is the bug itself", first.isNotBlank())
        assertEquals(first, store.selfId())
    }

    /** Pairing the same device again updates its row. This is the defect, in one assertion. */
    @Test
    fun pairingTheSameDeviceTwiceKeepsOneRow() = runBlocking {
        val theirId = "device-under-the-television"
        store.put(device(theirId, name = "Living Room TV", secret = "first", address = "192.168.2.103"))
        store.put(device(theirId, name = "Living Room TV", secret = "second", address = "192.168.2.180"))

        val all = store.current()
        assertEquals("re-pairing left a twin behind", 1, all.size)
        assertEquals("the newer secret did not win", "second", all.single().secret)
        assertEquals("the address it answered on was not refreshed", "192.168.2.180", all.single().address)
    }

    /** Two genuinely different devices still get a row each. */
    @Test
    fun twoDifferentDevicesAreTwoRows() = runBlocking {
        store.put(device("tv-1", name = "Living Room TV", secret = "a", address = "192.168.2.103"))
        store.put(device("tv-2", name = "Bedroom TV", secret = "b", address = "192.168.2.104"))

        assertEquals(2, store.current().size)
        assertEquals(setOf("a", "b"), store.secrets())
    }

    /** Two of the same phone get a code each; anything with its own name is left alone. */
    @Test
    fun onlyClashingNamesGetACode() {
        val phoneA = device("aaaa1111-one", name = "OnePlus 13s", secret = "a", address = "192.168.2.10")
        val phoneB = device("bbbb2222-two", name = "OnePlus 13s", secret = "b", address = "192.168.2.11")
        val tv = device("cccc3333-three", name = "Living Room TV", secret = "c", address = "192.168.2.103")

        val codes = shortCodes(listOf(phoneA, phoneB, tv))

        assertEquals("the device with its own name should not be labelled", setOf(phoneA.id, phoneB.id), codes.keys)
        assertNotEquals("two codes that match tell nothing apart", codes[phoneA.id], codes[phoneB.id])
        assertEquals("aaaa", codes[phoneA.id])
    }

    /** Case and stray spaces are still the same name to a reader, so they still clash. */
    @Test
    fun namesThatDifferOnlyByCaseStillClash() {
        val a = device("aaaa1111", name = "OnePlus 13s", secret = "a", address = "192.168.2.10")
        val b = device("bbbb2222", name = " oneplus 13S ", secret = "b", address = "192.168.2.11")

        assertEquals(2, shortCodes(listOf(a, b)).size)
    }

    /** A secret is a credential, so two of them are never the same string. */
    @Test
    fun secretsAreNotReused() {
        assertNotEquals(PairedDeviceStore.newSecret(), PairedDeviceStore.newSecret())
    }

    private fun device(id: String, name: String, secret: String, address: String) = PairedDevice(
        id = id,
        name = name,
        address = address,
        port = 8089,
        secret = secret,
        pairedAt = 1_000,
    )
}
