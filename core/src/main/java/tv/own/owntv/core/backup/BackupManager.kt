package tv.own.owntv.core.backup

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.FOLLOW_GLOBAL_LATENCY_SECS
import tv.own.owntv.core.database.entity.FOLLOW_GLOBAL_PREROLL
import tv.own.owntv.core.model.HlsSupport
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Phase 12 — backup & restore of the painful-to-re-enter setup: **profiles** (name/avatar/kids/PIN),
 * **sources** (URLs + credentials + per-source UA) and their profile links, plus per-profile
 * **customizations** (hidden/renamed/reordered categories & channels), favorites, watch history,
 * resume positions and manual Move positions — as a JSON file. The user picks which [Section]s to
 * include on export and which
 * to apply on restore. Content (channels/movies/series) is NOT backed up — it's large and re-syncs
 * from the sources after restore. Profile/source ids are preserved on restore, so customization keys
 * stay valid.
 */
class BackupManager(
    private val profileDao: ProfileDao,
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
    private val customize: CustomizationStore,
    private val userData: UserDataResolver,
    private val epgSources: tv.own.owntv.core.epg.EpgSourceStore,
    private val forceMpvStore: tv.own.owntv.core.player.ForceMpvStore,
    private val vodEngineStore: tv.own.owntv.core.player.VodEngineStore,
    private val db: tv.own.owntv.core.database.OwnTVDatabase,
    private val tmdbOverrides: tv.own.owntv.core.metadata.MetadataOverrideStore,
    private val metadataDao: tv.own.owntv.core.database.dao.MetadataDao,
    private val openSubAuth: tv.own.owntv.core.subtitles.OpenSubtitlesAuthStore,
    /** `filesDir/backgrounds` — where the Glass effect wallpaper lives, so a backup can carry it. */
    private val backgroundsDir: File,
    /** `filesDir/subtitles` — the shared subtitle file cache (see SubtitleRepository), carried too. */
    private val subtitlesDir: File,
    /** Writes a restored profile picture into `filesDir/avatars`; the one thing that puts files there. */
    private val avatarStore: tv.own.owntv.core.profile.ProfileAvatarStore,
) {
    /** What a backup can contain; the user multi-selects these for export and restore. Profiles are
     *  NOT a section: every backup is inherently profile-based — the export flow's first step picks
     *  which profiles to include (PIN-verified for locked non-active ones), and the ticked profiles'
     *  rows always ride in the file. */
    enum class Section {
        SOURCES,
        CUSTOMIZE,
        FAVORITES,
        HISTORY,
        RESUME,
        MANUAL_REORDER,
        SETTINGS,
    }

    /**
     * Writes the chosen [sections] into [folder] as `owntv-backup.own`; returns the file path.
     *
     * The output is a [BackupContainer]: the same backup JSON this class has always produced, plus
     * the Glass effect wallpaper's actual bytes when one is set. Exporting bare `.json` is gone (the
     * path in it was device-local and its contents were readable to anyone) — restore still accepts
     * old `.json` files, and always will.
     *
     * Secret fields (source passwords, proxy password) are NEVER written as plaintext. When
     * [backupPassword] is a non-blank passphrase, they are encrypted field-by-field (AES-GCM), a
     * root `crypto` block records the KDF params, **and the whole container is sealed with the same
     * passphrase** so nothing inside — URLs, usernames, history, the file list — is readable without
     * it. When it is null/blank, secrets are simply omitted and the container is a plain ZIP; the
     * caller is expected to have warned the user that passwords must be re-entered after restore.
     */
    suspend fun export(
        folder: File,
        sections: Set<Section> = Section.entries.toSet(),
        backupPassword: String? = null,
        /** Profiles to include (PIN-authorized by the caller). Null = all (legacy callers/tests). */
        profileIds: Set<Long>? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            // Profile scoping: only the ticked profiles' rows and per-profile data enter the file.
            val allProfiles = profileDao.getAllOnce()
            val profiles = if (profileIds == null) allProfiles else allProfiles.filter { it.id in profileIds }
            val pids = profiles.map { it.id }.toSet()
            val pidKeys = pids.map { it.toString() }.toSet()
            val allLinks = sourceDao.allLinks().filter { it.profileId in pids }
            val linkedSourceIds = allLinks.map { it.sourceId }.toSet()
            // Set up field encryption only if a passphrase was provided.
            val pass = backupPassword?.takeIf { it.isNotBlank() }
            val salt = if (pass != null) BackupCrypto.newSalt() else null
            val key = if (pass != null && salt != null) BackupCrypto.deriveKey(pass.toCharArray(), salt, BackupCrypto.ITERATIONS) else null
            val seal: ((String) -> JSONObject)? = key?.let { k -> { plain -> BackupCrypto.encrypt(k, plain) } }

            val root = JSONObject().apply {
                put("version", 21) // v21: "tombstones" — the user data the user DELETED, so a merge (restore or local sync) does not reinstate it. Older readers ignore the block and behave exactly as they do today. v20: playbackPrefs carry the per-item A/V-sync offset. v19: sources carry the per-playlist Live TV engine and Live latency overrides. v18: per-profile specific-channel startup targets. v17: startupModes/customizePins moved SOURCES→SETTINGS (readers accept both); PIN hashes and legacy URL-shaped player keys are encrypted-only; sources carry preferHls/livePrerollSecs/hlsSupported; source-keyed blocks scoped to the ticked profiles' sources. v16: optional Stalker serial/device IDs/signature. v15: custom category membership (issue #87) rides userData as kind "member"; customCategories blobs pass through unremapped. v14: .own container (wallpaper rides along). v13: sources.syncLive/Movies/Series. v12: per-profile OpenSubtitles login (encrypted-only). v11: profile-scoped export. v10: sources.mac. v9: custom TMDB names, encrypted TMDB key
                put("sections", JSONArray().apply { sections.forEach { put(it.name) } })
                if (salt != null) put("crypto", BackupCrypto.cryptoBlock(salt))
                // Ticked profiles always ride (backup is profile-based); restore needs SOURCES to apply them.
                put("profiles", JSONArray().apply { profiles.forEach { put(profileJson(it, seal)) } })
                // Which of them was in use. A fresh device used to adopt whichever profile happened to
                // come first out of the id map, so a two-profile household could land on the kids
                // profile after a restore. Only recorded when that profile is actually in the file.
                settings.activeProfileId.first().takeIf { it in pids }?.let { put("activeProfileId", it) }
                if (Section.SOURCES in sections) {
                    // Only sources at least one ticked profile uses, and only ticked profiles' links.
                    put("sources", JSONArray().apply { sourceDao.getAllOnce().filter { it.id in linkedSourceIds }.forEach { put(sourceJson(it, seal)) } })
                    put("links", JSONArray().apply { allLinks.forEach { put(JSONObject().put("profileId", it.profileId).put("sourceId", it.sourceId)) } })
                    put("epgSources", epgSources.exportJson()) // standalone EPG feeds ride with sources
                    // Per-source auto-refresh selections + default source, keyed by the preserved ids.
                    // Scoped to the ticked profiles' sources: an id belonging to a source that is NOT in
                    // this file cannot be remapped on restore, and an unmapped numeric id silently
                    // matches whatever unrelated source happens to hold it on the target device.
                    put("playlistAutoRefresh", filterBySourceId(settings.exportPlaylistAutoRefresh(), linkedSourceIds))
                    put("epgAutoRefresh", settings.exportEpgAutoRefresh())
                    put("epgUseLogos", settings.exportEpgUseLogos())
                    settings.currentDefaultSourceId()?.takeIf { it in linkedSourceIds }?.let { put("defaultSourceId", it) }
                }
                if (Section.CUSTOMIZE in sections) {
                    // Customization entries are keyed "cust_<profileId>_<TYPE>" — keep ticked profiles only.
                    put(
                        "customizations",
                        JSONObject().apply {
                            customize.exportAll()
                                .filterKeys { k -> k.removePrefix("cust_").substringBefore('_') in pidKeys }
                                .forEach { (k, v) -> put(k, v) }
                        },
                    )
                    put("homeConfigs", filterByProfile(settings.exportHomeConfigs(), pidKeys))
                    put("hideNewCategories", filterByProfile(settings.exportHideNewCategories(), pidKeys))
                    // User-corrected TMDB titles/years. Keyed by "type:sourceId:remoteId|name", remapped
                    // on restore — so, like every other source-keyed block, only the ticked profiles'
                    // sources ride: an entry whose source is absent from the file has no id to remap to.
                    filterTmdbOverridesBySourceId(tmdbOverrides.exportJson(), linkedSourceIds)
                        .takeIf { it.isNotBlank() }?.let { put("tmdbOverrides", it) }
                }
                // Favorites / history / resume positions, exported with stable keys (see UserDataResolver),
                // filtered to the ticked profiles (each record carries its owner in "p").
                val kinds = kindsFor(sections)
                if (kinds.isNotEmpty()) {
                    val all = userData.exportAll(kinds)
                    val filtered = JSONArray()
                    for (i in 0 until all.length()) {
                        val e = all.getJSONObject(i)
                        if (e.optLong("p", -1) in pids) filtered.put(e)
                    }
                    put("userData", filtered)
                    // The deletions that go with them (v21). A file carrying only the surviving rows
                    // is indistinguishable from one written before the user removed anything, so a
                    // merge — which is what both restore and local sync are — silently reinstates
                    // every favorite and history entry they have ever deleted.
                    userData.exportTombstones(kinds.intersect(UserDataResolver.TOMBSTONE_KINDS), pids)
                        .takeIf { it.length() > 0 }?.let { put("tombstones", it) }
                }
                if (Section.SETTINGS in sections) {
                    val s = settings.exportSettings() // non-secret keys, incl. proxy host/port/user/enabled
                    // Proxy password rides here as an encrypted object (key not in the settings whitelist,
                    // so importSettings ignores it); omitted entirely when there is no passphrase.
                    val proxyPass = settings.currentProxyPassword()
                    if (seal != null && proxyPass.isNotEmpty()) s.put("proxy_pass_enc", seal(proxyPass))
                    // The user's own TMDB API key: same secret policy — encrypted with a passphrase, else omitted.
                    val tmdbKey = settings.currentTmdbApiKey()
                    if (seal != null && tmdbKey.isNotEmpty()) s.put("tmdb_key_enc", seal(tmdbKey))
                    val openSubtitlesKey = settings.currentOpenSubtitlesApiKey()
                    if (seal != null && openSubtitlesKey.isNotEmpty()) s.put("opensub_api_key_enc", seal(openSubtitlesKey))
                    put("settings", s)
                    // Per-profile landing screen + the Customize PIN lock. These moved out of the
                    // SOURCES block in v17: neither is a playlist or a credential, so a user who
                    // deselected "Sources" was silently dropping them. Readers accept both places.
                put("startupModes", filterByProfile(settings.exportStartupModes(), pidKeys))
                put("startupChannels", filterByProfile(settings.exportStartupChannels(), pidKeys))
                    // The Customize PIN is stored as a salted hash, but a 4-digit PIN behind one SHA-256
                    // pass is seconds of offline brute force — so it follows the same rule as every other
                    // secret: encrypted with the backup passphrase, or left out entirely.
                    if (seal != null) {
                        put("customizePins", sealValues(filterByProfile(settings.exportCustomizePins(), pidKeys), seal))
                    }
                    // Per-item "compatibility mode" engine pins (Live + VOD).
                    //
                    // These are keyed by [enginePinKey] — "<sourceId>:<MEDIA_TYPE>:<remoteId>" — so the
                    // source id DOES have to be remapped on restore (the older comment here claimed they
                    // were stream-URL keyed; that stopped being true when P6 introduced the stable key,
                    // and an unremapped id silently pinned the wrong source's items). Rows are scoped to
                    // the ticked profiles' sources for the same reason. Keys still in the legacy
                    // stream-URL shape carry no source id and frequently embed the account's username
                    // and password, so they ride only when there is a passphrase.
                    val urlKeysAllowed = seal != null
                    put("compatMode", JSONObject().apply {
                        put("liveMpvUrls", JSONArray(filterEnginePinKeys(forceMpvStore.exportUrls(), linkedSourceIds, urlKeysAllowed)))
                        put("liveExoUrls", JSONArray(filterEnginePinKeys(forceMpvStore.exportExoUrls(), linkedSourceIds, urlKeysAllowed)))
                        put("vodMpvUrls", JSONArray(filterEnginePinKeys(vodEngineStore.exportMpvUrls(), linkedSourceIds, urlKeysAllowed)))
                        put("vodExoUrls", JSONArray(filterEnginePinKeys(vodEngineStore.exportExoUrls(), linkedSourceIds, urlKeysAllowed)))
                    })
                    // Per-item zoom / volume (DB v32). Same key shape as compatMode — [enginePinKey] —
                    // so BOTH ids travel and both are remapped on restore: the profile id (these rows
                    // are per profile) and the source id inside `contentKey`. Scoped to the ticked
                    // profiles and their sources; legacy stream-URL keys need a passphrase, exactly as
                    // in compatMode above. Optional block: older readers skip it.
                    run {
                        val ticked = profiles.map { it.id }.toSet()
                        val rows = runCatching { db.playbackPrefsDao().getAllOnce() }.getOrDefault(emptyList())
                        put("playbackPrefs", JSONArray().apply {
                            rows.filter { row ->
                                row.profileId in ticked &&
                                    filterEnginePinKeys(listOf(row.contentKey), linkedSourceIds, urlKeysAllowed).isNotEmpty()
                            }.forEach { row ->
                                put(
                                    JSONObject().apply {
                                        put("p", row.profileId)
                                        put("k", row.contentKey)
                                        row.zoomMode?.let { put("z", it) }
                                        row.volumeBoost?.let { put("v", it) }
                                        row.audioDelayMs?.let { put("d", it) }
                                    },
                                )
                            }
                        })
                    }
                    // Per-profile OpenSubtitles login (username + password/token). A secret: the whole
                    // session blob is encrypted with the backup passphrase, so it rides ONLY when one is
                    // set — omitted otherwise, exactly like the source/proxy/TMDB secrets. Ticked profiles only.
                    if (seal != null) {
                        put("openSubtitles", JSONArray().apply {
                            profiles.forEach { p ->
                                openSubAuth.exportJson(p.id)?.let { blob ->
                                    put(JSONObject().put("p", p.id).put("session", seal(blob.toString())))
                                }
                            }
                        })
                    }
                }
            }
            // Profile pictures, bytes and all, for the same reason as the wallpaper below: the path in
            // the profile row is this device's and means nothing on another one.
            val avatarFiles = exportAvatars(root, profiles)

            // The wallpaper's bytes, not its path. `settings.bg_image_path` still rides in the settings
            // block, but it points into THIS device's filesDir — restoring it verbatim gave the next
            // device a dangling path and a blank background. Import re-derives the path from this entry.
            val wallpaper = if (Section.SETTINGS in sections) currentWallpaper() else null
            wallpaper?.let { root.put("wallpaper", it.name) }

            // External subtitles: the remembered selection, the saved timing offsets, the per-title
            // links AND the files themselves. Same reasoning as the wallpaper — `cachedPath` is an
            // absolute path into this device's private storage, so the rows alone restore a selection
            // whose file does not exist. Files ride as container entries; see [exportSubtitles].
            val subtitleFiles =
                if (Section.SETTINGS in sections) exportSubtitles(root, pids, linkedSourceIds) else emptyMap()

            if (!folder.exists()) folder.mkdirs()
            val target = File(folder, BACKUP_FILENAME)
            val path = writeAtomically(
                target,
                BackupContainer.pack(
                    BackupContainer.Payload(root.toString(2), wallpaper, subtitleFiles, avatarFiles),
                    backupPassword,
                ),
            )
            // Recorded here and nowhere else: after the atomic rename, so a throw anywhere above
            // leaves the previous date standing rather than claiming a backup that does not exist.
            settings.recordBackup(
                at = System.currentTimeMillis(),
                bytes = target.length(),
                encrypted = pass != null,
                // Where it went, so "is my backup on the USB stick or in app storage?" has an answer
                // without opening a file browser.
                path = target.absolutePath,
            )
            path
        }
    }

    /**
     * Writes the `subtitles` block into [root] and returns the subtitle files to pack alongside it.
     *
     * What rides: the per-profile remembered selection, the saved timing offsets, the per-title links,
     * and the cached files those rows point at. Scoped like every other per-item block — [pids] for
     * the ticked profiles, [linkedSourceIds] for their sources (the content keys are
     * "movie:<sourceId>:…" / "episode:<sourceId>:…", so an unscoped row could not be remapped).
     *
     * Bounded on purpose. A heavy user's subtitle cache is unbounded, and a backup must stay under the
     * companion link's upload limit alongside an 8 MB wallpaper — so files are taken most-recently-used
     * first up to [MAX_SUBTITLE_BYTES], and any row whose file is missing or did not fit is dropped
     * along with the selections/links that reference it. A restore is then simply missing that
     * subtitle, never holding a selection pointing at a file that was never packed.
     */
    private suspend fun exportSubtitles(
        root: JSONObject,
        pids: Set<Long>,
        linkedSourceIds: Set<Long>,
    ): Map<String, ByteArray> {
        val dao = db.subtitleDao()
        fun inScope(profileId: Long, contentKey: String): Boolean =
            profileId in pids && contentKey.split(':').getOrNull(1)?.toLongOrNull() in linkedSourceIds

        val selections = runCatching { dao.allSelectionsOnce() }.getOrDefault(emptyList())
            .filter { inScope(it.profileId, it.contentKey) }
        val timings = runCatching { dao.allTimingsOnce() }.getOrDefault(emptyList())
            .filter { inScope(it.profileId, it.contentKey) }
        val links = runCatching { dao.allLinksOnce() }.getOrDefault(emptyList())
            .filter { inScope(it.profileId, it.contentKey) }
        if (selections.isEmpty() && timings.isEmpty() && links.isEmpty()) return emptyMap()

        // Only the cache rows those in-scope rows actually reference. A timing key of
        // "local:<cacheId>" names one too — an imported local file with a saved offset.
        val wanted = buildSet {
            selections.forEach { s -> s.cacheId?.let { add(it) } }
            links.forEach { add(it.cacheId) }
            timings.forEach { t -> t.subtitleKey.removePrefix(LOCAL_SUB_PREFIX)
                .takeIf { t.subtitleKey.startsWith(LOCAL_SUB_PREFIX) }?.toLongOrNull()?.let { add(it) } }
        }
        if (wanted.isEmpty()) return emptyMap()

        val files = LinkedHashMap<String, ByteArray>()
        val packed = HashMap<Long, String>() // cacheId → container entry name
        var budget = MAX_SUBTITLE_BYTES
        val cacheRows = runCatching { dao.allCacheOnce() }.getOrDefault(emptyList())
            .filter { it.id in wanted }
            .sortedByDescending { it.lastUsedAt }
        val exportedCache = JSONArray()
        for (row in cacheRows) {
            val file = File(row.cachedPath)
            if (!file.isFile) continue
            val length = file.length()
            if (length !in 1..budget) continue
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            // Entry name carries the row id so two titles' "movie.srt" never collide. Leaf name only —
            // BackupContainer rejects traversal, but never hand it a separator in the first place.
            val entry = "${row.id}_${File(row.fileName).name}"
            files[entry] = bytes
            packed[row.id] = entry
            budget -= length
            exportedCache.put(
                JSONObject().apply {
                    put("id", row.id); put("source", row.source)
                    row.openSubFileId?.let { put("openSubFileId", it) }
                    row.language?.let { put("language", it) }
                    row.languageName?.let { put("languageName", it) }
                    row.releaseName?.let { put("releaseName", it) }
                    row.format?.let { put("format", it) }
                    put("hearingImpaired", row.hearingImpaired)
                    put("fileName", row.fileName); put("lastUsedAt", row.lastUsedAt)
                    put("file", entry)
                },
            )
        }
        if (exportedCache.length() == 0) return emptyMap()

        root.put(
            "subtitles",
            JSONObject().apply {
                put("cache", exportedCache)
                // An "Off" selection is meaningful with no file behind it; a selection naming a file we
                // could not pack is not, so it is dropped rather than restored as a dangling reference.
                put("selections", JSONArray().apply {
                    selections.filter { it.cacheId == null || it.cacheId in packed }.forEach { s ->
                        put(
                            JSONObject().apply {
                                put("p", s.profileId); put("k", s.contentKey)
                                s.cacheId?.let { put("c", it) }
                                put("off", s.off); put("u", s.updatedAt)
                            },
                        )
                    }
                })
                put("timings", JSONArray().apply {
                    timings.filter { t ->
                        !t.subtitleKey.startsWith(LOCAL_SUB_PREFIX) ||
                            t.subtitleKey.removePrefix(LOCAL_SUB_PREFIX).toLongOrNull() in packed
                    }.forEach { t ->
                        put(
                            JSONObject().apply {
                                put("p", t.profileId); put("k", t.contentKey); put("s", t.subtitleKey)
                                put("o", t.offsetMs); put("u", t.updatedAt)
                            },
                        )
                    }
                })
                put("links", JSONArray().apply {
                    links.filter { it.cacheId in packed }.forEach { l ->
                        put(
                            JSONObject().apply {
                                put("p", l.profileId); put("k", l.contentKey); put("c", l.cacheId)
                                put("t", l.mediaType); put("n", l.contentTitle); put("a", l.addedAt)
                            },
                        )
                    }
                })
            },
        )
        return files
    }

    /**
     * Pack each exported profile's own picture into the container, and note the entry name on that
     * profile's JSON so restore can find it again (`avatarFile`).
     *
     * The same reasoning as the wallpaper: `profiles.avatarPath` points into this device's private
     * storage, so a profile restored onto another television would come back wearing the drawn tile
     * with no sign that a picture was ever chosen. A profile with no picture, or one whose file has
     * gone, simply contributes nothing.
     *
     * Nothing here needs a size cap the way the wallpaper does — `ProfileAvatarStore` has already
     * scaled every one of these to at most 512 px, so a whole household's pictures are a few hundred
     * kilobytes.
     */
    private fun exportAvatars(root: JSONObject, profiles: List<ProfileEntity>): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        val byId = (root.optJSONArray("profiles") ?: return files).let { array ->
            (0 until array.length()).associate { array.getJSONObject(it).optLong("id") to array.getJSONObject(it) }
        }
        profiles.forEach { profile ->
            val path = profile.avatarPath?.takeIf { it.isNotBlank() } ?: return@forEach
            val file = File(path)
            if (!file.isFile) return@forEach
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
            val entry = "${profile.id}_${file.name}"
            files[entry] = bytes
            byId[profile.id]?.put("avatarFile", entry)
        }
        return files
    }

    /**
     * Write a restored profile's picture into this device's own storage and point the row at it.
     * [localProfileId] is the id the profile has *here*, which a merge by name may well have changed
     * from the one in the file — the entry is found by the `avatarFile` field, never by the id in it.
     *
     * Returns the new absolute path, or null when the backup carried no picture for this profile, in
     * which case the caller leaves whatever the device already had alone.
     */
    private suspend fun restoreAvatar(
        profileJson: JSONObject,
        localProfileId: Long,
        avatars: Map<String, ByteArray>,
    ): String? {
        val entry = profileJson.optString("avatarFile").takeIf { it.isNotEmpty() } ?: return null
        val bytes = avatars[entry] ?: return null
        return runCatching { avatarStore.save(localProfileId, bytes.inputStream()) }
            .onFailure { Log.w(TAG, "Profile picture restore failed: ${it.message}") }
            .getOrNull()
    }

    /** The current Glass effect background as a container asset, or null when unset/missing/oversized. */
    private suspend fun currentWallpaper(): BackupContainer.Asset? {
        val path = settings.bgImagePath.first().trim()
        if (path.isEmpty()) return null
        val file = File(path)
        // Size cap: the wallpaper is a user-picked file and a backup the user may send over the
        // companion link. A 100 MB TIFF must not silently become a 100 MB backup.
        if (!file.isFile || file.length() !in 1..MAX_WALLPAPER_BYTES) return null
        return runCatching { BackupContainer.Asset(file.name, file.readBytes()) }.getOrNull()
    }

    /**
     * Writes the wallpaper carried by a restored container into `filesDir/backgrounds` and points the
     * setting at it. Mirrors `ingestBackgroundImage`: the folder is wiped first so it never
     * accumulates, and the filename keeps a fresh timestamp so Coil's path-keyed cache and the
     * settings Flow both see a genuinely new value.
     *
     * When the file carries no wallpaper (legacy `.json`, or a backup made with no background set),
     * the restored `bg_image_path` is a path from another device: cleared unless it happens to exist.
     */
    private suspend fun applyWallpaper(asset: BackupContainer.Asset?) {
        if (asset == null) {
            val restored = settings.bgImagePath.first().trim()
            if (restored.isNotEmpty() && !File(restored).isFile) settings.setBgImagePath("")
            return
        }
        runCatching {
            if (!backgroundsDir.exists()) backgroundsDir.mkdirs()
            backgroundsDir.listFiles()?.forEach { it.delete() }
            val ext = File(asset.name).extension.ifBlank { "png" }.lowercase()
            val dest = File(backgroundsDir, "background_${System.currentTimeMillis()}.$ext")
            dest.writeBytes(asset.bytes)
            settings.setBgImagePath(dest.absolutePath)
        }.onFailure { Log.w(TAG, "Wallpaper restore failed: ${it.message}") }
    }

    /**
     * Read a backup file in any supported format ([BackupContainer.Kind]), falling back to the
     * rotated `.bak` when the primary is unreadable — the case an interrupted pre-atomic export used
     * to leave behind. A [WrongPasswordException] from a sealed container is rethrown as-is rather
     * than triggering the `.bak` fallback: the file is fine, the password isn't.
     */
    private fun readBackup(file: File, password: String?): Pair<JSONObject, BackupContainer.Payload> {
        fun read(f: File): Pair<JSONObject, BackupContainer.Payload> =
            BackupContainer.open(f, password).let { JSONObject(it.json) to it }

        val primary = runCatching { read(file) }
        primary.getOrNull()?.let { return it }
        (primary.exceptionOrNull() as? WrongPasswordException)?.let { throw it }
        val bak = File(file.parentFile, "${file.name}$BAK_SUFFIX")
        if (bak.exists()) {
            runCatching { read(bak) }.getOrNull()?.let { return it }
        }
        throw primary.exceptionOrNull() ?: IllegalStateException()
    }

    /**
     * Result of inspecting a backup file: which sections it holds, whether secrets are encrypted, and
     * whether the whole file was sealed ([sealed]).
     *
     * [sealed] drives the restore UI's order. A field-encrypted backup can be inspected without the
     * password (only the secrets are opaque), so the user picks sections first and the password
     * afterwards — and may skip it. A sealed container reveals nothing at all until it is decrypted,
     * so the password comes FIRST and cannot be skipped.
     */
    data class Inspection(val sections: Set<Section>, val encrypted: Boolean, val sealed: Boolean = false)

    /** True when [file] is a container that cannot be inspected at all without the backup password. */
    suspend fun isSealed(file: File): Boolean = withContext(Dispatchers.IO) {
        BackupContainer.probe(file) == BackupContainer.Kind.ENCRYPTED_CONTAINER
    }

    /**
     * Outcome of a restore: how many rows/entries were applied, and how many `sources[]` entries were
     * left out because this build doesn't know their [SourceType] (B4 — see [sourceFrom]).
     */
    data class ImportSummary(
        val items: Int,
        val skippedSources: Int = 0,
        /** Optional locale field was present but not in the SupportedLocales catalogue. */
        val invalidLocale: Boolean = false,
    )

    /** Thrown when a backup is encrypted and the supplied passphrase is wrong (or missing where required). */
    class WrongPasswordException : Exception()

    /**
     * What a backup file contains + whether it carries encrypted secrets (older files have no
     * "sections"). [password] is required only for a sealed container — see [isSealed].
     */
    suspend fun sectionsIn(file: File, password: String? = null): Result<Inspection> = withContext(Dispatchers.IO) {
        runCatching {
            val sealed = BackupContainer.probe(file) == BackupContainer.Kind.ENCRYPTED_CONTAINER
            val (root, _) = readBackup(file, password)
            val out = mutableSetOf<Section>()
            // v11+ files always carry "profiles" (backup is profile-based); only "sources" marks the
            // Sources section. Older files wrote both together, so this reads them identically.
            if (root.has("sources")) out += Section.SOURCES
            if (
                root.optJSONObject("customizations")?.keys()?.hasNext() == true ||
                root.optJSONObject("homeConfigs")?.keys()?.hasNext() == true ||
                root.optJSONObject("hideNewCategories")?.keys()?.hasNext() == true ||
                root.optString("tmdbOverrides").isNotBlank()
            ) out += Section.CUSTOMIZE
            if (root.optJSONObject("settings")?.keys()?.hasNext() == true) out += Section.SETTINGS
            root.optJSONArray("userData")?.let { arr ->
                for (i in 0 until arr.length()) {
                    when (arr.getJSONObject(i).optString("kind")) {
                        "fav" -> out += Section.FAVORITES
                        "his" -> out += Section.HISTORY
                        "prog" -> out += Section.RESUME
                        "order", "sort", "member" -> out += Section.MANUAL_REORDER
                    }
                }
            }
            if (out.isEmpty()) error("backup_invalid")
            Inspection(out, encrypted = root.has("crypto"), sealed = sealed)
        }
    }

    /**
     * What [import] would change, counted without changing anything.
     *
     * Local sync exists to move a household's data between two devices that both have real data on
     * them, and the one outcome that must never happen is someone tapping the wrong direction and
     * finding out afterwards. So the counts here are per section and phrased as *change*, not as
     * volume: how many rows would appear that are not here now, and how many would be removed.
     *
     * Reads only. Every lookup mirrors what [import] does — profiles matched by name, sources by
     * type/URL/username — so the numbers are the same ones the apply will produce, minus anything
     * that lands after a sync of the catalogue (a favorite whose channel this device has not
     * downloaded yet counts as new, and heals into place later exactly as a restore's does).
     */
    data class Preview(
        val newProfiles: Int = 0,
        val newSources: Int = 0,
        val newFavorites: Int = 0,
        val newHistory: Int = 0,
        val newResume: Int = 0,
        val newReorder: Int = 0,
        val changedSettings: Int = 0,
        val hasCustomizations: Boolean = false,
        /** Rows this device would LOSE, because the other device deleted them more recently. */
        val deletions: Int = 0,
    ) {
        val isEmpty: Boolean
            get() = newProfiles == 0 && newSources == 0 && newFavorites == 0 && newHistory == 0 &&
                newResume == 0 && newReorder == 0 && changedSettings == 0 && !hasCustomizations && deletions == 0
    }

    suspend fun previewImport(
        file: File,
        sections: Set<Section> = Section.entries.toSet(),
        backupPassword: String? = null,
    ): Result<Preview> = withContext(Dispatchers.IO) {
        runCatching {
            val (root, _) = readBackup(file, backupPassword)
            // Secrets stay sealed: a preview never needs to read a password, so it never derives a key.
            val noSecrets: (Any?) -> String? = { v -> (v as? String)?.takeIf { it.isNotEmpty() } }

            val deviceProfiles = profileDao.getAllOnce()
            val knownProfileNames = deviceProfiles.map { profileMatchKey(it.name) }.toSet()
            val profileIdByFileId = HashMap<Long, Long>()
            var newProfiles = 0
            val fileProfiles = root.optJSONArray("profiles") ?: JSONArray()
            for (i in 0 until fileProfiles.length()) {
                val incoming = profileFrom(fileProfiles.getJSONObject(i), noSecrets)
                val existing = deviceProfiles.firstOrNull { profileMatchKey(it.name) == profileMatchKey(incoming.name) }
                if (existing != null) profileIdByFileId[incoming.id] = existing.id else newProfiles++
            }

            var newSources = 0
            val sourceIdByFileId = HashMap<Long, Long>()
            if (Section.SOURCES in sections) {
                val candidates = sourceDao.getAllOnce().toMutableList()
                val fileSources = root.optJSONArray("sources") ?: JSONArray()
                for (i in 0 until fileSources.length()) {
                    val incoming = sourceFrom(fileSources.getJSONObject(i), noSecrets) ?: continue
                    val existing = matchSourceForRestore(candidates, incoming)
                    if (existing == null) {
                        newSources++
                    } else {
                        candidates.remove(existing)
                        sourceIdByFileId[incoming.id] = existing.id
                    }
                }
            } else {
                // Not restoring sources, but the user-data counts still need the id mapping to look
                // anything up — so build it without counting anything as new.
                val candidates = sourceDao.getAllOnce().toMutableList()
                val fileSources = root.optJSONArray("sources") ?: JSONArray()
                for (i in 0 until fileSources.length()) {
                    val incoming = sourceFrom(fileSources.getJSONObject(i), noSecrets) ?: continue
                    matchSourceForRestore(candidates, incoming)?.let {
                        candidates.remove(it)
                        sourceIdByFileId[incoming.id] = it.id
                    }
                }
            }

            val kinds = kindsFor(sections)
            val counts = HashMap<String, Int>()
            var deletions = 0
            if (kinds.isNotEmpty()) {
                root.optJSONArray("userData")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val e = arr.getJSONObject(i)
                        val kind = e.optString("kind")
                        if (kind !in kinds) continue
                        val pid = profileIdByFileId[e.optLong("p", -1)] ?: continue
                        sourceIdByFileId[e.optLong("src", -1)]?.let { e.put("src", it) }
                        if (!userData.wouldAdd(pid, kind, e)) continue
                        counts[kind] = (counts[kind] ?: 0) + 1
                    }
                }
                root.optJSONArray("tombstones")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val e = arr.getJSONObject(i)
                        if (e.optString("kind") !in kinds) continue
                        val pid = profileIdByFileId[e.optLong("p", -1)] ?: continue
                        sourceIdByFileId[e.optLong("src", -1)]?.let { e.put("src", it) }
                        if (userData.wouldRemove(pid, e)) deletions++
                    }
                }
            }

            var changedSettings = 0
            if (Section.SETTINGS in sections) {
                root.optJSONObject("settings")?.let { incoming ->
                    val mine = settings.exportSettings()
                    incoming.keys().forEach { k ->
                        if (!mine.has(k) || mine.get(k).toString() != incoming.get(k).toString()) changedSettings++
                    }
                }
            }

            Preview(
                newProfiles = newProfiles,
                newSources = newSources,
                newFavorites = counts["fav"] ?: 0,
                newHistory = counts["his"] ?: 0,
                newResume = counts["prog"] ?: 0,
                newReorder = (counts["order"] ?: 0) + (counts["sort"] ?: 0) + (counts["member"] ?: 0),
                changedSettings = changedSettings,
                hasCustomizations = Section.CUSTOMIZE in sections &&
                    root.optJSONObject("customizations")?.keys()?.hasNext() == true,
                deletions = deletions,
            )
        }
    }

    /**
     * Applies the chosen [sections] of the file (only those it actually contains) as a MERGE — a
     * restore never deletes anything that already exists on the device (owner decision, 2026-07-18):
     *
     * - Profiles are matched **by name** (case-insensitive): a match is updated in place (avatar,
     *   kids flag, PIN); a new name is added (with a fresh id if the file's id is taken). Device
     *   profiles not in the file are untouched. Ids can't be the match key — they're per-device
     *   auto-increments, so "id 1" on two devices is usually two different people.
     * - Sources are matched by type+URL+username (plus the MAC for Stalker, whose playlists share a
     *   portal URL and have no username): matches keep the device row (secrets updated when the file
     *   carries them); unknown sources are added, and each device row is claimed at most once.
     *   Nothing is wiped.
     * - Every id in the file (profile/source/EPG-source) is remapped to its device id before any
     *   per-profile or per-source data (links, favorites/history/resume, customizations, custom TMDB
     *   names, auto-refresh, startup modes, Customize PINs, home configs) is applied.
     *
     * For encrypted backups: if [backupPassword] is provided it is validated BEFORE any write — a
     * wrong passphrase fails fast with [WrongPasswordException] and changes nothing. If the
     * passphrase is null/blank on an encrypted backup, non-secret data still restores and secret
     * fields are left blank (the caller tells the user to re-enter passwords). Legacy v5 backups with
     * plaintext passwords import exactly as before (no `crypto` block ⇒ strings treated as plaintext).
     */
    suspend fun import(
        file: File,
        sections: Set<Section> = Section.entries.toSet(),
        backupPassword: String? = null,
    ): Result<ImportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val (root, payload) = readBackup(file, backupPassword)
            val wallpaper = payload.wallpaper
            // Schema version of the FILE (not of this build). Only needed where a block moved between
            // sections: up to v16 the per-profile startup mode and Customize PIN lock rode with
            // SOURCES, from v17 they ride with SETTINGS. Both places are read, so either file restores
            // whichever section the user ticks — see the `startupModes` handling below.
            val fileVersion = root.optInt("version", 0)
            val crypto = root.optJSONObject("crypto")
            val pass = backupPassword?.takeIf { it.isNotBlank() }
            var existingProfileIds = profileDao.getAllOnce().map { it.id }.toSet()

            // Derive + validate the key up front, before any destructive write.
            val key = if (crypto != null && pass != null) {
                BackupCrypto.deriveKey(pass, crypto) ?: throw WrongPasswordException()
            } else null
            if (key != null && !validatePassphrase(root, key)) throw WrongPasswordException()
            // unseal: decrypt an encrypted secret object; null key (skip) or legacy plaintext returns as-is.
            val unseal: (Any?) -> String? = { v ->
                when {
                    BackupCrypto.isEncrypted(v) -> if (key != null) runCatching { BackupCrypto.decrypt(key, v as JSONObject) }.getOrNull() else null
                    v is String -> v.takeIf { it.isNotEmpty() }
                    else -> null
                }
            }

            var count = 0
            // Sources whose "type" this build can't parse: skipped rather than coerced (B4), and
            // reported back so the restore message can say what was left out.
            var skippedSources = 0
            // Locale is deliberately deferred until every other restore operation and the marker
            // clear have completed. Publishing it earlier can recreate the Activity while the
            // database/DataStore merge is still in flight.
            var pendingLocaleTag: String? = null
            var localeFieldPresent = false
            var invalidLocale = false

            // Id remapping (file id → device id). Filled during the profile/source merge below. When
            // the SOURCES section isn't being restored, matching still runs read-only so the other
            // sections can attach to the right device rows; unmatched ids fall through unchanged.
            val profileIdMap = HashMap<Long, Long>()
            // Each restored profile paired with its entry in the file, so the pictures the container
            // carries can be written once the database work is done.
            val restoredAvatars = ArrayList<Pair<Long, JSONObject>>()
            val sourceIdMap = HashMap<Long, Long>()
            var epgIdMap: Map<Long, Long> = emptyMap()

            val fileProfiles = root.optJSONArray("profiles") ?: JSONArray()
            val fileSources = root.optJSONArray("sources") ?: JSONArray()
            val applySources = Section.SOURCES in sections && (root.has("profiles") || root.has("sources"))

            // Interrupted-restore marker (B2): set before the first write of any kind, cleared only
            // after the last one. A restore spans the database AND several DataStore files, so no
            // single transaction can cover it; the marker is what makes a half-applied restore
            // visible instead of silent (the shell prompts about it at the next launch).
            settings.markRestoreStarted("${file.name} · ${sections.joinToString(", ") { it.name }}")
            Log.i(
                TAG,
                "Restore start file=${file.name} sections=${sections.joinToString(",") { it.name }} " +
                    "encrypted=${crypto != null} key=${key != null} profiles=${fileProfiles.length()} sources=${fileSources.length()}",
            )

            // MERGE-restore (owner decision): a restore never deletes existing profiles or sources.
            // One transaction wraps the profile/source/link row writes — the part where a partial
            // apply would be actively wrong (a source inserted but its profile link missing). The
            // DataStore-backed sections below (settings, customizations, engine pins, logins) can't
            // join it, and neither can the user-data resolve, which is deliberately chunked (B3) so
            // a large restore doesn't hold one write transaction for its whole duration.
            db.withTransaction {
                // --- profiles: match by NAME (case-insensitive) — ids are per-device counters and
                // collide across devices, so they can't identify a person. Match → update in place;
                // new name → insert (keeping the file id only when it's free).
                val deviceProfiles = profileDao.getAllOnce()
                val byName = deviceProfiles.associateBy { profileMatchKey(it.name) }
                val takenProfileIds = deviceProfiles.map { it.id }.toMutableSet()
                for (i in 0 until fileProfiles.length()) {
                    val incoming = profileFrom(fileProfiles.getJSONObject(i), unseal)
                    val existing = byName[profileMatchKey(incoming.name)]
                    // Which local profile each entry ended up as, so its picture can be written to
                    // disk AFTER this transaction — a database transaction is no place for file I/O.
                    fun claimedAvatar(localId: Long) {
                        restoredAvatars += localId to fileProfiles.getJSONObject(i)
                    }
                    when {
                        existing != null -> {
                            if (applySources) {
                                profileDao.update(
                                    existing.copy(
                                        avatarColor = incoming.avatarColor, avatarId = incoming.avatarId,
                                        isKids = incoming.isKids,
                                        // A passwordless backup omits PIN hashes (v17), so `null` here
                                        // means "not carried", not "this profile has no lock" — keep the
                                        // device's own PIN rather than silently unlocking the profile.
                                        pinHash = incoming.pinHash ?: existing.pinHash,
                                    ),
                                )
                            }
                            profileIdMap[incoming.id] = existing.id
                            if (applySources) claimedAvatar(existing.id)
                        }
                        applySources -> {
                            val keepId = incoming.id > 0 && incoming.id !in takenProfileIds
                            val rowId = profileDao.insert(if (keepId) incoming else incoming.copy(id = 0))
                            val deviceId = if (keepId) incoming.id else rowId
                            takenProfileIds += deviceId
                            profileIdMap[incoming.id] = deviceId
                            claimedAvatar(deviceId)
                        }
                        // else: not restoring SOURCES and no matching profile on the device — leave
                        // unmapped; the per-profile blocks below skip unmapped ids safely.
                    }
                }

                // --- sources: match by type+URL+username, plus the MAC for Stalker. Match → keep the
                // device row (refresh the secrets/extras the file carries); unknown → insert.
                // Nothing is wiped.
                val deviceSources = sourceDao.getAllOnce()
                // Claim-once: a device row leaves this list as soon as an incoming source merges onto
                // it, so two playlists in the file can never land on the same row (#114).
                val unclaimedSources = deviceSources.toMutableList()
                val takenSourceIds = deviceSources.map { it.id }.toMutableSet()
                for (i in 0 until fileSources.length()) {
                    val srcJson = fileSources.getJSONObject(i)
                    val incoming = sourceFrom(srcJson, unseal)
                    if (incoming == null) {
                        skippedSources++
                        continue
                    }
                    val existing = matchSourceForRestore(unclaimedSources, incoming)
                        ?.also { unclaimedSources.remove(it) }
                    when {
                        existing != null -> {
                            if (applySources) {
                                // Non-secret playlist settings are applied from the FILE when it carries
                                // them. They used to be skipped, so restoring onto an install that
                                // already had the playlist kept the device's values and silently lost
                                // the backup's name, per-section sync scope, Prefer HLS and per-playlist
                                // Pre-buffer. `has()` rather than a value check, so a backup written by
                                // an older build (no such key) still leaves the device's value alone.
                                sourceDao.update(
                                    existing.copy(
                                        name = if (srcJson.has("name")) incoming.name else existing.name,
                                        password = incoming.password ?: existing.password,
                                        mac = incoming.mac ?: existing.mac,
                                        stalkerSerialNumber = incoming.stalkerSerialNumber ?: existing.stalkerSerialNumber,
                                        stalkerDeviceId = incoming.stalkerDeviceId ?: existing.stalkerDeviceId,
                                        stalkerDeviceId2 = incoming.stalkerDeviceId2 ?: existing.stalkerDeviceId2,
                                        stalkerSignature = incoming.stalkerSignature ?: existing.stalkerSignature,
                                        userAgent = incoming.userAgent ?: existing.userAgent,
                                        epgUrl = incoming.epgUrl ?: existing.epgUrl,
                                        syncLive = if (srcJson.has("syncLive")) incoming.syncLive else existing.syncLive,
                                        importPortalEpg = if (srcJson.has("importPortalEpg")) incoming.importPortalEpg else existing.importPortalEpg,
                                        syncMovies = if (srcJson.has("syncMovies")) incoming.syncMovies else existing.syncMovies,
                                        syncSeries = if (srcJson.has("syncSeries")) incoming.syncSeries else existing.syncSeries,
                                        preferHls = if (srcJson.has("preferHls")) incoming.preferHls else existing.preferHls,
                                        livePrerollSecs = if (srcJson.has("livePrerollSecs")) incoming.livePrerollSecs else existing.livePrerollSecs,
                                        liveEnginePreference = if (srcJson.has("liveEnginePreference")) incoming.liveEnginePreference else existing.liveEnginePreference,
                                        liveLatencyMode = if (srcJson.has("liveLatencyMode")) incoming.liveLatencyMode else existing.liveLatencyMode,
                                        liveLatencyCustomSecs = if (srcJson.has("liveLatencyCustomSecs")) incoming.liveLatencyCustomSecs else existing.liveLatencyCustomSecs,
                                        // hlsSupported is a sync-time detection hint, not a user choice:
                                        // the device's own last answer wins, and UNKNOWN adopts the
                                        // file's so a fresh row is not left blank until the next sync.
                                        hlsSupported = if (existing.hlsSupported == HlsSupport.UNKNOWN) incoming.hlsSupported else existing.hlsSupported,
                                    ),
                                )
                            }
                            sourceIdMap[incoming.id] = existing.id
                        }
                        applySources -> {
                            val keepId = incoming.id > 0 && incoming.id !in takenSourceIds
                            val toInsert = if (keepId) incoming else incoming.copy(id = 0)
                            // A restored backup starts with empty catalog tables. Nullifying lastSyncAt ensures
                            // the first sync is treated as a fresh sync, preserving the restored category settings.
                            val rowId = sourceDao.insert(toInsert.copy(lastSyncAt = null))
                            val deviceId = if (keepId) incoming.id else rowId
                            takenSourceIds += deviceId
                            sourceIdMap[incoming.id] = deviceId
                        }
                    }
                }

                if (applySources) {
                    val links = root.optJSONArray("links") ?: JSONArray()
                    for (i in 0 until links.length()) {
                        val l = links.getJSONObject(i)
                        val pid = profileIdMap[l.getLong("profileId")] ?: continue
                        val sid = sourceIdMap[l.getLong("sourceId")] ?: continue
                        sourceDao.link(ProfileSourceCrossRef(profileId = pid, sourceId = sid)) // IGNORE on dup
                    }
                }
            }

            if (applySources) {
                // EPG sources merge by URL; the returned map remaps the auto-refresh keys below.
                epgIdMap = epgSources.mergeJson(root.optString("epgSources").takeIf { it.isNotBlank() })

                val profileIds = profileDao.getAllOnce().map { it.id }.toSet()
                // Only a device with no usable active profile (fresh install) adopts a restored one —
                // a merge restore must not switch the profile out from under someone mid-session.
                // Which one it adopts now comes from the file (v17) instead of being whichever id the
                // map happened to yield first; older files fall back to that same arbitrary pick.
                if (settings.activeProfileId.first() !in profileIds) {
                    val preferred = root.optLong("activeProfileId", -1L)
                        .takeIf { it > 0 }?.let { profileIdMap[it] }
                    (preferred ?: profileIdMap.values.firstOrNull())?.let { settings.setActiveProfile(it) }
                }
                // Pre-v17 files carried these in the SOURCES block, so keep honouring that for them.
                // v17+ files are handled in the SETTINGS block, where they now belong.
                if (fileVersion < 17) {
                    root.optJSONObject("startupModes")?.let { settings.importStartupModes(remapKeys(it, profileIdMap), profileIds) }
                    root.optJSONObject("customizePins")?.let { settings.importCustomizePins(remapKeys(it, profileIdMap), profileIds) }
                }
                existingProfileIds = profileIds
                // Auto-refresh maps + default source, remapped to device ids; entries whose ids didn't
                // survive the merge are dropped; the device's other choices are kept (merge).
                val sourceIds = sourceDao.getAllOnce().map { it.id }.toSet()
                root.optJSONObject("playlistAutoRefresh")?.let { settings.importPlaylistAutoRefresh(remapKeys(it, sourceIdMap), sourceIds) }
                root.optJSONObject("epgAutoRefresh")?.let { settings.importEpgAutoRefresh(remapKeys(it, epgIdMap), epgSources.getAll().map { s -> s.id }.toSet()) }
                root.optJSONObject("epgUseLogos")?.let { settings.importEpgUseLogos(remapKeys(it, epgIdMap), epgSources.getAll().map { s -> s.id }.toSet()) }
                if (root.has("defaultSourceId")) {
                    // Only a source that actually took part in this restore may become the default.
                    // Falling back to the file's raw id was an id-collision bug: an unmapped id still
                    // passed the `in sourceIds` check whenever some unrelated device source happened
                    // to hold that number, silently repointing the user's default playlist.
                    sourceIdMap[root.getLong("defaultSourceId")]?.let { settings.importDefaultSource(it, sourceIds) }
                }
                count += fileProfiles.length() + fileSources.length() - skippedSources
            }

            if (Section.CUSTOMIZE in sections) {
                root.optJSONObject("customizations")?.let { o ->
                    // Remap "cust_<pid>_<TYPE>" keys and the "<sourceId>:…" content keys inside each
                    // value, then MERGE — other profiles' customizations stay untouched.
                    val cust = HashMap<String, String>()
                    o.keys().forEach { k ->
                        if (!k.startsWith("cust_")) return@forEach
                        val body = k.removePrefix("cust_")
                        val filePid = body.substringBefore('_').toLongOrNull() ?: return@forEach
                        val pid = profileIdMap[filePid] ?: filePid
                        cust["cust_${pid}_${body.substringAfter('_')}"] = remapCustomizationValue(o.getString(k), sourceIdMap)
                    }
                    customize.mergeAll(cust)
                    count += cust.size
                }
                root.optJSONObject("homeConfigs")?.let { settings.importHomeConfigs(remapKeys(it, profileIdMap), existingProfileIds) }
                root.optJSONObject("hideNewCategories")?.let { settings.importHideNewCategories(remapKeys(it, profileIdMap), existingProfileIds) }
                // Custom TMDB names: merge (backup wins per key), then drop any cached match/details stored
                // under the imported keys so the corrected title is re-fetched instead of showing stale art.
                // Keys embed the source id ("movie:<sourceId>:…") — remap before merging.
                root.optString("tmdbOverrides").takeIf { it.isNotBlank() }?.let { raw ->
                    runCatching {
                        val touched = tmdbOverrides.importJson(remapTmdbOverrideKeys(raw, sourceIdMap))
                        // One transaction for the cache invalidation: these two deletes per key are
                        // a pair — a half-done pass would leave a stale details row keyed to a match
                        // that's already gone, which reads back as the old title's artwork.
                        db.withTransaction {
                            touched.forEach { k ->
                                metadataDao.deleteMatch(k)
                                metadataDao.deleteCache(k)
                            }
                        }
                    }
                }
            }

            // Profile pictures: written now that the transaction has closed. A backup that carries
            // none leaves every profile's picture exactly as this device had it — a restore must not
            // strip a picture off a profile just because the file predates the feature.
            restoredAvatars.forEach { (localId, json) ->
                restoreAvatar(json, localId, payload.avatars)?.let { path -> profileDao.setAvatarPath(localId, path) }
            }

            // Favorites/history/progress: stashed as pending records — they attach automatically as
            // the post-restore syncs repopulate the content tables (UserDataResolver.resolvePending).
            // Each record's profile ("p") and source ("src") ids are remapped to the merged device ids;
            // records whose profile has no home on this device are skipped.
            val kinds = kindsFor(sections)
            if (kinds.isNotEmpty()) {
                // Both the surviving rows and the deletions are records of the same shape and need the
                // same treatment, so they share one pass.
                fun remapUserData(arr: JSONArray): JSONArray {
                    val filtered = JSONArray()
                    for (i in 0 until arr.length()) {
                        val e = arr.getJSONObject(i)
                        if (e.optString("kind") !in kinds) continue
                        val fileP = e.optLong("p", -1)
                        val p = profileIdMap[fileP] ?: if (applySources) continue else fileP
                        e.put("p", p)
                        val src = e.optLong("src", -1)
                        sourceIdMap[src]?.let { e.put("src", it) }
                        // Manual-order context keys for provider folders use the same
                        // "<sourceId>:<stable-category-id>" namespace as customization keys.
                        // Custom-category ids ("custom:<uuid>") naturally pass through.
                        if (e.has("ctx")) {
                            e.put("ctx", remapContentContextKey(e.optString("ctx"), sourceIdMap))
                        }
                        filtered.put(e)
                    }
                    return filtered
                }
                // Deletions FIRST (v21): they remove any local row older than the deletion, and they
                // are recorded here, so the very next step cannot re-insert what this one removed.
                root.optJSONArray("tombstones")?.let { userData.applyTombstones(remapUserData(it)) }
                root.optJSONArray("userData")?.let { arr ->
                    val filtered = remapUserData(arr)
                    userData.importAll(filtered)
                    count += filtered.length()
                }
            }

            if (Section.SETTINGS in sections) {
                root.optJSONObject("settings")?.let { s ->
                    val importedSettings = settings.importSettings(s) // non-secret keys (incl. proxy host/port/user/enabled)
                    if (importedSettings.localePresent) {
                        localeFieldPresent = true
                        pendingLocaleTag = importedSettings.localeTag
                        invalidLocale = importedSettings.invalidLocale
                    }
                    // Proxy password: decrypt if we have a key; if encrypted but no key, leave blank.
                    if (s.has("proxy_pass_enc")) {
                        unseal(s.opt("proxy_pass_enc"))?.let { settings.setProxyPassword(it) }
                    }
            if (s.has("tmdb_key_enc")) {
                unseal(s.opt("tmdb_key_enc"))?.let { settings.setTmdbApiKey(it) }
            }
            if (s.has("opensub_api_key_enc")) {
                unseal(s.opt("opensub_api_key_enc"))?.let { settings.setOpenSubtitlesApiKey(it) }
            }
                    count += s.length()
                }
                // After importSettings, because that is what wrote the file's (device-local) bg path.
                applyWallpaper(wallpaper)
                // Per-profile landing screen + Customize PIN lock (v17: moved here from SOURCES).
                if (fileVersion >= 17) {
                    val pids = profileDao.getAllOnce().map { it.id }.toSet()
                root.optJSONObject("startupModes")?.let { settings.importStartupModes(remapKeys(it, profileIdMap), pids) }
                root.optJSONObject("startupChannels")?.let {
                    settings.importStartupChannels(
                        remapKeys(it, profileIdMap),
                        pids,
                        sourceIdMap,
                    )
                }
                settings.repairSpecificStartupModes(pids)
                    // Encrypted-only since v17, so this restores the lock exactly when the passphrase
                    // is available and otherwise leaves the device's own Customize PIN untouched.
                    root.optJSONObject("customizePins")?.let { o ->
                        settings.importCustomizePins(remapKeys(unsealValues(o, unseal), profileIdMap), pids)
                    }
                }
                // Per-item compatibility-mode engine pins. Optional; merged (union) into the current
                // pins so a restore never drops locally-set pins. Corrupt/non-string entries ignored.
                //
                // The keys are [enginePinKey] ("<sourceId>:<MEDIA_TYPE>:<remoteId>"), so the source id
                // has to be remapped to its device id first. Without this a merge restore either did
                // nothing (the file's id is free on this device, so the pin named a source that does
                // not exist) or, worse, pinned the items of whatever unrelated source already held
                // that id. Legacy stream-URL keys carry no id and pass through untouched.
                root.optJSONObject("compatMode")?.let { c ->
                    fun keys(name: String) = jsonStrings(c.optJSONArray(name)).map { remapEnginePinKey(it, sourceIdMap) }
                    runCatching { forceMpvStore.importUrls(keys("liveMpvUrls"), keys("liveExoUrls")) }
                    runCatching { vodEngineStore.importUrls(keys("vodMpvUrls"), keys("vodExoUrls")) }
                }
                // Per-item zoom / volume / audio delay. Merged in (REPLACE on the same profile+key), so a restore
                // never drops what this device already remembers for other items. Rows whose profile
                // isn't on this device are skipped — a foreign key would reject them anyway.
                root.optJSONArray("playbackPrefs")?.let { arr ->
                    val deviceProfileIds = profileDao.getAllOnce().map { it.id }.toSet()
                    val rows = ArrayList<tv.own.owntv.core.database.entity.PlaybackPrefsEntity>()
                    for (i in 0 until arr.length()) {
                        val e = arr.optJSONObject(i) ?: continue
                        val filePid = e.optLong("p", -1)
                        val pid = profileIdMap[filePid] ?: filePid
                        if (pid !in deviceProfileIds) continue
                        // Same [enginePinKey] shape as compatMode above — remap the source id, or the
                        // restored zoom/volume lands on the wrong item (or on nothing at all).
                        val key = e.optString("k").takeIf { it.isNotBlank() }
                            ?.let { remapEnginePinKey(it, sourceIdMap) } ?: continue
                        val zoom = e.optString("z").takeIf { it.isNotBlank() }
                        val volume = if (e.has("v")) e.optInt("v").coerceIn(0, 150) else null
                        // Absent on a pre-v20 backup, which is what "no per-item delay" already means.
                        val delay = if (e.has("d")) e.optInt("d").coerceIn(-5_000, 5_000) else null
                        if (zoom == null && volume == null && delay == null) continue
                        rows += tv.own.owntv.core.database.entity.PlaybackPrefsEntity(
                            profileId = pid, contentKey = key, zoomMode = zoom, volumeBoost = volume,
                            audioDelayMs = delay,
                        )
                    }
                    if (rows.isNotEmpty()) runCatching { db.playbackPrefsDao().insertAll(rows) }
                }
                // Per-profile OpenSubtitles login: decrypt each blob and store it under the remapped
                // device profile id. Encrypted-only, so it's skipped when there's no key (no passphrase).
                // Only profiles that exist on this device get one; an expired token later self-heals via
                // the store's silent re-login when a password rode along ("Stay signed in").
                root.optJSONArray("openSubtitles")?.let { arr ->
                    val deviceProfileIds = profileDao.getAllOnce().map { it.id }.toSet()
                    for (i in 0 until arr.length()) {
                        val e = arr.getJSONObject(i)
                        val filePid = e.optLong("p", -1)
                        val pid = profileIdMap[filePid] ?: filePid
                        if (pid !in deviceProfileIds) continue
                        unseal(e.opt("session"))?.let { plain ->
                            runCatching { openSubAuth.importJson(pid, JSONObject(plain)) }
                        }
                    }
                }
                // Subtitle files + the selection / timing / link rows that point at them.
                root.optJSONObject("subtitles")?.let { block ->
                    count += runCatching {
                        importSubtitles(block, payload.subtitles, profileIdMap, sourceIdMap)
                    }.getOrDefault(0)
                }
            }
            settings.clearRestoreMarker()
            // Locale writes are last by design: all restore work and the interrupted-restore marker
            // are complete before LocalizedContent can observe a script-family change and recreate
            // the Activity. An invalid optional locale is ignored and reported in the summary.
            if (localeFieldPresent) {
                pendingLocaleTag?.let { tag -> settings.applyImportedLocale(tag) }
            }
            Log.i(TAG, "Restore done items=$count skippedSources=$skippedSources invalidLocale=$invalidLocale")
            ImportSummary(items = count, skippedSources = skippedSources, invalidLocale = invalidLocale)
        }
    }

    /**
     * Restores the subtitle block: writes any file this device does not already have into the shared
     * cache dir, then re-attaches the selections, timing offsets and links to the merged ids.
     *
     * Three id spaces have to line up, which is why this is a merge rather than a bulk insert:
     *  - **profile** ids come from [profileIdMap]; a row whose profile has no home here is skipped.
     *  - **source** ids are embedded in every content key ("movie:<sourceId>:…"), remapped through
     *    [sourceIdMap] exactly as the TMDB overrides are.
     *  - **cache** ids are per-device auto-increments, so the file's id is meaningless here. Rows are
     *    deduped the same way the app itself does (OpenSubtitles file id, else a local file name), and
     *    everything downstream goes through the resulting file→device id map.
     *
     * Returns how many rows were applied.
     */
    private suspend fun importSubtitles(
        block: JSONObject,
        files: Map<String, ByteArray>,
        profileIdMap: Map<Long, Long>,
        sourceIdMap: Map<Long, Long>,
    ): Int {
        val dao = db.subtitleDao()
        val deviceProfileIds = profileDao.getAllOnce().map { it.id }.toSet()
        var applied = 0

        // --- cache rows + their files ---
        val cacheIdMap = HashMap<Long, Long>()
        val cache = block.optJSONArray("cache") ?: JSONArray()
        for (i in 0 until cache.length()) {
            val e = cache.optJSONObject(i) ?: continue
            val fileId = e.optLong("id", -1).takeIf { it > 0 } ?: continue
            val openSubFileId = if (e.has("openSubFileId")) e.optLong("openSubFileId") else null
            val fileName = e.optString("fileName").takeIf { it.isNotBlank() } ?: continue
            // Already here? Reuse it — the file cache is device-level and shared across profiles, so a
            // second copy of the same download would only waste space and split the dedupe.
            val existing = runCatching {
                if (openSubFileId != null) dao.findByOpenSubFileId(openSubFileId) else dao.findLocalByFileName(fileName)
            }.getOrNull()
            if (existing != null) {
                cacheIdMap[fileId] = existing.id
                continue
            }
            val bytes = e.optString("file").takeIf { it.isNotBlank() }?.let { files[it] } ?: continue
            val written = runCatching {
                if (!subtitlesDir.exists()) subtitlesDir.mkdirs()
                // Never reuse the backup's own file name verbatim: it came from another device and may
                // collide with a file already here for a different subtitle.
                File(subtitlesDir, "restored_${System.currentTimeMillis()}_${File(fileName).name}")
                    .also { it.writeBytes(bytes) }
            }.getOrNull() ?: continue
            val newId = runCatching {
                dao.insertCache(
                    tv.own.owntv.core.database.entity.SubtitleCacheEntity(
                        source = e.optString("source").ifBlank { "LOCAL" },
                        openSubFileId = openSubFileId,
                        language = e.optStringOrNull("language"),
                        languageName = e.optStringOrNull("languageName"),
                        releaseName = e.optStringOrNull("releaseName"),
                        format = e.optStringOrNull("format"),
                        hearingImpaired = e.optBoolean("hearingImpaired", false),
                        fileName = fileName,
                        cachedPath = written.absolutePath,
                        lastUsedAt = e.optLong("lastUsedAt", System.currentTimeMillis()),
                    ),
                )
            }.getOrNull()
            if (newId == null) {
                written.delete() // the row failed, so the orphaned file must not linger
                continue
            }
            cacheIdMap[fileId] = newId
            applied++
        }

        /** Profile + content key remapped to this device, or null when the row cannot be attached. */
        fun target(e: JSONObject): Pair<Long, String>? {
            val filePid = e.optLong("p", -1)
            val pid = profileIdMap[filePid] ?: filePid
            if (pid !in deviceProfileIds) return null
            val key = e.optString("k").takeIf { it.isNotBlank() } ?: return null
            return pid to remapTypedContentKey(key, sourceIdMap)
        }

        block.optJSONArray("selections")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val (pid, key) = target(e) ?: continue
                // A selection naming a file we could not place is dropped: a dangling cacheId would
                // read back as "a subtitle is selected" and then silently fail to load.
                val cacheId = if (e.has("c")) cacheIdMap[e.optLong("c")] ?: continue else null
                runCatching {
                    dao.upsertSelection(
                        tv.own.owntv.core.database.entity.SubtitleSelectionEntity(
                            profileId = pid, contentKey = key, cacheId = cacheId,
                            off = e.optBoolean("off", false),
                            updatedAt = e.optLong("u", System.currentTimeMillis()),
                        ),
                    )
                }.onSuccess { applied++ }
            }
        }

        block.optJSONArray("timings")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val (pid, key) = target(e) ?: continue
                val rawSubKey = e.optString("s").takeIf { it.isNotBlank() } ?: continue
                // "opensub:<file_id>" is a global OpenSubtitles id and travels as-is — that is what
                // makes a hand-tuned offset portable at all. "local:<cacheId>" names a device row, so
                // it has to follow the file to its new id.
                val subKey = if (rawSubKey.startsWith(LOCAL_SUB_PREFIX)) {
                    val mapped = rawSubKey.removePrefix(LOCAL_SUB_PREFIX).toLongOrNull()
                        ?.let { cacheIdMap[it] } ?: continue
                    "$LOCAL_SUB_PREFIX$mapped"
                } else {
                    rawSubKey
                }
                runCatching {
                    dao.upsertTiming(
                        tv.own.owntv.core.database.entity.SubtitleTimingEntity(
                            profileId = pid, contentKey = key, subtitleKey = subKey,
                            offsetMs = e.optInt("o", 0),
                            updatedAt = e.optLong("u", System.currentTimeMillis()),
                        ),
                    )
                }.onSuccess { applied++ }
            }
        }

        block.optJSONArray("links")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val (pid, key) = target(e) ?: continue
                val cacheId = cacheIdMap[e.optLong("c", -1)] ?: continue
                runCatching {
                    dao.insertLink(
                        tv.own.owntv.core.database.entity.SubtitleLinkEntity(
                            profileId = pid, contentKey = key, cacheId = cacheId,
                            mediaType = e.optString("t").ifBlank { "MOVIE" },
                            contentTitle = e.optString("n"),
                            addedAt = e.optLong("a", System.currentTimeMillis()),
                        ),
                    )
                }.onSuccess { applied++ }
            }
        }
        return applied
    }

    /** Confirms the derived key opens at least one encrypted secret in the file (GCM tag check). */
    private fun validatePassphrase(root: JSONObject, key: javax.crypto.SecretKey): Boolean {
        firstEncryptedSecret(root)?.let { sealed ->
            return runCatching { BackupCrypto.decrypt(key, sealed); true }.getOrDefault(false)
        }
        return true // crypto block but no actual encrypted field — nothing to validate against
    }

    /** Finds the first encrypted secret object in the file (a source password, a Stalker MAC, or the proxy/TMDB key). */
    private fun firstEncryptedSecret(root: JSONObject): JSONObject? {
        // Profile PIN hashes are sealed from v17 on — probe them first, because a settings-only or
        // profiles-only backup may have no source password to validate the passphrase against.
        root.optJSONArray("profiles")?.let { arr ->
            for (i in 0 until arr.length()) {
                val pin = arr.getJSONObject(i).opt("pinHash")
                if (BackupCrypto.isEncrypted(pin)) return pin as JSONObject
            }
        }
        root.optJSONObject("customizePins")?.let { pins ->
            pins.keys().forEach { k ->
                val sealed = pins.opt(k)
                if (BackupCrypto.isEncrypted(sealed)) return sealed as JSONObject
            }
        }
        root.optJSONArray("sources")?.let { arr ->
            for (i in 0 until arr.length()) {
                val src = arr.getJSONObject(i)
                val pw = src.opt("password")
                if (BackupCrypto.isEncrypted(pw)) return pw as JSONObject
                // A Stalker source's MAC is its only secret (password is null), so probe it too —
                // otherwise an all-Stalker backup couldn't validate the passphrase.
                listOf("mac", "stalkerSerialNumber", "stalkerDeviceId", "stalkerDeviceId2", "stalkerSignature").forEach { key ->
                    val value = src.opt(key)
                    if (BackupCrypto.isEncrypted(value)) return value as JSONObject
                }
            }
        }
        root.optJSONObject("settings")?.opt("proxy_pass_enc")?.let { if (BackupCrypto.isEncrypted(it)) return it as JSONObject }
        root.optJSONObject("settings")?.opt("tmdb_key_enc")?.let { if (BackupCrypto.isEncrypted(it)) return it as JSONObject }
        root.optJSONObject("settings")?.opt("opensub_api_key_enc")?.let { if (BackupCrypto.isEncrypted(it)) return it as JSONObject }
        // A per-profile OpenSubtitles login is encrypted too — probe it so an all-OpenSubtitles backup validates.
        root.optJSONArray("openSubtitles")?.let { arr ->
            for (i in 0 until arr.length()) {
                val sealed = arr.getJSONObject(i).opt("session")
                if (BackupCrypto.isEncrypted(sealed)) return sealed as JSONObject
            }
        }
        return null
    }

    private fun kindsFor(sections: Set<Section>): Set<String> = buildSet {
        if (Section.FAVORITES in sections) add("fav")
        if (Section.HISTORY in sections) add("his")
        if (Section.RESUME in sections) add("prog")
        // "sort" (per-series season/episode order) and "member" (custom category membership, #87)
        // ride with MANUAL_REORDER: all are per-profile, per-item ordering preferences, so one tick
        // covers everything the user has hand-ordered.
        if (Section.MANUAL_REORDER in sections) { add("order"); add("sort"); add("member") }
    }

    // --- mapping ---
    /**
     * One profile row. The PIN hash is a **secret** (v17): `Pin.hash` is a single salted SHA-256
     * pass, and a 4-digit PIN is only 10 000 candidates, so a hash sitting in an unencrypted backup
     * is recovered in well under a second. It therefore follows the same rule as every other secret —
     * sealed when there is a passphrase, omitted entirely when there is not. `pinLocked` still says
     * the profile *had* a lock, so restore can tell "no PIN carried" from "no PIN set".
     */
    private fun profileJson(p: ProfileEntity, seal: ((String) -> JSONObject)?) = JSONObject().apply {
        put("id", p.id); put("name", p.name); put("avatarColor", p.avatarColor); put("avatarId", p.avatarId)
        put("isKids", p.isKids); put("createdAt", p.createdAt)
        val pin = p.pinHash?.takeIf { it.isNotEmpty() }
        put("pinHash", if (pin != null && seal != null) seal(pin) else JSONObject.NULL)
        if (pin != null) put("pinLocked", true)
    }

    private fun profileFrom(o: JSONObject, unseal: (Any?) -> String?) = ProfileEntity(
        id = o.getLong("id"), name = o.getString("name"), avatarColor = o.getInt("avatarColor"),
        avatarId = o.optInt("avatarId", 0), isKids = o.optBoolean("isKids", false),
        // Pre-v17 backups stored the hash as a plain string; `unseal` returns those unchanged.
        pinHash = if (o.isNull("pinHash")) null else unseal(o.opt("pinHash")),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
    )

    private fun sourceJson(s: SourceEntity, seal: ((String) -> JSONObject)?) = JSONObject().apply {
        put("id", s.id); put("name", s.name); put("type", s.type.name); put("url", s.url)
        put("username", s.username ?: JSONObject.NULL)
        // Password: encrypted object when a passphrase was given, otherwise omitted (never plaintext).
        val pw = s.password?.takeIf { it.isNotEmpty() }
        put("password", if (pw != null && seal != null) seal(pw) else JSONObject.NULL)
        // Stalker MAC: same secret policy as the password — encrypted with a passphrase, else omitted.
        val macVal = s.mac?.takeIf { it.isNotEmpty() }
        put("mac", if (macVal != null && seal != null) seal(macVal) else JSONObject.NULL)
        val serialNumber = s.stalkerSerialNumber?.takeIf { it.isNotEmpty() }
        put("stalkerSerialNumber", if (serialNumber != null && seal != null) seal(serialNumber) else JSONObject.NULL)
        val deviceId = s.stalkerDeviceId?.takeIf { it.isNotEmpty() }
        put("stalkerDeviceId", if (deviceId != null && seal != null) seal(deviceId) else JSONObject.NULL)
        val deviceId2 = s.stalkerDeviceId2?.takeIf { it.isNotEmpty() }
        put("stalkerDeviceId2", if (deviceId2 != null && seal != null) seal(deviceId2) else JSONObject.NULL)
        val signature = s.stalkerSignature?.takeIf { it.isNotEmpty() }
        put("stalkerSignature", if (signature != null && seal != null) seal(signature) else JSONObject.NULL)
        put("userAgent", s.userAgent ?: JSONObject.NULL); put("epgUrl", s.epgUrl ?: JSONObject.NULL)
        put("syncLive", s.syncLive); put("syncMovies", s.syncMovies); put("syncSeries", s.syncSeries)
        put("importPortalEpg", s.importPortalEpg)
        // v17: the two per-playlist player settings. Both are deliberate user choices made in the
        // playlist editor and neither can be re-derived from the provider, so losing them on restore
        // silently reintroduced whatever streaming problem the user had already fixed. `hlsSupported`
        // rides too — it is only a detection hint, but carrying it spares a restored playlist the
        // "unknown until the next sync" gap.
        put("preferHls", s.preferHls); put("livePrerollSecs", s.livePrerollSecs)
        // v19: the per-playlist Live TV engine and Live latency overrides. Same reasoning as the two
        // above — deliberate user choices in the playlist editor that cannot be re-derived.
        put("liveEnginePreference", s.liveEnginePreference ?: JSONObject.NULL)
        put("liveLatencyMode", s.liveLatencyMode ?: JSONObject.NULL)
        put("liveLatencyCustomSecs", s.liveLatencyCustomSecs)
        put("hlsSupported", s.hlsSupported.code)
        put("createdAt", s.createdAt); put("lastSyncAt", s.lastSyncAt ?: JSONObject.NULL)
    }

    /**
     * Maps one `sources[]` entry, or **null when its `type` isn't a [SourceType] this build knows**
     * (B4). It used to coerce an unknown type to [SourceType.M3U], which silently turned a newer
     * build's Stalker/other portal into a broken M3U row pointing at a portal URL — worse than not
     * restoring it, because the user has no way to tell it apart from a real playlist. Callers skip
     * and count these, and the restore summary says how many were left out.
     */
    private fun sourceFrom(o: JSONObject, unseal: (Any?) -> String?): SourceEntity? {
        val type = parseSourceType(o.optString("type")) ?: run {
            Log.w(TAG, "Restore: skipping source '${o.optString("name")}' — unknown type '${o.optString("type")}'")
            return null
        }
        return SourceEntity(
            id = o.getLong("id"), name = o.getString("name"),
            type = type,
            url = o.getString("url"), username = o.optStringOrNull("username"),
            password = if (o.isNull("password")) null else unseal(o.opt("password")),
            // Stalker MAC: restored from its encrypted block when a passphrase was given; null on backups
            // older than v10 (no "mac" key) or when the MAC was omitted (no passphrase).
            mac = if (o.isNull("mac")) null else unseal(o.opt("mac")),
            stalkerSerialNumber = if (o.isNull("stalkerSerialNumber")) null else unseal(o.opt("stalkerSerialNumber")),
            stalkerDeviceId = if (o.isNull("stalkerDeviceId")) null else unseal(o.opt("stalkerDeviceId")),
            stalkerDeviceId2 = if (o.isNull("stalkerDeviceId2")) null else unseal(o.opt("stalkerDeviceId2")),
            stalkerSignature = if (o.isNull("stalkerSignature")) null else unseal(o.opt("stalkerSignature")),
            userAgent = o.optStringOrNull("userAgent"), epgUrl = o.optStringOrNull("epgUrl"),
            // Pre-v13 backups omit the flags — default On so restore matches today's behaviour.
            syncLive = if (o.has("syncLive")) o.optBoolean("syncLive", true) else true,
            // Absent in every backup written before v37; a portal guide is on unless it was turned off.
            importPortalEpg = o.optBoolean("importPortalEpg", true),
            syncMovies = if (o.has("syncMovies")) o.optBoolean("syncMovies", true) else true,
            syncSeries = if (o.has("syncSeries")) o.optBoolean("syncSeries", true) else true,
            // Pre-v17 backups omit these — fall back to the entity defaults (Prefer HLS off, follow
            // the global Pre-buffer, HLS support not yet known), which is what those installs had.
            preferHls = o.optBoolean("preferHls", false),
            livePrerollSecs = o.optInt("livePrerollSecs", FOLLOW_GLOBAL_PREROLL),
            // Pre-v19 backups omit these three, so they restore as "follow the global setting" — which
            // is exactly what those installs did, since the settings did not exist there.
            liveEnginePreference = o.optStringOrNull("liveEnginePreference"),
            liveLatencyMode = o.optStringOrNull("liveLatencyMode"),
            liveLatencyCustomSecs = o.optInt("liveLatencyCustomSecs", FOLLOW_GLOBAL_LATENCY_SECS),
            hlsSupported = HlsSupport.fromCode(o.optInt("hlsSupported", HlsSupport.UNKNOWN.code)),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            lastSyncAt = if (o.isNull("lastSyncAt")) null else o.optLong("lastSyncAt"),
        )
    }

    companion object {
        internal const val TAG = "BackupManager"

        /**
         * The `sources[].type` rule, kept pure so it's testable: a name this build's [SourceType]
         * knows, or null. Never a fallback — see [sourceFrom] for why coercing to M3U was worse
         * than skipping.
         */
        internal fun parseSourceType(raw: String?): SourceType? =
            raw?.takeIf { it.isNotBlank() }?.let { name -> SourceType.entries.firstOrNull { it.name == name } }

        /** What export writes since v4.2 — a [BackupContainer], not a bare JSON file. */
        const val BACKUP_FILENAME = "owntv-backup.own"

        /** The pre-4.2 name. Export never produces it any more; restore still accepts such files. */
        const val LEGACY_BACKUP_FILENAME = "owntv-backup.json"

        /** File-picker filter for restore: the new container plus every legacy `.json` in the wild. */
        val RESTORE_EXTENSIONS = setOf("own", "json")

        /**
         * Biggest wallpaper we will carry inside a backup (see `currentWallpaper`). Held below the
         * companion upload cap (`CompanionHttpServer.UPLOAD_BODY_LIMIT`, 16 MB) with room for base64's
         * ~33% inflation, so a backup that exports fine can always be sent back over the remote companion link.
         */
        private const val MAX_WALLPAPER_BYTES = 8L * 1024 * 1024

        /**
         * Total subtitle bytes a backup will carry. Sized so a worst-case file — 8 MB of wallpaper
         * plus this — still clears the companion upload cap (`CompanionHttpServer.UPLOAD_BODY_LIMIT`,
         * 16 MB) with room for base64 inflation. Subtitles are a few tens of KB each, so this holds
         * hundreds of them; beyond that the least recently used simply do not ride.
         */
        private const val MAX_SUBTITLE_BYTES = 4L * 1024 * 1024

        /**
         * `subtitle_timing.subtitleKey` prefix for an imported local file (see
         * `SubtitleRepository`: `openSubFileId?.let { "opensub:$it" } ?: "local:$cacheId"`). The
         * `opensub:` form is a global OpenSubtitles id and needs no remapping; this one names a
         * device-local cache row and does.
         */
        private const val LOCAL_SUB_PREFIX = "local:"

        internal const val TMP_SUFFIX = ".tmp"
        internal const val BAK_SUFFIX = ".bak"

        /**
         * Write [text] to [target] without ever leaving a truncated file behind: content goes to a
         * `.tmp` sibling, is flushed to disk (fsync) so a power cut can't leave an empty-but-renamed
         * file, the previous good backup rotates to `.bak`, and only then does the tmp take the final
         * name. A failure anywhere drops the tmp and leaves the old backup exactly as it was.
         *
         * Returns the **final** path — callers (the export UI, the companion download) rely on that.
         */
        internal fun writeAtomically(target: File, text: String): String =
            writeAtomically(target, text.toByteArray(Charsets.UTF_8))

        /** Byte-level [writeAtomically] — the container is binary, so text can't be the only entry point. */
        internal fun writeAtomically(target: File, bytes: ByteArray): String {
            val tmp = File(target.parentFile, "${target.name}$TMP_SUFFIX")
            try {
                java.io.FileOutputStream(tmp).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                    fos.fd.sync()
                }
                if (target.exists()) {
                    val bak = File(target.parentFile, "${target.name}$BAK_SUFFIX")
                    bak.delete()
                    target.renameTo(bak) // best effort: a failed rotation must not block the new write
                }
                if (!tmp.renameTo(target)) error("backup_finalize_failed")
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
            return target.absolutePath
        }
    }
}

