# OwnTV Core — Changelog

Core is versioned independently of the apps. A core version number never lines up with an OwnTV TV
app `v4.x` release, and the two must not be confused. Tags here are prefixed `core-`.

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
