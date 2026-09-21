# OwnTV Core — Changelog

Core is versioned independently of the apps. A core version never lines up with an OwnTV TV app
`v4.x` or a mobile `v0.x` release, and the two must not be confused. Tags here are prefixed `core-`.

> **Format.** Each release is a short list: what changed, and anything a consuming app has to know —
> a new API, a database version, a backup format, a breaking signature. Releases up to **core-1.0.36**
> are the original long-form notes and are kept as written, folded away at the end of this file.

**How to read the markers**

| Marker | Means |
|---|---|
| **DB vNN** | The Room database version changed — a migration ships with it |
| **Backup vNN** | The backup container format changed |
| **API** | New or changed public API for a consuming app |
| **Breaking** | A consuming app must change to take this version |
| **Strings** | New user-visible text, in every packaged locale |

---

## core-1.0.56 — 2026-09-21

Fixes a crash that stops the app starting at all after upgrading a database that is still at
**v40** — the version both apps shipped on `core-1.0.42`. No database, backup, API or string change:
the schema and every migration's result are exactly as in `core-1.0.55`.

### A migration opened its own transaction, and the app could never start again

`MIGRATION_40_41` — the one that fills `epg_channels.normName` / `.normId` — wrapped each batch of
its backfill in `BEGIN IMMEDIATE TRANSACTION` … `COMMIT TRANSACTION`. Room already runs the whole
migration chain inside one transaction, so that is a nested `BEGIN`, and SQLite refuses it:

```
android.database.SQLException: Error code: 1, message: cannot start a transaction within a transaction
```

Room then aborts the upgrade and retries it on the next open, so the failure is permanent — the
launcher icon opens a splash screen and the process dies, every time, with no way back short of
clearing the app's data.

**Why it shipped green.** Until `core-1.0.49` Room was driven through Android's own SQLite engine,
and that engine's session layer *intercepts* a bare `BEGIN` / `COMMIT` / `ROLLBACK` and maps it onto
its own transaction API — so the statement was a no-op and the backfill simply ran inside Room's
transaction. `core-1.0.49` moved to `BundledSQLiteDriver`, which hands the statement to SQLite
itself, which refuses it. `OwnTVDatabaseMigrationTest` kept building with `AndroidSQLiteDriver`,
so the one test written to prove this exact upgrade was still being run on the engine that hides the
bug.

The manual transaction is gone; the batching that keeps the row buffer bounded stays. Nothing is
lost by dropping it — an interrupted upgrade now rolls back rather than keeping whole batches, and
either way a `NULL` there simply means "normalize this one on the fly". `OwnTVDatabaseMigrationTest`
now opens with `BundledSQLiteDriver`, the engine production actually uses, so the same class of
mistake fails the test instead of the phone.

**Who this affects.** Anyone upgrading from a build pinned to `core-1.0.42` or earlier — OwnTV TV
`v5.0.0` and OwnTV Mobile `v1.0.0` — straight to a build on `core-1.0.49`…`1.0.55`. A device whose
database is already past v41 never runs this migration and was never affected.

## core-1.0.55 — 2026-09-21

Records MPEG-DASH live channels instead of quietly writing rubbish, and gives films and episodes the
Format row they never had. **API** — no database, backup or string change, and nothing that records
today changes behaviour.

### An unprotected DASH channel is recorded, not looped into a reconnect storm

`core-1.0.54` made these channels *play*. Recording one still fell through to the raw byte pump: the
manifest fetched fine, `HlsMediaPlaylist.looksLikePlaylist` did not match XML, and a few kilobytes of
MPD went into the recording file. `read()` then returned −1 — a manifest is a finite document — which
the pump reads as a dropped live stream and reports as `NETWORK`. `NETWORK` is not terminal, so the
engine waited, reconnected and **appended the same manifest again**, for the whole window. The
result was a file of hundreds of concatenated XML manifests, a reconnect storm against the provider,
one of the account's connections held the entire time, and a row that blamed the network. The same
shape as the DRM bug fixed in `core-1.0.54`, from the same cause: a document reaching a pump that
expects video.

`RecordingEngine.attemptRecord` now asks `DashManifest.looksLikeDashManifest` as well, on the body
and the content type both, and hands a manifest to the new `recordDash`.

### The DASH recorder

`DashManifest` reads what a recorder needs and ignores what it does not — `SegmentTemplate` with
`$Number$` or a `SegmentTimeline`, `SegmentList`, `SegmentBase`, `BaseURL` stacking, `dynamic` versus
`static`, `minimumUpdatePeriod`, `availabilityStartTime`, `timeShiftBufferDepth`, and
`<ContentProtection>`. Parsed with `javax.xml.parsers` rather than `android.util.Xml`, so every rule
in it is unit-tested without a device. Unknown elements are ignored, the stance `HlsMediaPlaylist`
already takes.

`DashRecordingPlan` holds the arithmetic: which Representations to record, which segments are due,
and how long to wait. Three decisions in it are deliberate.

- **The Representations are fixed at the start and never followed.** Highest bitrate wins, and a
  quality that disappears mid-programme ends the recording with a reason rather than being replaced.
  A resolution or codec change partway through is exactly what the mux cannot absorb.
- **Segments are identified by number, never by URL** — the rule `recordHls` already follows, because
  several providers sign each segment individually and a URL cached for one cycle is a 403 in the
  next.
- **A cycle is capped at 24 segments, and which end it takes from depends on the manifest.** A live
  stream keeps the newest, so a recorder coming back from a stall rejoins the edge instead of falling
  further behind; a static window — catch-up — keeps the oldest, because those are the opening
  minutes of the programme.

A live `$Number$` template with no `availabilityStartTime` has no zero point to count from and is
refused rather than guessed at, which would request thousands of segments that were never published.

### Two tracks, one file

DASH normally keeps video and audio in separate Representations, so concatenation — all HLS ever
needed — yields two half-files. `DashRemux` puts them back together with `MediaExtractor` and
`MediaMuxer`: plain `android.media`, no FFmpeg, no new dependency, and nothing that reaches across
into `:player-core`. Samples are copied untouched, interleaved by presentation time so the two stay
in step.

**A Representation that already carries both is written straight into the recording**, with no temp
file and no mux at all — the same path HLS takes.

**A muxed recording is named `.mp4`, and the row's `filePath` moves with it.** `RecordingRules`
chooses `.ts` because a transport stream plays while it is being written and survives being cut off;
the muxed file is neither, and a file manager, a media scanner and every external player go by the
extension. `RecordingRules.muxedNameOf` is the single rule. Consuming apps need no change — they
already read `filePath` from the row.

### An interrupted recording is finished on the next run

The mux runs once, at the end. `MediaMuxer` cannot append to an existing MP4, so remuxing
periodically would mean redoing the whole recording each time — sixty passes over a two-hour
programme. The temp files are the crash-proof part instead: each is a valid fragmented-MP4 stream,
flushed a segment at a time. `recoverInterruptedDashRecordings` runs once per drain, before anything
starts, and turns the ones left behind by a crash or a battery death into a playable recording. If
the mux itself fails, the captured bytes are **kept** rather than deleted, and tried once more there.

A recording whose window is still open when the app restarts is not resumed — it restarts, losing
what it captured before the crash. Resuming would need certainty that this run picks the same
Representations as the last one, and nothing on disk records what those were.

New: `RecordingDao.running()`, a `@Query` only — **no schema change, no migration, v43 stands**.

### Films and episodes finally show a Format row

`ExoSubtitleEngine.streamInfo()` emitted Video, HDR, bitrate, Audio and buffer rows and **no Format
row at all**, so the Stream info overlay had no Format line on VOD. Live knew its answer because it
*chose* the container; VOD handed the URL to Media3 and never asked what it concluded.

`StreamFormatLabels` is now the one vocabulary for all three engines — `HLS`, `DASH`, `MPEG-TS`,
`MP4`, `MKV`. `StreamRoute.formatLabel` reads its three values from there, VOD derives its label from
the container Media3 actually resolved, and **mpv was tidied to match**: it reported FFmpeg's raw
demuxer name, so a film read `MOV,MP4,M4A,3GP,3G2,MJ2` and an MKV read `MATROSKA,WEBM`. The same film
now reads `MP4` whichever engine is playing it. Anything still unrecognised keeps mpv's existing
raw-name fallback rather than showing a blank row.

`MP4` and `MKV` are deliberately **not** `StreamRoute` entries: they are containers, not routes, and
there is no MKV route to tune.

### Tests

81 new tests in `:core` covering the manifest reader, the scheduling arithmetic, the routing decision
and the failure modes, and 13 in `:player-core` for the label vocabulary — two of which pin that mpv
and ExoPlayer produce the *same* string for an MP4 and for an MKV.

---

## core-1.0.54 — 2026-09-21

Plays DRM-protected and plain MPEG-DASH live channels, which previously failed before the first
frame on both apps. **DB v43**, **API**, **Strings** — no backup change, and no channel that plays
today changes route.

### DASH channels play

A playlist can publish a channel at an address that says nothing about its container —
`https://host/live/mpd/173`, no extension — and only redirect to the real `…/render.mpd` once asked.
Media3 picks its media source *before* that redirect, and the choice was binary: HLS or progressive.
DASH had no way in at all, even though `media3-exoplayer-dash` was already on the classpath. Every
such channel was handed to the progressive extractor, which sniffed an XML manifest and stopped with
`ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` before the first frame. With DRM in play the item is
pinned to ExoPlayer — mpv has no CDM — so there was no fallback rung left and the channel simply
died.

`StreamRoute { HLS, DASH, PROGRESSIVE }` replaces that boolean, and `LivePreviewEngine.routeFor()`
makes the choice from every piece of evidence at once, most specific first. A DASH route names
`MimeTypes.APPLICATION_MPD` on the `MediaItem`, which is all `DefaultMediaSourceFactory` needs to
build a `DashMediaSource` — carrying the DRM session manager the item already had.

Three independent ways in, because no single one covers every source type:

- **The playlist's own declaration.** `#KODIPROP:inputstream.adaptive.manifest_type` was parsed and
  thrown away; it is now read into `ManifestType { MPD, HLS, ISM }` and stored. This makes the
  *first* attempt correct, with no failed try. `ism` is stored but deliberately not routed —
  `media3-exoplayer-smoothstreaming` is not a dependency, so it keeps exactly today's behaviour.
- **The response itself.** `isDashResponse()` recognises `application/dash+xml`,
  `video/vnd.mpeg.dash.mpd`, or a final URL whose path ends `.mpd`, and re-opens the same URL as
  DASH. This is the **only** route for Stalker (the portal hands back its own `cmd`) and Xtream (the
  live URL is one core builds as `.ts`) — neither can ever carry a declaration. The lesson is
  remembered per panel, so one channel's discovery spares the rest.
- **VOD too.** A protected film or episode published the same way now routes identically.

### Stream info reported DASH channels as MPEG-TS

The overlay's Format row was `if (isHls) "HLS" else "MPEG-TS"` — two values, so a third route fell
into the else and read as raw TS. It stayed wrong even on a tune that never opened, which is what
users were screenshotting. It now reports `HLS` / `DASH` / `MPEG-TS` from the route actually taken.

### A last-resort address for an Xtream channel that will not open

`ChannelEntity.directSource` stores the panel's own `direct_source`, when it publishes one. It is
**never** tuned first and never replaces `streamUrl`: panels build that field from the streaming
server's configured domain and fall back to its raw IP, so a misconfigured panel or load balancer
publishes an address only reachable inside their own network — which is why every major client
ignores it. It is tried once, last, after every other rung is spent, where the alternative is an
error screen. Used that way it can only add channels that would otherwise fail, never take one away.
Refused for a *request* refusal (429/458), which a different address cannot answer.

### Recording a DRM channel is refused instead of attempted

`RecordingFailure.DRM_PROTECTED`, checked before the request is made. A protected channel used to
fall through to the raw byte pump, which wrote the provider's response into the file, hit
end-of-body, reported `NETWORK` — a reason that is *not* terminal — and so reconnected and appended
again for the whole length of the programme. The user got an unplayable file, the provider got hours
of reconnects, and one of the account's connection slots was held throughout by a recording that
could never succeed. The CDM decrypts only into a secure decoder for immediate display, so there is
no point at which the frames exist in the clear to write down; the reason is terminal for the same
reason `ENCRYPTED` is.

Kept distinct from `ENCRYPTED`, which is HLS transport encryption (`#EXT-X-KEY`, usually plain
AES-128) found inside a playlist that had to be fetched first. Telling a user with an AES-128 channel
that it is DRM-protected would be false.

### Database

**v43** — `manifestType` on `channels`, `movies` and `episodes`; `directSource` on `channels`.
Additive `ALTER TABLE` only, NULL on every existing row, and a NULL row behaves exactly as before, so
an upgraded install is unchanged until its playlist is re-synced. No table rewrite even on a
170k-item catalog. Both new fields fold into `computeContentHash` **only when non-null**, so no
existing hash moves and no catalog is rewritten on the next sync.

### For a consuming app

`OwnTVPlayer.play()`, `PlaylistItem` and `LivePreviewEngine.play()` take `manifestType`, and
`LivePreviewEngine.play()` also takes `directSource`. Both default to null, so an app that passes
neither compiles and behaves exactly as before — but a DASH channel only routes correctly on the
first attempt if the app passes `channel.manifestType`, and the last-resort rung only exists if it
passes `channel.directSource`.

## core-1.0.53 — 2026-09-20

Adds the settings and strings behind the TV app's second Movies & Series layout. **Strings**, **API**
— no database or backup change, and no existing behaviour moves.

### A layout choice for Movies & Series

`SettingsRepository.VodLayout { SEPARATE, CINEMATIC }`, with `vodLayout: Flow<VodLayout>` and
`setVodLayout()`, keyed `vod_layout`. It defaults to `SEPARATE` — the three-panel layout both apps
draw today — so nothing changes for an existing install until the user picks otherwise. Modelled on
the existing `vodViewMode`, and it rides with the settings backup alongside it.

The TV app uses it to draw the focused title's TMDB backdrop behind the whole browse screen with a
read-only detail block above a poster grid. Core carries only the preference and the text; the
layout itself is the consuming app's.

