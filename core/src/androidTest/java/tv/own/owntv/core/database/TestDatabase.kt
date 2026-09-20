package tv.own.owntv.core.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry

/**
 * An in-memory database built on the engine the app actually ships.
 *
 * **The `setDriver` call is the point of this function.** A builder without one leaves Room in
 * compatibility mode, where the Support path still exists and `androidx.room.withTransaction` works
 * — so a test can pass against behaviour production cannot reach. That is exactly how the
 * `withTransaction` breakage shipped in core-1.0.48: `DatabaseModule` configures
 * `BundledSQLiteDriver`, every test opened a compat-mode database instead, and nothing in the build
 * ever ran the two against each other. Open test databases through here, not through
 * `Room.inMemoryDatabaseBuilder` directly.
 */
fun ownTVTestDatabase(
    context: Context = InstrumentationRegistry.getInstrumentation().targetContext,
): OwnTVDatabase =
    Room.inMemoryDatabaseBuilder(context, OwnTVDatabase::class.java)
        .setDriver(BundledSQLiteDriver())
        .allowMainThreadQueries()
        .build()
