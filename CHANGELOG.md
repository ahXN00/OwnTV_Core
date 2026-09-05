# OwnTV Core — Changelog

Core is versioned independently of the apps. A core version number never lines up with an OwnTV TV
app `v4.x` release, and the two must not be confused. Tags here are prefixed `core-`.

## core-1.0.19 — unreleased

### 📃 M3U titles keep their commas

- **The display name is what follows the first comma outside a quoted attribute, not the last
  one.** `M3uParser` took `substringAfterLast(',')`, so `Movie, The (1999)` was listed as
  `The (1999)` and `Live, Love, Music` as `Music`. The stable key an M3U row is upserted by is
  derived from the name, so every such title also changed key whenever its trailing text did, and
  its favourites, history and resume position were orphaned at that resync. Quoted values are still
  skipped, so a `group-title="News, Politics"` cannot be mistaken for the separator, and a line
  with an unbalanced quote keeps the last-comma reading it always had rather than being dropped.
- **A line with no separator, or with nothing after it, falls back to `tvg-name`** instead of
  taking the raw `#EXTINF…` text as the title. With neither there is nothing to call the entry,
  and it is skipped as before.
- Titles without a comma parse to the same name as before, so an ordinary live lineup is not
  re-keyed. A title that *was* truncated gets its full name — and a new key — on the first sync
  after this change; favourites, history and resume position pinned to the truncated row do not
  survive that one sync, because the relink looks for the old name, which no longer exists. Only
  titles containing a comma are affected.

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
