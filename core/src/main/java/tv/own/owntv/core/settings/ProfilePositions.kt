package tv.own.owntv.core.settings

import org.json.JSONObject

/**
 * The remembered positions (last category per section, last channel) are kept per profile, under
 * `<name>_<profileId>` beside the value the whole device used to share. The rules, apart from the
 * stores, so they can be tested.
 */
internal object ProfilePositions {

    fun name(shared: String, profileId: Long): String = "${shared}_$profileId"

    /** The profile's own value, else the device-wide one from before values were kept per profile. */
    fun read(values: Map<String, Any?>, shared: String, profileId: Long): Any? =
        values[name(shared, profileId)] ?: values[shared]

    /**
     * `{ "<profileId>": { "<shared name>": value } }` for every profile's own value of [shared] keys
     * that passes [portable]. The device-wide values are not included.
     */
    fun export(values: Map<String, Any?>, shared: List<String>, portable: (String) -> Boolean): JSONObject {
        val out = JSONObject()
        values.forEach { (k, v) ->
            val base = shared.firstOrNull { k.startsWith("${it}_") } ?: return@forEach
            val pid = k.removePrefix("${base}_").toLongOrNull() ?: return@forEach
            val value = (v as? String)?.takeIf(portable) ?: return@forEach
            (out.optJSONObject(pid.toString()) ?: JSONObject().also { out.put(pid.toString(), it) }).put(base, value)
        }
        return out
    }

    /** What to write for [export]'s form: stored name → value, for profiles in [existingProfileIds] only. */
    fun import(o: JSONObject, shared: List<String>, existingProfileIds: Set<Long>, portable: (String) -> Boolean): Map<String, String> {
        val out = HashMap<String, String>()
        o.keys().forEach { key ->
            val pid = key.toLongOrNull()?.takeIf { it in existingProfileIds } ?: return@forEach
            val values = o.optJSONObject(key) ?: return@forEach
            shared.forEach { base -> values.optString(base).takeIf(portable)?.let { out[name(base, pid)] = it } }
        }
        return out
    }
}
