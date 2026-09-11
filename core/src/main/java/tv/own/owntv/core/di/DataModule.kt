package tv.own.owntv.core.di

import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import tv.own.owntv.core.backup.BackupManager
import tv.own.owntv.core.database.BulkInsertHelper
import tv.own.owntv.core.backup.UserDataResolver
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.download.DownloadEngine
import tv.own.owntv.core.download.DownloadManager
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.parser.M3uParser
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.launcher.LauncherIntegrationRepository
import tv.own.owntv.core.launcher.LauncherLaunchResolver
import tv.own.owntv.core.launcher.LauncherRecommendationPlanner
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.repository.SeriesRepository
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.tv.TvHomeRepository
import tv.own.owntv.core.update.UpdateManager
import tv.own.owntv.core.sync.SyncManager
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
import tv.own.owntv.core.sync.work.EpgSyncScheduler
import tv.own.owntv.core.weather.WeatherRepository
import java.util.concurrent.TimeUnit

/** Networking, parsers, sync engine, and repositories (Phase 5). */
val dataModule = module {
    // Live snapshot of the global proxy. Backs OkHttp's ProxySelector/Authenticator AND mpv's http-proxy,
    // so the proxy can be toggled at runtime without rebuilding the singleton OkHttpClient below.
    single { tv.own.owntv.core.network.ProxyConfigHolder(get<tv.own.owntv.core.settings.SettingsRepository>().proxyConfig) }
    // Live snapshot of the global custom DNS (plain UDP or DoH). Same pattern as proxy — reads the
    // live DataStore snapshot so DNS can be toggled without rebuilding the OkHttpClient singleton.
    single { tv.own.owntv.core.network.DnsConfigHolder(get<tv.own.owntv.core.settings.SettingsRepository>().dnsConfig) }
    single {
        val proxyHolder = get<tv.own.owntv.core.network.ProxyConfigHolder>()
        val dnsHolder = get<tv.own.owntv.core.network.DnsConfigHolder>()
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)  // fast fail on dead host
            .readTimeout(20, TimeUnit.SECONDS)    // detect mid-sync disconnect quickly
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)       // let SyncManager handle retries, not OkHttp
            // Global proxy (Approach 1): a ProxySelector/Authenticator that read the live snapshot, so
            // enabling/disabling the proxy takes effect immediately. Proxy off = DIRECT = exact prior
            // behavior. Credentials are never logged.
            .proxySelector(proxyHolder.proxySelector)
            .proxyAuthenticator(proxyHolder.proxyAuthenticator)
            // Global custom DNS: a Dns that reads the live snapshot so the DNS server can be changed
            // at runtime without rebuilding this singleton client. Off = system DNS = exact prior behavior.
            .dns(dnsHolder.dns)
            // Force HTTP/1.1. Several IPTV panels / EPG hosts (and their CDNs) have flaky HTTP/2 stacks
            // that send RST_STREAM(PROTOCOL_ERROR) on large/slow responses — e.g. big EPG XML downloads
            // (#17) — which OkHttp surfaces as "stream was reset: PROTOCOL_ERROR". HTTP/1.1 sidesteps it
            // with no real downside for our mostly-single-stream downloads.
            .protocols(listOf(Protocol.HTTP_1_1))
            // Default a player-style UA for any request that didn't set one (e.g. Coil image loads),
            // since some IPTV panels reject the stock OkHttp UA. Per-source UAs still override this.
            .addInterceptor { chain ->
                val req = chain.request()
                val out = if (req.header("User-Agent").isNullOrBlank()) {
                    req.newBuilder().header("User-Agent", HttpClient.DEFAULT_USER_AGENT).build()
                } else {
                    req
                }
                chain.proceed(out)
            }
            .build()
    }
    // The playback engines' client: same proxy/DNS/UA/protocol configuration as the singleton above,
    // but its own connection pool, so a live stop can evict *stream* sockets without dropping keep-alive
    // for EPG, panel API, metadata and image traffic (F28).
    single { tv.own.owntv.core.network.StreamingHttpClient(get()) }
    single { HttpClient(get()) }
    single { ConnectivityObserver(androidContext()) }
    single { CustomizationStore(androidContext()) }
    single { tv.own.owntv.core.epg.EpgSourceStore(androidContext()) }
    single { tv.own.owntv.core.player.ForceMpvStore(androidContext()) }
    single { tv.own.owntv.core.player.ArchiveDecodeStore(androidContext()) }
    // Per-channel "watched without a picture" memory, for the mobile audio-only mode.
    single { tv.own.owntv.core.player.AudioOnlyStore(androidContext()) }
    // Per-item zoom/volume the player remembers (playbackPrefsDao, settings).
    single { tv.own.owntv.core.player.PlaybackPrefsStore(get(), get()) }
    single { tv.own.owntv.core.player.ExternalPlayerLauncher(androidContext()) }
    // store, sourceDao, epgRepository
    single { tv.own.owntv.core.epg.EpgMigration(get(), get(), get()) }
    single { M3uParser() }
    single { XtreamClient(get()) }
    // Stalker portal (plan Phase A/B): protocol client on the shared OkHttpClient + in-memory sessions.
    single { tv.own.owntv.core.stalker.StalkerClient(get()) }
    single { tv.own.owntv.core.stalker.StalkerAuthManager(get()) }
    single { tv.own.owntv.core.stalker.StreamUrlResolver(get(), get()) }
    // The portal's own guide — the only EPG a Stalker portal that publishes no XMLTV has.
    single { tv.own.owntv.core.stalker.StalkerEpgLoader(get(), get()) }
    // http, xtreamClient, stalkerAuth — the Test button behind each saved playlist row.
    single { tv.own.owntv.core.repository.SourceTester(get(), get(), get()) }
    // TMDB metadata enrichment (plan §4): one provider, three tiers resolved from SettingsRepository.
    // Opaque per-install id sent to the default Worker only, so one abusive install can be capped
    // without blocking the IP address a whole household/carrier NAT shares.
    single { tv.own.owntv.core.metadata.OwnTVClientId(androidContext()) }
    // Per-install allowance for the shared default Worker (40/min, 150/hr, 400/day). Own key and
    // self-hosted server are never metered.
    single { tv.own.owntv.core.metadata.MetadataBudget(androidContext()) }
    single<tv.own.owntv.core.metadata.MetadataProvider> {
        tv.own.owntv.core.metadata.TmdbProvider(get(), get(), get(), get())
    }
    // provider, metadataDao, settings, overrideStore — the on-demand resolve + cache orchestrator (plan §7, §11.2 U5b).
    single { tv.own.owntv.core.metadata.MetadataRepository(get(), get(), get(), get(), get()) }
    // Gates the TMDB Trending download to once every 5–8 days per playlist and holds the shared
    // candidate list; deliberately DataStore, not Room (derived state, no migration, no backup).
    single { tv.own.owntv.core.trending.TrendingScheduleStore(androidContext()) }
    single { tv.own.owntv.core.trending.TrendingRepository(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // Per-content TMDB name overrides (plan §11.2 U5b): DataStore side-store, no Room schema change.
    single { tv.own.owntv.core.metadata.MetadataOverrideStore(androidContext()) }
    // OpenSubtitles (subtitle plan Phase 1): Worker-proxied REST client + Keystore-sealed
    // per-profile sessions + the sign-in/out orchestrator with one-shot silent re-login.
    single { tv.own.owntv.core.subtitles.OpenSubtitlesClient(get(), get(), get()) }
    single { tv.own.owntv.core.subtitles.OpenSubtitlesAuthStore(androidContext()) }
    single { tv.own.owntv.core.subtitles.OpenSubtitlesAccountManager(get(), get()) }
    // context, client, accountManager, okHttpClient, subtitleDao — search/download/cache orchestration
    single { tv.own.owntv.core.subtitles.SubtitleRepository(androidContext(), get(), get(), get(), get()) }
    single { WeatherRepository(get(), get()) }
    // Per-item VOD engine pins made with the player's gear toggle (VOD counterpart of ForceMpvStore).
    single { tv.own.owntv.core.player.VodEngineStore(androidContext()) }
    // Remote (companion) add-source LAN server — one shared instance for Setup + Settings.
    single { tv.own.owntv.core.companion.CompanionController(androidContext(), get()) }
    // Local sync between two OwnTV devices on the same Wi-Fi (Plan 4 Phase 12). The payload is a
    // backup container, the listener is the companion server, and the merge is the restore path —
    // what is new here is the client, the pairing and the discovery.
    single { tv.own.owntv.core.sync.local.PairedDeviceStore(androidContext()) }
    single { tv.own.owntv.core.sync.local.LocalSyncClient() }
    single { tv.own.owntv.core.sync.local.LocalSyncDiscovery(androidContext()) }
    single {
        tv.own.owntv.core.sync.local.LocalSyncManager(
            context = androidContext(),
            companion = get(),
            backups = get(),
            paired = get(),
            client = get(),
            discovery = get(),
        )
    }
    single { BulkInsertHelper(get()) }
    single {
        tv.own.owntv.core.sync.ImportFinalizer(
            channelDao = get(),
            movieDao = get(),
            seriesDao = get(),
            db = get(),
            bulkInsertHelper = get(),
            metadataDao = get(),
            epgSourceStore = get(),
        )
    }
    // context, channelDao, movieDao, seriesDao, profileDao, favoriteDao, historyDao, progressDao,
    // contentOrderDao, customCategoryDao, seriesSortOrderDao, tombstoneDao, db
    single { UserDataResolver(androidContext(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // The one place a user deletion is written: it records the tombstone local sync needs and does
    // the delete in the same transaction. db, favoriteDao, historyDao, progressDao, customCategoryDao, userData
    single { tv.own.owntv.core.backup.UserDataWriter(get(), get(), get(), get(), get(), get()) }
    // sourceDao, syncManager, userDataResolver, channelDao, movieDao, seriesDao, categoryDao
    single { SourceRepository(get(), get(), get(), get(), get(), get(), get()) }
    // settings, sourceRepository, channelDao, movieDao, seriesDao
    single { tv.own.owntv.core.nav.NavVisibility(get(), get(), get(), get(), get()) }
    single {
        SyncManager(
            context = androidContext(),
            sourceDao = get(),
            categoryDao = get(),
            channelDao = get(),
            movieDao = get(),
            seriesDao = get(),
            xtream = get(),
            m3u = get(),
            http = get(),
            bulkInsertHelper = get(),
            stalkerClient = get(),
            stalkerAuth = get(),
            activityTracker = get(),
            customize = get(),
            settings = get(),
        )
    }
    // App-wide "sync running" signal for the shell status pill (every sync funnels through SyncManager).
    single { tv.own.owntv.core.sync.SyncActivityTracker() }
    // Same idea for EPG: EpgSyncWorker reports started/progress/finished here so the pill also reflects
    // guide/EPG downloads (manual resync from Settings, auto startup refresh, …).
    single { tv.own.owntv.core.sync.EpgActivityTracker() }
    single { tv.own.owntv.core.sync.TrendingActivityTracker() }
    // epgDao, httpClient, xtreamClient, channelDao, customize, settings, context, db, bulkInsertHelper
    single {
        EpgRepository(
            epgDao = get(),
            http = get(),
            xtream = get(),
            channelDao = get(),
            customize = get(),
            settings = get(),
            context = androidContext(),
            db = get(),
            bulkInsertHelper = get(),
            sourceDao = get(),
            stalkerEpg = get(),
        )
    }
    // seriesDao, sourceDao, xtreamClient, userDataResolver, stalkerClient, stalkerAuthManager
    single { SeriesRepository(get(), get(), get(), get(), get(), get()) }
    // sourceDao, movieDao, seriesDao, progressDao
    single { LauncherRecommendationPlanner(get(), get(), get(), get(), get(), get()) }
    // sourceDao, channelDao, movieDao, seriesDao, progressDao
    single { LauncherLaunchResolver(get(), get(), get(), get(), get(), get(), get()) }
    // context, sourceDao, channelDao, movieDao, seriesDao, progressDao, tvProviderProgramDao, customize, settings, localeStore
    single { TvHomeRepository(androidContext(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // planner, resolver, tvHomeRepository
    single { LauncherIntegrationRepository(get(), get(), get()) }
    // downloadDao, okHttpClient, sourceDao, movieDao, seriesDao, streamUrlResolver
    // (the last four are D-3: Stalker downloads resolve the stored cmd at download-start time)
    single { DownloadEngine(get(), get(), get(), get(), get(), get()) }
    // context, downloadDao, settings, engine
    single { DownloadManager(androidContext(), get(), get(), get()) }
    // profileDao, sourceDao, settings, customizationStore, userDataResolver, epgSourceStore,
    // forceMpvStore, vodEngineStore, db, metadataOverrideStore, metadataDao, openSubtitlesAuthStore,
    // backgroundsDir (same folder ingestBackgroundImage writes to — the .own container carries the wallpaper),
    // subtitlesDir (SubtitleRepository's shared cache — the container carries the subtitle files too)
    single {
        BackupManager(
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            java.io.File(androidContext().filesDir, "backgrounds"),
            java.io.File(androidContext().filesDir, "subtitles"),
            get(),
        )
    }
    // profileDao, sourceDao, settings, launcherIntegration, openSubtitlesAccounts — creating,
    // editing, switching and deleting a profile, shared by both apps' profile gates.
    single { tv.own.owntv.core.profile.ProfileAvatarStore(androidContext()) }
    single { tv.own.owntv.core.profile.ProfileManager(get(), get(), get(), get(), get(), get()) }
    // context, okHttpClient — in-app updates from GitHub Releases
    single { UpdateManager(androidContext(), get()) }
    single { CatalogSyncScheduler(androidContext()) }
    single { EpgSyncScheduler(androidContext()) }
    // profileDao, sourceDao, sourceRepository, backup, settings, connectivity, importFinalizer,
    // launcherIntegration, catalogSyncScheduler, stalkerAuth — onboarding: add a source, sync it,
    // undo it when it fails. Factory, not single: each wizard run owns its own state machine.
    factory { tv.own.owntv.core.setup.SourceImporter(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
