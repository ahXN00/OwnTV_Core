package tv.own.owntv.core.sync.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.pairedDeviceStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_paired_devices")
private val DEVICES_KEY = stringPreferencesKey("devices")
private val SELF_ID_KEY = stringPreferencesKey("selfId")

/**
 * Another OwnTV device this one has been paired with.
 *
 * [secret] is what replaces the six-digit PIN on every sync after the first. Whichever device hosted
 * the pairing minted it and remembers it as an accepted credential; the device that connected
 * remembers it as the credential to present. Both ends store the same string, which is why one row
 * shape serves both directions.
 *
 * [address] is the last address that worked, kept only as a hint: an IP handed out by a router is
 * not stable, so discovery is tried first and this is the fallback when the network will not answer.
 */
data class PairedDevice(
    val id: String,
    val name: String,
    val address: String,
    val port: Int,
    val secret: String,
    val pairedAt: Long,
    val lastSyncAt: Long = 0,
)

/**
 * Short codes for the paired devices whose names alone would not tell them apart.
 *
 * Two of the same phone in one house are two rows reading "OnePlus 13s", and the user cannot know
 * which is which — the name comes from the device and is not ours to invent. So the ones that clash,
 * and only those, get four characters of their own id appended. A household with one of each thing
 * never sees a code at all.
 *
 * Returns id → code, holding only the devices that need one.
 */
fun shortCodes(devices: List<PairedDevice>): Map<String, String> {
    val clashing = devices.groupBy { it.name.trim().lowercase() }.filterValues { it.size > 1 }
    return clashing.values.flatten().associate { it.id to it.id.filter(Char::isLetterOrDigit).take(CODE_LENGTH) }
}

/** Four characters is enough to separate the handful of devices one household pairs. */
private const val CODE_LENGTH = 4

/**
 * The devices the user has paired with, on disk.
 *
 * DataStore rather than Room deliberately: no query joins these against anything, there are at most
 * a handful, and a pairing is a credential rather than user content — it has no business in a backup
 * file that gets carried to a third device.
 */
class PairedDeviceStore(context: Context) {

    private val store = context.applicationContext.pairedDeviceStore

    val devices: Flow<List<PairedDevice>> = store.data.map { prefs -> parse(prefs[DEVICES_KEY]) }

    suspend fun current(): List<PairedDevice> = parse(store.data.first()[DEVICES_KEY])

    /**
     * This installation's own identity, minted once and then never again.
     *
     * It is what makes [put] able to do what it says: a pairing used to be filed under a fresh random
     * id every time, so re-pairing the same television could never match the row already there and
     * simply added another one — three pairings, three identical entries in the list. Each device now
     * tells the other who it is, and the id it gives is the id its row is filed under.
     *
     * Written inside `edit` and re-read there, because two flows can ask for it at once and only one
     * of them may win.
     */
    suspend fun selfId(): String {
        store.data.first()[SELF_ID_KEY]?.takeIf { it.isNotBlank() }?.let { return it }
        var minted = UUID.randomUUID().toString()
        store.edit { prefs ->
            val existing = prefs[SELF_ID_KEY]?.takeIf { it.isNotBlank() }
            if (existing == null) prefs[SELF_ID_KEY] = minted else minted = existing
        }
        return minted
    }

    /** The secrets any paired device may present instead of the PIN. */
    suspend fun secrets(): Set<String> = current().map { it.secret }.toSet()

    /**
     * Adds or updates a pairing, matched on [PairedDevice.id] so re-pairing the same television
     * refreshes its address and secret instead of leaving a second, dead entry behind.
     */
    suspend fun put(device: PairedDevice) {
        store.edit { prefs ->
            val kept = parse(prefs[DEVICES_KEY]).filterNot { it.id == device.id }
            prefs[DEVICES_KEY] = write(kept + device)
        }
    }

    /** Records where a device answered and when, after a sync with it succeeded. */
    suspend fun touch(id: String, address: String, port: Int, at: Long = System.currentTimeMillis()) {
        store.edit { prefs ->
            val all = parse(prefs[DEVICES_KEY])
            prefs[DEVICES_KEY] = write(
                all.map { if (it.id == id) it.copy(address = address, port = port, lastSyncAt = at) else it },
            )
        }
    }

    /**
     * Forgets a device. The other end keeps its own row until it is unpaired there too — nothing here
     * can reach across and delete it — but the secret it holds stops being accepted immediately,
     * which is what "unpaired" has to mean.
     */
    suspend fun remove(id: String) {
        store.edit { prefs ->
            prefs[DEVICES_KEY] = write(parse(prefs[DEVICES_KEY]).filterNot { it.id == id })
        }
    }

    private fun parse(raw: String?): List<PairedDevice> {
        val array = raw?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PairedDevice(
                id = id,
                name = o.optString("name"),
                address = o.optString("address"),
                port = o.optInt("port"),
                secret = o.optString("secret"),
                pairedAt = o.optLong("pairedAt"),
                lastSyncAt = o.optLong("lastSyncAt"),
            )
        }
    }

    private fun write(devices: List<PairedDevice>): String = JSONArray().apply {
        devices.forEach {
            put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("address", it.address)
                    .put("port", it.port)
                    .put("secret", it.secret)
                    .put("pairedAt", it.pairedAt)
                    .put("lastSyncAt", it.lastSyncAt),
            )
        }
    }.toString()

    companion object {
        /** 256 bits from [SecureRandom], hex — the PIN's million possibilities are a one-time gate. */
        fun newSecret(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
