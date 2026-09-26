package tv.own.owntv.core.tv

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Makes channel-logo poster art that the launcher can render without cropping: the logo scaled to fit
 * a fixed canvas of the card's aspect ratio, centred, with a 5% margin.
 *
 * Nothing here runs while a launcher row is being written. [cached] only looks on disk; a logo that
 * is not ready yet is handed to [prepare], which downloads it in the background and calls back once
 * the batch is done, so the row can be republished with the fitted art. Until then the card keeps
 * the logo's own URL, exactly as before.
 */
class LiveLogoPosterArt(
    private val context: Context,
    client: OkHttpClient,
) {
    // A logo host that hangs must not hold a prepare batch for the client's default minutes.
    private val client = client.newBuilder().callTimeout(DOWNLOAD_TIMEOUT_S, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** The fitted art for [source], if it is on disk and not expired; never touches the network. */
    fun cached(source: Uri, aspectRatio: Int): Uri? {
        val file = fileFor(source, aspectRatio)
        if (!file.isFile) return null
        if (System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS) return null
        return uriFor(file)
    }

    /**
     * Fit every logo in [sources] that is not cached yet, in the background. [onReady] runs once, after
     * the batch, and only if at least one new file was written.
     */
    fun prepare(sources: List<Uri>, aspectRatio: Int, onReady: suspend () -> Unit) {
        val missing = sources.distinct().filter { cached(it, aspectRatio) == null && inFlight.add(key(it, aspectRatio)) }
        if (missing.isEmpty()) return
        scope.launch {
            var wrote = false
            try {
                for (source in missing) {
                    if (write(source, aspectRatio)) wrote = true
                }
                if (wrote) trim()
            } finally {
                missing.forEach { inFlight.remove(key(it, aspectRatio)) }
            }
            if (wrote) runCatching { onReady() }
        }
    }

    private fun write(source: Uri, aspectRatio: Int): Boolean {
        val bytes = runCatching {
            client.newCall(Request.Builder().url(source.toString()).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val length = response.body.contentLength()
                if (length > MAX_DOWNLOAD_BYTES) return@use null
                response.body.bytes().takeIf { it.size <= MAX_DOWNLOAD_BYTES }
            }
        }.getOrNull() ?: return false
        val bitmap = decodeCapped(bytes) ?: return false
        val fitted = runCatching { fitInside(bitmap, aspectRatio) }.getOrNull()
        bitmap.recycle()
        fitted ?: return false
        val destination = fileFor(source, aspectRatio)
        val temporary = File(directory, "${destination.name}.tmp")
        val written = runCatching {
            directory.mkdirs()
            temporary.outputStream().use { fitted.compress(Bitmap.CompressFormat.PNG, 100, it) } &&
                (!destination.exists() || destination.delete()) &&
                temporary.renameTo(destination)
        }.getOrDefault(false)
        fitted.recycle()
        if (!written) {
            temporary.delete()
            return false
        }
        enableProvider()
        return true
    }

    /** Decode no larger than the canvas needs: a 4000-pixel logo is sampled down before it is read. */
    private fun decodeCapped(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= CANVAS_LONG_SIDE || bounds.outHeight / (sample * 2) >= CANVAS_LONG_SIDE) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Keep the folder bounded: expired files go, then the oldest beyond [MAX_FILES]. */
    private fun trim() {
        val files = directory.listFiles { f -> f.name.endsWith(".png") }?.sortedByDescending { it.lastModified() } ?: return
        val now = System.currentTimeMillis()
        files.forEachIndexed { index, file ->
            if (index >= MAX_FILES || now - file.lastModified() > MAX_AGE_MS) file.delete()
        }
    }

    /**
     * The provider ships disabled, so an app that never publishes launcher rows — the phone app — never
     * exposes it. The first fitted file turns it on.
     */
    private fun enableProvider() {
        val component = ComponentName(context, LiveLogoArtProvider::class.java)
        val pm = context.packageManager
        if (pm.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return
        runCatching {
            pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        }
    }

    private fun fileFor(source: Uri, aspectRatio: Int) = File(directory, "${key(source, aspectRatio)}.png")

    private fun uriFor(file: File): Uri = Uri.parse("content://${context.packageName}$AUTHORITY_SUFFIX/${file.name}")

    private val directory get() = File(context.cacheDir, DIRECTORY)

    companion object {
        internal const val DIRECTORY = "tv-live-logo-art"
        private const val AUTHORITY_SUFFIX = ".owntv-live-logo-art"
        private const val DOWNLOAD_TIMEOUT_S = 8L
        private const val MAX_DOWNLOAD_BYTES = 5L * 1024 * 1024
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_FILES = 200

        /** The canvas's long side in pixels — plenty for a launcher card, small on disk. */
        internal const val CANVAS_LONG_SIDE = 640
        private const val PADDING_FRACTION = 0.05f

        internal fun fitInside(source: Bitmap, aspectRatio: Int): Bitmap {
            val canvas = canvasSize(aspectRatio)
            val output = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
            val bounds = fitInsideBounds(source.width, source.height, aspectRatio)
            Canvas(output).drawBitmap(source, null, Rect(bounds.left, bounds.top, bounds.right, bounds.bottom), Paint(Paint.FILTER_BITMAP_FLAG))
            return output
        }

        /** Where the logo lands on the canvas: scaled up or down to fit inside the margin, centred. */
        internal fun fitInsideBounds(sourceWidth: Int, sourceHeight: Int, aspectRatio: Int): Bounds {
            require(sourceWidth > 0 && sourceHeight > 0)
            val canvas = canvasSize(aspectRatio)
            val boxWidth = canvas.width * (1f - 2f * PADDING_FRACTION)
            val boxHeight = canvas.height * (1f - 2f * PADDING_FRACTION)
            val scale = minOf(boxWidth / sourceWidth, boxHeight / sourceHeight)
            val width = Math.round(sourceWidth * scale)
            val height = Math.round(sourceHeight * scale)
            val left = (canvas.width - width) / 2
            val top = (canvas.height - height) / 2
            return Bounds(left, top, left + width, top + height)
        }

        internal fun canvasSize(aspectRatio: Int): CanvasSize {
            val ratio = when (aspectRatio) {
                TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9 -> 16f / 9f
                TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_3_2 -> 3f / 2f
                TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_4_3 -> 4f / 3f
                TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_1_1 -> 1f
                TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3 -> 2f / 3f
                TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_MOVIE_POSTER -> 1f / 1.441f
                TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_3_4 -> 3f / 4f
                else -> 16f / 9f
            }
            return if (ratio >= 1f) {
                CanvasSize(CANVAS_LONG_SIDE, Math.round(CANVAS_LONG_SIDE / ratio))
            } else {
                CanvasSize(Math.round(CANVAS_LONG_SIDE * ratio), CANVAS_LONG_SIDE)
            }
        }

        /** One file per logo URL and card shape — 64 hex characters, which the provider checks. */
        private fun key(source: Uri, aspectRatio: Int): String = MessageDigest.getInstance("SHA-256")
            .digest("$aspectRatio|$source".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

internal data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal data class CanvasSize(val width: Int, val height: Int)