### The Cinematic detail block's height is its own setting, not a panel share

`cinematicDetailsHeight(section)` / `setCinematicDetailsHeight(section, percent)`, per section, keyed
`cinematic_details_movies` and `cinematic_details_series`, defaulting to
`CINEMATIC_DETAILS_DEFAULT` (35) and capped at `CINEMATIC_DETAILS_MAX` (60).

Deliberately **not** folded into `PanelShares`. Those three are one row's widths and must total 100;
a height sharing that budget means a taller detail block can only be bought by narrowing the
posters, and a stored 0 can never mean 0 because something has to be left for the other two. Both
constants live in `PanelWidths.kt` next to `PanelWidthLimits`, so a consumer resolving the layout
reads the same ceiling the repository clamps to. Backed up with the other panel numbers.

### Strings

Eleven new keys in `strings_settings.xml`, base plus every packaged locale that needs them: the
layout row, its description and the chooser's subtitle; both options with a description each; the
settings-search keywords; and `settings_panel_width_content_area`,
`settings_panel_width_details_height` and `settings_panel_width_details_hint` for the panel-width
screen, whose second and third sliders mean something different once Cinematic is on.

`values-en-rGB` is deliberately untouched — it is a sparse override carrying only British spellings,
and none of the eleven has one.

---

## core-1.0.52 — 2026-09-20

Three fixes, all reproduced on a real phone against a real television before being written.

### A guide sync could never finish with the screen off, and restarted from zero every time

`EpgSyncWorker` was a plain background worker. On a ColorOS phone the OEM battery manager freezes
the process 29 seconds after the display dims — and blacklists its network at the same time:

```
OplusHansManager: freeze uid:10056 tv.own.owntv.mobile pids:[…] scene: LcdOff
OAppNetControlService: Hans update:[10056=true] blackList:[… 10056]
```

The download dies mid-parse. On the next unfreeze WorkManager starts the work again *from the first
byte*, and because `store.setSynced` only runs on a clean finish, the source never stops being
stale. A large feed on a phone with a 30-second screen timeout therefore loops forever.

The worker now promotes itself to a foreground service with an ongoing notification, which is what
its siblings `DownloadWorker` and `RecordingWorker` have always done — same
`FOREGROUND_SERVICE_DATA_SYNC` permission, same `SystemForegroundService`, no manifest change. The
promotion is best-effort: where Android 12+ refuses a foreground service started from the
background, the sync still runs exactly as it did before. One notification per source, because EPG
work is unique per source and two feeds can sync at once. No new strings — the notification reuses
`settings_syncing_guide`, `common_nav_guide` and the existing programme-count plural.

**Consuming apps need do nothing**, but a user who has denied notifications will not see the
notification; the service still runs.

### Local sync handed out a key that did not open the container it was serving

`LocalSyncManager.startHosting` assigned `sessionPassword` the moment the key was minted, then spent
seconds exporting the container it belonged to. With a listener already running from an earlier
hosting session, `/sync/hello` answered with the **new** key while `/backup.own` still served the
**previous** file. The receiving device downloaded a container it could not open, `previewImport`
threw, and the screen said "Something went wrong" and nothing else.

Verified from outside the app: the advertised session key failed the AES-GCM tag on the served
container, and succeeded against a freshly started hosting session.

The key is now published in `startServing`, alongside the file it opens, so the two can no longer
disagree. `WrongPasswordException` is also classified as `SyncFailure.BadPayload` rather than
falling through to `Unknown`, so the user is told "What arrived could not be read" instead of
"Something went wrong".

### The first-run restore took the whole backup file, with nothing to say about it

`SourceImporter.importBackup` had no `sections` parameter, so both wizards restored everything —
while Settings → Backup & Restore and the local-sync setup step have always offered the tick-list.
"My playlists but not that device's settings" could not be expressed on the one screen where a
restore is most likely.

`importBackup` and `restoreWithPassword` now take `sections`, defaulting to all of it, so an app
that passes nothing behaves exactly as before.

**For consuming apps:** additive only, no signature breaks. Both apps pass a choice as of this
version.

## core-1.0.51 — 2026-09-20

Two fixes, both confirmed on real hardware. The first is urgent: since **core-1.0.48** every
transaction in core failed the moment it was entered.

### Every write transaction was dead under the bundled SQLite engine

`core-1.0.48` configured a `SQLiteDriver` (`BundledSQLiteDriver`). Room's
`androidx.room.withTransaction` extension is the *Support*-path one — its body is
`beginTransaction()` / `setTransactionSuccessful()` / `endTransaction()`, and `beginTransaction()`
goes through `RoomDatabase.openHelper`, which throws outright once a driver is set:

```
java.lang.IllegalStateException: Cannot return a SupportSQLiteOpenHelper since no
SupportSQLiteOpenHelper.Factory was configured with Room.
```

All 13 call sites were affected across `EpgRepository`, `UserDataWriter`, `UserDataResolver` and
`BackupManager` — so EPG sync (both XMLTV and the Stalker portal crawl) reported an error, and
unfavouriting, "Remove from history", "Clear watch history", local sync relinking and backup restore
all threw. In the apps most of those run in an unguarded `viewModelScope.launch`, so they crashed.

Replaced with a core-owned `RoomDatabase.transaction` built on `useWriterConnection` +
`immediateTransaction`. Every call site's body is unchanged, and `immediateTransaction` is the exact
equivalent — with WAL on, the old path issued `BEGIN IMMEDIATE` too.

**Nothing in the build could catch this.** Generated DAO code is driver-native and compiled fine
either way; no unit test opens a database at all, and every instrumentation test built its database
with `Room.inMemoryDatabaseBuilder` and *no* driver, leaving Room in compatibility mode where the
Support path still works. Test databases now go through a shared `ownTVTestDatabase()` that
configures `BundledSQLiteDriver`, so a test and production can no longer disagree about the engine.

### Restoring a backup is no longer treated as a sync merge — **API**

Restoring a backup onto a device the user had just cleared reinstated nothing and reported success.
`BackupManager.import` was the only entry point for both jobs, so an explicit restore inherited the
merge rule: a local deletion marker beats an older incoming record, which is correct for local sync
and wrong for "my data is what this file says". It only showed on the *same* device — onto a fresh
one there are no markers, so it looked perfect.

`import` now takes an `ImportMode`:

- **`RESTORE`** (the default, and what both apps' Backup screens and first-run setup already do)
  drops this device's deletion markers for the profiles in the file before applying it. The file's
  own deletions still apply — they are part of the snapshot — and no stale marker is left for the
  next local sync to act on.
- **`MERGE`** is exactly the previous behaviour, and local sync now asks for it explicitly. All three
  directions (send, receive, merge) are unchanged.

The reported item count was also wrong: a record refused by a newer deletion was dropped for good yet
counted as restored. `UserDataResolver` now distinguishes applied, refused and still-pending records,
and the count excludes refusals. Records that merely have no content row yet still count — they
resolve after the next sync, which is what a first-run restore depends on.

No database change, no backup-format change, no new strings. `ImportMode` defaults to `RESTORE`, so
no consuming app needs to change.

## core-1.0.50 — 2026-09-19

A diagnostic release: no behaviour changes, one new log line.

### A watch session says when it opens and closes

`WatchSession` is the hook a host app raises when the user starts watching, and the background
catalogue drain waits on it. It had no logging, so an app that never raised it was indistinguishable
from one that did — every check green, and the drain simply never yielding.

That is not hypothetical: it is how the phone shipped a hook the drain was waiting on and nothing
ever called. The television raised it from its shell and worked; the phone raised it from its player
screen, which is not composed when a channel is started from the Live TV list, so on a
single-connection portal the drain paged straight through the user's channel. Nothing in the build
could have caught it.

`open` and `close` now log the source and the resulting set under the `WatchSession` tag, so a drain
that fails to yield can be told apart from a session that was never opened at all:

```
I/WatchSession: watch open sourceId=10 watching=[10]
I/WatchSession: watch close sourceId=10 watching=[]
```

Confirmed on both apps against real hardware, on Stalker and Xtream playlists. No API change, no
database change, no new strings.

---

## core-1.0.49 — 2026-09-19

**DB v42 · API · Strings**

Refreshing a playlist was rewriting almost the whole catalogue when almost nothing had changed, a
Stalker playlist spent its first minute measuring instead of importing, and the database engine was
whatever the television happened to ship. All three are fixed, and the engine now travels in the APK.

### A re-sync stops rewriting rows that only moved

The hash diff has always had three outcomes — inserted, changed, and *moved*: a row whose content is
byte-identical but whose position in the provider's list shifted. Moved rows were thrown in with
genuinely changed ones and written through Room's `@Update`, so each one rewrote all ~20 columns,
re-parsed its title to rebuild the provider-catalogue metadata, and fired the FTS trigger. Measured
on a real playlist, 1,397 real changes produced 255,775 full-row rewrites.

- A position change now writes **one column**, through `moveAll`.
- Where a whole span shifted by the same amount — the usual shape, because removing an item renumbers
  everything below it — the span collapses into **one statement**. Measured on a phone against a
  provider that had genuinely reshuffled: **218,277 moved rows became 436 statements**, and the whole
  re-sync took 17.4 s.
- `UpsertStats` gains `moved`, `movedByRange` and `movedScattered`, and the per-phase log reports
  them. Those counters are what found the defect; they are not decoration.

### The stream-limit measurement leaves the setup path

Adding a Stalker playlist sat for **55.5 seconds** on "Checking how many channels this provider
allows" before fetching anything — all of it one step, and nothing logged to say so. It ran there
because the probe works by opening streams, and the moment before the first sync was the only one
guaranteed to have nothing playing to cut off.

- The measurement now runs in `ConnectionMeasurementWorker`, and the new `WatchSession` tells it when
  to step aside. Setup reaches its first phase in **165 ms** instead of 55.5 s.
- It is Stalker-shaped: an Xtream panel publishes the number, so the same step costs ~700 ms there.

### The catalogue drain yields to playback, and follows the user

- `WatchSession` is a new **hook the host app must supply** — the playback screen opens and closes it.
  Without it the background Stalker catalogue drain never yields: `OpenStreamRegistry` is the
  connection budget for Multiview and recordings, and fullscreen playback deliberately never claims
  against it, so the old check could not fire. Measured: the drain now steps aside **230 ms** after a
  channel starts.
- `CatalogPriority` lets a browse screen say which VOD category the user opened. The drain re-reads it
  **between categories**, so opening one moves it to the head of the queue within seconds, and wakes a
  sleeping drain instead of leaving it to its retry delay.
- When a drain finishes it now re-runs `ensureContentIndexes()` and enqueues the Trending refresh.
  Both used to run while a lazily-added source held about 3% of its rows — `ANALYZE` on a near-empty
  table, and a Trending snapshot that then sat behind a multi-day timer.

### SQLite now ships inside the APK

`minSdk = 26` meant an Android 8 device ran SQLite 3.18 — no UPSERT, no window functions, no
`UPDATE … FROM`. Room is now driven through `SQLiteDriver`, and the engine is bundled.

- All **40 migrations** and the schema self-heal are written against `SQLiteConnection`. No migration's
  SQL changed and no exported schema moved; only the API they are expressed in.
- Verified on a television and a phone: `sqlite=3.50.1, journal=wal, heal=clean`, upgrading over an
  existing install with a real catalogue, on both `arm64-v8a` and `x86_64`.
- **Consuming apps:** `RoomDatabase.openHelper` throws once a driver is configured. Use
  `useWriterConnection { }`. Core exposes `androidx.sqlite` as `api` for this.
- The arm flavour grows **2.49 MiB** (two `libsqliteJni.so` copies).
- First use of the new floor: `SeriesSortOrderDao.setOrder` was a lookup-then-REPLACE written around
  UPSERT not existing before 3.24, and is now a single upsert.

### A truncated bulk fetch stops looking like a clean success

A provider's `get_vod_streams` was dropping mid-response — `SocketException: Connection reset` after
242 s at 139,924 of 178,720 films. The per-category fallback recovered the catalogue, so the run
reported plain `Success` and the only trace was a warning under a log tag nobody filters on.

- A truncated bulk phase now raises a `BULK_TRUNCATED` warning carrying where the cut fell and whether
  the fallback made it whole, with new strings in every packaged locale.
- Gzip was already in play and the read timeout was not the cause; both were checked before anything
  was changed, and `HttpClient` now logs the wire encoding at info level so the question stays
  answerable.

### Other

- **DB v42** — `catalog_backfill`, the Stalker VOD pages setup deliberately does not fetch.
- **API** — `SourceRepository.catalogueComplete(sourceId)` answers "is this catalogue whole?" in one
  read. It is *not* the same as `lastSyncAt != null`: a lazily-added source is stamped synced while
  still filling in.
- **API** — `SourceImporter` gains `makeDefault`, so an app no longer needs its own copy of the import
  sequence to offer it.

---

## core-1.0.47 — 2026-09-18

A live channel's measured frame rate is no longer read a notch too low, and the mpv side of the same
number is documented as unverifiable rather than quietly trusted.

### The measured frame rate stops landing on the wrong standard rate

`FpsSample` measured one second of rendered frames and accepted the answer as soon as it snapped to a
standard rate. At 25fps a one-second window holds 25 frames, so a single frame of slack reads 24.x —
and 24 is a standard rate too, so the wrong answer looked exactly as convincing as the right one.
Measured on a television: BBC Two Northern Ireland showed **24 FPS** while the hardware decoder was
rendering a steady 25/s.

- `FpsSample.confident` now requires **two consecutive windows to agree** on the same rate, and
  `LivePreviewEngine`'s measurement window is **2 s instead of 1 s**. Confirmed on the same television:
  the channel now reads 25 FPS, and a genuine 50fps channel still reads 50.
- `resetWindow()` clears the agreement history as well, so a rate carried over from the previous tune
  cannot confirm the next one after a single window.
- `snapToStandardRate` is replaced by `nearestStandardRate`, which returns the rate when the reading
  is within tolerance and null otherwise. The old code inferred "this snapped" from *the value having
  changed*, so a window landing exactly on 50.0 was treated as unrecognised and counted for nothing.
- The decision is split out as `FpsSample.accept(raw)` so it can be tested without an ExoPlayer —
  covered by the new `FpsSampleTest`.

### mpv's frame rate can be wrong, and there is no better property to read

Recorded because it was investigated at length and the obvious fix does not exist. On a provider
stream measured on a television, mpv reported **60** while the hardware decoder rendered **50/s** and
ExoPlayer measured 50 with zero dropped frames — the re-mux stamps the transport stream at a 60 rate
while carrying 50 frames.

- `container-fps`, the new `demux-fps` and `estimated-vf-fps` **all read 60.0** on that stream, so
  swapping `container-fps` for a "more honest" property does not work: the metadata is uniformly
  wrong. Under `vo=mediacodec_embed` mpv never sees the frames, and only MediaCodec — which ExoPlayer
  exposes and libmpv keeps to itself — can count what reaches the screen.
- Behaviour is **unchanged**: mpv still reports what the stream declares. The picture was never
  affected; only the label can be.
- The once-per-load `playback stats` log line now also prints `demux-fps`, so the next stream like
  this is recognisable from one line. `videoDemuxFps` is diagnostic only and its KDoc says so.

## core-1.0.46 — 2026-09-18

The EPG subsystem, rewritten: the picker lists what the guide actually holds, matches survive a
re-added playlist, and the guide is read one row at a time instead of all at once.

### The guide no longer reads the whole database to draw one screen

`GuideReader.window(from, to)` and `EpgDao.programmesInWindowPage` are **deleted**. They paged the
entire guide window into one list and grouped it by channel. Measured on a real television: **349,077
programme rows in a single `ArrayList`** over a 166-hour window, to draw the eight rows on screen —
and a guide re-sync re-triggered it on every batch write, six loads deep and overlapping, until the
app died with an `OutOfMemoryError` inside `EpgDedupe.collapse`.

- Nothing replaces them. `GuideReader.row` already answers per channel through the
  `(epgChannelId, startMs)` index, which is how the phone has always drawn its guide.
- Every windowed read now carries `startMs > :from - 86400000` as well as `stopMs > :from`, so the
  index can be *seeked* rather than walked. The 24-hour bound is an assumption, stated in `EpgDao`.
- Measured after: **2–17 ms per row**, and guide load **8.5 s → 1.8 s** on the same television.

### "Match EPG" lists what the guide really has — `GuideCandidates`

`EpgDao.listEpgChannels` and `LiveEpgReader.availableEpgChannels` are **deleted**, replaced by
`tv.own.owntv.core.epg.GuideCandidates`, which takes only an `EpgDao`.

- **No `sourceId` filter.** Every guide *read* stopped filtering in core-1.0.37; the picker and
  auto-match did not, so the grid drew programmes for channels the picker refused to list. Reported,
  correctly, as "EPG matching stopped working".
- **Reads `epg_programmes` as well as `epg_channels`**, so a feed carrying `<programme>` without
  `<channel>` is pickable at all.
- Deterministic display name (longest non-blank) and a `hasProgrammes` flag on every candidate.

### Search goes through the matcher's own normalizer

`EpgMatcher.matchesSearch` / `matchesNormalizedSearch` replace a SQL `LIKE`, which had two faults:
SQLite's `LOWER()` folds **ASCII only**, so a lowercase Cyrillic or Greek query could never reach an
uppercase name; and a raw substring cannot see spelled-out numbers, so `bbc1` did not find "BBC One"
even though the matcher scores that pair high enough to auto-apply.

### Manual matches survive a deleted and re-added playlist

New `tv.own.owntv.core.customize.EpgMatchResolver`, reached through
`SectionCustomizations.epgMatchResolver`. Item keys are `"<sourceId>:<remoteId>"` and `sourceId` is a
local row id, so re-importing a playlist orphaned **every** hand-made and auto-applied match. Three
tiers — exact key, same provider id under any source, same normalised name — with tiers 2 and 3 used
only when unambiguous.

### Auto-match stops claiming success it cannot deliver — `EpgAutoMatcher`

One implementation replaces three that had already drifted apart.

- `neededEpgIds` reads **every profile's** matches; reading only the active one dropped other
  profiles' matched channels out of the download.
- "Needs a match" now means **no programmes**, not "unknown id" — a tvg-id present as an empty
  `<channel>` entry no longer marks a blank row as already solved.
- A confident winner whose guide channel has **no programmes** goes to review instead of being
  applied silently.

### Database **v41** — normalized columns and a time index

- `epg_channels` gains `normName` and `normId` (both **nullable**), written at sync time, with an
  index on `normName`. The picker was recomputing an NFKC pass plus four regexes per candidate on
  every keystroke.
- `epg_programmes` gains an index on `(startMs, stopMs)`.
- `MIGRATION_40_41` backfills existing channel rows in batches of 500, each its own transaction.
  **Nullable is the point:** an un-backfilled row is normalized on the fly, so an interrupted upgrade
  cannot make a channel unmatchable.

### Retention is split by catch-up

Seven days of finished programmes were kept for **every** channel, though only `catchup = 1` channels
can replay any of it. Past programmes are now kept only for guide ids a catch-up channel reads —
**including the id a hand-made match points at**, resolved through `EpgMatchResolver`, not the raw
column. Everything else keeps 6 hours, which is what the player overlay's "Before" slot needs.

- **If the catch-up set cannot be determined, nothing is pruned.** A failed read returns `null`, not
  an empty set: a provider archive cannot be re-downloaded, so an unknown answer costs disk, never
  data.
- Measured: 397,415 → 172,083 stored programmes, *while storing seven days ahead instead of two*.

### The stored horizon is the user's, not 48 hours — `guideDaysToKeep`

`SettingsRepository.guideDaysToKeep` (default **7**, bounds in `GuideRetention`, 1–14) replaces a
hard-coded 48-hour window. One value feeds the sync window, the prune and each app's scrollable
range. Backed up and restored with the other int settings.

### EPG auto-refresh gains `MANUAL:<days>` — `EpgRefresh`

New `EpgRefresh(mode, manualDays)` mirroring `PlaylistRefresh` exactly — same serialization, same
`thresholdMs` derivation, same bounds.

- **Existing selections are never rewritten.** The old format was a bare enum name and the new one is
  a superset, so a stored `HOURS_48` still parses to `HOURS_48`. Verified on a device that had one.
- New sources still default to `OFF`.

### Duplicate programmes are collapsed when stored

`EpgDao.collapseDuplicateProgrammes` runs after the prune, using **`EpgDedupe`'s exact rule** (same
title, overlapping time, keep the longest, tie-break the lowest id) so storage and reads cannot
disagree. `EpgDedupe` stays on the read paths as the safety net. Measured: **16,080 rows removed** on
one sync, after which the read-side collapse removes ~0.

