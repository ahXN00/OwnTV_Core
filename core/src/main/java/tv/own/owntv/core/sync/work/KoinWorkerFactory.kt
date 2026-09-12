package tv.own.owntv.core.sync.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import org.koin.core.context.GlobalContext
import tv.own.owntv.core.download.DownloadWorker
import tv.own.owntv.core.recording.RecordingWorker

class KoinWorkerFactory : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        val koin = GlobalContext.get()
        return when (workerClassName) {
            EpgSyncWorker::class.java.name -> EpgSyncWorker(
                appContext,
                workerParameters,
                koin.get(),
                koin.get(),
                koin.get(),
                koin.get(),
            )
            TrendingRefreshWorker::class.java.name -> TrendingRefreshWorker(
                appContext,
                workerParameters,
                koin.get(),
                koin.get(),
                koin.get(),
            )
            CatalogSyncWorker::class.java.name -> CatalogSyncWorker(
                appContext,
                workerParameters,
                koin.get(),
                koin.get(),
                koin.get(),
                koin.get(),
                koin.get(),
                koin.get(),
                koin.get(),
                koin.get(),
            )
            ContentIndexWorker::class.java.name -> ContentIndexWorker(
                appContext,
                workerParameters,
                koin.get(),
            )
            DownloadWorker::class.java.name -> DownloadWorker(
                appContext,
                workerParameters,
                koin.get(),
                koin.get(),
            )
            RecordingWorker::class.java.name -> RecordingWorker(
                appContext,
                workerParameters,
                koin.get(),
                koin.get(),
            )
            else -> null
        }
    }
}