// --- merge-restore id remapping helpers ---
//
// Top-level and `internal` rather than private members of BackupManager: they are pure String/JSON
// functions with no dependency on the manager's DAOs, and a merge-restore that gets one of them
// wrong silently attaches a restored profile's favorites, folder customizations or TMDB overrides to
// the WRONG source on the device. That is worth unit tests, and unit tests need them reachable.

/**
 * Identity a restored profile is merged onto: the display name, trimmed and case-folded. Case-folded
 * with Kotlin's locale-invariant [lowercase] on purpose — a Turkish-locale device must not decide
 * "Kids" and "KIDS" are different people and end up with two profiles.
 */
internal fun profileMatchKey(name: String) = name.trim().lowercase()

/**
 * Identity a restored source is merged onto. Username is part of the key because one portal commonly
 * serves several accounts; the password is not, so a re-exported backup with a rotated password
 * still updates the existing row instead of duplicating it.
 *
 * The MAC joins the key for **Stalker only** (#114). A Stalker source has no username, and several
 * playlists routinely share one portal URL, so type+url+username made every Stalker playlist in a
 * backup the same source: they all merged onto one device row, each one overwriting its MAC, and
 * every playlist's favorites/history/customizations collapsed onto that single row. Other source
 * types ignore the argument, so their keys are unchanged.
 */
