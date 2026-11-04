package tv.own.owntv.core.customize

import org.json.JSONArray
import org.json.JSONObject

/** The JSON form of one [SectionCustomizations] blob in DataStore (org.json, matching BackupManager's style). */
internal object CustomizationJson {

    fun parse(raw: String?): SectionCustomizations {
        if (raw.isNullOrBlank()) return SectionCustomizations()
        return runCatching {
            val o = JSONObject(raw)
            SectionCustomizations(
                hiddenCategories = o.optJSONArray("hiddenCats").toStringSet(),
                hiddenItems = o.optJSONObject("hiddenItems").toStringMap(),
                categoryNames = o.optJSONObject("catNames").toStringMap(),
                itemNames = o.optJSONObject("itemNames").toStringMap(),
                // An older "catOrder" list held every category of the rail, so nothing could follow the
                // sort chip; it is dropped and only moves made from now on keep a position.
                categoryPositions = o.optJSONObject("catPos").toIntMap(),
                epgMatches = o.optJSONObject("epgMatch").toStringMap(),
                epgShifts = o.optJSONObject("epgShift").toStringMap(),
                customCategories = o.optJSONArray("customCats").toCustomCategories(),
                movedFromOrigin = o.optJSONObject("movedFrom").toStringMap(),
                lockedCategories = o.optJSONArray("lockedCats").toStringSet(),
            )
        }.getOrDefault(SectionCustomizations())
    }

    fun serialize(c: SectionCustomizations): String = JSONObject().apply {
        put("hiddenCats", JSONArray(c.hiddenCategories.toList()))
        put("hiddenItems", JSONObject(c.hiddenItems as Map<*, *>))
        put("catNames", JSONObject(c.categoryNames as Map<*, *>))
        put("itemNames", JSONObject(c.itemNames as Map<*, *>))
        put("catPos", JSONObject(c.categoryPositions as Map<*, *>))
        put("epgMatch", JSONObject(c.epgMatches as Map<*, *>))
        put("epgShift", JSONObject(c.epgShifts as Map<*, *>))
        put("movedFrom", JSONObject(c.movedFromOrigin as Map<*, *>))
        put("lockedCats", JSONArray(c.lockedCategories.toList()))
        put(
            "customCats",
            JSONArray(c.customCategories.map {
                JSONObject().put("id", it.id).put("name", it.name).put("icon", it.icon)
            }),
        )
    }.toString()

    private fun JSONArray?.toStringList(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }

    private fun JSONArray?.toStringSet(): Set<String> = toStringList().toSet()

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        val out = HashMap<String, String>()
        keys().forEach { k -> out[k] = getString(k) }
        return out
    }

    private fun JSONObject?.toIntMap(): Map<String, Int> {
        if (this == null) return emptyMap()
        val out = HashMap<String, Int>()
        keys().forEach { k -> out[k] = getInt(k) }
        return out
    }

    private fun JSONArray?.toCustomCategories(): List<CustomCategory> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            CustomCategory(
                id = id,
                name = o.optString("name"),
                icon = o.optString("icon").ifEmpty { null },
            )
        }
    }
}
