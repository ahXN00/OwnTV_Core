package tv.own.owntv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v52 → v53: the per-playlist "Ignore connection limit" switch — `sources.ignoreConnectionLimit`, off
 * for every existing playlist, so nothing changes until the user asks. Additive only. Last hop, so it
 * carries [OwnTVDatabase.healSchema] (standing rule).
 */
val MIGRATION_52_53 = object : Migration(52, 53) {
    override fun migrate(connection: SQLiteConnection) {
        if (!OwnTVDatabase.hasColumn(connection, "sources", "ignoreConnectionLimit")) {
            connection.execSQL("ALTER TABLE `sources` ADD COLUMN `ignoreConnectionLimit` INTEGER NOT NULL DEFAULT 0")
        }
        OwnTVDatabase.healSchema(connection)
    }
}
