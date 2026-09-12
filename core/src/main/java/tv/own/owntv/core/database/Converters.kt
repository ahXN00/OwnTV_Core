package tv.own.owntv.core.database

import androidx.room.TypeConverter
import tv.own.owntv.core.database.entity.TrendingSnapshotStatus
import tv.own.owntv.core.database.entity.TrendingAttemptStatus
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.model.HlsSupport
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.RecordingFailure
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.tv.TvProviderSurface

/** Stores the app's enums as their stable names (survives reordering). */
class Converters {
    @TypeConverter fun mediaTypeToString(v: MediaType): String = v.name
    @TypeConverter fun stringToMediaType(v: String): MediaType = MediaType.valueOf(v)

    @TypeConverter fun sourceTypeToString(v: SourceType): String = v.name
    @TypeConverter fun stringToSourceType(v: String): SourceType = SourceType.valueOf(v)

    @TypeConverter fun downloadStatusToString(v: DownloadStatus): String = v.name
    @TypeConverter fun stringToDownloadStatus(v: String): DownloadStatus = DownloadStatus.valueOf(v)

    @TypeConverter fun recordingStatusToString(v: RecordingStatus): String = v.name
    @TypeConverter fun stringToRecordingStatus(v: String): RecordingStatus = RecordingStatus.valueOf(v)

    @TypeConverter fun recordingFailureToString(v: RecordingFailure): String = v.name
    @TypeConverter fun stringToRecordingFailure(v: String): RecordingFailure = RecordingFailure.valueOf(v)

    @TypeConverter fun tvProviderSurfaceToString(v: TvProviderSurface): String = v.name
    @TypeConverter fun stringToTvProviderSurface(v: String): TvProviderSurface = TvProviderSurface.valueOf(v)

    @TypeConverter fun trendingSnapshotStatusToString(v: TrendingSnapshotStatus): String = v.name
    @TypeConverter fun stringToTrendingSnapshotStatus(v: String): TrendingSnapshotStatus = TrendingSnapshotStatus.valueOf(v)

    @TypeConverter fun trendingAttemptStatusToString(v: TrendingAttemptStatus): String = v.name
    @TypeConverter fun stringToTrendingAttemptStatus(v: String): TrendingAttemptStatus = TrendingAttemptStatus.valueOf(v)

    // The one enum stored as an INTEGER rather than its name: `sources.hlsSupported` was a boolean
    // column before, and keeping the same affinity with the same 0/1 meanings is what lets it gain a
    // third state without a schema change or a migration. See [HlsSupport].
    @TypeConverter fun hlsSupportToInt(v: HlsSupport): Int = v.code
    @TypeConverter fun intToHlsSupport(v: Int): HlsSupport = HlsSupport.fromCode(v)
}
