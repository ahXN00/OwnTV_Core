package tv.own.owntv.core.database

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

/**
 * Run [block] in one write transaction.
 *
 * **Use this, never `androidx.room.withTransaction`.** That extension is the Support-path one: its
 * body is `beginTransaction()` / `setTransactionSuccessful()` / `endTransaction()`, and
 * `beginTransaction()` goes through `RoomDatabase.openHelper`, which throws outright once a
 * `SQLiteDriver` is configured — "Cannot return a SupportSQLiteOpenHelper since no
 * SupportSQLiteOpenHelper.Factory was configured with Room." `DatabaseModule` configures
 * `BundledSQLiteDriver`, so every call to it fails on entry, at runtime only: the generated DAO code
 * is driver-native and compiles fine either way, and nothing in the build catches the difference.
 *
 * [useWriterConnection] hands the block the pooled writer and puts it in the coroutine context, so
 * the suspend DAO calls inside reuse that same connection instead of deadlocking on a second one.
 * Every suspend DAO write inside the block is itself an inner transaction, which the pooled connection
 * turns into a `SAVEPOINT`. Correct, and fine for a handful of statements — but **never put a bulk DAO
 * insert inside this block**: one savepoint held across thousands of rows makes the bundled SQLite
 * re-walk its whole in-memory journal after every row (7 ms a row on a TV, quadratic). Write a bulk
 * batch through one prepared statement on the connection instead — see `EpgRepository.writeProgrammes`.
 */
suspend fun <R> RoomDatabase.transaction(block: suspend () -> R): R =
    useWriterConnection { it.immediateTransaction { block() } }
