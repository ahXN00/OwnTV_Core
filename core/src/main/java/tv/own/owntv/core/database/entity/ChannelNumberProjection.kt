package tv.own.owntv.core.database.entity

/** A channel's playlist position and number — what numbering a playlist that has none needs (see `PlaylistNumbering`). */
data class ChannelNumberProjection(
    val id: Long,
    val remoteId: String?,
    val sortOrder: Int,
    val number: Int?,
)
