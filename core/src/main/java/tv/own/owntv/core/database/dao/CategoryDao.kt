package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.model.MediaType

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(categories: List<CategoryEntity>): List<Long>

    /** Returns rowids (−1 for IGNOREd conflicts) so the sync can learn new category ids without a re-query. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>): List<Long>

    @Update
    suspend fun updateAll(categories: List<CategoryEntity>)

    /** Grouped by provider (source creation order) first, so multi-source lists don't interleave two
     *  providers' categories. */
    @Query(
        "SELECT categories.* FROM categories " +
            "JOIN sources ON sources.id = categories.sourceId " +
            "WHERE categories.sourceId IN (:sourceIds) AND categories.mediaType = :type " +
            "ORDER BY sources.createdAt ASC, categories.sortOrder ASC, categories.name ASC",
    )
    fun observe(sourceIds: List<Long>, type: MediaType): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories WHERE sourceId IN (:sourceIds) AND mediaType = :type")
    fun count(sourceIds: List<Long>, type: MediaType): Flow<Int>

    @Query("DELETE FROM categories WHERE sourceId = :sourceId AND mediaType = :type")
    suspend fun clear(sourceId: Long, type: MediaType)

    @Query("SELECT * FROM categories WHERE sourceId = :sourceId AND mediaType = :type AND remoteId IN (:remoteIds)")
    suspend fun findByRemoteIds(sourceId: Long, type: MediaType, remoteIds: List<String>): List<CategoryEntity>

    @Query("SELECT remoteId FROM categories WHERE sourceId = :sourceId AND mediaType = :type AND remoteId IS NOT NULL")
    suspend fun remoteIdsForSource(sourceId: Long, type: MediaType): List<String>

    @Query("DELETE FROM categories WHERE sourceId = :sourceId AND mediaType = :type AND remoteId IN (:remoteIds)")
    suspend fun deleteByRemoteIds(sourceId: Long, type: MediaType, remoteIds: List<String>)

    /** A source's film categories that hold no film. Returns how many went. */
    @Query(
        "DELETE FROM categories WHERE sourceId = :sourceId AND mediaType = 'MOVIE' " +
            "AND NOT EXISTS (SELECT 1 FROM movies WHERE movies.categoryId = categories.id)",
    )
    suspend fun deleteEmptyMovieCategories(sourceId: Long): Int

    /** A source's series categories that hold no show. Returns how many went. */
    @Query(
        "DELETE FROM categories WHERE sourceId = :sourceId AND mediaType = 'SERIES' " +
            "AND NOT EXISTS (SELECT 1 FROM series WHERE series.categoryId = categories.id)",
    )
    suspend fun deleteEmptySeriesCategories(sourceId: Long): Int
}
