package tv.own.owntv.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.model.SourceType

/**
 * v51: "Clean channel names". Every existing playlist comes through with the option off and no
 * custom words, every channel with no provider name and its hash untouched, so the upgrade changes
 * nothing on screen and the next refresh does not rewrite every row. The full upgrade sweep lives
 * in [OwnTVDatabaseMigrationTest]; this pins the v51 defaults.
 */
@RunWith(AndroidJUnit4::class)
class Migration50To51Test {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun migrateVersion50To51_addsChannelNameCleanupOff_keepingSourcesAndChannels() {
        context.deleteDatabase(DB_NAME)
        val db50 = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        try {
            createSchema(db50, "tv.own.owntv.core.database.OwnTVDatabase/50.json")
            db50.execSQL(
                "INSERT INTO sources (id, name, type, url, syncLive, syncMovies, syncSeries, hlsSupported, " +
                    "preferHls, livePrerollSecs, liveLatencyCustomSecs, maxConnections, maxConnectionsProbedAt, createdAt) " +
                    "VALUES (10, 'Playlist', '${SourceType.M3U.name}', 'https://example.test/list.m3u', 1, 1, 1, 0, 0, -1, -1, 0, 0, 2)",
            )
            db50.execSQL(
                "INSERT INTO channels (id, sourceId, name, streamUrl, remoteId, sortOrder, catchup, catchupDays, contentHash) " +
                    "VALUES (30, 10, 'UK: News One HD', 'https://example.test/live.ts', 'key-30', 0, 0, 0, 42)",
            )
            db50.version = 50
        } finally {
            db50.close()
        }

        val db = Room.databaseBuilder(context, OwnTVDatabase::class.java, DB_NAME)
            .addMigrations(*OwnTVDatabase.ALL_MIGRATIONS)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        try {
            // Room migrates lazily; open once so the file below is the migrated one.
            runBlocking { db.useWriterConnection { } }
            BundledSQLiteDriver().open(context.getDatabasePath(DB_NAME).absolutePath).use { sqlite ->
                sqlite.prepare("SELECT name, cleanChannelNames, channelNameStrip FROM sources").use {
                    assertTrue(it.step())
                    assertEquals("Playlist", it.getText(0))
                    assertEquals(0L, it.getLong(1))
                    assertTrue(it.isNull(2))
                }
                sqlite.prepare("SELECT name, contentHash, providerName FROM channels").use {
                    assertTrue(it.step())
                    assertEquals("UK: News One HD", it.getText(0))
                    assertEquals(42L, it.getLong(1))
                    assertTrue(it.isNull(2))
                }
            }
        } finally {
            db.close()
        }
    }

    /** Builds a database at an exported schema version, the way the main migration test does. */
    private fun createSchema(db: SQLiteDatabase, assetPath: String) {
        val json = JSONObject(testContext.assets.open(assetPath).bufferedReader().use { it.readText() })
        val database = json.getJSONObject("database")
        val entities = database.getJSONArray("entities")
        for (entityIndex in 0 until entities.length()) {
            val entity = entities.getJSONObject(entityIndex)
            val tableName = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
            val indices = entity.optJSONArray("indices") ?: continue
            for (index in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(index).getString("createSql").replace("\${TABLE_NAME}", tableName))
            }
        }
        val setupQueries = database.getJSONArray("setupQueries")
        for (index in 0 until setupQueries.length()) {
            db.execSQL(setupQueries.getString(index))
        }
    }

    private companion object {
        const val DB_NAME = "owntv-migration-51-test.db"
    }
}
