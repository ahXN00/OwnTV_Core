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
 * Nesting is safe — Room's pooled connection opens a `SAVEPOINT` for an inner transaction — though
 * no caller currently nests.
 */
suspend fun <R> RoomDatabase.transaction(block: suspend () -> R): R =
    useWriterConnection { it.immediateTransaction { block() } }
