# CLAUDE.md — Range Log

Personal Android app for Michael Laurio to log range sessions, round counts and gun
cleaning. It replaces a Google Slides log; `LAURIO_Shooting_Records.txt` is a text export
of that deck and the source of the preloaded history. Michael isn't an Android developer.
Explain build and install steps plainly and prefer doing things over telling him to.

## Current status (read first)

- **Builds cleanly** (`assemblePersonalDebug`; see Flavors below). Installed on Michael's phone (Samsung, adb id `R5CY20N34VZ`)
  and in daily trial use as of 2026-10-03. Cloud sync works end to end: phone signs in with Google
  and the web page at https://the-laurios.com/range-app/ shows the same sessions. Photo sync (below)
  was built right after and is not yet confirmed by him.
- Install/update: `adb install -r app/build/outputs/apk/personal/debug/app-personal-debug.apk` (keeps data; Room
  migrations run). Before risky changes copy the DB off the phone with
  `adb exec-out run-as com.laurio.rangelog cat databases/rangelog.db`. A copy from 2026-10-03 is in
  `phone-db-backup/` (git-ignored).
- Toolchain (portable, no Android Studio, not on PATH): JDK 17 at
  `D:\Users\micha\Android\jdk-17`, SDK at `D:\Users\micha\Android\sdk` (platform 35,
  build-tools 35.0.0, platform-tools/adb). `local.properties` points at the SDK. Set
  `JAVA_HOME` before running Gradle, e.g. in PowerShell:
  `$env:JAVA_HOME='D:\Users\micha\Android\jdk-17'; .\gradlew.bat assemblePersonalDebug`.
  Python is not installed (needed only for `tools/make_seed.py`).
- Emulator: AVD `RangeLogPhone` (Pixel 7, Android 15 google_apis x86_64, WHPX accel,
  PC keyboard on, emulated back camera). Start it with
  `D:\Users\micha\Android\sdk\emulator\emulator.exe -avd RangeLogPhone` (or the
  `Range Log Emulator.lnk` shortcut in the project root), then
  `adb install -r` as above. The app is installed there, launches, and seeds the history
  (checked Sessions and Guns tabs by screenshot on 2026-09-29).
- If you bump versions, keep them consistent: Kotlin, the compose compiler plugin and
  KSP must match (`2.0.21` / `2.0.21-1.0.28`).

## Flavors: personal and share (added 2026-10-30)

- One code base, two apps (`productFlavors`, dimension `edition`). **personal** = his app, unchanged
  (`com.laurio.rangelog`, seed history, Firebase sync, undo). **share** = Android-only build for friends
  (`com.laurio.rangelog.share`, `-share` version suffix, named "Range Logger" with a navy crosshair icon
  vs. the personal green target): starts empty, no Firebase, data only on the phone,
  zip backup/restore still works. iPhone support was considered (phone web app) and parked.
- The switch is `BuildConfig.CLOUD_SYNC` (`CloudSync.ENABLED`). Off: `auth`/`fs` are never touched (lazy),
  `uid` is always null so pushes/undo no-op, `start`/`signOut` return, `signIn` throws. Admin hides the
  Undo and Sync cards. Shared code stays in `src/main`; flavor-only bits:
  `src/personal/assets/seed.json` (seeding skips when the asset is absent),
  `src/share/AndroidManifest.xml` (removes `FirebaseInitProvider`), `src/share/res/values/share.xml`
  (app name, icon background color, placeholder `default_web_client_id`), `src/share/res/drawable/ic_launcher_foreground.xml`. The google-services task is disabled for share variants, so
  share builds need no `google-services.json` (which is how GitHub can build it).
- New features go in `src/main` and land in both apps; anything that needs Firebase must check
  `CloudSync.ENABLED` (or live behind `uid`).
- Signing: personal uses the debug key (all build types). shareRelease uses the share release key when
  `keystore.properties` (git-ignored; storeFile/storePassword/keyAlias/keyPassword) exists, else debug.
  The key lives on his PC in `share-signing-key/` (git-ignored): `share-release.jks`, a filled-in
  `keystore.properties` (copy it to the project root to sign shareRelease locally) and the base64 text for the secret. Updates must be
  signed with that same key.
