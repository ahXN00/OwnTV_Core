package tv.own.owntv.core.i18n

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The single source of truth for the selected application locale, as a BCP-47 tag. `""`
 * ([AppLocale.SYSTEM_DEFAULT_TAG]) means "follow the current device locale list".
 *
 * **Why SharedPreferences and not DataStore** (see `docs/internationalization.md` 0b): the selected
 * locale must be readable *synchronously* from `Application.attachBaseContext` and
 * `Activity.attachBaseContext`. DataStore is asynchronous and cannot be read cleanly from those
 * lifecycle hooks without blocking or redesigning startup. This one bootstrap-critical setting
 * therefore lives in SharedPreferences — alone, with no DataStore mirror, no dual write, and no
 * mirror-repair collector (all of v1's reconciliation complexity is gone because OwnTV no longer
 * participates in the Android system per-app-language screen).
 *
 * Every locale write goes through [set]: the in-app picker, "reset to system default" (`""`),
 * backup import, and any settings-reset operation. There is no second write path.
 */
class LocaleStore internal constructor(
    private val preferences: SharedPreferences,
    /**
     * The application context for [applyGlobally] after an in-session write, or null for the throwaway
     * read-only instances built in `attachBaseContext` (which only call [readBlocking]; a write there
     * would be a bug). The Koin singleton is constructed with the real `Application` so writes update
     * the process `Locale` defaults immediately (see [set]).
     */
    private val applicationContext: Context?,
) {

    private val _currentTag: MutableStateFlow<String> = MutableStateFlow(readBlocking())

    /**
     * Serializes [set] so concurrent writers (rapid picker taps, overlapping backup import) cannot
     * interleave IO commits with StateFlow / process-locale updates out of call order.
     */
    private val writeMutex = Mutex()

    /** The currently selected tag, observable in-process. `""` means follow system. */
    val currentTag: StateFlow<String> = _currentTag.asStateFlow()

    /**
     * Synchronous read of the persisted tag. Safe to call from `attachBaseContext`.
     * Returns `""` (never null) when nothing is stored — i.e. follow the system default.
     */
    fun readBlocking(): String {
        // SharedPreferences can contain a value of the wrong primitive type after a damaged or
        // hand-edited migration; getString itself throws ClassCastException in that case. A corrupt
        // locale must never take down Application.attachBaseContext, so treat every read failure as
        // the system-default selection.
        val stored = runCatching { preferences.getString(KEY_UI_LANGUAGE, "") }.getOrNull()
        return normalize(stored) ?: AppLocale.SYSTEM_DEFAULT_TAG
    }

    /** Canonicalizes a persisted/imported value, or returns null when it is not supported. */
    fun normalize(raw: String?): String? = SupportedLocales.canonicalTag(raw)

    /**
     * Durably persists [tag], publishes it to [currentTag], and re-applies the process `Locale` /
     * `LocaleList` defaults so `java.text`, `java.time`, `String.format` and every other
     * `Locale.getDefault()` reader follow the new selection immediately — not on the next unrelated
     * configuration callback. `LocalizedContent` handles the Compose locals and the script-family
     * `Activity.recreate()`; this handles the non-Compose `Locale.getDefault()` readers (see
     * `docs/internationalization.md` 0b, "The write path updates process defaults").
     *
     * Uses `commit()` (synchronous, durable) off the main thread so the operation only returns after
     * the value is on disk — the locale is needed on the *next cold start*, so durability is required,
     * not "best effort".
     *
     * Returns `true` when the write was committed. A `false` (failed `commit`) is surfaced as an
     * [IllegalStateException] rather than swallowed: a silent locale-write failure would leave the
     * user thinking they switched language while nothing persisted.
     */
    @SuppressLint("UseKtx") // KTX edit {} cannot return commit()'s result, which is checked here
    suspend fun set(tag: String): Boolean = writeMutex.withLock {
        val canonical = normalize(tag)
            ?: throw IllegalArgumentException("Unsupported application locale: ${tag.trim()}")
        val committed = withContext(Dispatchers.IO) {
            preferences.edit().putString(KEY_UI_LANGUAGE, canonical).commit()
        }
        check(committed) { "Failed to persist application locale" }
        _currentTag.value = canonical
        applicationContext?.let { AppLocale.applyGlobally(canonical) }
        true
    }

    companion object {
        private const val KEY_UI_LANGUAGE = "ui_language"
        private const val PREFS_NAME = "owntv_locale"

        /**
         * A [LocaleStore] over the application-scoped `owntv_locale` SharedPreferences. Used both at
         * cold start (Koin is not yet started in `attachBaseContext`, so callers there build this
         * directly from the base context) and as the shared Koin singleton afterwards — the file is
         * the same, so the persisted value is always consistent. The in-process [StateFlow] is
         * per-instance, so callers that must observe writes (the picker, renderers) take the Koin
         * singleton rather than building their own.
         *
         * The bootstrap path never dereferences `context.applicationContext`. During
         * `Application.attachBaseContext` the framework has not yet assigned `LoadedApk.mApplication`,
         * so the supplied base context is used directly for preferences and the application reference
         * is simply null. The Koin singleton is built from the fully-initialised `Application` after
         * `startKoin`, where writes may update process defaults.
         */
        fun from(context: Context): LocaleStore {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // Do not call context.applicationContext here: during Application.attachBaseContext the
            // framework has not assigned LoadedApk.mApplication yet. The fully initialized Koin path
            // passes the Application itself; all bootstrap/read-only contexts intentionally get null.
            val app = context as? Application
            return LocaleStore(prefs, app)
        }

        /**
         * A store over an arbitrary [preferences] file, for instrumentation that must not touch the
         * real one. The constructor itself stays module-private so nothing in the app can build a
         * second store over the live preferences by accident. Pass [applicationContext] only when
         * the test exercises the *write* path and needs [set] to apply the locale process-wide;
         * leave it null for read-only use.
         */
        @androidx.annotation.VisibleForTesting
        fun overPreferences(
            preferences: SharedPreferences,
            applicationContext: Context? = null,
        ): LocaleStore = LocaleStore(preferences, applicationContext)
    }
}