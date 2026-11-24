package tv.own.owntv.core.skip

import org.json.JSONObject

/** The parts of a film or episode a viewer may want to skip. */
enum class SkipKind { INTRO, RECAP, CREDITS, PREVIEW }

/** One part as the service sent it: either end may be missing (from the start / to the end). */
data class RawSkipSegment(val kind: SkipKind, val startMs: Long?, val endMs: Long?)

/** One part of the playing item, cleaned up by [SkipSegments.normalize]: both ends known, inside the file. */
data class SkipSegment(val kind: SkipKind, val startMs: Long, val endMs: Long) {
    operator fun contains(positionMs: Long): Boolean = positionMs in startMs until endMs
}

object SkipSegments {

    /** Shorter than this is a mis-timed submission, not a part anyone needs to skip. */
    const val MIN_LENGTH_MS = 3_000L

    /** Credits ending this close to the end of the file run to the end: they are the end credits. */
    const val END_CREDITS_SLACK_MS = 10_000L

    private val KEYS = mapOf(
        "intro" to SkipKind.INTRO,
        "recap" to SkipKind.RECAP,
        "credits" to SkipKind.CREDITS,
        "preview" to SkipKind.PREVIEW,
    )

    /** TheIntroDB `/v4/media` body → raw parts. Unknown fields (`tvdb_id`, `type`, …) are ignored. */
    fun parse(body: String): List<RawSkipSegment> {
        val root = JSONObject(body)
        val out = ArrayList<RawSkipSegment>()
        for ((key, kind) in KEYS) {
            val arr = root.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out += RawSkipSegment(kind, o.longOrNull("start_ms"), o.longOrNull("end_ms"))
            }
        }
        return out
    }

    private fun JSONObject.longOrNull(name: String): Long? =
        if (!has(name) || isNull(name)) null else optLong(name, -1L).takeIf { it >= 0 }

    /**
     * The parts the player can act on, in playing order.
     *
     * A missing start is the start of the file and a missing end is its end. Zero-length entries are
     * how the service records "this episode has none" (`recap: [{0, 0}]`), so they — and anything
     * shorter than [MIN_LENGTH_MS] — are dropped. Credits in the first half that overlap the intro are
     * the opening credits a contributor timed separately; they are folded into that intro, so the
     * viewer sees one "Skip intro" instead of two buttons for the same seconds.
     */
    fun normalize(raw: List<RawSkipSegment>, durationMs: Long): List<SkipSegment> {
        if (durationMs <= 0) return emptyList()
        val clean = raw.mapNotNull { r ->
            val start = (r.startMs ?: 0L).coerceIn(0L, durationMs)
            val end = (r.endMs ?: durationMs).coerceIn(0L, durationMs)
            if (end - start < MIN_LENGTH_MS) null else SkipSegment(r.kind, start, end)
        }
        var intros = mergeOverlaps(clean.filter { it.kind == SkipKind.INTRO })
        val credits = ArrayList<SkipSegment>()
        for (c in mergeOverlaps(clean.filter { it.kind == SkipKind.CREDITS })) {
            val intro = intros.firstOrNull { c.startMs < durationMs / 2 && c.startMs < it.endMs && it.startMs < c.endMs }
            if (intro == null) {
                credits += c
            } else {
                intros = intros.map {
                    if (it === intro) SkipSegment(SkipKind.INTRO, minOf(it.startMs, c.startMs), maxOf(it.endMs, c.endMs)) else it
                }
            }
        }
        val others = listOf(SkipKind.RECAP, SkipKind.PREVIEW).flatMap { kind -> mergeOverlaps(clean.filter { it.kind == kind }) }
        return (intros + credits + others).sortedBy { it.startMs }
    }

    /** One kind's entries with overlapping ones joined, so the same seconds never make two buttons. */
    private fun mergeOverlaps(segments: List<SkipSegment>): List<SkipSegment> {
        val out = ArrayList<SkipSegment>()
        for (s in segments.sortedBy { it.startMs }) {
            val last = out.lastOrNull()
            if (last != null && s.startMs <= last.endMs) {
                out[out.size - 1] = last.copy(endMs = maxOf(last.endMs, s.endMs))
            } else {
                out += s
            }
        }
        return out
    }

    /** The end credits: the last credits that run (nearly) to the end of the file, or null. */
    fun endCredits(segments: List<SkipSegment>, durationMs: Long): SkipSegment? =
        segments.lastOrNull { it.kind == SkipKind.CREDITS }
            ?.takeIf { durationMs > 0 && it.endMs >= durationMs - END_CREDITS_SLACK_MS }
}
