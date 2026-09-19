package tv.own.owntv.core.sync

import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

/**
 * Per-section choice on Add (Now / Later / Off). Edit uses only On(=Now) / Off — now-vs-later
 * only matters during the first import.
 */
enum class SyncScopeChoice {
    Now,
    Later,
    Off,
    ;

    companion object {
        fun parse(raw: String?, default: SyncScopeChoice = Now): SyncScopeChoice = when (raw?.trim()?.lowercase()) {
            "now", "true", "on", "1", "yes" -> Now
            "later" -> Later
            "off", "false", "0", "no" -> Off
            else -> default
        }
    }
}

/** Live source-sync progress reported as per-content counters. */
data class ImportStage(
    val liveProcessed: Int = 0,
    val moviesProcessed: Int = 0,
    val seriesProcessed: Int = 0,
    val liveActive: Boolean = false,
    val moviesActive: Boolean = false,
    val seriesActive: Boolean = false,
    /**
     * Which stream the connection measurement is opening, and how many it may open, or 0/0 when it
     * is not running.
     *
     * Carried on the ordinary progress object so the measurement appears on whatever screen is
     * already watching a sync — it runs *before* a single row is written and can take two minutes,
     * which without a word on screen is indistinguishable from the app having hung.
     */
    val measuringStream: Int = 0,
    val measuringOf: Int = 0,
) {
    val measuringConnections: Boolean get() = measuringOf > 0

    val totalProcessed: Int
        get() = liveProcessed + moviesProcessed + seriesProcessed
}

/** Xtream / M3U progress is reported in these three phases. */
enum class SyncPhase {
    LIVE,
    MOVIES,
    SERIES,
}

/** Terminal result of a sync run. */
sealed interface SyncResult {
    data class Success(
        val warnings: List<SyncWarning> = emptyList(),
        /** Category churn on a resync (always 0 on a source's first sync, where everything is "new"). */
        val categoriesAdded: Int = 0,
        val categoriesRemoved: Int = 0,
    ) : SyncResult

    data object Cancelled : SyncResult
    data class Failed(val message: String) : SyncResult
}

sealed interface SyncWarningKind {
    data object GENERIC : SyncWarningKind
    data object PAGE_FAILURE : SyncWarningKind
    data class CATALOG_SHRINK(val stored: Int, val percentFewer: Int) : SyncWarningKind

    /**
     * The provider's bulk list stopped part-way and the per-category fallback finished the job
     * (plan N2d).
     *
     * Measured on a real playlist: `get_vod_streams` ran for 242 seconds and the peer sent a
     * connection reset at 139,924 of 178,720 films. The catalogue still ended up complete, because
     * the fallback recovered it — but it took ten minutes instead of thirty-six seconds, and
     * *nothing said so*. The run reported `Success`, and the only trace was a warning under a log
     * tag nobody was filtering on. That silence is the defect this kind exists to end.
     *
     * [recovered] says whether the fallback got everything back, which is what decides whether the
     * user is looking at a slow sync or a short catalogue.
     */
    data class BULK_TRUNCATED(val itemsBeforeCutoff: Int, val recovered: Boolean) : SyncWarningKind
}

data class SyncWarning(
    val phase: String,
    /** Raw provider/exception text, only used for an otherwise-unclassified phase error. */
    val message: String = "",
    val kind: SyncWarningKind = SyncWarningKind.GENERIC,
    val count: Int = 0,
)

data class SyncContentTypes(
    val live: Boolean = true,
    val movies: Boolean = true,
    val series: Boolean = true,
) {
    val hasAny: Boolean get() = live || movies || series

    fun intersect(other: SyncContentTypes) = SyncContentTypes(
        live = live && other.live,
        movies = movies && other.movies,
        series = series && other.series,
    )

    /** enabled - priority: the background remainder AFTER the foreground pass (enabled-relative). */
    fun remainderAfter(priority: SyncContentTypes) = SyncContentTypes(
        live = !priority.live && live,
        movies = !priority.movies && movies,
        series = !priority.series && series,
    )

    /** M3U/backup are single-stream (live-only) regardless of the persisted flags. */
    fun constrainedTo(type: SourceType) = when (type) {
        SourceType.M3U, SourceType.LOCAL_BACKUP -> copy(movies = false, series = false)
        else -> this
    }

    /** True iff this pass covered every section in [target]. Compare against [enabledFor], NOT [enabledOf]. */
    fun isCompleteFor(target: SyncContentTypes): Boolean = intersect(target) == target

    /** The single derivation used at every sync boundary: request ∩ enabled, then type-constrained. */
    fun effectiveFor(source: SourceEntity): SyncContentTypes =
        intersect(enabledOf(source)).constrainedTo(source.type)

    companion object {
        /** Raw persisted enabledScope (Off = false). */
        fun enabledOf(s: SourceEntity) = SyncContentTypes(s.syncLive, s.syncMovies, s.syncSeries)

        /** enabledScope constrained by source type — the completion TARGET (M3U → live-only). */
        fun enabledFor(s: SourceEntity) = enabledOf(s).constrainedTo(s.type)

        fun fromChoices(live: SyncScopeChoice, movies: SyncScopeChoice, series: SyncScopeChoice) = SyncContentTypes(
            live = live != SyncScopeChoice.Off,
            movies = movies != SyncScopeChoice.Off,
            series = series != SyncScopeChoice.Off,
        )

        fun priorityFromChoices(live: SyncScopeChoice, movies: SyncScopeChoice, series: SyncScopeChoice) = SyncContentTypes(
            live = live == SyncScopeChoice.Now,
            movies = movies == SyncScopeChoice.Now,
            series = series == SyncScopeChoice.Now,
        )
    }
}

data class SyncRunStats(
    val sourceId: Long,
    val startedAt: Long,
    val finishedAt: Long,
    val result: SyncResult,
    val phaseTiming: Map<String, Long>,
    val processedCounts: Map<String, Int>,
    val phaseErrors: Map<String, String>,
    val usedFallback: Boolean,
)