internal fun sourceMatchKey(type: String, url: String, username: String?, mac: String? = null): String {
    val base = "$type|${url.trim()}|${username.orEmpty()}"
    return if (type == SourceType.STALKER.name) "$base|${normalizeMacForMatch(mac)}" else base
}

/** Compare MACs by value, not by punctuation: `aabb…` and `AA:BB:…` are the same portal login. */
private fun normalizeMacForMatch(mac: String?): String =
    mac?.takeIf { it.isNotBlank() }
        ?.let { StalkerClient.canonicalizeMac(it) ?: it.trim().uppercase() }
        .orEmpty()

/**
 * The device row [incoming] merges onto, or null to insert it as a new source. [candidates] holds
 * only rows no earlier incoming source has already claimed — a device row must never be claimed
 * twice, which is what let several Stalker playlists pile onto one row (#114).
 *
 * Exact key first. A Stalker source then falls back to portal+username when the MAC is unknown on
 * either side: a backup written WITHOUT a passphrase omits the MAC entirely (it is a secret), and
 * such a restore has to keep merging onto the existing playlist rather than duplicating it.
 */
internal fun matchSourceForRestore(candidates: List<SourceEntity>, incoming: SourceEntity): SourceEntity? {
    val key = sourceMatchKey(incoming.type.name, incoming.url, incoming.username, incoming.mac)
    candidates.firstOrNull { sourceMatchKey(it.type.name, it.url, it.username, it.mac) == key }?.let { return it }
    if (incoming.type != SourceType.STALKER) return null
    val portalKey = sourceMatchKey(incoming.type.name, incoming.url, incoming.username)
    return candidates.firstOrNull {
        (incoming.mac.isNullOrBlank() || it.mac.isNullOrBlank()) &&
            sourceMatchKey(it.type.name, it.url, it.username) == portalKey
    }
}

