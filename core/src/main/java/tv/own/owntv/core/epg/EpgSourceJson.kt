package tv.own.owntv.core.epg

import org.json.JSONArray
import org.json.JSONObject

/**
 * The stored form of the EPG source list: one JSON array, in the order the user sees it. The same
 * text is what a backup carries, so encoding and decoding live here once.
 */
internal object EpgSourceJson {

    fun parse(raw: String?): List<EpgSource> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                EpgSource(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    url = o.getString("url"),
                    userAgent = o.optString("ua").takeIf { it.isNotEmpty() },
                    lastSyncAt = if (o.has("at")) o.getLong("at") else null,
                    lastError = o.optString("err").takeIf { it.isNotEmpty() },
                )
            }
        }.getOrDefault(emptyList())
    }

    fun write(list: List<EpgSource>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id).put("name", s.name).put("url", s.url)
                    .apply {
                        s.userAgent?.let { put("ua", it) }
                        s.lastSyncAt?.let { put("at", it) }
                        s.lastError?.let { put("err", it) }
                    },
            )
        }
        return arr.toString()
    }
}