### Breaking for consuming apps

- `GuideReader.window`, `EpgDao.programmesInWindowPage`, `EpgDao.listEpgChannels` and
  `LiveEpgReader.availableEpgChannels` are gone.
- `SettingsRepository.epgAutoRefresh` is now `Flow<Map<Long, EpgRefresh>>`, and
  `setEpgAutoRefresh` takes an `EpgRefresh`.
- `EpgChannelName` gains `normName` / `normId`.

*Database **v41** (additive, backfilled) · backup gains `guide_days_to_keep` and understands
`MANUAL:<days>` for EPG refresh · three new string keys in every packaged locale.*

---

## core-1.0.45 — 2026-09-16

### A category the provider lists is no longer allowed to arrive empty

A panel can answer the bulk "give me everything" request with a list that quietly leaves a whole
category out, while still listing that category in its category list. The category then appears in
the app with nothing in it. Adult categories are the usual case — Stalker flags the genre `censored`,
Xtream-style panels gate the content per line — but nothing here is specific to adult content.

- **Stalker live** — `get_all_channels` is checked against the genres the portal published, and any
  genre the dump never mentioned is fetched with `get_ordered_list`. Verified against a live portal:
  its adult genre holds 174 channels, every one absent from the dump. The existing truncation guard
  could not see this, because the `"*"` total it compares against is filtered the same way — both
  numbers agreed at 11,494 while 174 channels were missing.
- **Xtream live, movies and series** — the same rule on `get_live_streams` / `get_vod_streams` /
  `get_series_streams`: a category no streamed item referenced is re-fetched with `&category_id=`.
- **The backfill runs before the prune**, so rows an earlier sync stored for a hidden category are
  not seen as stale and deleted — which would have taken their favourites, history and resume
  positions with them. A backfill category that fails to fetch holds the prune back entirely.
- **A dump that carries no category information at all backfills nothing.** Every category would look
  absent, the whole catalog would be fetched twice, and a first sync has no duplicate filter. Stalker
  guards the same case by remembering the remote ids the dump already inserted.

### Xtream sync overlaps all three sections

- Live, movies and series may now be in flight together (was two at a time). Each is one bulk request
  per section, not a stream, so a panel's `max_connections` is unaffected.

*No database change · no backup change · no new strings.*

---

## core-1.0.44 — 2026-09-15

### A new device can be set up from the one you already have

- **Strings** — the first-run setup screen gains a third choice, "From another device", beside
  "New profile" and "Restore backup". Two new keys, `setup_sync_device` and
  `setup_sync_device_description`, in all 25 packaged locales plus the `en-GB` overlay.
- **`setup_setup_choice_description` was reworded** and re-translated everywhere. It said data came
  back "from a backup file"; with a third route on the same screen that was no longer true.
- **No new API and no new sync code.** `LocalSyncManager`, `LocalSyncClient`, `LocalSyncDiscovery` and
  `PairedDeviceStore` are untouched — both apps drive the existing `LocalSyncViewModel` step machine
  from their wizards, forcing `SyncDirection.RECEIVE` and leaving the section picker in place. A
  device being set up never hosts: it has nothing worth serving, and announcing an empty container on
  the network would only be something for the other device to find by mistake.
- Closes `ahXN00/OwnTV#189`.

*No database change · no backup change · new strings in every packaged locale.*

---

## core-1.0.43 — 2026-09-14

### Stalker catch-up actually plays

- **The archive command carried the wrong id, so catch-up did nothing on any portal.** A Ministra
  portal reads the play command's filename as `<programme id>_<channel id>` and looks that pair up in
  its own guide; core synthesized `<channel id>_<start>_<duration>` instead, which matches nothing, so
  the portal answered **HTTP 200 with an empty body**. That surfaced as an `EOFException`, became a
  null URL, and left "Watch from start" and "Go back to…" doing nothing at all. **API** — new
  `StalkerClient.getArchiveDay(…)` (`type=epg&action=get_simple_data_table`) fetches the day's guide
  and `StreamUrlResolver.resolveCatchup` sends the portal's own id for the programme covering the
  chosen instant. Verified end to end against a live portal: real MPEG-TS, correct programme.
- **Portals that do not serve that table keep working** — the previous command is still tried when no
  programme id can be found, which is the shape genuine Ministra accepts.
- **The guide lookup binary-searches the day's pages** instead of reading it from the front. A busy
  channel lists eighty programmes a day over eight pages, so an evening programme cost eight round
  trips before playback could even be requested, and got slower the later in the day it aired. Four
  at the very worst now, usually two or three, measured against a live portal.
- **Portal calls survive a dropped keep-alive.** The shared HTTP client disables OkHttp's
  connection-failure retry so sync owns its own retries, but a portal call is one small request, often
  the first in minutes, against a server that closes idle sockets. The dead connection was handed out
  and the request failed in three milliseconds with `unexpected end of stream`. `StalkerClient` now
  opts back in, exactly as `OpenSubtitlesClient` already did.

- **CI can set up an Android SDK again.** `android-actions/setup-android` installs `tools platform-tools`
  by default, and Google removed the obsolete `tools` package from the SDK repository on 2026-09-14 —
  between `core-1.0.42`'s release that afternoon, which installed it fine, and the evening's first push,
  which did not. `Failed to find package 'tools'` fails the whole step, and every later step then reports
  `./gradlew: Permission denied`, because the `chmod +x` lives inside the unit-test step and never ran —
  which reads like a repository problem and is not one. The action now asks for `platform-tools` only,
  here and in both app repositories, which were one push from the same wall.

*No database change · no backup change · no new strings.*

---

## core-1.0.42 — 2026-09-14

### The documentation, rewritten to the point

- **This changelog is a summary again.** Each release is now a short list of what changed and what a
  consuming app has to know, with markers for a database version, a backup format, a new API, a
  breaking change or new strings. Releases up to `core-1.0.36` are the original long-form notes,
  kept exactly as written and folded away at the end of the file.
- **README facts corrected** — **26** packaged locales rather than 24, a current version in the
  "consuming core" example instead of `1.0.5`, and the mobile app listed as shipping rather than in
  progress. The toolchain versions were refreshed to match `core-1.0.41`.
- **Weblate is credited**, with its logo, in all three repositories. The credit links to
  **weblate.org** rather than to OwnTV's own translation page: `test_i18n_tools.py` requires exactly
  one clickable link to the project page in the README — the one inside the managed
  `i18n-contribution` block — and a second copy broke it.
- **One warning fixed** in `LiveLadderTest`: a bare `Unit` as a `while` body reads as an expression
  whose value is discarded. All three repositories now build with **no warnings at all**.

*No API change · no database change · no new strings · the published artifact is identical to
`core-1.0.41`.*

---

## core-1.0.41 — 2026-09-14

### The playback engine, the database and the build toolchain move up

- **ExoPlayer/Media3 1.11.0 → 1.11.1** and **Room 2.8.4 → 2.8.5** — bug-fix releases on the versions
  already in use. Media3 is the ExoPlayer half of the player, so it is felt by live television in
  both apps; Room is every query in the library.
- **Both are pinned in the consuming apps' catalogues too.** Core generating Room 2.8.5 code beside
  an app carrying the 2.8.4 runtime is the "two Room versions on one classpath" this catalogue's own
  header warns about, so the three repositories move together.
- **AGP 9.3.2 → 9.4.0 and Kotlin 2.4.10 → 2.4.20**, in all three repositories at once: they compile
  these sources through a composite build during local development, and Gradle cannot mix two AGP or
  Kotlin versions across it.
