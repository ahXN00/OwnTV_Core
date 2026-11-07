package tv.own.owntv.core.database.dao

/**
 * Where `applyPositionRanges` parks a shifted row until the batch is done. Range statements run one
 * after another, and each shifts whatever sits in its span at that moment, so a row an earlier range
 * moved into a later range's span would be shifted twice. Every range therefore adds this offset,
 * which lifts its rows far above any real position, and one statement takes it off again at the end.
 * Real positions stay below [PARKED_SORT_ORDER] / 2 (a catalogue would need half a billion titles),
 * which is what the final statement keys on.
 */
internal const val PARKED_SORT_ORDER = 1_000_000_000