- Versions: `versionCode` = git commit count (`git rev-list --count HEAD`, read in `app/build.gradle.kts`;
  falls back to 1 without git), `versionName` = `1.0.<count>` (+ `-share`). Same on his PC and GitHub (checkout
  uses `fetch-depth: 0`), so each build installs over the last. Shown at the bottom of the Admin tab.
- `.github/workflows/build-share-apk.yml` builds `assembleShareRelease` on pushes that touch the app (paths filter)
  and uploads `RangeLogger-1.0.<count>.apk` as an artifact; a `share-v*` tag also runs a separate `release` job
  (the only one with `contents: write`) that makes a GitHub Release with the APK. Signing secrets:
  SHARE_KEYSTORE_BASE64, SHARE_STORE_PASSWORD, SHARE_KEY_ALIAS, SHARE_KEY_PASSWORD (without them: debug key + warning).

## Cloud sync (added 2026-10-03)

- Firebase project `range-log-88193` (his Google account). Firestore layout:
  `users/{uid}/{guns|sessions|cleanings|photos}/{id}`. A session doc nests its gun entries, strings and
  photo names. `syncId` columns on guns, sessions and cleanings (`MIGRATION_1_2`); `notMine` on guns
  (`MIGRATION_2_3`). DB is at version 5 (`MIGRATION_3_4`: `cleanMonths`, `photo`; `MIGRATION_4_5`: `deleted` on guns). Rules are in `firestore.rules` (published in the console).
- `data/CloudSync.kt` does both directions. Repository wraps each local write in
  `sync.exclusive {}` and pushes the doc. Remote changes arrive as snapshot events (own pending
  writes are skipped). Editing code must keep the stored `syncId` (see `saveGun`, `saveSessionRows`).
- Photos: each image is shrunk to a ~1280px JPEG (under 600 KB) and stored base64 in
  `users/{uid}/photos/{fileName}`, because Firebase Storage needs a billing plan. Phone uploads
  after session saves and on startup; web page can add/remove photos; phone downloads missing files.
- First sign-in: cloud empty -> upload phone; phone empty -> download; both have data -> user picks
  in the Sync card (Stats tab). Restoring a backup makes the phone's data the truth (replaces cloud).
- Google sign-in uses Credential Manager. It needs the debug SHA-1 registered in Firebase and a fresh
  `app/google-services.json` (error 16 means the Android OAuth client was missing). Debug SHA-1:
  `BC:5D:46:A6:C6:EA:76:2A:A8:D9:BC:13:F3:0A:D2:13:43:B2:B8:4B`.
- Web page: `web/index.html` (single file, Firebase JS SDK from gstatic). Local use:
  `web/Start Range Log Web.ps1` (localhost:8080). Live copy: `.github/workflows/deploy-web.yml` FTPs
  `web/` to the-laurios.com/range-app on every push that touches `web/` (repo secrets FTP_SERVER,
  FTP_USERNAME, FTP_PASSWORD, FTP_DIR). `the-laurios.com` must stay in Firebase Authorized domains.
- Repo: https://github.com/michaellaurio-oss/range-log (private, branch `main`). Commit and push when asked.
- Not synced: photo captions are stored but never edited anywhere; reminders are local to the phone.

## Behavior decisions (2026-10-03)

- Cleaning reminder: a local notification when saving a session pushes a gun to or past its
  `cleanInterval` (`CleaningReminders.kt`); new guns default to 500 rounds, seed guns are set to 500.
- Sessions and guns open in read-only view mode with an Edit button (phone and web). Cards on the web
  open on click. Photos open in a viewer: swipe on the phone, on-image arrows and arrow keys on the web.
- Guns have "Not my gun" (`notMine`) for rented/borrowed ones: no cleaning log, reminders or due state.

## Undo (added 2026-10-16)

- One shared history of the last 10 changes in Firestore, `users/{uid}/undo/{id}`:
  `{label, time, device: "phone"|"web", ops: [{c, id, before, after}]}` (c = guns|sessions|cleanings, id = syncId,
  before/after = the cloud doc or null). Needs sign-in; nothing is recorded or undoable while signed out.
- Phone: `Repository.undoable {}` wraps every save/delete/cleaning/photo-import/restore, diffing
  `CloudSync.docState()` before and after (`recordUndo`). `undoLast(force)` checks `undoConflicts` (docs edited
  since the step) and `applyUndo` reverts locally and in the cloud. New repository writes must go through `undoable`.
