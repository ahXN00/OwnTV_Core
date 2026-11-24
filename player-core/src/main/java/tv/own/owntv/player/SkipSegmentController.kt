package tv.own.owntv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.skip.SkipChoices
import tv.own.owntv.core.skip.SkipKind
import tv.own.owntv.core.skip.SkipMode
import tv.own.owntv.core.skip.SkipSegment
import tv.own.owntv.core.skip.SkipSegmentRepository
import tv.own.owntv.core.skip.SkipSegments
import tv.own.owntv.core.subtitles.SubtitleController

/**
 * Skip intro, recap, credits and preview for the film or episode playing, in both apps.
 *
 * The playing title's TMDB id comes from [SubtitleController.current], which both apps already set at
 * every film and episode start (autoplay and downloads included) and clear when the player closes —
 * so this needs no wiring of its own. A context is trusted only while the player is on a film or an
 * episode (never live or catch-up) whose season and episode, when the player knows them, match it.
 *
 * Every decision lives here — when a button shows, what it does, the automatic skip, when the
 * next-episode card appears and when the episode moves on — so the apps only draw [active] and
 * [upNext] and call [skip]. See [SkipPlanner] for the rules themselves.
 */
class SkipSegmentController(
    private val player: OwnTVPlayer,
    private val subtitles: SubtitleController,
    private val repository: SkipSegmentRepository,
    settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The parts of the item playing, cleaned up, and its end credits if it has any. [item] is the
     *  player's item they were loaded for: the moment the player moves on, they no longer apply. */
    private data class Loaded(val segments: List<SkipSegment>, val endCredits: SkipSegment?, val item: ItemIdentity)

    private data class ItemIdentity(val contentKey: String?, val title: String?, val season: Int?, val episode: Int?)

    private fun MediaMeta.identity() = ItemIdentity(contentKey, title, seasonNumber, episodeNumber)

    private val loaded = MutableStateFlow<Loaded?>(null)
    private val inSegment = MutableStateFlow<Pair<SkipSegment, Long>?>(null)

    private val choices: StateFlow<SkipChoices> =
        settings.skipChoices.stateIn(scope, SharingStarted.Eagerly, SkipChoices())
    private val autoPlayNext: StateFlow<Boolean> =
        settings.autoPlayNext.stateIn(scope, SharingStarted.Eagerly, true)

    private val situation: StateFlow<SkipPlanner.Situation> =
        combine(player.nav, autoPlayNext, player.stopsAtItemEnd) { nav, auto, stops ->
            SkipPlanner.Situation(nextFollows = nav.hasNext, autoPlayNext = auto, stopsAtItemEnd = stops)
        }.stateIn(scope, SharingStarted.Eagerly, SkipPlanner.Situation(false, true, false))

    /** The part the playhead is in and its button; null when there is no button to show. */
    val active: StateFlow<ActiveSkip?> =
        combine(inSegment, loaded, choices, situation) { inside, l, c, s ->
            val (segment, enteredAt) = inside ?: return@combine null
            val isEnd = segment == l?.endCredits
            SkipPlanner.action(segment, isEnd, c.modeOf(segment.kind), s)?.let { ActiveSkip(segment, it, enteredAt) }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** When the next-episode card shows and when the episode moves on. */
    val upNext: StateFlow<UpNext> =
        combine(loaded, player.duration, choices, situation) { l, duration, c, s ->
            SkipPlanner.upNext(l?.endCredits, duration, c.credits, s)
        }.stateIn(scope, SharingStarted.Eagerly, SkipPlanner.upNext(null, 0L, SkipMode.OFF, SkipPlanner.Situation(false, true, false)))

    /** How long the button stays: its first seconds, or the whole part. */
    val buttonShow: StateFlow<tv.own.owntv.core.skip.SkipButtonShow> =
        choices.map { it.buttonShow }.stateIn(scope, SharingStarted.Eagerly, tv.own.owntv.core.skip.SkipButtonShow.DEFAULT)

    private val _skipped = MutableSharedFlow<SkipKind>(extraBufferCapacity = 4)
    /** A part was skipped automatically — for the short "Skipped" notice. */
    val skipped: SharedFlow<SkipKind> = _skipped

    // Per item: the parts the playhead has already been in (an automatic skip fires once), the last
    // position seen (to tell playing into a part from landing in it), and whether this item moved on.
    private val seen = HashSet<SkipSegment>()
    private var previousMs: Long? = null
    private var advanced = false
    private var skippedPart: SkipSegment? = null

    init {
        scope.launch { subtitles.current.collect { ctx -> load(ctx) } }
        scope.launch { player.position.collect { onPosition(it) } }
        // Autoplay starts the next episode before the app hands over its context: the times of the
        // episode that just ended must not act on the one that just began.
        scope.launch {
            player.currentMeta.collect { meta ->
                if (loaded.value?.item?.let { it != meta.identity() } == true) {
                    loaded.value = null
                    inSegment.value = null
                }
            }
        }
    }

    private var loadJob: kotlinx.coroutines.Job? = null

    private fun load(ctx: SubtitleController.Context?) {
        loadJob?.cancel()
        loaded.value = null
        inSegment.value = null
        seen.clear()
        previousMs = null
        advanced = false
        skippedPart = null
        if (ctx == null) return
        val tmdbId = ctx.tmdbId?.toInt()?.takeIf { it > 0 } ?: return
        loadJob = scope.launch {
            if (choices.value.allOff) return@launch
            // The context is set right after the load starts: wait for this item's own length.
            val (meta, duration) = combine(player.currentMeta, player.duration) { meta, d -> meta to d }
                .first { (meta, d) -> d > 0 && matches(ctx, meta) }
            if (player.isLiveContent || player.isArchiveItem) return@launch
            val key = if (ctx.isEpisode) {
                SkipSegmentRepository.Key(tmdbId, ctx.season ?: return@launch, ctx.episode ?: return@launch)
            } else {
                SkipSegmentRepository.Key(tmdbId, null, null)
            }
            val segments = SkipSegments.normalize(repository.segmentsFor(key, duration), duration)
            if (subtitles.current.value != ctx || player.currentMeta.value.identity() != meta.identity()) return@launch
            loaded.value = Loaded(segments, SkipSegments.endCredits(segments, duration), meta.identity())
            android.util.Log.i(TAG, "times for tmdb=$tmdbId s=${key.season} e=${key.episode}: ${segments.joinToString { "${it.kind} ${it.startMs / 1000}-${it.endMs / 1000}s" }.ifEmpty { "none" }}")
            onPosition(player.position.value)
        }
    }

    /** Season and episode must agree when the player knows them; a film is never an episode. */
    private fun matches(ctx: SubtitleController.Context, meta: MediaMeta): Boolean =
        if (ctx.isEpisode) {
            (meta.seasonNumber == null || meta.seasonNumber == ctx.season) &&
                (meta.episodeNumber == null || meta.episodeNumber == ctx.episode)
        } else {
            meta.episodeNumber == null
        }

    private fun onPosition(positionMs: Long) {
        val l = loaded.value ?: return
        // A part just skipped stays hidden until the playhead has left it: the seek takes a moment, and
        // the button flashing back for the old position would read as the skip not working.
        val found = l.segments.firstOrNull { positionMs in it }
        if (found != skippedPart) skippedPart = null
        val segment = found?.takeIf { it != skippedPart }
        val current = inSegment.value
        if (segment != current?.first) {
            inSegment.value = segment?.let { it to positionMs }
        }

        if (segment != null && segment !in seen) {
            val isEnd = segment == l.endCredits
            val auto = !isEnd && choices.value.modeOf(segment.kind) == SkipMode.AUTO
            if (auto && SkipPlanner.playedInto(previousMs, positionMs, segment)) {
                seen += segment
                skippedPart = segment
                inSegment.value = null
                player.seekTo(segment.endMs)
                _skipped.tryEmit(segment.kind)
                previousMs = segment.endMs
                return
            }
            seen += segment
        }

        val next = upNext.value
        // Back before the card (a seek back, the episode watched again): the move-on is armed again.
        if (positionMs < next.cardAtMs) advanced = false
        if (next.coreAdvances && !advanced && SkipPlanner.playedPast(previousMs, positionMs, next.advanceAtMs) &&
            !player.autoNextCancelled && player.nav.value.hasNext
        ) {
            advanced = true
            player.next()
        }
        previousMs = positionMs
    }

    /** The button: past the part, to the next episode, or to the end the way a natural end gets there. */
    fun skip() {
        val a = active.value ?: return
        val duration = player.duration.value
        when {
            a.action == SkipAction.NEXT_EPISODE -> player.next()
            a.segment == loaded.value?.endCredits && duration > 0 ->
                player.seekTo((duration - FINISH_LEAD_MS).coerceAtLeast(a.segment.startMs))
            else -> player.seekTo(a.segment.endMs)
        }
        seen += a.segment
        skippedPart = a.segment
        inSegment.value = null
    }

    /** The position the app saves for the item playing: inside its end credits, it counts as finished. */
    fun savedPosition(positionMs: Long, durationMs: Long): Long =
        SkipPlanner.savedPosition(positionMs, durationMs, loaded.value?.endCredits)

    private companion object {
        const val TAG = "OwnTV-Skip"
        /** "Skip credits" with nothing after: land just short of the end, so the file ends as it would have. */
        const val FINISH_LEAD_MS = 1_000L
    }
}
