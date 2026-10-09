package tv.own.owntv.core.recording

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.dao.RecordingDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.epg.CatchupUrl
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.database.entity.RecordingRuleEntity
import tv.own.owntv.core.live.StreamGrant
import tv.own.owntv.core.live.StreamPurpose
import tv.own.owntv.core.live.connectionBudget
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.live.OpenStreamRegistry
import tv.own.owntv.core.model.RecordingFailure
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.storage.MediaFolders
import tv.own.owntv.core.storage.MediaRoot
import tv.own.owntv.core.storage.MediaTarget
import tv.own.owntv.core.parser.XtreamClient
import java.util.TimeZone

/**
 * The control half of recording — what the apps call. The bytes are [RecordingEngine]'s, running
 * inside [RecordingWorker] so they survive the user leaving.
 *
 * [RecordingDao] is the single source of truth; nothing here holds a queue.
 *
 * Recordings are written to `TV/` inside the same root downloads use (D1) — one folder the user
 * chose, three folders OwnTV keeps inside it.
 */
class RecordingManager(
    private val context: Context,
    private val recordingDao: RecordingDao,
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
    private val streams: OpenStreamRegistry,
    private val engine: RecordingEngine,
    private val scheduler: RecordingScheduler,
    private val channelDao: ChannelDao,
    private val epgDao: EpgDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // A row left RECORDING is one the process died in the middle of. If its window is still open
        // the engine picks it up and appends; if it has closed, rearmAll marks it missed.
        engine.markQueued()
        scope.launch {
            scheduler.rearmAll()
            RecordingWorker.kick(context)
        }
    }

    /**
     * Whether timers will fire at the right minute on this device. False when the user has revoked
     * the exact-alarm permission — recordings still happen, but they may start a few minutes late,
     * which is why the window they run in is given a bigger head start. The apps show this.
     */
    fun timersAreExact(): Boolean = scheduler.canBeExact()

    /**
     * The window a programme should be recorded over, paddings and the inexact-alarm head start
     * included. The apps call this rather than doing the arithmetic themselves, so both agree.
     */
    suspend fun windowFor(programmeStartMs: Long, programmeStopMs: Long): LongRange {
        val (pre, post) = settings.recordingRollMinutes()
        return RecordingSchedule.windowFor(
            programmeStartMs = programmeStartMs,
            programmeStopMs = programmeStopMs,
            preRollMinutes = pre,
            postRollMinutes = post,
            exactAlarms = scheduler.canBeExact(),
        )
    }

    fun observe(profileId: Long): Flow<List<RecordingEntity>> = recordingDao.observeForProfile(profileId)

    /** Everything currently being written, for the pill and the player's REC indicator (D13). */
    fun observeRunning(): Flow<List<RecordingEntity>> = recordingDao.observeRunning()

    /** Free/total space of the volume the recordings land on — the same volume downloads use. */
    suspend fun storageInfo(): RecordingStorageInfo = withContext(Dispatchers.IO) {
        val space = recordingsRoot().space()
        RecordingStorageInfo(
            freeBytes = space.freeBytes,
            totalBytes = space.totalBytes,
            reserveBytes = RecordingRules.RESERVE_BYTES,
        )
    }

    /**
     * Would one more recording on this playlist be allowed right now? The apps ask before they offer
     * the button, so a refusal is a sentence rather than a failure (D5/D10/D11).
     */
    suspend fun canRecordOn(sourceId: Long): StreamGrant = connectionBudget(
        source = sourceDao.getById(sourceId),
        open = streams.openOn(sourceId),
        purpose = StreamPurpose.RECORDING,
        reserveOneForWatching = settings.recordingReserveConnection(),
    )

    /**
     * Anything already claiming this playlist over the same window — the clash the UI warns about at
     * scheduling time, before the user has committed to anything (D10).
     */
    suspend fun clashesWith(sourceId: Long, startMs: Long, stopMs: Long, excludeId: Long = 0): List<RecordingEntity> =
        recordingDao.overlapping(sourceId, startMs, stopMs, excludeId)

    /**
     * Schedule (or start, if its window is already open) one recording, and return the row.
     *
     * Pressing Record twice on the same programme finds the first row instead of making a second —
     * that is what the table's unique index is for — so this is safe to call from anywhere the
     * programme appears.
     */
    suspend fun schedule(recording: RecordingEntity): RecordingEntity = withContext(Dispatchers.IO) {
        val existing = recordingDao.forProgramme(
            recording.profileId,
            recording.channelId,
            recording.programmeStartMs,
        )
        // Already recording or already recorded: leave it alone. Re-scheduling a finished recording
        // from the guide would otherwise wipe the row pointing at the file on disk.
        if (existing != null && existing.status != RecordingStatus.MISSED &&
            existing.status != RecordingStatus.CANCELLED
        ) {
            return@withContext existing
        }
        val target = recordingsRoot()
            .child(MediaFolders.TV, RecordingRules.fileName(recording.channelName, recording.title, recording.startMs))
        val row = recording.copy(
            id = existing?.id ?: 0,
            filePath = target?.stored,
            status = RecordingStatus.SCHEDULED,
            failure = RecordingFailure.NONE,
            bytes = 0,
            startedAt = null,
            endedAt = null,
            updatedAt = System.currentTimeMillis(),
        )
        val id = recordingDao.upsert(row)
        val saved = row.copy(id = if (id > 0) id else row.id)
        // Arm the timer first: a recording that is in the table but has no alarm is one that never
        // happens, and the kick below only helps if its window is already open.
        RecordingSchedule.wakeAtFor(saved, System.currentTimeMillis())?.let { scheduler.arm(saved.id, it) }
        kick()
        saved
    }

    /**
     * Record [channel] between two times the user picked, guide or not (#2). The recording carries the
     * channel's name as its title, and no paddings: the user chose the minutes. Returns null — and
     * schedules nothing — when [RecordingSchedule.manualWindow] refuses the times.
     */
    suspend fun scheduleManual(
        profileId: Long,
        channel: ChannelEntity,
        startMs: Long,
        stopMs: Long,
    ): RecordingEntity? {
        val manual = RecordingSchedule.manualWindow(startMs, stopMs, System.currentTimeMillis()) ?: return null
        val window = RecordingSchedule.windowFor(manual.first, manual.last, 0, 0, scheduler.canBeExact())
        return schedule(
            RecordingEntity(
                profileId = profileId,
                sourceId = channel.sourceId,
                channelId = channel.id,
                channelName = channel.name,
                channelIconUrl = channel.logoUrl,
                epgChannelId = channel.epgChannelId,
                streamUrl = channel.streamUrl,
                httpHeaders = channel.httpHeaders,
                title = channel.name,
                description = null,
                programmeStartMs = manual.first,
                programmeStopMs = manual.last,
                startMs = window.first,
                stopMs = window.last,
            ),
        )
    }

    /**
     * Record a programme that has **already aired**, from the provider's archive, starting now.
     *
     * This is the only way to record something that has already happened, and it costs almost
     * nothing: `CatchupUrl` already builds a playable archive URL for every convention the app
     * supports — Xtream's timeshift, and M3U's `append` / `shift` / `flussonic` / `xc` — so a
     * catch-up recording is an ordinary recording pointed at a different URL.
     *
     * It differs from a scheduled one in two ways, both of which the recorder derives rather than
     * being told: there is **no timer**, because the window opens now, and the source is **finite**,
     * so end-of-body means the programme is complete instead of meaning the provider dropped us.
     *
     * Returns null — and schedules nothing — when the channel has no archive, when the programme has
     * not finished airing, or when it is older than the archive goes.
     */
    suspend fun recordFromArchive(
        profileId: Long,
        channel: ChannelEntity,
        programme: EpgProgrammeEntity,
        source: SourceEntity,
        timeZone: TimeZone,
        xtream: XtreamClient,
    ): RecordingEntity? = withContext(Dispatchers.IO) {
        if (!channel.catchup) return@withContext null
        val now = System.currentTimeMillis()
        if (!RecordingSchedule.isWithinArchive(programme.stopMs, channel.catchupDays, now)) return@withContext null
        val url = CatchupUrl.forSource(channel, programme, source, timeZone, xtream) ?: return@withContext null

        val window = RecordingSchedule.catchUpWindowFor(programme.startMs, programme.stopMs, now)
        schedule(
            RecordingEntity(
                profileId = profileId,
                sourceId = source.id,
                channelId = channel.id,
                channelName = channel.name,
                channelIconUrl = channel.logoUrl,
                epgChannelId = channel.epgChannelId,
                streamUrl = url,
                httpHeaders = channel.httpHeaders,
                title = programme.title,
                description = programme.description,
                // The programme's own times, untouched — this row still describes last Tuesday's
                // nine o'clock news, whatever time it is being fetched at.
                programmeStartMs = programme.startMs,
                programmeStopMs = programme.stopMs,
                startMs = window.first,
                stopMs = window.last,
            ),
        )
    }

    // --- Series recording: "record every showing of this title on this channel" (D7) -------------

    /** This profile's standing rules, for the screens that list and cancel them. */
    fun observeRules(profileId: Long): Flow<List<RecordingRuleEntity>> = recordingDao.observeRules(profileId)

    /** The rule covering this programme on this channel, or null when there is none. */
    suspend fun ruleFor(profileId: Long, channelId: Long, title: String): RecordingRuleEntity? =
        recordingDao.findRule(profileId, channelId, RecordingRuleMatcher.fold(title))

    /**
     * Start recording every showing of [title] on [channel], and schedule the ones the guide already
     * knows about.
     *
     * Scoped to one channel on purpose: "every showing anywhere" across a twenty-thousand-channel
     * playlist is a different and much worse feature, and nobody asked for it.
     */
    suspend fun addSeriesRule(
        profileId: Long,
        channel: ChannelEntity,
        title: String,
    ): RecordingRuleEntity = withContext(Dispatchers.IO) {
        val key = RecordingRuleMatcher.fold(title)
        val existing = recordingDao.findRule(profileId, channel.id, key)
        val rule = (existing ?: RecordingRuleEntity(
            profileId = profileId,
            sourceId = channel.sourceId,
            channelId = channel.id,
            channelName = channel.name,
            epgChannelId = channel.epgChannelId,
            title = title,
            titleKey = key,
        )).copy(enabled = true)
        val id = recordingDao.upsertRule(rule)
        val saved = rule.copy(id = if (id > 0) id else rule.id)
        applyRules()
        saved
    }

    /**
     * Stop recording every showing, and cancel the showings this rule had queued up.
     *
     * Anything already recorded — or being recorded right now — is left alone. The user asked to stop
     * recording *future* showings, not to throw away last week's.
     */
    suspend fun removeSeriesRule(rule: RecordingRuleEntity) = withContext(Dispatchers.IO) {
        val pending = RecordingRuleMatcher.pendingFor(rule.id, recordingDao.scheduled())
        pending.forEach { row ->
            scheduler.cancel(row.id)
            recordingDao.updateProgress(
                id = row.id,
                status = RecordingStatus.CANCELLED,
                failure = RecordingFailure.NONE,
                bytes = 0,
                filePath = null,
                startedAt = null,
                endedAt = System.currentTimeMillis(),
                timestamp = System.currentTimeMillis(),
            )
        }
        recordingDao.deleteRule(rule)
    }

    /**
     * Walk every enabled rule and schedule the showings the guide now knows about that are not
     * already spoken for.
     *
     * **Called after a guide refresh**, which is what makes a standing rule a standing rule: a
     * programme three weeks out does not exist in the database until the EPG that mentions it is
     * fetched. It is idempotent by construction — `showingsToSchedule` excludes anything already in
     * the table, and `schedule` finds an existing row rather than adding a second.
     *
     * Clashes are **not** resolved here. A clash is decided when the recording is due, by the
     * connection budget, and reported as a MISSED row with a reason (D10) — deciding it now, against
     * a guide that may still change, would refuse recordings that would have been fine.
     */
    suspend fun applyRules() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val rules = recordingDao.enabledRules()
        if (rules.isEmpty()) return@withContext
        for (rule in rules) {
            val channel = channelDao.getById(rule.channelId) ?: continue
            val epgKey = rule.epgChannelId ?: channel.epgChannelId ?: continue
            // No source filter: a series rule matches on the channel's guide, and that guide routinely
            // arrives from a separate EPG feed rather than the playlist the channel came from. Pinning
            // it to the channel's own source made a rule miss showings that were sitting in the table.
            val programmes = epgDao.programmesForChannel(
                epgKey = epgKey,
                from = now,
                to = now + RULE_HORIZON_MS,
            )
            val showings = RecordingRuleMatcher.showingsToSchedule(
                titleKey = rule.titleKey,
                channelId = rule.channelId,
                programmes = programmes,
                // Every row, not just the scheduled ones: a showing already recorded, failed or
                // cancelled by hand must not come back on the next refresh.
                existing = recordingDao.observeForProfile(rule.profileId).first(),
                now = now,
            )
            for (programme in showings) {
                val window = windowFor(programme.startMs, programme.stopMs)
                schedule(
                    RecordingEntity(
                        profileId = rule.profileId,
                        sourceId = channel.sourceId,
                        channelId = channel.id,
                        channelName = channel.name,
                        channelIconUrl = channel.logoUrl,
                        epgChannelId = channel.epgChannelId,
                        streamUrl = channel.streamUrl,
                        httpHeaders = channel.httpHeaders,
                        title = programme.title,
                        description = programme.description,
                        programmeStartMs = programme.startMs,
                        programmeStopMs = programme.stopMs,
                        startMs = window.first,
                        stopMs = window.last,
                        ruleId = rule.id,
                    ),
                )
            }
        }
    }


    /**
     * Stop a recording that is running, keeping what it has captured — a `.ts` is playable to
     * whatever point it reached, so this is a finished short recording and not a failure.
     */
    fun stop(recording: RecordingEntity) {
        scope.launch {
            engine.stop(recording.id)
            try {
                val row = recordingDao.getById(recording.id) ?: return@launch
                val bytes = MediaTarget.of(context, row.filePath)?.length() ?: 0L
                val (status, failure) = RecordingRules.outcomeOf(bytes, RecordingFailure.NONE)
                recordingDao.updateProgress(
                    id = row.id,
                    status = status,
                    failure = failure,
                    bytes = bytes,
                    filePath = row.filePath,
                    startedAt = row.startedAt,
                    endedAt = System.currentTimeMillis(),
                    timestamp = System.currentTimeMillis(),
                )
            } finally {
                engine.release(recording.id)
            }
        }
    }

    /** Drop a scheduled recording without touching anything on disk. */
    fun cancel(recording: RecordingEntity) {
        scope.launch {
            scheduler.cancel(recording.id)
            recordingDao.updateProgress(
                id = recording.id,
                status = RecordingStatus.CANCELLED,
                failure = RecordingFailure.NONE,
                bytes = 0,
                filePath = null,
                startedAt = null,
                endedAt = System.currentTimeMillis(),
                timestamp = System.currentTimeMillis(),
            )
        }
    }

    /** Remove the row **and** the file. The only thing in the app that deletes a recording (D2). */
    fun delete(recording: RecordingEntity) {
        scope.launch {
            scheduler.cancel(recording.id)
            engine.stop(recording.id)
            try {
                MediaTarget.of(context, recording.filePath)?.delete()
                recordingDao.delete(recording)
            } finally {
                engine.release(recording.id)
            }
        }
    }

    /**
     * Point a finished recording at a file the user has moved it to, and let go of the old one.
     *
     * This is Export's second half: the bytes are already at [movedTo], and until the row agrees the
     * user has two copies and the app is tracking the wrong one.
     *
     * Uses `updateProgress` and never `upsert`, for the same reason [RecordingEngine] does: the
     * table's unique index on `(profileId, channelId, programmeStartMs)` makes a REPLACE delete this
     * row and insert a new one with a different id, orphaning anything still holding the old one.
     * Everything but the location is written back exactly as it was.
     */
    suspend fun relocate(recording: RecordingEntity, movedTo: String): Boolean =
        withContext(Dispatchers.IO) {
            val previous = MediaTarget.of(context, recording.filePath)
            recordingDao.updateProgress(
                id = recording.id,
                status = recording.status,
                failure = recording.failure,
                bytes = recording.bytes,
                filePath = movedTo,
                startedAt = recording.startedAt,
                endedAt = recording.endedAt,
                timestamp = System.currentTimeMillis(),
            )
            previous?.delete()
            true
        }

    private suspend fun recordingsRoot(): MediaRoot =
        MediaRoot.of(context, settings.downloadRoot.first()).also { it.ensureFolders() }

    private fun kick() {
        engine.markQueued()
        scope.launch { RecordingWorker.kick(context) }
    }
}

/**
 * How far ahead a series rule looks. Two weeks is more guide than most providers publish, and a rule
 * is re-applied on every refresh — so looking further would only schedule rows for programmes whose
 * times are still going to change.
 */
private const val RULE_HORIZON_MS = 14L * 24 * 60 * 60 * 1000

/** Free/total space on the volume recordings are written to, and the floor they stop at (D8). */
data class RecordingStorageInfo(val freeBytes: Long, val totalBytes: Long, val reserveBytes: Long) {
    /** What is actually available to a recording: everything above the reserve. */
    val writableBytes: Long get() = (freeBytes - reserveBytes).coerceAtLeast(0L)
}
