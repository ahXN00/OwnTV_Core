package tv.own.owntv.core.backup

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Which device wrote a backup, so a restore can tell "this television, reinstalled" from "another
 * device" — and only in the second case hold back the settings that describe the hardware (see
 * `SettingsRepository.importSettings`).
 *
 * [id] is a hash, never the raw `ANDROID_ID`: a backup is a file that gets copied around, and the
 * hash answers "same device?" without identifying the device to anyone who reads it. [name] is the
 * model, shown nowhere yet but kept so a file can say where it came from.
 */
data class DeviceIdentity(val id: String, val name: String) {

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)

    companion object {
        fun of(context: Context): DeviceIdentity {
            val raw = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull().orEmpty()
            return DeviceIdentity(id = hash(raw), name = "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        }

        fun fromJson(o: JSONObject?): DeviceIdentity? {
            val id = o?.optString("id").orEmpty().takeIf { it.isNotBlank() } ?: return null
            return DeviceIdentity(id, o?.optString("name").orEmpty())
        }

        /** SHA-256, first 16 hex characters; empty input (no id available) hashes to an empty id. */
        internal fun hash(raw: String): String {
            if (raw.isBlank()) return ""
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }

        /**
         * Whether a backup written by [file] may be treated as this device's own. A file with no
         * identity (written before this existed), or a device that cannot read its own id, counts as
         * another device — the cautious answer, which only means the user is asked.
         */
        fun sameDevice(file: DeviceIdentity?, here: DeviceIdentity): Boolean =
            file != null && here.id.isNotEmpty() && file.id == here.id
    }
}
