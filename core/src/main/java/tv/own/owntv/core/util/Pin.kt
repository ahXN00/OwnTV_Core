package tv.own.owntv.core.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * Lightweight salted PIN hashing for profile locks. Not a password vault — a 4-digit PIN has only
 * 10k combinations — but it keeps the PIN out of plaintext storage. Format: `salt:sha256(salt+pin)`,
 * both hex-encoded. Every compare takes the same time however many leading characters match.
 */
object Pin {
    fun hash(pin: String): String {
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return "${salt.toHex()}:${digest(salt, pin)}"
    }

    /** Whether [pin] matches [stored], a value from [hash]. Anything else stored never matches. */
    fun verify(pin: String, stored: String?): Boolean {
        if (stored.isNullOrBlank()) return true // no PIN set
        if (!isHashed(stored)) return false
        val (saltHex, expected) = stored.split(":")
        val salt = saltHex.fromHex() ?: return false
        return sameText(digest(salt, pin), expected)
    }

    /**
     * [verify] for the Customize PIN only, which before hashing (July 2026) was saved as plain digits:
     * such a value is compared as is. Profile and backup PINs were always hashed and use [verify].
     */
    fun verifyCustomize(pin: String, stored: String?): Boolean {
        if (stored.isNullOrBlank() || isHashed(stored)) return verify(pin, stored)
        return sameText(pin, stored)
    }

    /** Whether [stored] has [hash]'s form: an 8-byte hex salt, a colon and a hex SHA-256. */
    fun isHashed(stored: String): Boolean = HASH_FORM.matches(stored)

    private val HASH_FORM = Regex("^[0-9a-fA-F]{16}:[0-9a-fA-F]{64}$")

    private fun sameText(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun digest(salt: ByteArray, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(salt + pin.toByteArray()).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun String.fromHex(): ByteArray? = runCatching {
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }.getOrNull()
}
