package tv.own.owntv.core.backup

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The `.own` backup container — what [BackupManager.export] writes from v4.2 on.
 *
 * A backup is no longer a bare `owntv-backup.json`. Two reasons:
 *
 * 1. **It has to carry files, not just text.** The Glass effect background image lives in app-private
 *    storage as an absolute path (`filesDir/backgrounds/background_<ts>.jpg`). Backing that *path* up
 *    was useless — it does not exist on the device being restored, so the wallpaper silently came back
 *    blank. The image bytes now ride inside the container.
 * 2. **A plain JSON file exposes everything.** Field-level [BackupCrypto] only ever sealed the
 *    secrets (source/proxy passwords, TMDB key, OpenSubtitles login); playlist URLs, usernames,
 *    profile names and watch history sat next to them in readable text. With a passphrase the whole
 *    container is now encrypted — the file list included.
 *
 * ## Layout
 *
 * - **No passphrase** → a plain ZIP (`PK\x03\x04`): `backup.json` at the root, plus
 *   `wallpaper/<filename>` when a background is set. Secrets are omitted, exactly as before.
 * - **Passphrase** → [MAGIC] + a 4-byte big-endian header length + a plaintext header JSON (the
 *   [BackupCrypto] KDF params: scheme/kdf/iterations/salt) + `iv || ciphertext` of the entire ZIP.
 *   The header must stay plaintext: restore needs the salt and iteration count to derive the key
 *   before it can decrypt anything.
 *
 * The inner `backup.json` is byte-for-byte the schema the old `.json` export used, field-level
 * sealing included. That is deliberate — [BackupManager] builds and parses one JSON shape, and a
 * container is only ever a wrapper around it.
 *
 * Reading is format-sniffing, never extension-trusting: [probe] looks at the first bytes, so a legacy
 * `.json` (or a `.own` a user renamed) restores through the same call.
 *
 * Logging rule, inherited from [BackupCrypto]: nothing here logs the passphrase, key or plaintext.
 */
object BackupContainer {
    /** File magic for an ENCRYPTED container. Plain containers are ordinary ZIPs and have none. */
    private val MAGIC = "OWNTVBK1".toByteArray(Charsets.US_ASCII)
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // "PK"

    const val JSON_ENTRY = "backup.json"
    const val WALLPAPER_DIR = "wallpaper/"

    /**
     * Downloaded/imported subtitle files (v17). Like the wallpaper, these are real files the JSON can
     * only *point* at: `subtitle_cache.cachedPath` is an absolute path into this device's private
     * storage, so restoring the row alone gave the next device a remembered subtitle selection whose
     * file did not exist. Entry names are `<cacheId>_<fileName>`, matched back to their row by the
     * `file` field the manager writes alongside each exported cache row.
     */
    const val SUBTITLE_DIR = "subtitles/"

    /**
     * Profile pictures (v37) — the same problem as the wallpaper and the subtitle files: the JSON can
     * only point at `profiles.avatarPath`, which is an absolute path into *this* device's private
     * storage and means nothing on another one. Without the bytes, restoring a profile on a new
     * television would silently drop the picture the user chose.
     *
     * Entry names are `<profileId>_<fileName>`, matched back by the `avatarFile` field the manager
     * writes alongside each exported profile — the id in the name is the *exported* one, which a
     * restore may map onto a different local profile, so the field is what resolves it, not the name.
     */
    const val AVATAR_DIR = "avatars/"

    /** A file riding inside the container alongside the JSON (today: the background image). */
    data class Asset(val name: String, val bytes: ByteArray) {
        // ByteArray gives identity equals/hashCode, which makes this data class quietly wrong in any
        // set/map. Nothing relies on that today, but the trap is cheap to close.
        override fun equals(other: Any?) =
            other is Asset && name == other.name && bytes.contentEquals(other.bytes)

        override fun hashCode() = 31 * name.hashCode() + bytes.contentHashCode()
    }

    /** The unpacked contents of a backup file, whatever format it arrived in. */
    data class Payload(
        val json: String,
        val wallpaper: Asset?,
        /** Subtitle files, keyed by their container entry name (see [SUBTITLE_DIR]). */
        val subtitles: Map<String, ByteArray> = emptyMap(),
        /** Profile pictures, keyed by their container entry name (see [AVATAR_DIR]). */
        val avatars: Map<String, ByteArray> = emptyMap(),
    )

    /** What a file on disk turns out to be. */
    enum class Kind {
        /** A `.own` ZIP with no passphrase — readable straight away. */
        CONTAINER,

        /** A `.own` sealed with a passphrase — [open] needs the password. */
        ENCRYPTED_CONTAINER,

        /** A pre-4.2 bare `owntv-backup.json`. Still restorable, forever. */
        LEGACY_JSON,

        /** Not something we can read. */
        UNKNOWN,
    }