- Web: `save`/`remove` call `record()`, which groups one click's writes into one entry; `undoLast()` mirrors the
  phone, with a `confirm` on conflicts. Header button, Ctrl+Z, history card on the Stats tab.
- Photo files on the phone are never deleted directly: removed names go in a prefs "trash" set and are deleted only
  when neither the DB nor any undo entry references them. Cloud photo docs are never deleted, so pictures always come back.
- Each history row has its own Undo button, shown only while every item the step touched is still as the step left it
  (otherwise "edited since"). The snackbar and header button still offer "undo anyway" on conflicts.
- Sessions are never deleted. Removing a gun offers archive (`active=false`, shown "(archived)") or delete permanently:
  a gun no session uses is removed; otherwise it gets `deleted=true` (hidden, shown "(deleted)"). Undoing the creation
  of a gun that sessions use also tombstones it. `deleted` is only written to the cloud doc when true.
- Admin tab (phone and web) holds undo history, sync, photo import and backup; Stats is stats only.
- Gun pictures live in the same photos folder (`Gun.photo`, `cleanMonths`, DB v4).

## Stack

Kotlin 2.0.21 · AGP 8.7.3 · Gradle 8.11.1 (wrapper included) · JDK 17 · compileSdk/targetSdk 35,
minSdk 26 · Jetpack Compose (BOM 2024.12.01, Material 3, material-icons-core only, no
extended icons) · Navigation Compose 2.8.5 · Room 2.6.1 via KSP · kotlinx.serialization 1.7.3
· Coil 2.7.0 · Firebase BOM 33.7.0 (auth, firestore) + Credential Manager · exifinterface. No DI framework: `RangeLogApp.repository` is a lazy singleton, and ViewModels
are `AndroidViewModel`s created with the default factory (with `SavedStateHandle` for nav args).

## Layout

```
app/src/main/java/com/laurio/rangelog/
  RangeLogApp.kt        Application; owns Repository
  MainActivity.kt       edge-to-edge, sets RangeLogTheme + RangeLogNav
  data/
    Entities.kt         Room entities (also @Serializable), relation POJOs, Backup
    RangeDao.kt         all queries
    AppDatabase.kt      Room DB "rangelog.db", version 5, migrations, exportSchema=false
    Repository.kt       business logic: save/delete session, gun summaries,
                        photos, JSON backup/restore, first-launch seeding
    CloudSync.kt        Firestore two-way sync, Google sign-in, photo up/download
    Drafts.kt           editor state (SessionDraft/GunEntryDraft/StringDraft/PhotoDraft)
  ui/
    Nav.kt              bottom tabs (sessions, guns, stats) + routes session/{id}, gun/{id}
                        (id 0 = new); outer Scaffold uses WindowInsets(0)
    MainViewModel.kt    activity-scoped: sessions, gun summaries, snackbar messages,
                        export/import, triggers seeding
    SessionsScreen.kt   list grouped by year, search
    SessionEditScreen.kt  SessionEditViewModel; view mode + editor (guns, distances, notes, photos)
    GunsScreen.kt       gun cards with rounds since cleaning, "Cleaned it today"
    GunDetailScreen.kt  GunDetailViewModel; view mode + gun edit, cleaning log, sessions for the gun
    StatsScreen.kt      totals, bar lists, photo import by filename, backup export/restore
                        (Repository.importPhotosByName: files named YYYYMMDD##.jpg go to the session
                        on that date, stored as <name>.jpg so re-importing skips duplicates; if two
                        sessions share a date the lowest id gets them. Tested in the emulator on
                        2026-10-02 with 9 photos from `photos/`.)
    Theme.kt, Format.kt date/number helpers
app/src/personal/assets/seed.json   preloaded history (Backup JSON format), personal build only
app/src/share/                  share-build overrides (manifest, placeholder string)
tools/make_seed.py              regenerates seed.json from hand-transcribed records
web/index.html                 desktop web page (Firebase JS), deployed to the-laurios.com/range-app
firestore.rules                 Firestore security rules (own data only)
```

## Data model

