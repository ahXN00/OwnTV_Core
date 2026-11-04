package tv.own.owntv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v50 → v51: the per-playlist "Clean channel names" option — `sources.cleanChannelNames` (off for
 * every existing playlist, so nothing changes until the user asks) and `sources.channelNameStrip`
 * (the user's own words to remove) — plus `channels.providerName`, the provider's name kept beside
 * a cleaned one for guide matching. Additive only: every existing row reads as "not cleaned".
 *
 * Lives outside [OwnTVDatabase] because that file is over its size budget; it is wired into
 * [OwnTVDatabase.ALL_MIGRATIONS] like every other step.
 */
val MIGRATION_50_51 = object : Migration(50, 51) {
    override fun migrate(connection: SQLiteConnection) {
        if (!OwnTVDatabase.hasColumn(connection, "sources", "cleanChannelNames")) {
            connection.execSQL("ALTER TABLE `sources` ADD COLUMN `cleanChannelNames` INTEGER NOT NULL DEFAULT 0")
        }
        if (!OwnTVDatabase.hasColumn(connection, "sources", "channelNameStrip")) {
            connection.execSQL("ALTER TABLE `sources` ADD COLUMN `channelNameStrip` TEXT")
        }
        if (!OwnTVDatabase.hasColumn(connection, "channels", "providerName")) {
            connection.execSQL("ALTER TABLE `channels` ADD COLUMN `providerName` TEXT")
        }
        OwnTVDatabase.healSchema(connection)
    }
}