- **One source change came with the toolchain.** AGP 9.4's lint no longer follows a `SDK_INT >= Q`
  guard across a call boundary and failed the build on `PlaybackErrorLog`'s scoped-storage writer,
  whose single caller has always been inside that guard. It now states the contract with
  `@RequiresApi(Q)` — the code was always correct, only the proof was implicit.

*No API change · no database change · no new strings.*

---

## core-1.0.40 — 2026-09-14

### The updater asks the right repository

- **`CoreBuildInfo.releaseRepo`** — which repository the in-app updater checks is now the host app's
  to state, alongside the version, the edge key and the other build facts core takes rather than
  bakes in. **API**
- **It fixed a latent bug.** The television's repository was a constant, so the phone app would have
  found the *television's* newest release, downloaded a whole APK, handed it to the system installer
  and been refused — `tv.own.owntv` is not `tv.own.owntv.mobile` — with nothing in that sequence
  saying why.
- **Defaults to the television's repository**, so that app needed no change and cannot regress. The
  mobile app sets its own in `onCreate`, beside `tvHome`.
- **Asset matching needed nothing** — it selects on a name ending `.apk` plus an `x86_64` marker, and
  both repositories' assets carry those.

*No database change · no new strings.*

---

## core-1.0.39 — 2026-09-13

### Words for a first-run step that sets how big everything is

- **Three new strings** — `setup_display_size_title`, `_description` and `_preview` — for a first-run
  step offering interface zoom and text size, with a sample sentence that resizes as the user adjusts
  so the size is judged against real text rather than a number. **Strings**
- **Everything else the step shows was already translated** and is reused: the zoom and font-size
  labels, the step buttons, Reset, Back, Continue, and the low-memory zoom warning.
- **No new settings and no storage change** — it writes the same `uiZoomPercent` and font size the
  Settings screens have always written. Community request, TV #179.

*No API change · no database change.*

---

## core-1.0.38 — 2026-09-13

### One category order, instead of two that had to agree

Hiding or reordering a category from the browse screen needs the rail's categories in exactly the
order the rail is showing them. That list was already core's — but the move rebuilt the same ordering
inline, **six times** across both apps. Two implementations of one ordering have to agree, and the
day they stop agreeing, a move reorders a list that is not the one on screen.

`core/customize/CategoryOrdering.kt` collapses it: **API**

- **`railCategories`** — the kids filter, custom combined categories, hides, renames and the manual
  order, in one place. Every rail in both apps calls it.
- **`CategoryMove`** — one category being moved, stepping through the existing `moveBlock`.
- **`CategoryRailEditor`** — resolve a `LiveKey` to a customization key, then hide, begin a move, or
  move and commit.
- **`CategoryOrderingTest`** proves the rail and a move build the same list, and that committing a
  move reproduces the order the user saw. That test is the point of the change.

*Additive only — nothing that existed changed shape or meaning. No database change, no new strings.*

---

## core-1.0.37 — 2026-09-13

### Downloads and recordings can be saved to a folder the user picks

- **`MediaTarget`, `MediaRoot` and `DocumentVolumes`** in `core/storage/` — a destination is now
  either a path or a SAF document, so a phone bound for Google Play can write to a folder of the
  user's choosing without `MANAGE_EXTERNAL_STORAGE`. **API**
- **No database change, and none was needed** — `filePath` and `downloadRoot` are both `String`, so a
  `content://` URI lives in the same column and every existing row keeps working.
- **`truncate()` exists because `retry()` used to delete**, which on a document destroys the entry
  the folder grant points at. **`canAppend()` exists because SAF does not require append mode**, and
  a provider refusing it would truncate a partial file into a plausible-looking corrupt film.
- **Export is a move, not a copy** — `ExportDocument` asks for a *persistable* grant, because
  `CreateDocument` alone returns one that dies with the process.
- mpv plays a document through an already-open file descriptor handed over as `fd://`; ExoPlayer
  needed nothing. New dependency: `androidx.documentfile`. **Strings** (three, all locales).

### How many streams a provider allows, measured rather than assumed

- **`ConnectionProbe`, `ConnectionProbeRun`, `ProbeChannelSource`, `ConnectionLimits`** — most
  providers never publish the limit, so it is measured once, at a playlist's first sync, before any
  channel rows exist and while nothing is playing. Nine unit tests. **API**
- **HLS is followed to its segments**, because an M3U channel URL is a few hundred bytes of text that
  close at once — every channel otherwise looked stillborn.
- **DB v40** — `sources.maxConnectionsProbedAt`, so "never measured" is distinct from "measured as
  zero". **DB v40**
- **Backup v22** carries the measured limit: it describes the *account*, not the device, so a
  restored playlist would otherwise forget an expensive answer. **Backup v22**
- **Nothing guesses a provider's limit from a stalled tile.** A learned-refusal rule built on that
  idea was removed rather than reworded — it fired four times and was wrong every time.

### The guide, the channel row and the preview pane finally agree

- **Four `EpgDao` queries and `RecordingManager`'s series-rule scan lost their `sourceId` filter.**
  Every guide read but the preview's also required `sourceId IN (…)`, so rows left behind by a deleted
  or re-added source were invisible to two of the three, permanently. **Breaking**
- **`EpgDedupe.collapse`** removes the duplicate programmes two EPG feeds covering one channel
  produce. Nine tests. **API**
- **A guide row whose stored data has run out now asks the provider**, as the preview always has.
- **`GuideReader` gained a `LiveEpgReader` parameter** and its `window`/`row`/`slice`/`onNow` lost
  `sourceIds`. **Breaking**

### One live-engine watchdog, shared by both apps

- **`LiveExoWatchdog` moved into `:player-core`** and the television now calls it instead of its own
  copy. Every rung exists because a real channel failed that way: a picture that never arrives while
  the audio plays, segment URLs the provider refuses, a stream that opens and delivers nothing, no
  decodable audio, and played-then-froze. **API**
- **`LiveLadder` was already shared** — the estimate that ~700 lines could be lifted out of the
  television's view model was wrong; only the watchdog was both TV-only and genuinely shareable.

### Recording, and what it actually costs

- **The original design is gone.** "Record what I'm watching" was built on mpv's `stream-record`,
  which copies the open stream and costs no second connection. **That cannot work**, and it was
  proven on a real television: FFmpeg's `hls` demuxer opens each segment itself, so those bytes never
  pass through mpv's stream layer. On a normal IPTV playlist every live channel is HLS.
- **Recording now goes through the same engine as a scheduled recording** on both apps. It costs one
  of the playlist's connections, asks `canRecordOn` first so a refusal is a sentence rather than a
  failure, and keeps running when the channel is changed or the player is left.
- **`RecordingSchedule.NO_GUIDE_RUNTIME_MINUTES`** (120) for recording a channel with no guide data.
- **`"record"` added to `ContentMenus.LIVE_ACTIONS`** — a menu key not in that list is silently
  dropped by the arrangement, which is why the phone's long-press Record did not appear.
- **`PlayerControl`** now defines one HUD control order for both apps. **API**

### Fixes

- **An episode download appeared in no list at all, in both apps** — each wrote `mediaType == SERIES`
  for its Series tab, and a download is filed as `EPISODE`. `MediaFolders.folderFor(MediaType)` owns
  the rule now, with tests. **API**
- **HTTP `407` is read off the exception and treated as a session limit**, joining `458`, so a
  reconnect refusal waits once instead of burning eight reconnects in six seconds. Only when no proxy
  is configured.
- **`startStreamRecord` now requires mpv to actually hold a file** — it used to accept an idle mpv
  and report success.
- **A playlist's Test button became Info**, with **Re-test** behind a warning and a **Skip**.
  `SourceTestResult.Ok.expiryText` and `StalkerAuthManager.accountInfo` support it. **API**
- **Removed**: `OwnTVPlayer`'s stream-record surface, `RecordingManager.beginPlayerRecording` /
  `endPlayerRecording`, and `EpgDao.programmeSummariesInWindow` — all left over from the abandoned
  design. **Breaking**
- **Eleven new strings** in all packaged locales, and one description reworded. **Strings**

---

<!--
  Everything below is the original long-form changelog, kept exactly as written.

  Note for whoever cuts the next release: publish.yml extracts a release body by reading from
  "## <tag>" to the next line starting "## core-". That is unaffected by the fold below, because the
  newest version is always at the top of this file with the next "## core-" immediately after it.
-->

<details>
<summary><b>Older releases — core-1.0.36 and earlier</b> (original long-form notes)</summary>


## core-1.0.36 — 2026-09-12

### A download row says where the file went

Both apps showed a download's name and its size and nothing about where to find it. Core now works
the folder trail out from the file's own path — `Series › Game of Thrones › Season 6` — and both apps
read it from here. The television had its own copy of this with the folder names `"Movies"` and
`"Series"` written into it by hand, which is precisely the drift `MediaFolders` was introduced to
stop.

- `MediaFolders.crumb(filePath, separator)`, anchored on the three folder names core already owns, so
  the trail starts where the user's library starts rather than at `Android/data/…`.
- One new string, `content_downloads_options`, in all 25 packaged languages: the title of the sheet
  holding the phone's two download preferences.

### Live TV recording, all of the engine and none of the buttons

Everything needed to record a live programme, minus the screens that start one — those belong to each
app and arrive next. Nothing in either app reaches this yet, so upgrading to this version changes
nothing a user can see.

**The database is now version 39.** Two new, empty tables: `recordings` and `recording_rules`. No
existing table is altered and no existing row is rewritten, so an upgrade is as cheap as a migration
gets. Neither table takes any part in local sync, tombstones or backup, and that is a decision rather
than an omission — a recording is a file on one device, and a row describing a file that is not there
is worse than no row at all.

- **The recorder** opens its own connection to the provider and writes the stream to a `.ts` file
  between two times. `.ts` and not `.mp4` on purpose: it plays while it is still being written, and
  it survives being cut off, which is what an interrupted recording is.
- **It stops on the clock, never on end-of-stream** — a live stream has no end — and a dropped
  connection reconnects and appends rather than starting again.
- **It stops with 500 MB still free** and says so, keeping everything it captured. It never deletes a
  download or another recording to make room.
- **Several recordings run at once**, up to what the playlist allows, with one stream kept back so
  there is always something to watch. That reserve can be given up in settings. A playlist that
  allows one stream records one thing at a time — and a recording is allowed to take that one stream,
  because a live programme is gone forever and a picture is not.
- **A recording that cannot happen says why** — no free connection, a clash with another recording,
  the channel would not play, no space, a scrambled channel — in all 25 packaged languages.
- **HLS channels are recorded segment by segment**, re-reading the playlist every cycle so providers
  that sign each segment keep working. Segments are tracked by their sequence number, not their URL.
- **Scrambled (encrypted HLS) channels are refused rather than attempted.** Writing those segments
  out unchanged makes a file of exactly the right size that will not play, which is worse than saying
  no at the start.
- **Timers are exact alarms** where Android allows them, and where it does not they start a few
  minutes early instead, so a late wake-up still catches the opening. They are re-armed after a
  reboot, after an update, and when the exact-alarm permission changes — that last one matters
  because revoking it cancels every alarm the app has set.
- **Programmes that have already been on can be recorded from catch-up**, starting immediately, for
  every archive convention the app already understands.
- Core's manifest gains the `mediaPlayback` foreground-service type, the exact-alarm permission and
  the boot receiver. `mediaPlayback` rather than `dataSync` because Android 15 caps `dataSync` at six
  hours a day, which a DVR would hit.

## core-1.0.35 — 2026-09-12

### The Multiview refusals are real plurals

`core-1.0.34` shipped two of them as plain strings with a `%d` in front of a noun, on the reasoning
that the sentence only ever appears when a playlist allows two or more streams. Android lint refused
the build, and it was right to: a string that puts a number in front of a word is a template, not a
translation, and nothing stops the singular being reached later.

- `multiview_refused_all_in_use` and `multiview_refused_all_in_use_recording` are `<plurals>` in all
  packaged locales, each with exactly the CLDR quantities that locale requires, and every form
  carries the number — including `one`, which in several languages also covers zero.
- The warning dialog's second button is "Use anyway" rather than "Use 4 anyway". The count it refers
  to is in the dialog it sits in, and no language then has to agree with it.

**No API change** — `StreamGrant.Refused.displayText` resolves the plurals itself, so nothing that
calls it moves.

## core-1.0.34 — 2026-09-12

### Multiview's rules, and one owner for the three folder names

The engine half of watching up to four live channels at once. Nothing here shows a grid — both apps
do that — but everything that decides whether a tile is *allowed* to start now lives in one place, so
a television and a phone can never answer that question differently.

- **`connectionBudget`** (`core/live/ConnectionBudget.kt`) — pure arithmetic over a playlist's
  `maxConnections`: may one more stream start, and if not, which sentence explains it. The feature is
  never capped; an individual tile is checked before it tunes and told the reason instead of failing
  into a spinner. A recording may take the only connection a one-stream account has, because a live
  programme does not come back and a rewatch does; on a bigger account one connection is kept free for
  watching unless the user gives it up.
- **`OpenStreamRegistry`** — who currently holds a stream on which playlist. Tiles and recordings
  spend the same provider connections, so both count against one register. Not persisted: a claim is
  only true while the app is running it.
- **Multiview settings storage** — on/off, tile count 1–4, and the "more than two tiles" warning flag.
- **`LiveEnginePool`** (`:player-core`) — one live engine per tile, with two rules it owns: exactly one
  tile has the sound, and the tiles without it are asked for a smaller picture. Also the sound-only
  tile, for watching one channel while another's commentary plays.
- **`LivePreviewEngine` is safe to build more than once**, and now says so. Every field of it is
  per-instance; what is genuinely process-wide is shared on purpose. It also gained a per-tile video
  ceiling and a distinct **`PlaybackFailure.DecoderExhausted`** — "this device has no decoder left" is
  not "this decoder broke", and only one of them is worth retrying.
- **`MediaFolders`** (`core/storage/`) — `TV/`, `Movies/` and `Series/<show>/Season N` are core's names
  now, and core creates them. They were string literals at four call sites across the two apps, which
  is exactly how a library quietly splits in two. Paths are byte-identical to what was written before;
  nothing on disk moves.
- 25 new strings in all packaged locales.

## core-1.0.33 — 2026-09-12