/** Rewrites an id-keyed JSON map ({"<fileId>": …}) to device ids; unmapped keys pass through. */
internal fun remapKeys(o: JSONObject, idMap: Map<Long, Long>): JSONObject {
    if (idMap.isEmpty()) return o
    val out = JSONObject()
    o.keys().forEach { k ->
        val mapped = k.toLongOrNull()?.let { idMap[it] }?.toString() ?: k
        out.put(mapped, o.get(k))
    }
    return out
}

/** Remaps a provider-folder context key while preserving custom and built-in context keys. */
internal fun remapContentContextKey(contextKey: String, sourceIdMap: Map<Long, Long>): String {
    val sourceId = contextKey.substringBefore(':').toLongOrNull() ?: return contextKey
    val mapped = sourceIdMap[sourceId] ?: return contextKey
    return "$mapped:${contextKey.substringAfter(':')}"
}

/**
 * Rewrites the "<sourceId>:<rest>" content keys inside one SectionCustomizations JSON blob.
 *
 * Custom category ids ("custom:<uuid>", the `customCats` array and any custom keys in the maps)
 * contain no source id — "custom" never parses as a Long, so [remapContentKey] passes them through
 * verbatim. The `customCats` array holds OBJECTS (id/name/icon) rather than strings, so the array
 * branch handles both element kinds; without this the whole blob fell back unremapped.
 */
