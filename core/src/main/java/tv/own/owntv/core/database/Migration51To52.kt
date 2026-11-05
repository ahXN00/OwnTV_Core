package tv.own.owntv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v51 → v52: drops the single-column `(sourceId)` and `(categoryId)` indexes of `channels`, `movies`
 * and `series`, and `channels (name)`. Every query they served is served as well by a composite that
 * starts with the same column (checked with EXPLAIN QUERY PLAN over every catalogue query, for one
 * playlist and for several), and `channels (name)` served none. Each is one more B-tree that every
 * insert, update and delete of a 100k-row catalogue has to keep. Dropping an index rewrites no rows.
 * Last hop, so it carries [OwnTVDatabase.healSchema] (standing rule).
 */
val MIGRATION_51_52 = object : Migration(51, 52) {
    override fun migrate(connection: SQLiteConnection) {
        for (table in listOf("channels", "movies", "series")) {
            connection.execSQL("DROP INDEX IF EXISTS `index_${table}_sourceId`")
            connection.execSQL("DROP INDEX IF EXISTS `index_${table}_categoryId`")
        }
        connection.execSQL("DROP INDEX IF EXISTS `index_channels_name`")
        OwnTVDatabase.healSchema(connection)
    }
}
