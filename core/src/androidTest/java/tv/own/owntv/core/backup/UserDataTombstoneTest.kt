package tv.own.owntv.core.backup

import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.entity.FavoriteEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.PlaybackProgressEntity
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.database.entity.WatchHistoryEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType

/**
 * The merge rule local sync stands on: a deletion is a fact with a time on it, and the newer of the
 * two facts wins.
 *
 * Every case here is one the owner will actually perform on his own two devices — unfavorite on the
 * television, sync, look at the phone — and every one of them fails silently if the rule is wrong.
 * The failure mode is not a crash: it is a favorite quietly coming back from the dead on every sync,
 * for ever, which is exactly the kind of thing nobody reports as a bug and everybody stops trusting.
 *
 * Instrumentation, not JVM: the rule is expressed in DAO queries and a real transaction.
 */
@RunWith(AndroidJUnit4::class)
class UserDataTombstoneTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: OwnTVDatabase
    private lateinit var resolver: UserDataResolver
    private lateinit var writer: UserDataWriter

    private var profileId = 0L
    private var sourceId = 0L

    // The `: Unit` is load-bearing: without it Kotlin infers the last expression's type, JUnit
    // rejects the class with "Method setUp() should be void", and the whole suite never runs.
    @Before
    fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, OwnTVDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        resolver = UserDataResolver(
            context = context,
            channelDao = db.channelDao(),
            movieDao = db.movieDao(),
            seriesDao = db.seriesDao(),
            profileDao = db.profileDao(),
            favoriteDao = db.favoriteDao(),
            historyDao = db.historyDao(),
            progressDao = db.progressDao(),
            contentOrderDao = db.contentOrderDao(),
            customCategoryDao = db.customCategoryDao(),
            seriesSortOrderDao = db.seriesSortOrderDao(),
            tombstoneDao = db.tombstoneDao(),
            db = db,
        )
        writer = UserDataWriter(
            db = db,
            favoriteDao = db.favoriteDao(),
            historyDao = db.historyDao(),
            progressDao = db.progressDao(),
            customCategoryDao = db.customCategoryDao(),
            userData = resolver,
        )
        profileId = db.profileDao().insert(ProfileEntity(name = "Primary", avatarColor = 0x112233))
        sourceId = db.sourceDao().insert(
            SourceEntity(name = "Portal", type = SourceType.XTREAM, url = "https://portal.test", username = "user"),
        )
        context.pendingStore.edit { it.remove(PENDING_KEY) }
    }

    @After
    fun tearDown() = runBlocking {
        context.pendingStore.edit { it.remove(PENDING_KEY) }
        db.close()
    }

    /** Unfavoriting records the deletion, so the other device can be told about it. */
    @Test
    fun removingAFavoriteRecordsADeletionThatTravels() = runBlocking {
        val movieId = insertMovie("m-1", "Blade Runner")
        db.favoriteDao().add(FavoriteEntity(profileId = profileId, mediaType = MediaType.MOVIE, itemId = movieId, addedAt = 100))

        writer.removeFavorite(profileId, MediaType.MOVIE, movieId)

        assertEquals(0, db.favoriteDao().getAllOnce().size)
        val exported = resolver.exportTombstones(setOf("fav"))
        assertEquals(1, exported.length())
        assertEquals("fav", exported.getJSONObject(0).getString("kind"))
    }

    /**
     * The whole point. The other device still has the favorite, its copy is older than the deletion,
     * and a merge must not put it back.
     */
    @Test
    fun anIncomingFavoriteOlderThanTheDeletionIsNotReinstated() = runBlocking {
        val movieId = insertMovie("m-1", "Blade Runner")
        db.favoriteDao().add(FavoriteEntity(profileId = profileId, mediaType = MediaType.MOVIE, itemId = movieId, addedAt = 100))
        writer.removeFavorite(profileId, MediaType.MOVIE, movieId)

        // What the other device sends: the same favorite, as it was before the deletion.
        resolver.importAll(incomingFavorites(movieId, at = 100))

        assertEquals("a deleted favorite came back", 0, db.favoriteDao().getAllOnce().size)
    }

    /** The opposite direction: favoriting it again afterwards is newer, and must survive a sync. */
    @Test
    fun anIncomingFavoriteNewerThanTheDeletionIsApplied() = runBlocking {
        val movieId = insertMovie("m-1", "Blade Runner")
        db.favoriteDao().add(FavoriteEntity(profileId = profileId, mediaType = MediaType.MOVIE, itemId = movieId, addedAt = 100))
        writer.removeFavorite(profileId, MediaType.MOVIE, movieId)

        resolver.importAll(incomingFavorites(movieId, at = System.currentTimeMillis() + 60_000))

        assertEquals(1, db.favoriteDao().getAllOnce().size)
    }

    /** A deletion arriving from the other device removes the local row when the local row is older. */
    @Test
    fun anIncomingDeletionRemovesTheLocalRow() = runBlocking {
        val movieId = insertMovie("m-1", "Blade Runner")
        db.favoriteDao().add(FavoriteEntity(profileId = profileId, mediaType = MediaType.MOVIE, itemId = movieId, addedAt = 100))

        val applied = resolver.applyTombstones(incomingDeletion(movieId, at = 200))

        assertEquals(1, applied)
        assertEquals(0, db.favoriteDao().getAllOnce().size)
    }

    /** ...and leaves it alone when the user re-added it here after the other device deleted it. */
    @Test
    fun anIncomingDeletionOlderThanTheLocalRowLeavesItAlone() = runBlocking {
        val movieId = insertMovie("m-1", "Blade Runner")
        db.favoriteDao().add(FavoriteEntity(profileId = profileId, mediaType = MediaType.MOVIE, itemId = movieId, addedAt = 500))

        resolver.applyTombstones(incomingDeletion(movieId, at = 200))

        assertEquals("a favorite re-added after the deletion was lost", 1, db.favoriteDao().getAllOnce().size)
    }

    /**
     * A deletion survives the content ids changing underneath it, because it is keyed on the same
     * stable identity a favorite is — otherwise the next playlist refresh would forget every deletion
     * and the next sync would undo them all at once.
     */
    @Test
    fun aDeletionStillMatchesAfterAResyncChangesTheRowId() = runBlocking {
        val oldId = insertMovie("m-1", "Blade Runner")
        db.favoriteDao().add(FavoriteEntity(profileId = profileId, mediaType = MediaType.MOVIE, itemId = oldId, addedAt = 100))
        writer.removeFavorite(profileId, MediaType.MOVIE, oldId)

        db.movieDao().clearSource(sourceId)
        val newId = insertMovie("m-1", "Blade Runner")
        assertTrue(newId != oldId)

        resolver.importAll(incomingFavorites(newId, at = 100))

        assertEquals(0, db.favoriteDao().getAllOnce().size)
    }

    /** The dry run has to promise the same answer the apply will give. */
    @Test
    fun thePreviewAgreesWithWhatTheMergeWillDo() = runBlocking {
        val movieId = insertMovie("m-1", "Blade Runner")
        val incoming = incomingFavorites(movieId, at = 100).getJSONObject(0)

        assertTrue("a favorite this device does not have is new", resolver.wouldAdd(profileId, "fav", incoming))

        db.favoriteDao().add(FavoriteEntity(profileId = profileId, mediaType = MediaType.MOVIE, itemId = movieId, addedAt = 100))
        assertFalse("one it already has is not", resolver.wouldAdd(profileId, "fav", incomingFavorites(movieId, at = 100).getJSONObject(0)))

        val deletion = incomingDeletion(movieId, at = 200).getJSONObject(0)
        assertTrue("a deletion of a row that is here counts", resolver.wouldRemove(profileId, deletion))
    }

    // --- newest wins, for records as well as deletions --------------------------------------------
    //
    // A deletion already obeyed the clock; an ordinary record did not, and went in with REPLACE. So
    // the merge's answer depended on which device happened to apply last rather than on which fact
    // was newer. These four cases are the owner's own: watch on one device, sync, look at the other.

    /** Finish an episode on the television; the phone's older position must not overwrite it. */
    @Test
    fun anIncomingResumePositionOlderThanTheLocalOneIsIgnored() = runBlocking {
        val movieId = insertMovie("m-1", "Blade Runner")
        db.progressDao().save(progress(movieId, positionMs = 3_600_000, at = 500))

        resolver.importAll(incomingProgress(movieId, positionMs = 60_000, at = 100))

        val kept = db.progressDao().get(profileId, MediaType.MOVIE, movieId)!!
        assertEquals("an older resume position overwrote a newer one", 3_600_000L, kept.positionMs)
        assertEquals(500L, kept.updatedAt)
    }

    /** ...and the other way round: a genuinely newer position from the other device is taken. */
    @Test
    fun anIncomingResumePositionNewerThanTheLocalOneWins() = runBlocking {
        val movieId = insertMovie("m-1", "Blade Runner")
        db.progressDao().save(progress(movieId, positionMs = 60_000, at = 100))

        resolver.importAll(incomingProgress(movieId, positionMs = 3_600_000, at = 500))

        val kept = db.progressDao().get(profileId, MediaType.MOVIE, movieId)!!
        assertEquals(3_600_000L, kept.positionMs)
        assertEquals(500L, kept.updatedAt)
    }

    /** A position for something this device has never played still arrives. */
    @Test
    fun anIncomingResumePositionForAnUnknownItemIsInserted() = runBlocking {
        val movieId = insertMovie("m-1", "Blade Runner")

        resolver.importAll(incomingProgress(movieId, positionMs = 60_000, at = 100))

        assertEquals(60_000L, db.progressDao().get(profileId, MediaType.MOVIE, movieId)!!.positionMs)
    }

    /** Watch history obeys the same clock: the time only ever moves forward. */
    @Test
    fun watchHistoryNeverMovesBackwards() = runBlocking {
        val movieId = insertMovie("m-1", "Blade Runner")
        db.historyDao().record(WatchHistoryEntity(profileId = profileId, mediaType = MediaType.MOVIE, itemId = movieId, watchedAt = 500))

        resolver.importAll(incomingHistory(movieId, at = 100))
        assertEquals("history was aged by a sync", 500L, db.historyDao().getAllOnce().single().watchedAt)

        resolver.importAll(incomingHistory(movieId, at = 900))
        assertEquals(900L, db.historyDao().getAllOnce().single().watchedAt)
    }

    private fun progress(itemId: Long, positionMs: Long, at: Long) = PlaybackProgressEntity(
        profileId = profileId, mediaType = MediaType.MOVIE, itemId = itemId,
        positionMs = positionMs, durationMs = 7_200_000, updatedAt = at,
    )

    private suspend fun incomingProgress(itemId: Long, positionMs: Long, at: Long): JSONArray {
        val record = resolver.identityOf(MediaType.MOVIE, itemId)!!
        return JSONArray().put(
            record.put("p", profileId).put("kind", "prog").put("at", at)
                .put("pos", positionMs).put("dur", 7_200_000),
        )
    }

    private suspend fun incomingHistory(itemId: Long, at: Long): JSONArray {
        val record = resolver.identityOf(MediaType.MOVIE, itemId)!!
        return JSONArray().put(record.put("p", profileId).put("kind", "his").put("at", at))
    }

    private suspend fun incomingFavorites(itemId: Long, at: Long): JSONArray {
        val record = resolver.identityOf(MediaType.MOVIE, itemId)!!
        return JSONArray().put(record.put("p", profileId).put("kind", "fav").put("at", at))
    }

    private suspend fun incomingDeletion(itemId: Long, at: Long): JSONArray = incomingFavorites(itemId, at)

    private suspend fun insertMovie(remoteId: String?, name: String): Long {
        db.movieDao().insertAll(
            listOf(MovieEntity(sourceId = sourceId, name = name, streamUrl = "https://portal.test/$name", remoteId = remoteId)),
        )
        return db.movieDao().findByName(sourceId, name)!!.id
    }
}