internal fun remapCustomizationValue(raw: String, sourceIdMap: Map<Long, Long>): String {
    if (sourceIdMap.isEmpty()) return raw
    fun remapContentKey(k: String): String {
        val sid = k.substringBefore(':').toLongOrNull() ?: return k
        val mapped = sourceIdMap[sid] ?: return k
        return "$mapped:${k.substringAfter(':')}"
    }
    return runCatching {
        val o = JSONObject(raw)
        val out = JSONObject()
        o.keys().forEach { field ->
            when (val v = o.get(field)) {
                is JSONArray -> out.put(field, JSONArray().apply {
                    for (i in 0 until v.length()) {
                        val e = v.opt(i)
                        when (e) {
                            // Keyed list (hiddenCats, catOrder): remap content keys; custom keys pass through.
                            is String -> put(remapContentKey(e))
                            // customCats objects: ids are "custom:<uuid>" (never remapped); copy verbatim.
                            is JSONObject -> put(e)
                            else -> put(e)
                        }
                    }
                })
                is JSONObject -> {
                    if (field == "movedFrom") {
                        // movedFrom values are ORIGIN CATEGORY keys ("<sourceId>:…"), not labels —
                        // unlike every other map (hiddenItems → label, epgMatch → epg id), BOTH
                        // sides are content keys, so the values must be remapped too or the folder
                        // filter never matches after a cross-device restore.
                        out.put(field, JSONObject().apply { v.keys().forEach { k -> put(remapContentKey(k), remapContentKey(v.getString(k))) } })
                    } else {
                        out.put(field, JSONObject().apply { v.keys().forEach { k -> put(remapContentKey(k), v.get(k)) } })
                    }
                }
                else -> out.put(field, v)
            }
        }
        out.toString()
    }.getOrDefault(raw)
}

