package tv.own.owntv.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.database.entity.ProfileEntity

/**
 * [transaction] against the engine the app ships, because the predecessor it replaced could not run
 * there at all.
 *
 * `androidx.room.withTransaction` reaches `RoomDatabase.openHelper`, which throws once a
 * `SQLiteDriver` is configured — so every transaction in core failed at runtime from core-1.0.48
 * while the build stayed green, because no test opened a database the way `DatabaseModule` does.
 * These two cases are cheap, and they are the ones that were missing.
 */
@RunWith(AndroidJUnit4::class)
class RoomTransactionTest {
    private lateinit var db: OwnTVDatabase

    @Before
    fun setUp() {
        db = ownTVTestDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** The case that regressed: a transaction opens and commits instead of throwing on entry. */
    @Test
    fun aTransactionCommitsItsWrites() = runBlocking {
        db.transaction {
            db.profileDao().insert(ProfileEntity(name = "Primary", avatarColor = 0x112233))
            db.profileDao().insert(ProfileEntity(name = "Junior", avatarColor = 0x445566))
        }

        assertEquals(2, db.profileDao().getAllOnce().size)
    }

    /**
     * Still a real transaction, not just a block that runs. Every caller depends on this: an
     * unfavorite writes a tombstone *and* deletes the row, and half of that is worse than neither.
     */
    @Test
    fun aFailureRollsTheWholeBlockBack() = runBlocking {
        runCatching {
            db.transaction {
                db.profileDao().insert(ProfileEntity(name = "Primary", avatarColor = 0x112233))
                error("boom")
            }
        }

        assertEquals(0, db.profileDao().getAllOnce().size)
    }
}
