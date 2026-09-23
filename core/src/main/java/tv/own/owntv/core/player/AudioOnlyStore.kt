package tv.own.owntv.core.player

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.audioOnlyStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_audio_only")

/**
 * Channels the user last watched with the picture switched off, so a radio station opens without a
 * video decoder next time and a television channel does not. Self-learning in exactly the same way as
 * [ForceMpvStore]: the user turns the picture off once, and that one channel remembers.
 *
 * Keyed by [enginePinKey] — sourceId + media type + provider remoteId — because channel rows are
 * REPLACE-upserted on every playlist sync, so a Room id or a stream URL would be forgotten on the next
 * refresh (and a Stalker URL is a single-use token that is never the same twice).
 *
 * Honoured only while the "Remember per channel" setting is on; the store keeps its entries when that
 * is switched off so turning it back on restores what the user had taught it.
 *
 * Since v44 stored in `playback_quirks`, shared by every profile and deleted with its playlist; the old
 * DataStore file is copied in once. Now in backups too, with the other per-item quirks.
 */
class AudioOnlyStore(
    private val context: Context,
    private val dao: tv.own.owntv.core.database.dao.PlaybackQuirkDao,
) {
    // The pre-v44 DataStore key. Read once, by [copy], and never written again.
    private val key = stringSetPreferencesKey("keys")
    private val movedKey = androidx.datastore.preferences.core.booleanPreferencesKey("moved_to_db_v44")

    private val copy = CopyOnce(
        isDone = { context.audioOnlyStore.data.first()[movedKey] == true },
        markDone = { context.audioOnlyStore.edit { it[movedKey] = true } },
        copy = {
            (context.audioOnlyStore.data.first()[key] ?: emptySet()).forEach { write(it, true) }
        },
    )

    val keys: Flow<Set<String>> = flow {
        copy.ensure()
        emitAll(dao.observeAudioOnly().map { it.toSet() })
    }

    /** Was this item last watched without a picture? */
    suspend fun isAudioOnly(pinKey: String): Boolean {
        copy.ensure()
        return dao.get(pinKey)?.audioOnly == true
    }

    /** Record — or clear — the sound-only choice for one item. */
    suspend fun set(pinKey: String, audioOnly: Boolean) {
        copy.ensure()
        write(pinKey, audioOnly)
    }

    private suspend fun write(pinKey: String, audioOnly: Boolean) =
        dao.setAudioOnly(pinKey, sourceIdOfPinKey(pinKey), mediaTypeOfPinKey(pinKey) ?: "LIVE", audioOnly)
}