/** Rewrites "type:<sourceId>:<rest>" keys of the custom-TMDB-name map to device source ids. */
internal fun remapTmdbOverrideKeys(raw: String, sourceIdMap: Map<Long, Long>): String {
    if (sourceIdMap.isEmpty()) return raw
    return runCatching {
        val o = JSONObject(raw)
        val out = JSONObject()
        o.keys().forEach { k ->
            val parts = k.split(':', limit = 3)
            val mapped = if (parts.size == 3) {
                parts[1].toLongOrNull()?.let { sourceIdMap[it] }?.let { "${parts[0]}:$it:${parts[2]}" } ?: k
            } else k
            out.put(mapped, o.get(k))
        }
        out.toString()
    }.getOrDefault(raw)
}

/** Keeps only the entries of a profile-id-keyed JSON map that belong to the ticked profiles. */
internal fun filterByProfile(map: JSONObject, pidKeys: Set<String>): JSONObject {
    val out = JSONObject()
    map.keys().forEach { k -> if (k in pidKeys) out.put(k, map.get(k)) }
    return out
}

/**
 * Remaps the source id of a "<type>:<sourceId>:<rest>" content key — the shape the subtitle tables
 * and the TMDB overrides both use ("movie:7:1234", "episode:7:1234:S1E2"). Unlike [remapEnginePinKey]
 * the id is the SECOND segment, because the media type leads.
 */