### One answer to "is this downloading?", and a download line for the status pill

The phone starts a download and then shows nothing: its detail screen never watched the download
state, so the icon stayed a plain arrow whatever was happening. The television has watched it
properly all along. Rather than copy the television's logic into the phone — where the two would
drift — the state machine moves here, and a tracker is added so the pill both apps already show at
the bottom of the screen can carry a download line too.

- **`DownloadStripKind` / `DownloadStripState` / `downloadStripFor(rows)`** move into core
  (`core/download/DownloadStripState.kt`) from the TV app's `ui/components/DownloadStatusStrip.kt`.
  Pure data over `DownloadEntity` — only `@Immutable` travels with it — so both apps decide
  "downloading / queued / paused / failed" with one function. The TV app's copy is deleted in its own
  change; the drawing stays in each app.
- **`DownloadActivityTracker`** — the running transfer as a `StateFlow`, shaped after
  `SyncActivityTracker` and `EpgActivityTracker` and registered in the same Koin module. Fed from
  `DownloadEngine`'s existing progress callback, and cleared whenever a transfer ends — completed,
  failed, paused or deleted. Downloads remain strictly one at a time; nothing about the queue changes.
- Two new strings, `sync_status_download` and `sync_status_download_with_progress`, in all packaged
  locales.

## core-1.0.32 — 2026-09-11

### Two guides in one playlist header are two guides again (TV #171)

A playlist may advertise more than one XMLTV feed in a single `url-tvg`, separated by commas — a
provider covering two countries, say. The whole string was stored as the guide address and then
requested as one URL, which can only 404: the EPG source appeared in Settings with both addresses
joined together, and no programmes ever arrived.

- `EpgRepository.guideUrls` / `splitGuideUrls` — a stored address is split into its feeds, but only
  when **every** comma-separated part is an absolute `http(s)` address. A URL with commas in its
  query string, and the Stalker portal's marker URL, are therefore never split. Parts are trimmed and
  de-duplicated.
- The split happens **on read, not at import**, so a playlist that already stored a joined value is
  fixed by the next sync. No migration, and `M3uSyncer` is unchanged.
- `EpgMigration` registers one EPG source per feed. The playlist name is reused for each; the address
  shown beneath it is what tells them apart, so no new string was needed.
- `EpgRepository.refresh` deliberately syncs only the **first** feed of such a header. Everything held
  under one store id is one feed's worth of guide, and `ProgrammeHashTracker` prunes rows that a
  download did not contain — so a second feed written under the same id would silently delete the
  first one's programmes. The further feeds are registered as EPG sources of their own, each with its
  own id, and refreshed through `refreshUrl` like any other feed.
- New `SplitGuideUrlsTest` pins the splitter, including the two cases that must **not** split.

## core-1.0.31 — 2026-09-11

Five user reports, answered. Two of them turned out to be the same Stalker portal failing in two
different ways, and both were ours rather than the portal's.

### A portal that said "slow down" was heard as "you are logged out"

Users reported HTTP 403s, endless loading and syncs that mostly did not finish — "the same portal
works perfectly in another player". 403 was being treated as an authentication failure, so every one
of them tore down a working session and handshaked again. Ministra and its reseller panels answer 403
for *this MAC has too many connections open*, which is a throttle, not a logout: the worst possible
reply is to reconnect immediately. It surfaced after 4.2.4, whose overlapped import removed the pause
that used to pace the crawl.

- `StalkerClient.httpFailure` — only **401** is an auth failure now. A token that genuinely died still
  arrives as 401 or as the portal's own `{"js":false}` body, so nothing is lost.
- 403 joins the retry-with-backoff set **and** the throttle set, so it shrinks concurrency instead of
  growing it.
- `StalkerAuthManager` invalidates a session only if the one that failed is still the cached one. A
  burst of auth failures used to throw away each freshly handshaken replacement in turn — a handshake
  storm against a portal already asking for less.
- Session lifetime follows the portal's own `watchdog_timeout` (clamped 1–15 min) instead of a flat
  five minutes, so the token is refreshed before it is refused.
- Live paging joins the shared adaptive budget it used to bypass with a fixed six-wide window, and
  that budget now starts at 3 and stops at 8 rather than 6 and 16.

Verified against a 12 000-channel portal: full catalogue crawl — 12K channels, 65K movies, 22K series
— in about two minutes, no errors.

### The portal's own guide, so Stalker finally has EPG and catch-up

A Stalker portal that publishes no XMLTV feed had no guide at all, and therefore no catch-up either:
picking a programme to replay means picking it out of a guide that was never there. `get_epg_info`
was avoided as an OOM risk. It is not one when the reply is never held.

- `StalkerEpgLoader` downloads the whole guide to a temp file, **closes the connection**, and only
  then parses and writes it in batches. The first attempt parsed while writing to the database with
  the response still open; a keep-alive socket left idle while SQLite works gets closed by the far
  end, which failed every time with `unexpected end of stream`. Measured: 9 MB in about a second.
- A broken or stale connection is retried, the period steps down 7 → 3 → 1 days, and a portal with no
  working bulk endpoint falls back to per-channel `get_short_epg` (bounded, and abandoned early if the
  portal refuses).
- Guide rows are written under the key the channel is actually stored under. The portal keys its guide
  by its own channel id, but a channel that came with an `xmltv_id` is stored under that — so the two
  disagreed on exactly the channels most likely to have a guide, and the rows were stored but never
  found.
- The portal guide appears in Settings → EPG as **"Guide from the portal"**, registered after a
  catalogue sync but never downloaded on its own: EPG has been user-initiated since v2.2.0.
- `sources.importPortalEpg` (**v38**) lets a playlist opt out; on for everything that exists.

Verified: 2 148 channels and 13 729 programmes stored in about ten seconds, and the guide matches.

### Catch-up never worked on a Stalker portal

`tv_archive` is **Xtream's** field name and Ministra does not send it — a portal channel carries
`enable_tv_archive` and `archive`. Reading only the Xtream name meant every Stalker channel was
recorded as having no archive. On the test portal, 427 of 11 545 channels have one.

`tv_archive_duration` is in **hours** (the portal reports 24, 48, 72, 168) and was being stored as
days, which would have offered a 72-day archive on a three-day one.

### When an episode first aired

Series with thousands of near-identical episode titles gave no way to tell them apart.
`episodes.airDateMs` holds the provider's own date (`release_date` / `air_date` / `added`, none of
which were being read); `metadata_cache.airDate` holds TMDB's as the fallback, since the metadata
layer never writes to the content tables. Both **v37**. Merged at render time, parsed and formatted
in UTC — an air date is a calendar day, and formatting UTC midnight in the device's zone shows the
day before to everyone west of Greenwich.

A provider refresh that carries no date keeps the one already stored, so a TMDB-filled date is not
blanked out.

### A picture of your own for a profile

`profiles.avatarPath` (**v37**) and `ProfileAvatarStore`: the image is copied into app-private
storage, cropped square about its centre and scaled to 512 px. It rides inside the `.own` backup
container next to the wallpaper and the subtitle files — the path alone means nothing on another
device — so a restore brings the picture with it, and finds the right profile through the exported
`avatarFile` field rather than the id in its name, because profiles merge by name.

### Also

- `EpgProgrammeEntity.description` reaches the apps' Live TV surfaces, which showed only titles.
- `SourceTester` still answers **"not authorised"** for a 403 on a single Test-connection request,
  which is what 403 means when nothing is being crawled.
- `EpgDao.pruneOutsideWindow` and `ChannelDao.guideKeysForSource`.

### Database

**v36 → v38.** v37: `episodes.airDateMs`, `metadata_cache.airDate`, `profiles.avatarPath`.
v38: `sources.importPortalEpg`. All additive columns on existing tables; nothing is rewritten and
nothing existing changes meaning. `importPortalEpg` is a version of its own because v37 had already
run on real devices — Room fingerprints the schema, so widening a migration after it has executed
leaves those databases claiming a version whose shape no longer matches, and the app then refuses to
open.

### Strings

Two new, in all 25 packaged locales: the portal guide's label, and the Guide's "a filter is hiding
everything" message.

## core-1.0.30 — 2026-09-11

