# OwnTV Core — Changelog

> Release notes: two parts per version — New features (by name) and Fixes. This file feeds the GitHub
> release body. Tags are prefixed `core-`; a core version never lines up with a TV or mobile app version.
>
> **Rule: bullet points only — no descriptions.** Each line is a short bolded feature/fix title and
> nothing more. The only extra detail ever allowed is a contribution credit for community work. A
> release that changes the database, backup format or anything a consuming app must adapt to says so
> in one bolded line of its own (e.g. **Database v46 · Backup v24 · Breaking**). The detail — what,
> why, files and verification — belongs in the commit message, never here.

## core-1.0.60 — 2026-09-26

### 🐛 Fixes

- **🔒 Playlists on servers with the newest Let's Encrypt certificates import again (#208)**

## core-1.0.59 — 2026-09-26

**Breaking**

### ✨ New features

- **🖼️ Match EPG brings the guide's logo, with an "Include guide logos" choice**
- **⚙️ Video player settings in smaller categories**

### 🐛 Fixes

- **💾 A removed USB stick no longer stops downloads**
- **📺 Channel logos on the Android TV home row are no longer cropped** (community PR #6 by @quangtruongnb)
- **📃 Playlists saved with a byte-order mark keep their EPG address** (community PR #7 by @Sekator778)
- **🔤 The EPG logo setting works on the phone**

## core-1.0.58 — 2026-09-26

**Database v46 · Backup v24 · Breaking**

### ✨ New features

- **📺 One live tuner shared by both apps**
- **⏸️ Pause and rewind live TV on channels without catch-up**
- **⏮️ Previous channel**
- **🎬 OwnTV's own mpv engine**
- **🔊 One volume scale on every player, night mode, volume leveling and a passthrough switch**
- **🖼️ Maximum video quality and tunneled playback**
- **🌐 Preferred audio and subtitle language per profile, 50 languages and "Original language"**
- **📶 Film buffer, network timeout and reconnect attempts**
- **😴 Sleep timer**
- **🧠 The player remembers engine choices, sound-only channels and audio delays per stream**
- **🎨 Eight app icons and logos, one switcher**

### 🐛 Fixes

- **🔄 "Restart now" reopens the app after an icon change**

## core-1.0.57 — 2026-09-21

### 🐛 Fixes

- **🗓️ An empty guide source says so instead of spinning for minutes**

## core-1.0.56 — 2026-09-21

### 🐛 Fixes

- **💥 The app would not open after upgrading from database v40**

## core-1.0.55 — 2026-09-21

### ✨ New features

- **⏺️ DASH channels can be recorded**
- **🎞️ Films and episodes show a Format row**

### 🐛 Fixes

- **🔁 An unprotected DASH channel no longer loops into a reconnect storm**
- **⏺️ An interrupted recording is finished on the next run**

## core-1.0.54 — 2026-09-21

### ✨ New features

- **▶️ DASH channels play**
- **🔗 A last-resort address for an Xtream channel that will not open**

### 🐛 Fixes

- **ℹ️ Stream info no longer reports DASH channels as MPEG-TS**
- **🔒 Recording a DRM channel is refused instead of attempted**

## core-1.0.53 — 2026-09-20

### ✨ New features

- **🎬 A layout choice for Movies & Series**
- **📐 The Cinematic detail block's height is its own setting**

## core-1.0.52 — 2026-09-20

### 🐛 Fixes

- **🗓️ A guide sync can finish with the screen off, and no longer restarts from zero**
- **🔄 Local sync hands out a key that opens its own backup**
- **💾 The first-run restore reports what it restored**

## core-1.0.51 — 2026-09-20

### 🐛 Fixes

- **💥 EPG sync, unfavourite, clear history and backup restore work again**
- **💾 Restoring a backup is no longer treated as a sync merge**

## core-1.0.50 — 2026-09-19

### ✨ New features

- **👁️ A watch session says when it opens and closes**

## core-1.0.49 — 2026-09-19

### ✨ New features

- **📦 SQLite now ships inside the app**

### 🐛 Fixes

- **⚡ A re-sync no longer rewrites rows that only moved**
- **⏩ The catalogue sync yields to playback**
- **📶 The stream-limit measurement no longer slows setup**
- **⚠️ A truncated bulk fetch no longer looks like a clean success**

## core-1.0.47 — 2026-09-18

### 🐛 Fixes

- **🎞️ The measured frame rate no longer lands on the wrong standard rate**

## core-1.0.46 — 2026-09-18

**Database v41 · Breaking**

### ✨ New features

- **🗓️ Guide days to keep is the user's choice, not 48 hours**
- **🔄 EPG auto-refresh every N days**

### 🐛 Fixes

- **⚡ The guide no longer reads the whole database to draw one screen**
- **🔎 "Match EPG" lists what the guide really has**
- **🔗 Manual matches survive a deleted and re-added playlist**
- **✅ Auto-match no longer claims success it cannot deliver**
- **🧹 Duplicate programmes are collapsed when stored**

## core-1.0.45 — 2026-09-16

### ✨ New features

- **⚡ Xtream sync fetches all three sections at once**

### 🐛 Fixes

- **📂 A category the provider lists no longer arrives empty**

## core-1.0.44 — 2026-09-15

### ✨ New features

- **📱 A new device can be set up from the one you already have**

## core-1.0.43 — 2026-09-14

### 🐛 Fixes

- **⏪ Stalker catch-up plays**

## core-1.0.42 — 2026-09-14

### ✨ New features

- **📄 The documentation, rewritten to the point**

## core-1.0.41 — 2026-09-14

### ✨ New features

- **⬆️ The playback engine, the database and the build toolchain move up**

## core-1.0.40 — 2026-09-14

### 🐛 Fixes

- **⬇️ The updater asks the right repository**

## core-1.0.39 — 2026-09-13

### ✨ New features

- **🔠 A first-run step that sets how big everything is**

## core-1.0.38 — 2026-09-13

### 🐛 Fixes

- **🔢 One category order instead of two that had to agree**

## core-1.0.37 — 2026-09-13

**Database v40**

### ✨ New features

- **📁 Downloads and recordings can be saved to a folder you pick**
- **📶 How many streams a provider allows, measured rather than assumed**
- **⏺️ Live TV recording**
- **🧪 A playlist's Test button became Info**

### 🐛 Fixes

- **🗓️ The guide, the channel row and the preview pane agree**
- **🐶 One live-engine watchdog, shared by both apps**
- **⬇️ An episode download appears in the Series list**
- **🚫 HTTP 407 is treated as a session limit**

## core-1.0.36 — 2026-09-12

**Database v39**

### ✨ New features

- **📂 A download row says where the file went**
- **⏺️ The Live TV recording engine**

## core-1.0.35 — 2026-09-12

### 🐛 Fixes

- **🔢 The Multiview refusals are real plurals**

## core-1.0.34 — 2026-09-12

### ✨ New features

- **🔲 Multiview's rules, and one owner for the three folder names**

## core-1.0.33 — 2026-09-12

### ✨ New features

- **⬇️ One answer to "is this downloading?", and a download line for the status pill**

## core-1.0.32 — 2026-09-11

### 🐛 Fixes

- **🗓️ Two guides in one playlist header are two guides again** (TV #171)

## core-1.0.31 — 2026-09-11

### ✨ New features

- **📺 The Stalker portal's own guide, so Stalker has EPG and catch-up**
- **📅 When an episode first aired**
- **👤 A picture of your own for a profile**

### 🐛 Fixes

- **🔑 A portal that said "slow down" is no longer read as "logged out"**
- **⏪ Catch-up works on a Stalker portal**

## core-1.0.30 — 2026-09-11

### 🐛 Fixes

- **📃 M3U titles keep their commas, and keep their favourites**
- **🔤 Alphabetical sort reaches the items inside a folder**

## core-1.0.29 — 2026-09-11

### 🐛 Fixes

- **🔄 Local sync keeps the newer of two facts**
- **🔑 Local sync no longer asks for a password it should not**
- **📱 Local sync no longer lists the same device twice**

## core-1.0.28 — 2026-09-11

### ✨ New features

- **💾 The last backup is recorded**
- **⋯ Text for the new More screen**

## core-1.0.27 — 2026-09-07

### ✨ New features

- **⋯ A More section in the main menu**

### 🐛 Fixes

- **🗑️ Nine unused strings removed**

## core-1.0.26 — 2026-09-06

**Database v36**

### ✨ New features

- **🔄 Local sync between devices**
- **🪦 Deletions that survive a merge**
- **👁️ A dry run before anything is applied**

## core-1.0.25 — 2026-09-06

### ✨ New features

- **📡 A player failure for a receiver that cannot play the stream**
- **🔈 The player can say the sound is not coming out of this device**

## core-1.0.24 — 2026-09-06

### ✨ New features

- **✨ The glass arrival shine is a setting**

## core-1.0.23 — 2026-09-06

### ✨ New features

- **📄 The README says how the apps get core**

## core-1.0.22 — 2026-09-06

### ✨ New features

- **👤 One implementation of what a profile is**
- **🔒 The profile gate at launch, decided once**

## core-1.0.21 — 2026-09-06

### ✨ New features

- **🎛️ Now Trending in two shapes**

## core-1.0.20 — 2026-09-06

### ✨ New features

- **🔎 Why the Now Trending row is, or is not, on Home**

## core-1.0.19 — 2026-09-05

### ✨ New features

- **🌍 Three new strings in every packaged language**

## core-1.0.18 — 2026-09-05

### ✨ New features

- **🎨 The shell's region colours moved to core**

### 🐛 Fixes

- **🗓️ The EPG separator lost its spaces**

## core-1.0.17 — 2026-09-05

### ✨ New features

- **🪟 Two more surfaces the glass can be scoped to**

## core-1.0.16 — 2026-09-04

### ✨ New features

- **📱 Settings for a player you can carry around**
- **🎵 Which channels were watched without a picture**

## core-1.0.15 — 2026-09-04

### ✨ New features

- **🪟 One more glass preset**

## core-1.0.14 — 2026-09-04

### ✨ New features

- **✂️ Span selection and bulk rename, shared by both apps**
- **📺 A Stalker portal's expiry date, read in one place**

## core-1.0.13 — 2026-09-03

### ✨ New features

- **🔎 One search for both apps**
- **📱 The settings a phone has and a television does not**
- **📶 Downloads can wait for Wi-Fi**

## core-1.0.12 — 2026-09-02

### ✨ New features

- **🧱 The parts a second app needs, taken out of the TV app**
- **⚙️ Settings for a touch screen**

## core-1.0.11 — 2026-09-02

### ✨ New features

- **🖼️ Cached TMDB posters fill the tiles a provider left blank**

## core-1.0.10 — 2026-09-02

### ✨ New features

- **📱 The playback session can behave like a phone**

### 🐛 Fixes

- **🔊 Audio focus is no longer lost on every pause**

## core-1.0.9 — 2026-09-02

### 🐛 Fixes

- **⚡ EPG auto-match finishes on TV hardware**
- **🌍 EPG auto-match works outside the Latin alphabet**

## core-1.0.8 — 2026-09-02

### ✨ New features

- **🗂️ The content menus and Live TV queries moved to core**
- **▶️ The player pieces both apps need**

## core-1.0.7 — 2026-09-01

### ✨ New features

- **🎨 The colour values moved to core**
- **🧭 The main-menu sections moved to core**

## core-1.0.6 — 2026-09-01

### ✨ New features

- **📱 A non-TV app can use core**
- **🤖 Release automation**

## core-1.0.5 — 2026-08-31

### ✨ New features

- **🧪 A playlist can be tested**
- **🔄 Playlist auto-refresh takes a custom number of days**

## core-1.0.4 — 2026-08-30

### ✨ New features

- **📄 The README describes the release pipeline**

## core-1.0.3 — 2026-08-30

### ✨ New features

- **🏷️ Every core version gets a GitHub Release, which tells the apps to update**

## core-1.0.2 — 2026-08-30

### ✨ New features

- **🌍 Hungarian is a fully translated language**

## core-1.0.1 — 2026-08-29

### 🐛 Fixes

- **©️ The About screen's copyright line reads "© 2026 OwnTV"**

## core-1.0.0 — 2026-08-29

### ✨ New features

- **📦 First release as a standalone library**
