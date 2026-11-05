package tv.own.owntv.core.categorylock

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tv.own.owntv.core.database.entity.ReminderEntity

/**
 * The first of these due reminders that belongs to [profileId] and whose channel is not in a
 * [sealed] category. [categoryOf] gives a channel's category; a reminder whose channel is gone stays,
 * since it can no longer be tied to a category (and watching it finds nothing to tune).
 */
fun Flow<List<ReminderEntity>>.firstUnsealed(
    profileId: Long,
    sealed: Flow<Set<Long>>,
    categoryOf: suspend (channelId: Long) -> Long?,
): Flow<ReminderEntity?> = combine(this, sealed) { due, ids ->
    due.firstOrNull { it.profileId == profileId && (ids.isEmpty() || categoryOf(it.channelId) !in ids) }
}