    /** Sniffs [file] by its leading bytes — the extension is never trusted. */
    fun probe(file: File): Kind {
        val head = runCatching {
            file.inputStream().use { input ->
                ByteArray(MAGIC.size).let { buf ->
                    val n = input.read(buf)
                    if (n <= 0) ByteArray(0) else buf.copyOf(n)
                }
            }
        }.getOrElse { return Kind.UNKNOWN }
        return when {
            head.size >= MAGIC.size && head.copyOf(MAGIC.size).contentEquals(MAGIC) -> Kind.ENCRYPTED_CONTAINER
            head.size >= 4 && head.copyOf(4).contentEquals(ZIP_MAGIC) -> Kind.CONTAINER
            // Anything else non-empty is treated as a legacy JSON file and left to the JSON parser —
            // a file starting with whitespace or a BOM is still perfectly valid JSON.
            head.isNotEmpty() -> Kind.LEGACY_JSON
            else -> Kind.UNKNOWN
        }
    }

    /** Serializes [payload] into container bytes, encrypting the whole thing when [passphrase] is set. */
    fun pack(payload: Payload, passphrase: String?): ByteArray {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zos ->
                zos.putNextEntry(ZipEntry(JSON_ENTRY))
                zos.write(payload.json.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                payload.wallpaper?.let { asset ->
                    zos.putNextEntry(ZipEntry(WALLPAPER_DIR + asset.name))
                    zos.write(asset.bytes)
                    zos.closeEntry()
                }
                payload.subtitles.forEach { (name, bytes) ->
                    zos.putNextEntry(ZipEntry(SUBTITLE_DIR + name))
                    zos.write(bytes)
                    zos.closeEntry()
                }
                payload.avatars.forEach { (name, bytes) ->
                    zos.putNextEntry(ZipEntry(AVATAR_DIR + name))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }.toByteArray()

        val pass = passphrase?.takeIf { it.isNotBlank() } ?: return zip
        val salt = BackupCrypto.newSalt()
        val key = BackupCrypto.deriveKey(pass.toCharArray(), salt, BackupCrypto.ITERATIONS)
        val header = BackupCrypto.cryptoBlock(salt).put("scheme", CONTAINER_SCHEME).toString().toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().also { out ->
            out.write(MAGIC)
            out.write(intToBytes(header.size))
            out.write(header)
            out.write(BackupCrypto.encryptBytes(key, zip))
        }.toByteArray()
    }

    /**
     * Reads [file] in whatever format it is. [passphrase] is only consulted for an encrypted
     * container; a wrong or missing one throws [BackupManager.WrongPasswordException] before anything
     * is parsed, so a bad password can never half-apply a restore.
     */
    fun open(file: File, passphrase: String? = null): Payload = when (probe(file)) {
        Kind.LEGACY_JSON -> Payload(json = file.readText(), wallpaper = null)
        Kind.CONTAINER -> unzip(file.readBytes())
        Kind.ENCRYPTED_CONTAINER -> unzip(decryptContainer(file.readBytes(), passphrase))
        Kind.UNKNOWN -> error("Not an OwnTV backup file")
    }

    private fun decryptContainer(bytes: ByteArray, passphrase: String?): ByteArray {
        val pass = passphrase?.takeIf { it.isNotBlank() } ?: throw BackupManager.WrongPasswordException()
        require(bytes.size > MAGIC.size + 4) { "Truncated backup file" }
        val headerLen = bytesToInt(bytes, MAGIC.size)
        val headerEnd = MAGIC.size + 4 + headerLen
        require(headerLen in 1..MAX_HEADER_BYTES && headerEnd <= bytes.size) { "Corrupt backup header" }
        val header = JSONObject(String(bytes, MAGIC.size + 4, headerLen, Charsets.UTF_8))
        val key = BackupCrypto.deriveKey(pass, header) ?: throw BackupManager.WrongPasswordException()
        // GCM authenticates: a wrong passphrase fails here rather than yielding garbage.
        return runCatching { BackupCrypto.decryptBytes(key, bytes.copyOfRange(headerEnd, bytes.size)) }
            .getOrElse { throw BackupManager.WrongPasswordException() }
    }

    private fun unzip(bytes: ByteArray): Payload {
        var json: String? = null
        var wallpaper: Asset? = null
        val subtitles = LinkedHashMap<String, ByteArray>()
        val avatars = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                when {
                    name == JSON_ENTRY -> json = zis.readBytes().toString(Charsets.UTF_8)
                    // Zip-slip guard: the name is only ever used as a leaf filename, never joined to a
                    // path, but reject traversal outright so a hand-made container can't get creative.
                    name.startsWith(WALLPAPER_DIR) && !entry.isDirectory && !name.contains("..") ->
                        wallpaper = Asset(File(name).name, zis.readBytes())
                    name.startsWith(SUBTITLE_DIR) && !entry.isDirectory && !name.contains("..") ->
                        subtitles[File(name).name] = zis.readBytes()
                    name.startsWith(AVATAR_DIR) && !entry.isDirectory && !name.contains("..") ->
                        avatars[File(name).name] = zis.readBytes()
                }
                zis.closeEntry()
            }
        }
        return Payload(
            json = json ?: error("Not an OwnTV backup file"),
            wallpaper = wallpaper,
            subtitles = subtitles,
            avatars = avatars,
        )
    }

    private fun intToBytes(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun bytesToInt(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or
            (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)

    /** Sanity bound so a corrupt length field can't make us allocate wildly. */
    private const val MAX_HEADER_BYTES = 8 * 1024
    private const val CONTAINER_SCHEME = "container-aes-gcm"
}
