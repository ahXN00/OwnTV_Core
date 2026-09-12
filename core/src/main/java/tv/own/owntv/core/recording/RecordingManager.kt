package tv.own.owntv.core.recording

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.RecordingDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.epg.CatchupUrl
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.live.StreamGrant
import tv.own.owntv.core.live.StreamPurpose
import tv.own.owntv.core.live.connectionBudget
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.live.OpenStreamRegistry
import tv.own.owntv.core.model.RecordingFailure
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.storage.MediaFolders
import tv.own.owntv.core.storage.StorageAccess
import tv.own.owntv.core.parser.XtreamClient
import java.io.File
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
        val root = recordingsRoot()
        RecordingStorageInfo(
            freeBytes = root.usableSpace,
            totalBytes = root.totalSpace,
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
        val root = File(recordingsRoot(), MediaFolders.TV).apply { mkdirs() }
        val file = File(root, RecordingRules.fileName(recording.channelName, recording.title, recording.startMs))
        val row = recording.copy(
            id = existing?.id ?: 0,
            filePath = file.absolutePath,
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

    /**
     * Stop a recording that is running, keeping what it has captured — a `.ts` is playable to
     * whatever point it reached, so this is a finished short recording and not a failure.
     */
    fun stop(recording: RecordingEntity) {
        scope.launch {
            engine.stop(recording.id)
            try {
                val row = recordingDao.getById(recording.id) ?: return@launch
                val bytes = row.filePath?.let { File(it) }?.takeIf { it.exists() }?.length() ?: 0L
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
                recording.filePath?.let { runCatching { File(it).delete() } }
                recordingDao.delete(recording)
            } finally {
                engine.release(recording.id)
            }
        }
    }

    private suspend fun recordingsRoot(): File =
        StorageAccess.resolveRoot(context, settings.downloadRoot.first())
            .also { MediaFolders.ensureIn(it) }

    private fun kick() {
        engine.markQueued()
        scope.launch { RecordingWorker.kick(context) }
    }
}

/** Free/total space on the volume recordings are written to, and the floor they stop at (D8). */
data class RecordingStorageInfo(val freeBytes: Long, val totalBytes: Long, val reserveBytes: Long) {
    /** What is actually available to a recording: everything above the reserve. */
    val writableBytes: Long get() = (freeBytes - reserveBytes).coerceAtLeast(0L)
}
