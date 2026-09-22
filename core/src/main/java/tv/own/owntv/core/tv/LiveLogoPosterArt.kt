package tv.own.owntv.core.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/** Makes channel-logo poster art that the launcher can render without cropping. */
class LiveLogoPosterArt(
    private val context: Context,
    private val client: OkHttpClient,
) {
    suspend fun fitInside(source: Uri, aspectRatio: Int): Uri? = withContext(Dispatchers.IO) {
        if (source.scheme !in setOf("http", "https")) return@withContext null
        val destination = File(directory, "${sha256(source.toString())}.png")
        if (destination.isFile) return@withContext uriFor(destination)

        val bitmap = runCatching {
            client.newCall(Request.Builder().url(source.toString()).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body.byteStream().use(BitmapFactory::decodeStream)
            }
        }.getOrNull() ?: return@withContext null
        val fitted = fitInside(bitmap, aspectRatio)
        val written = runCatching {
            directory.mkdirs()
            val temporary = File(directory, "${destination.name}.tmp")
            temporary.outputStream().use { fitted.compress(Bitmap.CompressFormat.PNG, 100, it) }
            temporary.renameTo(destination)
        }.isSuccess
        if (fitted !== bitmap) fitted.recycle()
        bitmap.recycle()
        if (written) uriFor(destination) else null
    }

    private fun uriFor(file: File): Uri = Uri.parse("content://${context.packageName}.owntv-live-logo-art/${file.name}")

    private val directory get() = File(context.cacheDir, DIRECTORY)

    companion object {
        private const val DIRECTORY = "tv-live-logo-art"

        internal fun fitInside(source: Bitmap, aspectRatio: Int): Bitmap {
            val canvas = canvasSize(source.width, source.height, aspectRatio)
            val output = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
            val bounds = fitInsideBounds(source.width, source.height, aspectRatio)
            Canvas(output).drawBitmap(source, null, Rect(bounds.left, bounds.top, bounds.right, bounds.bottom), Paint(Paint.FILTER_BITMAP_FLAG))
            return output
        }

        internal fun fitInsideBounds(sourceWidth: Int, sourceHeight: Int, aspectRatio: Int): Bounds {
            require(sourceWidth > 0 && sourceHeight > 0)
            val canvas = canvasSize(sourceWidth, sourceHeight, aspectRatio)
            val width = sourceWidth
            val height = sourceHeight
            val left = (canvas.width - width) / 2
            val top = (canvas.height - height) / 2
            return Bounds(left, top, left + width, top + height)
        }

        private fun canvasSize(sourceWidth: Int, sourceHeight: Int, aspectRatio: Int): CanvasSize {
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
            val contentWidth = maxOf(sourceWidth, kotlin.math.ceil(sourceHeight * ratio).toInt())
            val width = kotlin.math.ceil(contentWidth / (1f - 2f * PADDING_FRACTION)).toInt()
            val height = kotlin.math.ceil(width / ratio).toInt()
            return CanvasSize(width, height)
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

        private const val PADDING_FRACTION = 0.05f
    }
}

internal data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

private data class CanvasSize(val width: Int, val height: Int)