internal fun remapTypedContentKey(key: String, sourceIdMap: Map<Long, Long>): String {
    if (sourceIdMap.isEmpty()) return key
    val parts = key.split(':', limit = 3)
    if (parts.size < 3) return key
    val mapped = parts[1].toLongOrNull()?.let { sourceIdMap[it] } ?: return key
    return "${parts[0]}:$mapped:${parts[2]}"
}

/** Keeps only the entries of a source-id-keyed JSON map whose source rides in this backup. */
internal fun filterBySourceId(map: JSONObject, sourceIds: Set<Long>): JSONObject {
    val out = JSONObject()
    map.keys().forEach { k -> if (k.toLongOrNull() in sourceIds) out.put(k, map.get(k)) }
    return out
}

/**
 * Remaps the leading source id of a per-item player key — [tv.own.owntv.core.player.enginePinKey]'s
 * "<sourceId>:<MEDIA_TYPE>:<remoteId>". Used for the compatibility-mode pins and the per-item
 * zoom/volume rows, both of which embed the id of the source the item came from.
 *
 * A key in the older stream-URL shape has no source id to remap ("http" never parses as a Long), and
 * an id with no entry in [sourceIdMap] took no part in this restore — both pass through unchanged.
 */
internal fun remapEnginePinKey(key: String, sourceIdMap: Map<Long, Long>): String {
    if (sourceIdMap.isEmpty()) return key
    val sourceId = key.substringBefore(':').toLongOrNull() ?: return key
    val mapped = sourceIdMap[sourceId] ?: return key
    return "$mapped:${key.substringAfter(':')}"
}