- `Gun(id, name, description, notes, cleanInterval?, cleanMonths?, photo, active, notMine, syncId, deleted)`. `name` is the short label
  he uses ("SP01", "HCP", "G19.3"). `notes` holds upgrades and mods. Retired
  (`active=false`) guns are hidden from the picker but keep their history.
- `Session(id, date, location, notes)`. **Dates are `LocalDate.toEpochDay()` longs.**
- `SessionGun(id, sessionId, gunId, rounds?, notes)`. `rounds == null` means not
  recorded. Keep null distinct from 0, because the UI shows these as "not counted".
- `DrillString(id, sessionGunId, rounds, yards: Double, notes)`: "30 @ 15 yds".
  If a gun's round box is empty on save, rounds = sum of its strings.
- `Cleaning(id, gunId, date, notes)`, `Photo(id, sessionId, fileName, caption)`.
  Photo files live in `filesDir/photos/` and are shared through FileProvider
  (`${applicationId}.fileprovider`, `res/xml/file_paths.xml`) for the camera.
- **No SQL foreign keys**, on purpose. Child rows are deleted by hand in
  `Repository.deleteSession` / `saveSession`. A gun can only be deleted if it has no
  sessions (`deleteGunIfUnused`); otherwise retire it.
- Saving a session replaces all its child rows (strings, session_guns, photos) inside one
  transaction. Photo files removed during an edit are deleted after the save. Files added
  and then discarded are cleaned up by `SessionEditViewModel.discard()`.
- **Rounds since cleaning** (`Repository.gunSummaries`): sum of rounds in sessions dated
  strictly after the gun's latest cleaning. A session on the same day as a cleaning counts
  as before it.
- Schema changes: bump the DB version and add a Room `Migration`. Don't use
  destructive fallback, because this holds his real log. Keep the `Backup` JSON format
  backward compatible (the decoder uses `ignoreUnknownKeys` and field defaults).

## Seed data

`seed.json` is loaded once on first launch (SharedPreferences flag `seeded`), and only if
the DB is empty. It uses the same format as Export/Restore. To change the preloaded
history, edit `tools/make_seed.py` and run `python tools/make_seed.py` (writes `app/src/personal/assets/seed.json`). It prints per-gun
totals and rounds since cleaning so you can check them against the cleaning table in the
records file. Data decisions already made:

- Slides with the same date were merged into one session. There were two 9/10/24 entries;
  Michael later corrected the one with the "Cajun Gunworks" note (HCP trigger upgrade) to
  **12/10/24** (2026-10-02), so it is no longer a duplicate date and nothing is merged.
- 9/13/25: SP01 90 + 40 merged into 130.
- 7/15/25 "Glock 19" is assigned to **G19.2 (Gen 2)**, because that makes G19.3 since
  cleaning = 250, matching his 80+120+50. Confirmed by Michael.
- Unspecified "Glock 19"/"G19" otherwise means G19.3. "CZ" means SP01. "CZ 75B" (5/8/24)
  is its own gun.
- 7/25/26 Nevada trip: the other guns shot (Ruger American, Sig 226/320, .22, clays with
  the Mossberg, Marlin) are in the session notes, not as entries, because there were no counts.
- Cleaning dates come from the cleaning table. G19.2, .357 and .38 were "cleaned 1/1/25".
  HCP is also his carry gun ("& carry"), so its range count undercounts.
- Target photos in the original slides weren't carried over.

## Conventions

- Compose + Material 3. Keep it simple and phone-first: big touch targets, numeric
  keyboards for counts, and nothing that needs a network connection.
- Data syncs through his own Firebase project `range-log-88193` (Firestore + Google sign-in), added
  2026-10-03 at his request, so the log works on phone and desktop. Local Room DB stays the
  offline copy. No analytics. Backup is a user-chosen zip (Storage Access Framework): backup.json plus photos/<name> (Repository.exportZip/importBackup); restore also accepts old plain .json backups. Plain wording; the phone UI and the web page should stay in step.
- Plain wording in the UI ("rds", "yds", "Cleaned it today").
- Verify changes with a build (and on the phone when possible) before saying they work.

## Ideas he may ask for later (not built)

Per-gun ammo type/brand, drill templates (e.g. his silhouette drill), accuracy scoring or
group size, charts over time, photo annotation, home
screen widget, Google Drive auto-backup, arrows on the phone photo viewer, view mode for cleaning entries.