Two community fixes — **[#4](https://github.com/ahXN00/OwnTV_Core/pull/4)** and
**[#5](https://github.com/ahXN00/OwnTV_Core/pull/5)**, both from Sekator778 — each extended here so it
covers the whole of what it fixes.

**No database change.** No migration, no schema JSON, no new column. Four new queries, nothing else.

### 📃 M3U titles keep their commas — and keep their favourites

- **The display name is what follows the first comma outside a quoted attribute, not the last one.**
  `M3uParser` took `substringAfterLast(',')`, so `Movie, The (1999)` was listed as `The (1999)` and
  `Live, Love, Music` as `Music`. Quoted values are still skipped, so a `group-title="News, Politics"`
  cannot be mistaken for the separator, and a line with an unbalanced quote keeps the last-comma
  reading it always had rather than being dropped.
- **A line with no separator, or with nothing after it, falls back to `tvg-name`** instead of taking
  the raw `#EXTINF…` text as the title. With neither there is nothing to call the entry, and it is
  skipped as before.
- **The correction no longer costs you the title's favourites, history and resume position.** An M3U
  row's stable key is derived from its name, so fixing the name also changes the key — the corrected
  entry would have been inserted as a new row and the truncated one pruned, taking everything pinned
  to it. `M3uSyncer` now tries the old rule's key once for any current key the database doesn't know,
  and a hit updates that row in place, same local id. It is deliberately skipped where the answer
  would be a guess — two names collapsing onto one legacy key, or a legacy key that is itself a name
  in the playlist (a real "Music" alongside "Live, Love, Music"). Channels, movies and shows alike.

### 🔤 Alphabetical sort reaches the items inside a folder

- **The Folder and Custom-category branches never looked at the sort mode**, so switching to
  alphabetical sorted the rail's folders A–Z while their contents stayed in provider order.
  `ChannelDao.pagingByCategoryAlpha` had existed all along with no caller.
- Fixed for **Live, Movies and Series alike** — PR #5 covered Live, and `VodQueries` had the identical
  gap. Four new queries fill in what was missing: `pagingByCategoryManualAlpha` on `ChannelDao`,
  `MovieDao` and `SeriesDao`, and `pagingChannelsAlpha` / `pagingMoviesAlpha` / `pagingSeriesAlpha` on
  `CustomCategoryDao`.
- A folder or custom category with a manual order keeps its manually placed items exactly where the
  user put them and sorts the rest A–Z — the same "manual order wins, the rest goes A–Z" convention
  the folder list itself already uses. Playlist, Rating and Date-added modes are unchanged.

## core-1.0.29 — 2026-09-11

**Local sync stops losing the newer of two facts, stops asking for a password it should never have
asked for, and stops listing the same device twice.** Three defects in one area, two of them found on
the owner's own television and phone.

**No database change.** No migration, no schema JSON, no new column.

**Newest wins, for records as well as deletions.** Watch history and resume positions were written
through Room's `REPLACE`, so a record arriving from another device overwrote the local one **whatever
its timestamp said** — whichever device applied last won, not whichever fact was newer. Finish
episode 7 on the television, sync, and the phone's stale "episode 5, twelve minutes in" wrote itself
straight over it. `HistoryDao` gained `insertIfAbsent` + `bumpIfNewer` and `ProgressDao`
`insertIfAbsent` + `updateIfNewer`; `UserDataResolver.resolveAndInsert` now inserts when the row is
absent and moves it forward **only** when the incoming copy is genuinely later. Deletions already
obeyed the clock (`removeIfOlderThan`); ordinary records now do too. Favorites are unchanged —
`INSERT ... IGNORE` keeps the earliest `addedAt`, which is additive and already correct. Reorder,
membership and sort positions are also unchanged: a position carries no timestamp of its own, so
last-applied still wins there.

**Each install now has a lasting identity, so re-pairing updates a device instead of duplicating it.**
`PairedDeviceStore.put` always matched on `PairedDevice.id` — its own comment said so — but both
callers in `LocalSyncManager` minted a fresh `UUID.randomUUID()` every pairing, so the match could
never hit and each pairing left another identical row behind. Three pairings, three "OnePlus 13s".
`PairedDeviceStore.selfId()` mints one id per installation and keeps it; it rides in the `/sync/pair`
body as `id=`, is reported by `/sync/hello` as `device`, and is announced in the `_owntv._tcp` service
record as the `id` attribute. Both sides file the pairing under the far device's own id. A device too
old to send one still pairs and still gets a random id, which is the old behaviour rather than a
refusal.

**The sync flow no longer asks for a backup password — and the playlist logins finally travel.** The
payload is a backup container, so the backup screen's passphrase field had come along with it. That
question was never really about protecting the transfer: with the field left empty `BackupManager`
**omits the source and proxy secrets entirely**, so the honest meaning of an empty box was "send my
other device everything except the part it needs", and the container crossed the network as a plain
ZIP. Now the two devices agree a key between themselves. `startHosting` seals its prepared container
with a fresh random session passphrase and hands it to an authenticated caller over `/sync/hello`
(which already demands the PIN or a pairing secret); `send` seals with the secret the pairing
established; the receiving side tries the secrets it knows until one opens the file. New
`LocalSyncManager.SyncPayload(file, preview, password)` carries the key from the dry run to the apply
so no screen has to hold one. `startHosting`, `fetch` and `send` lost their `password` parameters and
`preview` became `previewIncoming`.

> **Consumer note — both ends must run this version or newer.** A sealed container reveals nothing
> until it is decrypted, and a build older than this one does not know to ask `/sync/hello` for the
> key. Pulling from an updated device to an older one therefore fails. Updating both apps together is
> the normal case; a household that updates one television and not the other is not.

**A device already paired says so, instead of asking for its PIN again.** `DiscoveredDevice` gained
`deviceId`, read from the service record, so a found device can be matched against the paired list
before anyone is asked for anything. `LocalSyncDiscovery.advertise` now takes the id to announce.

**Two devices of the same model are told apart.** The device name comes from the device and is not
ours to invent, so two OnePlus 13s in one house were two rows reading the same thing. New top-level
`shortCodes(devices)` returns four characters of their own id for the devices whose names clash, and
**only** for those — a household with one of each never sees a code.

**Two new strings, in the base locale and all 25 packaged translations**: `local_sync_already_paired`
and `local_sync_device_with_code`. **One string deleted** from the base locale and every translation:
`local_sync_password_hint`, whose field no longer exists.

**The instrumentation test suite had never run, and now does.** `androidx.test:runner` was missing
from the test classpath — `androidx.test.ext:junit` does not pull it in — so every instrumentation
test died with `ClassNotFoundException` on `AndroidJUnitRunner` before its first line, and
`UserDataTombstoneTest.setUp()` returned `Preferences` rather than `Unit`, which JUnit rejects
outright. Both fixed; 47 tests now run, including the Room migration suite. New cases cover the
newest-wins rule in both directions and the pairing identity.

## core-1.0.28 — 2026-09-11

The core share of **Plan M — the shape of the More screen**, which rebuilds the television's More hub
as the same two-pane surface Settings uses. Core's part is the text it needs and one new fact the app
never recorded.

**No database change.** No migration, no schema JSON, no new query. The backup record below is a
DataStore preference, not a Room column — a migration was offered and turned out not to be needed.

**The last backup is now recorded.** Nothing in either app knew when a backup had last been taken, so
"am I backed up?" had no answer anywhere. `SettingsRepository` gained `LastBackup(at, bytes,
encrypted, path)` and `recordBackup(...)`, exposed as `lastBackup: Flow<LastBackup?>`, and
`BackupManager.export()` writes it **after** the atomic rename — so a failed export leaves the
previous record standing rather than claiming a backup that does not exist. `lastBackup` is `null`
until one has been taken, so a screen can say "Never" instead of showing the epoch. A record written
before the path was added has a blank `path`; the date it does carry stays valid.

`BackupManager`'s constructor is unchanged — it already took `SettingsRepository`.

**19 new strings, in the base locale and all 24 packaged translations**, all for the More hub: seven
short spine subtitles (the long `*_description` strings stay and are still used by the wider pane),
the spine header line, the Quick/Groups pane labels, the Local sync "listening / not listening"
headlines, "Last backup", "Location", "Encrypted", "Languages", and the pane's `OK — open <x>` hint.
No string was deleted and no existing string changed meaning, so no consumer can break on this.

Four new `preference-key` entries in the literal inventory for the backup record's DataStore keys.

## core-1.0.27 — 2026-09-07

The core share of **Plan Z — the More hub**, which gives both apps one place for everything that is
neither content nor a preference. Core's part is deliberately tiny: one additive enum value, and the
removal of nine strings the apps stopped displaying.

**No database change.** No migration, no schema JSON, no new query — Plan Z is built entirely on
queries that already existed (`pagingFavorites`, `pagingHistory`, `countFavorites`, `countHistory`).

### ⋯ `MainSection.MORE` (`core/nav/MainSection.kt`)

- **A new nav destination, appended after `SETTINGS`**, reusing `common_nav_more` — already
  translated in all 24 packaged locales, so it cost nothing to name.
- **Outside `browseOrder`, and `isBrowse` is false for it**, exactly as `SETTINGS` is. That is what
  makes it un-hidable for free: the Nav menu settings page only ever offers the browse items, so
  nobody can hide their way out of their own settings.
- **Additive, so no existing behaviour changes.** The one cost is the documented one: a new enum
  value breaks every exhaustive `when` on `MainSection`. Five broke, all in the TV app, and all were
  mechanical. Consumers on an older branch will see *"'when' expression must be exhaustive"* until
  they handle the new value — expected, not a bug.

### 🗑️ Nine dead strings removed, in the base locale and all 24 translations

Each one confirmed at zero references across core, the TV app and the mobile app before deletion:

`settings_group_summary_data` · `settings_search_keywords_backup` · `local_sync_search_keywords` ·
`settings_search_keywords_history` · `settings_search_keywords_errors` ·
`settings_search_keywords_about` · `settings_search_keywords_download` ·
`settings_search_keywords_wifi_only` · `settings_clear_history_description`

They described rows that left Settings: Backup, Local sync, Clear history, the error log, About, the
download folder and Wi-Fi-only. A search entry for something that is no longer in Settings is a lie
about where it lives, so the entries went — and with nothing left referencing the keywords, the
strings went too. 226 lines across 26 locale files.

**`settings_group_data` was deliberately kept.** The plan expected it dead; it is not. The
television's new More screen uses it as the heading over Favourites, History, Backup and Local sync
— the group did not disappear, it moved. The whole profile family
(`settings_profile_group`, `settings_group_summary_profile`, `settings_search_keywords_profiles`) is
kept too: the television still has Settings → Profiles, which is its only door to renaming a
profile, setting a PIN, turning on kids mode or deleting one.

**Validators:** `validate_strings.py` reports `i18n validation OK` at 100% on every packaged locale.

## core-1.0.26 — 2026-09-06

Local sync: two OwnTV devices on the same Wi-Fi exchanging their data directly, with no account, no
cloud and no server of ours. Core carries all of it except the two screens — the transport, the
pairing, the merge rule and the deletions — so the television and the phone run one implementation
rather than two.

**One database version, `35 → 36`.** It adds an empty table and changes nothing that exists.

### 🔄 Local sync (`core/sync/local/`)

Deliberately thin, because most of it already existed. The payload **is** a backup container, so
`BackupManager` writes and reads it unchanged. Applying it **is** a restore, which has merged rather
than overwritten since 2026-07-18. The listener **is** the companion HTTP server the Remote flow
uses, with one mode appended. What is genuinely new:

- `LocalSyncClient` — the client half the companion server never had, because until now the thing at
  the other end was always a browser. It speaks the endpoints that already exist: `/sync/hello`,
  `/sync/pair`, `GET /backup.own`, `POST /backup`. Deliberately `HttpURLConnection` rather than the
  app's OkHttp, so a plain-HTTP call to the local network cannot inherit the proxy, interceptors,
  cookie jar or user-agent an IPTV provider's client is configured with.
- `PairedDeviceStore` — the paired devices and their secrets, on disk. DataStore rather than Room: a
  pairing is a credential, not user content, and has no business in a backup carried to a third
  device.
- `LocalSyncDiscovery` — Android NSD (`_owntv._tcp`), advertise and browse. A convenience and never
  the only way in: mDNS is blocked by AP isolation, by some routers outright, and across VLANs, so
  the screens always also offer the address and a QR code.
- `LocalSyncManager` — the orchestrator, with `SyncDirection.SEND` / `RECEIVE` / `MERGE` named
  explicitly. There is no bare "sync" whose direction a user has to infer.

`CompanionMode.LOCAL_SYNC` is **appended** to the enum (the ordinal is a stored bitmask elsewhere).
It is the only mode with no web page behind it, the only one that both accepts an upload and serves
a download in one session — a merge does both — and the only one where a stored pairing secret is
accepted in place of the six-digit PIN. A secret can never mint another secret: pairing requires the
PIN, so one leaked pairing cannot widen itself into a second device nobody approved.

### 🪦 Deletions that survive a merge (database v36)

`user_data_tombstones` — the table without which local sync quietly reinstates every favourite,
history entry and resume position the user has ever deleted. A merge cannot tell an absent row from
one the other device has not heard about yet, so an absence has to become a fact with a time on it.

- Keyed on the same stable content identity a backup exports — source, provider id, name, or show
  plus season/episode — never the volatile `itemId`, so a deletion survives both the other device's
  different ids and the clear-then-insert of a re-sync here.
- The merge rule is newest-wins, in both directions: an incoming record older than a deletion is
  dropped, and an incoming deletion older than a local row leaves it alone. A favourite re-added
  after the other device removed it survives.
- Applying a deletion records it locally too, so it carries on to a third device instead of stopping
  at the second.
- Bounded to the 20 000 newest, because "Clear watch history" writes one per row.

`UserDataWriter` is the one place a user deletion is now written: it records the marker and performs
the delete in a single transaction, so the two cannot come apart. **Only user actions go through
it** — the orphan purges after a re-sync and the profile cascade still call the DAOs directly and
deliberately, because turning "the playlist was refreshed" into "delete this everywhere" would lose
real data.

### 👁️ A dry run before anything is applied

`BackupManager.previewImport` counts what an import would change without changing anything: new
profiles, playlists, favourites, history, resume positions and ordering, settings that differ, and
the rows this device would **lose** because the other one deleted them more recently. Every lookup
mirrors what the import does, so the numbers are the ones the apply will produce.

The one outcome worth engineering against is somebody tapping the wrong direction and finding out
afterwards.

### 🌍 Strings

Sixty-one new keys in all 25 packaged locales — the whole Local sync feature on both apps, plus the
page the companion server serves if somebody opens the sync address in a browser. The counted lines
of the summary are written as a label and a number ("New favourites: 3") rather than a number inside
a sentence, which is correct in every language without a plural rule per locale.

### 🧪 Tests

`UserDataTombstoneTest` — instrumentation, because the merge rule is expressed in DAO queries and a
real transaction. It pins the cases the owner will actually perform: unfavourite here and it stays
gone there, re-favourite here and it survives, and a deletion still matches after a re-sync has
changed every content id. Each of them fails silently rather than loudly if the rule is wrong, which
is exactly the kind of bug nobody reports and everybody stops trusting.

The migration test asserts v36 arrives empty: an upgrade must not invent deletions.

## core-1.0.25 — 2026-09-06

Core's share of the mobile app's casting phase. Additive throughout: every new member has a default
that is exactly what the TV app does today, so a television is unaffected. No database change, no
migration.

### 📡 A player failure for a receiver that cannot play the stream

`PlaybackFailure.CastUnsupported`, with its wording in `describe()` and its string
`player_error_cast_unsupported` in all 25 packaged locales. A Chromecast decodes the stream itself
and cannot decode everything an IPTV playlist holds; this is how a sender says so in the user's
language instead of showing a dead screen.

`player_cast_playing_on` — "Playing on <device>" — comes with it, for the notification and the cast
screen.

### 🔈 An engine can now say the sound is not coming out of this device

`PlaybackEngine.playsLocally`, defaulting to `true`. `PlaybackSession` skips its audio-focus request
and its headphone-unplug receiver when an attached engine returns `false`. Without it, unplugging
headphones or taking a call on the phone would pause a film playing on a Chromecast in another room,
and the app would duck every other app on the device for sound it was not making. Nothing in the TV
app returns `false`.

## core-1.0.24 — 2026-09-06

One new setting and the two strings that label it. Additive throughout: the setting defaults to the
behaviour the TV app already has, so nothing changes for a television.

### ✨ The glass arrival shine is now a setting

`GlassConfig` gains `glint`, stored as `glass_glint` and carried in a backup like every other glass
switch. It decides whether a glass pane arrives with a band of light travelling across it. It
defaults to `true`, which is exactly what both apps did before, and only the mobile app offers a row
for it — the television has no screen for it and is unaffected.

Two strings come with it, `settings_glass_shine_short` and its description, translated into all 25
packaged locales.

## core-1.0.23 — 2026-09-06

Documentation only. No code, no strings, no database change, no behaviour difference in either app —
`:core` and `:player-core` are identical to `core-1.0.22`.

### 📄 The README says how the apps get this

"Who depends on this" now states that publishing a release here opens a pin-bump pull request on both
apps, that it moves `owntvCore` and refreshes the app's copy of `tools/i18n/locales.json`, and that
each app merges it itself. That was true for some time and written down only in the apps.

### 🔁 Why this version exists at all

Both apps have just changed what they run on a `bump/core-*` pull request. The unit tests, lint and
the whole i18n suite now step aside — they only ever re-examined app Kotlin identical to `main` —
and one seconds-long `verify-pin` job runs instead: the pin must be this repository's newest
published release, the branch name must agree with the pin, and the app's `locales.json` must be
byte-identical to the copy here at that tag. That last check is the one with teeth, because a stale
locale catalogue strips a language out of the APK with every build green.

Nothing in that lives here, but it can only be proved by a real release travelling down the path.
This is that release. Two consequences do belong on this side: a consumer's build is **no longer
exercised by the bump pull request**, so a core change that compiles from source but not from the
published AAR now surfaces on the app's next ordinary push; and `verify-pin` compares against
`releases/latest`, so **a release published out of version order would fail every consumer's bump**.

## core-1.0.22 — 2026-09-06

Additive. Nothing existing changed meaning, so the TV app keeps its current behaviour. No new
strings, no database version change.

### 👤 `ProfileManager` — one implementation of what a profile is

Creating, editing, switching and deleting a profile now lives here instead of in each app's shell. A
profile spans more than its own row: the sources linked to it, its OpenSubtitles login, its "start on
this channel" target and the app-wide active id. Two shells doing that by hand against one shared
database would drift, and deleting a profile has to erase all of it.

Bound in `dataModule` as a singleton, so either app injects it.

### 🔒 `profileGateRequired` and `shellMayCompose` — the launch decision, decided once

Whether the profile chooser must be shown, and whether the app proper may be composed yet, are two
security-relevant rules that were written separately in each app. They are now one pair of pure
functions here, with the television's existing behaviour as their behaviour: a single unlocked
profile enters immediately; a chooser, a PIN, an unanswered database or an unlock bound to a
different profile does not. The TV app's own function keeps its name and signature and delegates, so
its tests are unchanged.

`PROFILE_AVATAR_COUNT` moves here too — both shells number the avatars the same, so a profile made on
the phone shows the same picture on the television.

## core-1.0.21 — 2026-09-06

Additive. Nothing existing changed meaning, so the TV app keeps its current behaviour.

### 🎛️ `HomeConfig.trendingStyle` — Now Trending in two shapes

The Now Trending row can now be drawn either as the full hero card (artwork, badges, reasons) or as a
plain strip of posters, and the choice is stored per profile alongside the rest of the Home
configuration. `HomeTrendingStyle.HERO` is the default and is what every existing config and every
existing backup reads as, so nobody who never opens the setting sees a change.

The value lives in the Home config JSON blob, which is written with defaults and read with fallbacks
— no database version change and no migration.

### 🌍 Three new strings, in all 25 packaged locales

- **`home_trending_style`** — the settings row that chooses the layout.
- **`home_trending_style_hero`** — the detailed card.
- **`home_trending_style_posters`** — posters only.

## core-1.0.20 — 2026-09-06

Additive. Nothing existing changed meaning, so the TV app keeps its current behaviour.

### 🌍 One new string, in all 25 packaged locales

- **`settings_about_description_full_mobile`** — the About page's description on the phone. The
  television's own line names the remote and the ten-foot screen; a phone needs the same sentence
  without them, and both live here because this repo owns every user-visible string.

### 🔎 `TrendingAvailability` — why the Now Trending row is, or is not, on Home

The row can be empty for six different reasons and only one of them is a fault: metadata turned off,
a provider with no films or shows, a sync that has not run yet, too few matches to fill a row. The
new shared classifier turns that state into one answer, so the TV app and the phone say the same
sentence about the same data instead of each guessing separately. Nothing calls it in the TV app yet,
so nothing there changes.

## core-1.0.19 — 2026-09-05

Strings only, all additive. The TV app was rebuilt and verified against it (Rule 5).

### 🌍 Three new strings, in all 25 packaged locales

Every one of them is for the phone, and every one is added here rather than there because this repo
owns all user-visible text.

- **`player_channel_number_entry`** — the label on the phone's direct-tune field. The television
  tunes by number from the remote's keypad, which needs no label; a phone needs a text field, and a
  text field needs to say what goes in it.
- **`settings_playback_tv_only_note`** — one line at the foot of the phone's Playback settings,
  saying that live preview and remote-control shortcuts are television features. A page that simply
  lacks a row reads as a bug; a page that says why does not.
- **`content_episode_options`** — the title of the phone's new episode-options sheet, which gathers
  hide-watched and both sort orders behind one button.

## core-1.0.18 — 2026-09-05

Additive. The TV app was rebuilt and verified against it (Rule 5).

### 🎨 The shell's three region colours moved here

`OwnTVPalette` gained `DarkRailPanel` / `DarkContentPanel` / `DarkPreviewPanel` and their three light
counterparts. They are deliberately not part of the M3 ladder: they are the colour identity of the
navigation, the content area and the detail pane, which both apps draw and which the generic
elevation steps flatten into the same grey. The television's `RoundedPanel` now reads them from here
instead of holding its own copies, so the two apps cannot drift apart.

### 🐛 The EPG separator lost its spaces

`content_epg_bits_separator` is a middle dot padded with a space on each side, and the padding was
being stripped by the resource parser — so an EPG line read `20:00·Drama·HD` instead of
`20:00 · Drama · HD`. The value is now quoted in the base locale and all 24 translations, which is
how a resource string keeps leading and trailing whitespace.

## core-1.0.17 — 2026-09-05

Additive. The TV app was rebuilt and verified against it (Rule 5).

### 🪟 Two more surfaces the glass can be scoped to

`GlassSurface` gained **`PLAYER_CONTROLS`** and **`TOASTS`**, so an app can let the user decide
whether the controls drawn over a video, and the messages that flash over it, are frosted like the
rest of the interface. Both are **appended** to the enum, never inserted: the stored scope is a
bitmask over the ordinals, so an existing installation keeps exactly the surfaces it had.

- New strings, in the base locale and all 24 translated locales:
  `settings_glass_surface_player_controls` and `settings_glass_surface_toasts`.
- The television reads neither today — its ten-foot HUD has no caller for them — and its own settings
  screen deliberately leaves both out rather than showing a switch that does nothing. The mobile app
  is the first consumer.

## core-1.0.16 — 2026-09-04

Additive. The TV app was rebuilt and verified against it (Rule 5).

### 📱 Settings for a player you can carry around

Seven new stored settings, all with the current behaviour as their default, so nothing an existing
installation does changes. The television reads none of them today; they exist because the phone's
mini player, picture-in-picture window and sound-only mode need somewhere to keep their choices, and
settings storage is core's.

- **`miniPlayerStyle`** — `FLOATING`, `DOCKED` or `OFF` (default `FLOATING`).
- **`pipOnBack`** (default off) — whether Back drops the player into a picture-in-picture window
  instead of leaving it.
- **`pipSize`** — `SMALL`, `MEDIUM` or `LARGE` (default `MEDIUM`).
- **`pipSnap`** (default on) — whether a dragged window springs back to the nearest edge.
- **`audioOnScreenOff`** (default on) — keep the sound when the screen goes off.
- **`audioOnMobileData`** (default off) — start without a picture on a metered connection.
- **`audioPerChannel`** (default on) — remember, per channel, that it was watched without a picture.

All seven are in backup and restore: the two enums under `backupStringKeys`, the five switches under
`backupBoolKeys`.

### 🎵 Which channels were watched without a picture

- **`AudioOnlyStore`** — a small per-item store, built like `ForceMpvStore` and keyed the same way by
  `enginePinKey(sourceId, mediaType, remoteId)`, so the memory survives a re-sync even where the
  stream URL is a single-use token. Registered in `DataModule`.

### 🌍 Strings

- **25 new strings, in all 25 packaged locales** — 19 for the settings above, 6 for the player: the
  sound-only screen's "video off" chip and its way back to the picture, the sleep timer with its
  end-of-programme option and its remaining-time label, and the expand action for the
  picture-in-picture window. `values-en-rGB` is deliberately untouched; none of the 25 is spelled
  differently in British English.

## core-1.0.15 — 2026-09-04

Additive. The TV app was rebuilt and verified against it (Rule 5).

### 🪟 One more glass preset

- **`GlassPreset.AURORA`** — the mobile app's signature material, added between `OPAQUE` and `CUSTOM`
  so no existing ordinal moves and no stored scope or preset is disturbed. Alpha 0.46, blur strength
  0.94. It is the phone's default look, where it renders as real backdrop blur with a lit edge; on the
  television, which has no backdrop blur, it simply reads as a slightly clearer Balanced. Both apps
  offer it, and the TV app's two exhaustive `when` blocks over the enum were extended for it.

### 🌍 Strings

- **`settings_search_keywords_glass`** — search keywords for the Glass Effect settings page, in the
  base locale and all 24 translations.

## core-1.0.14 — 2026-09-04

Everything here is additive. The TV app was rebuilt and verified against all of it (Rule 5), and
nothing it already did changed meaning.

### ✂️ Span selection and bulk rename, shared instead of duplicated

- **`core/customize/SpanSelector.kt`** — the span model the TV app's Customize screen has always had,
  lifted out of its view model with no UI in it: `SpanSelector<T>` (start, extend, clear, the ordered
  low/high pair), `MoveKind` (`UP`, `DOWN`, `TOP`, `BOTTOM`) and `moveBlock(list, lo, hi, kind)`,
  which moves a whole contiguous block and returns `null` when the move would fall off the end.
- **`core/customize/BulkRenameSession.kt`** — the bulk-rename engine: the rule set, the preview rows
  (`BulkPreviewRow`), per-row accept and decline, the guards against emptying a name or colliding
  with another, and the originals kept so a rename can be undone. The TV app was rewired onto both
  files in the same change and behaves exactly as before.
- Both were moved because the mobile app now has the same two features on touch. The Customize view
  models did **not** move: core has no lifecycle dependency and Paging is `implementation` there, so
  hosting app-level view models would have widened core's dependency surface for nothing.

### 📺 A Stalker portal's expiry date, read in one place

- **`core/stalker/StalkerExpiry.kt`** — `stalkerExpiryOf(fields)`, pulling a subscription end date
  out of a portal's `account_info` / `get_profile` map. It tries the five real keys in turn, then
  falls back to `phone`, which some portals stuff the date into, and only when the value actually
  looks like a date. Placeholder values (`0000-00-00`, `null`, `0`, empty) are ignored, and the date
  is returned verbatim, because portals write it in their own format and re-parsing invents wrong
  dates. The TV app had a private copy of this and now calls core's.

### 🌍 Strings

Ten new base strings in `strings_settings.xml`, translated into all packaged languages in the same
change:

- **`settings_quick_empty_hint_touch`** — the Quick group's empty hint, worded for a phone. The
  existing `settings_quick_empty_hint` says "Hold OK on any setting", which is a remote control's
  select button; the mobile app says "Long-press" instead. Additive — the TV app still reads the
  original.
- **Six touch wordings for span selection** — `settings_customize_span_hide`, `_span_move`,
  `_span_rename` and the three matching prompts `settings_customize_range_hide_start_touch`,
  `_range_move_start_touch` and `_range_rename_start_touch`, which say "Tap the last item" where the
  television's own say "press".
- **`settings_customize_move_top`** and **`settings_customize_move_bottom`** — the two jump actions.
- **`settings_customize_custom_category`** — "Custom", the label under a folder the user made
  themselves. This is a fix as well as an addition: the TV app hardcodes it in English.

## core-1.0.13 — 2026-09-03

### 🔎 One search, and the storage a phone is allowed to write to

- **`core/content/SearchReader.kt`** — the search the TV app ran from its view model, moved out whole:
  channels, movies and series in one call, honouring hidden categories and hidden items, plus a
  `curated()` for the empty field (continue watching, unwatched favourites, channels). The TV app was
  rewired onto it in the same change and searches exactly as before. `ftsQuery()` sanitising a user's
  typing into an FTS expression now lives with the query instead of being written twice.
- **`StorageAccess.appRoots(context)`** — the volumes an app can write to with **no permission at
  all**: its own folder on internal storage, and one on every mounted SD card or USB stick. It is what
  a phone offers in place of `storageRoots()`, which needs All-files access a phone should not ask
  for. Additive; `storageRoots()` and `defaultRoot()` are untouched, so the TV app keeps its folder
  picker.

### 📱 The settings a touch device has and a television does not

All five are new keys with defaults that leave the TV app exactly as it was, and all five are carried
by backup and restore.

- **`backgroundPlayback`** (default on), **`pipEnabled`** (default on), **`dataSaver`** (default off),
  **`gestureSensitivityPct`** (default 100, clamped 50–200) and **`downloadsWifiOnly`**
  (default off), with `downloadsWifiOnlyNow()` and `dataSaverNow()` for the callers that need one
  read rather than a flow.
- **They travel in a backup.** `gestureSensitivityPct` joins the backed-up integer keys and the four
  switches join the boolean ones, so a phone's settings restore onto a phone.

### 📶 Downloads can be held back to Wi-Fi

- **`ConnectivityObserver.isMeteredNow()`** — a one-shot metered check, treating "unknown" as
  unmetered so a missing answer never blocks playback.
- **`DownloadWorker.kick(context, wifiOnly, replace)`** — the queue's work request now takes
  `NetworkType.UNMETERED` instead of `CONNECTED` when the setting is on, and can `REPLACE` an
  enqueued run instead of keeping it. Both parameters default to the old behaviour.
- **`DownloadManager` follows the switch while a transfer is running.** It kicks with the stored
  setting, and watches it: turning Wi-Fi-only on mid-download re-enqueues with the stricter
  constraint, so the change reaches a transfer already in flight rather than only the next one.

### 🌍 Strings

21 new base strings in `strings_settings.xml` and `strings_player.xml` — the mobile settings groups
above, the selection-highlight and navigation-bar labels, the data-saver playback message, and five
search-keyword entries so the new settings are findable — translated into all 26 packaged languages
in the same change.

## core-1.0.12 — 2026-09-02

### 🧱 The parts a second app needs, taken out of the TV app

Everything here already existed and worked — inside `OwnTV`'s view models, where a phone could not
reach it. It moved so that two apps share one implementation instead of drifting apart, and the TV
app was rewired onto every piece of it in the same change. Nothing behaves differently on a
television.

- **`core/setup/SourceImporter.kt`** — the whole "add a playlist" state machine: validating an Xtream,
  M3U or Stalker source, writing it, syncing it, reporting progress, and undoing it when the sync
  fails. A `factory`, not a `single`, because each run of a wizard owns its own state.
  **`core/setup/SetupText.kt`** and **`core/sync/SyncCountsText.kt`** carry the wording that goes with
  it, so a failure reads the same on both devices.
- **`core/content/VodQueries.kt`** — the Movies and Series catalogue queries a paged grid needs, with
  the sort, category and hidden-item rules applied once rather than per app.
- **`core/live/GuideReader.kt`** — the heaviest query in the suite, in one place. `window()` reads a
  span of guide in id-keyset pages, so a large lineup cannot overflow a cursor window; `row()` serves
  a single shifted channel; **`slice()`** answers a whole rail in one query *per shift group* rather
  than per channel; `onNow()` is built on `slice()`; `description()` fetches the synopsis the list
  queries deliberately drop.
- **`core/home/HomeFeed.kt`** — everything Home shows, for one profile, at one moment. `HomeFeedReader`
  runs the fifteen dependent reads (overlapped, since WAL serves concurrent readers) and applies the
  rules that are the *app's* rather than any one screen's: which playlists count, what a kids profile
  may not see, what the user hid, how trending titles are de-duplicated across playlists, and which
  items may be the hero. A television and a phone lay Home out completely differently and must still
  agree, item for item, on what is in it.

### ⚙️ Three settings keys for a touch screen

All three are backed up and restored with the rest, and all three default to "decide from the screen"
rather than to a fixed answer, because a phone in portrait, the same phone in landscape and a tablet
do not want the same one.

- **`guideView`** — grid, "on now" list, or one channel's schedule down the page.
- **`guideDensityPct`** — the guide's time scale, 70–130%.
- **`vodGridColumns`** — how many posters a row of the catalogue grid holds, stored when the user
  pinches.

## core-1.0.11 — 2026-09-02

### 🖼️ Cached TMDB posters can fill the grid tiles a provider left blank

Providers ship plenty of movies and shows with no artwork at all. Their tiles show a placeholder even
once a detail pane has resolved and cached a TMDB poster for the very same title, which is most
visible under "Date added" — a freshly imported batch lands at the front of the list together.

- **`MetadataRepository.cachedMoviePosters` / `cachedSeriesPosters`** return the poster URLs already
  held in the cache for a page of items, keyed by local id. Cache-only by design: no network, no
  search, no negative-cache write, so a consumer may call it on every scroll without touching TMDB
  quota. A title nothing is known about is simply absent from the result.
- **One local TMDB id can cover several rows.** The same film listed once per quality by one provider
  shares a match, so all of its rows get the poster from a single cached row — which is exactly the
  case that produces a run of blank tiles.
- **`MetadataDao.getMatches`** batches the `metadata_match` read the same way `getCaches` already
  batches the detail rows: one query per page of tiles rather than one per tile.

No schema change, no migration, and nothing existing behaves differently.

## core-1.0.10 — 2026-09-02

### 📱 `PlaybackSession` can behave like a phone as well as a television

All of this is additive and keyed off a new constructor parameter whose default is the television's
existing behaviour, so the TV app is unchanged. It exists because the mobile app needs a media session
that pauses for a phone call, and a television must not.

- **`FocusPolicy`, `DUCK` or `PAUSE`.** On `DUCK` — the default, and what the TV app gets — a transient
  loss of audio focus lowers the volume as before. On `PAUSE` it pauses playback and resumes it when
  focus comes back, which is the only sane behaviour on a device that receives calls. Ducking a live
  stream costs a quiet moment; pausing one costs the live edge, which is why the television never does.
- **`setWillPauseWhenDucked` follows the policy.** Under `PAUSE` the platform is told not to duck us
  behind our back, so it delivers `AUDIOFOCUS_LOSS_TRANSIENT` — the event that pauses — instead of
  attenuating us silently and never calling back. `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` also pauses
  under `PAUSE`, as a backstop for dialers and OEM builds that hand out `CAN_DUCK` regardless.
- **`pauseWhenOutputDisconnects`.** Opt-in `ACTION_AUDIO_BECOMING_NOISY` handling: unplugging
  headphones pauses, and deliberately does **not** arm a resume, so plugging them back in cannot blast
  a film out of a pocket. Off by default.
- **A `token` accessor** for the session, so a consumer can hang a `Notification.MediaStyle` on the
  session this class already publishes rather than building a second, disagreeing one.

### 🐛 Audio focus was thrown away on every pause

- **`publish()` no longer abandons audio focus for a pause that is owed a resume.** It abandoned the
  focus request whenever the state went non-playing — including the session's own `onPause` — and that
  request is the thing whose `AUDIOFOCUS_GAIN` drives the resume. Anything the session paused could
  therefore never restart itself. Latent for the TV app, since its `DUCK` policy never pauses for
  focus in the first place, and fatal for the phone's.

## core-1.0.9 — 2026-09-02

### ⚡ EPG auto-match finishes on TV hardware

- **`EpgMatcher.bestEpgMatchBulk` scans a whole catalogue across all cores**, mirroring the existing
  `rankForPickerParallel`. Auto-match grows as channels × candidates — 1,786 channels against a
  1,907-channel guide is ~3.4M scorings — and the single-threaded loop ran for over half an hour of
  CPU time on a 2020 Android TV without finishing, leaving the guide behind its "channel ids don't
  match" banner the whole time. Rows are independent, so results and their order are unchanged.
- **`Prepared` now carries precomputed digit runs**, and `bestEpgMatchPrepared` computes the
  target's once per channel instead of once per comparison. The digit-mismatch guard re-ran the same
  regex on both sides of every pair, which dominated the scan's allocation. This speeds up the
  single-threaded path too, so the picker benefits without any caller change.
- Measured on a 1,786 × 1,907 catalogue: sequential 3,336 ms → 1,570 ms from the precomputation
  alone, and 373 ms with the parallel scan — about 9× end to end, with identical results.

### 🌍 EPG auto-match works outside the Latin alphabet

- **`EpgMatcher.normalizeForEpg` no longer throws away non-Latin names.** Its cleanup class was
  `[^a-z0-9 ]`, so a Cyrillic, Greek or CJK channel name reduced to an empty string, and
  `bestEpgMatch` returns null on an empty target — auto-match could never pair those channels with a
  guide entry, leaving the guide stuck behind "channel ids don't match your channels' EPG ids". The
  class now keeps letters and digits of any script.
- **Names are NFKC-normalised first**, so decorative compatibility spellings still fold away: `ᴴᴰ`
  becomes `HD` and is dropped as noise, and halfwidth katakana returns to its normal form. Composing
  rather than decomposing matters — NFKD would leave combining marks that the cleanup class turns
  into spaces, splitting `Чайка` into two tokens and degrading `ﾊﾟ` to `ハ`.
- **The channel-number guard reads digits of any script.** `DIGIT_RUN` was `\d+`, which is ASCII-only
  in Java, so once non-Latin digits survived normalisation `قناة ٢` and `قناة ٣` scored high enough to
  auto-apply onto each other. It now matches `\p{N}+` and compares digits by numeric value, so `MTV ٢`
  and `MTV 2` are recognised as the same channel while `٢` and `٣` stay apart.

## core-1.0.8 — 2026-09-02

### 🗂️ The content menus and the Live TV queries live here now

- **New `core/menu/ContentMenus.kt`** — `ContentMenu`, `MenuAction` and `applyMenuOrder()`, the
  user's own arrangement of the long-press actions. It was only ever in the TV app, so a second app
  would have shown a different menu in a different order from the same setting.
- **New `core/live/`** — `LiveKey`, `LiveQueries`, `LiveEpgReader` and `EpgNowNext`, moved out of the
  TV app whole. The TV app is rewired onto them and its own copies are deleted; its tests and release
  build are green.

### ▶️ The player pieces both apps need

- **New `core/live/LiveTimeshift.kt` and `core/live/CatchupJumps.kt`, with their tests** — the maths
  behind rewinding a live channel into the provider's archive and jumping between catch-up
  programmes, moved out of the TV app so the phone rewinds live television by the same rules the
  television does.
- **`PlayerFailureReason.messageRes`** — a failure reason now knows its own translated wording, so
  the two apps explain a broken stream identically instead of each writing its own sentence.
- **`StreamInfoLabel.titleRes` and `StreamInfoValue.displayText(Resources)`** — the stream
  information table renders itself from `Resources` rather than from Compose, so a consumer that is
  not the TV app can show it without copying the labels.
- **`OwnTVPlayer.active`** — `hasActiveStream` as a flow, for UI that has to appear and disappear
  with the stream rather than ask about it. The mobile app's docked mini player cannot poll a getter.
- **`OwnTVPlayer.detachSurface(surface)`** — detaches only while that surface is still the one being
  rendered into. A view handing the picture to another view is torn down *after* its replacement has
  attached, so an unconditional detach at that moment blanks the view that just took over. The
  existing no-argument `detachSurface()` is untouched and is what the TV app still calls.

### 🌍 Strings

- **Three new strings, translated into all 24 packaged locales:** `player_tool_brightness`,
  `player_skip_back` and `player_skip_forward`, for the mobile player's controls.

## core-1.0.7 — 2026-09-01

### 🎨 The colour values live here now

- **New `core/theme/Palette.kt`** — the accent presets, the neutral ladders and the custom-accent
  derivation (`parseAccentHex`, `accentRolesFromSeed`) moved out of the TV app, so both apps read one
  set of hex codes instead of drifting copies. They are plain ARGB longs, not Compose `Color`: core
  carries the Compose runtime only and must not gain `compose-ui`, so consumers wrap them at the
  edge. The TV app does exactly that, with every public symbol and every rendered value unchanged.

### 🧭 The main-menu sections live here now

- **New `core/nav/MainSection.kt`** — the sections a user can navigate to, and `dynamicVisible()`,
  the rule that hides a section when no source has that kind of content.
- **New `core/nav/NavVisibility.kt`, registered in `DataModule`** — the whole computation, not just
  the rule: the static hidden-sections setting, the content-capability flow over the channel, movie
  and series counts, and the combination of the two. A consumer asks for a set of visible sections
  rather than assembling one. This deleted a second, independent copy of the capability flow that had
  grown inside the TV app's settings screen; both call sites now go through the one implementation.
- The TV app is rebuilt on it with no behaviour change — same flows, same defaults, same
  `distinctUntilChanged` — and its tests and release build are green.

### 🌍 Strings

- **Three new strings, translated into all 24 packaged locales:** `common_nav_library` and
  `common_nav_more` for the mobile app's bottom bar, and `common_cast` for its cast button.
  `content_media_cast` was deliberately not reused — it means the cast of a film.

## core-1.0.6 — 2026-09-01

### 📱 A non-TV app can consume core

Building the mobile app's harness against core surfaced four things that only ever worked because the
TV app was the only caller. All four are additive — the TV app's behaviour is unchanged, its release
build and core's unit tests are green, and it has been device-tested.

- **`player-core` exposes libmpv as `api`, not `implementation`.** `OwnTVPlayer`'s supertype is
  `MPVLib.EventObserver`, so a consumer could not compile against the published artifact without
  libmpv on its compile classpath. The TV app never noticed because it declares libmpv itself. Both
  apps are now pinned to one libmpv version, which is what we want anyway.
- **New `CoreBuildInfo.tvHome`, defaulting to `true`, gates `SettingsRepository.androidTvHomeEnabled`.**
  Core does no TV detection at all, so on a phone the sync worker published Watch Next entries to a
  content provider that is not there — silent only because the call site wraps it in `runCatching`.
  This is a host fact, not a device check: the question is whether the app belongs on a TV home
  screen, not whether the hardware is a TV. Every publish path and both TV-app readers already go
  through that one flow.
- **`SourceRepository.sync()` takes `onProgress` last.** Kotlin binds a trailing lambda to the final
  parameter, so `sync(source) { … }` aimed the progress callback at `forcePrune` and failed with
  "'Boolean' was expected". All four existing callers already passed it by name, so nothing moved.

### 🤖 Release plumbing

- **`ahXN00/OwnTV_Mobile` joins the pin-bump consumer matrix**, so it gets the same "Pin core x.y.z"
  pull request the TV app gets on every release. It is private until the app's first release, so
  `CONSUMER_BUMP_TOKEN` must grant access to it explicitly.

## core-1.0.5 — 2026-08-31

### 🧪 A playlist can be tested

- **New `SourceTester`**, a read-only probe that answers "is this playlist usable?" for all three
  source types and returns one of `Ok` / `AuthFailed` / `Expired` / `Unreachable`. Xtream reads the
  account API, M3U fetches the first kilobyte and checks it really starts with `#EXTM3U`, Stalker
  performs a portal handshake. Nothing is written to the database, so it is safe to run against a
  playlist that has not been saved yet.
- **`XtreamClient.XtAccountDetails` now also carries `activeConnections`, `status`, `authOk` and
  `trial`**, each parsed whether the panel sends it as a number or as a string. `active_cons` is the
  figure behind "2 of 3 connections in use"; `status` is passed through verbatim because panels invent
  their own words for it.
- **New `fetchAccountStatus()` throws where `fetchAccountDetails()` returns null.** A test has to tell
  "the host never answered" apart from "the host said no", and a null cannot carry that difference.
  `fetchAccountDetails()` is now a thin non-throwing wrapper around it, so existing callers are
  unchanged.
- Ten new strings for the result popup, in the base locale and all 24 translations.

### 🔄 Playlist auto-refresh takes a custom number of days

- **The fixed 24-hour, 48-hour and 7-day intervals are replaced by a single Manual mode carrying a day
  count from 1 to 99.** `PlaylistAutoRefresh` keeps `OFF`, `STARTUP`, `HOURS_6` and `HOURS_12` and gains
  `MANUAL`; the new `PlaylistRefresh` value type pairs a mode with `manualDays`.
- **Existing choices are translated on read, not migrated.** `PlaylistRefresh.parse()` maps the stored
  `HOURS_24`, `HOURS_48` and `DAYS_7` names to 1, 2 and 7 days, so nothing has to be rewritten in
  settings storage and a backup taken on an older build keeps working forever.
- **The stored form is unchanged in shape** — `MODE` or `MODE:days`, e.g. `MANUAL:14` — so backup
  export/import and the companion payload need no new field.
- **The companion web form** offers 1, 2, 7, 14 and 30-day presets in place of the old 24h/48h entries;
  the exact figure is dialled in on the television.
- New `settings_sources_refresh_manual`, a `settings_sources_refresh_days` plural with the correct CLDR
  quantities per language, and the day-picker title and hint — base locale plus all 24 translations. The
  two Stalker-only inline test strings are removed, replaced by the shared result popup.

## core-1.0.4 — 2026-08-30

**No library changes.** Same code as `core-1.0.3`; documentation only.

- **The README now describes the release pipeline**, and carries a status badge for it. The version
  in the "consuming core from an app" snippet was still showing `1.0.1`.

## core-1.0.3 — 2026-08-30

**No library changes.** Same code as `core-1.0.2`; this version exists to exercise the new release
pipeline end to end.

- **Every core version now gets a GitHub Release**, with its notes taken from this file. Previously
  a version existed only as a tag and a package, which was hard to read and impossible to link to.
- **The release is what tells the apps to move.** The publish workflow runs the tests, pushes both
  artifacts to GitHub Packages, and only then publishes the release — so a release can exist only
  for a version that actually built and shipped, and it is the release that opens the pin-bump pull
  request on each app. A tag whose tests fail now stops there.

## core-1.0.2 — 2026-08-30

- **Hungarian is now a fully translated, packaged language.** All 2132 strings across the six
  resource files are translated, and Hungarian is selectable in the app's language picker.

## core-1.0.1 — 2026-08-29

- The About screen's copyright line now reads **© 2026 OwnTV** instead of naming the author.
  Updated in the base locale and all 23 translations.

## core-1.0.0 — 2026-08-29

First release as a standalone library. No behaviour changed: this is the same code the OwnTV TV app
shipped in its `:core` and `:player-core` modules, extracted into its own repository with its
history intact.

- **`:core`** — Room database and 33 shipped schemas (v2–35), playlist sync and parsing for M3U /
  Xtream / Stalker, EPG, backup and restore, profiles, downloads, settings storage, launcher
  integration, and all 149 string resource files across 24 packaged locales.
- **`:player-core`** — the playback engine: libmpv, the Media3/ExoPlayer handoff, the fallback
  ladder, watchdogs and stream diagnostics.
- Both modules build and test standalone, with no app in the build graph — 309 unit tests in
  `:core`, 118 in `:player-core`.
- Published as `tv.own.owntv:core` and `tv.own.owntv:player-core`, always on the same version.
- The i18n toolkit and its four validators moved here with the strings.

</details>