/**
 * Scopes per-item player keys to the sources riding in this backup.
 *
 * [allowUrlKeys] admits the legacy stream-URL keys, and is true only when a passphrase was given:
 * an IPTV stream URL routinely carries the account's username and password in its path or query, so
 * in an unencrypted backup those keys are exactly the credential leak the secret policy exists to
 * prevent. A stable key is only ever "<sourceId>:<TYPE>:<remoteId>" and leaks nothing.
 */
internal fun filterEnginePinKeys(
    keys: Collection<String>,
    sourceIds: Set<Long>,
    allowUrlKeys: Boolean,
): List<String> = keys.filter { key ->
    val sourceId = key.substringBefore(':').toLongOrNull()
    if (sourceId == null) allowUrlKeys else sourceId in sourceIds
}

/** Keeps the "type:<sourceId>:<rest>"-keyed TMDB overrides whose source rides in this backup. */
internal fun filterTmdbOverridesBySourceId(raw: String, sourceIds: Set<Long>): String {
    if (raw.isBlank()) return raw
    return runCatching {
        val o = JSONObject(raw)
        val out = JSONObject()
        o.keys().forEach { k ->
            val parts = k.split(':', limit = 3)
            if (parts.size == 3 && parts[1].toLongOrNull() in sourceIds) out.put(k, o.get(k))
        }
        if (out.length() == 0) "" else out.toString()
    }.getOrDefault(raw)
}

/** Seals every string value of a flat JSON map, leaving the keys readable. */
internal fun sealValues(map: JSONObject, seal: (String) -> JSONObject): JSONObject {
    val out = JSONObject()
    map.keys().forEach { k -> (map.opt(k) as? String)?.let { out.put(k, seal(it)) } }
    return out
}

/** Opens a [sealValues] map. Entries that cannot be decrypted (no key / wrong key) are dropped. */
internal fun unsealValues(map: JSONObject, unseal: (Any?) -> String?): JSONObject {
    val out = JSONObject()
    map.keys().forEach { k -> unseal(map.opt(k))?.let { out.put(k, it) } }
    return out
}

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

/** Reads a JSON array as a list of non-blank strings, tolerating nulls/non-string entries. */
private fun jsonStrings(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    val out = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
        val v = arr.opt(i)
        if (v is String && v.isNotBlank()) out += v
    }
    return out
}
