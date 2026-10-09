package tv.own.owntv.core.recording

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS `METHOD=AES-128` (RFC 8216 §5.2): each segment is AES-128-CBC with PKCS7 padding, under a
 * 16-byte key fetched from the key URI. Not DRM — the key is a plain URL every player fetches — so
 * the recorder decrypts the way the player does and writes clear MPEG-TS (#243).
 */
object HlsAes {

    /** The segment's IV: the one the key line states, else its media sequence number, big-endian. */
    fun ivFor(segment: HlsMediaPlaylist.Segment): ByteArray {
        val stated = segment.key?.iv?.removePrefix("0x")?.removePrefix("0X")
        if (stated != null && stated.length == IV_BYTES * 2) {
            return ByteArray(IV_BYTES) { stated.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        return ByteBuffer.allocate(IV_BYTES).putLong(0L).putLong(segment.sequence).array()
    }

    fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, ALGORITHM), IvParameterSpec(iv))
            doFinal(data)
        }

    /** An AES-128 key is exactly this long; anything else is not a key (often an error page). */
    const val KEY_BYTES = 16
    private const val IV_BYTES = 16
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
}
