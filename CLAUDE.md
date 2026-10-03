# CLAUDE.md — Range Log

Personal Android app for Michael Laurio to log range sessions, round counts and gun
cleaning. It replaces a Google Slides log; `LAURIO_Shooting_Records.txt` is a text export
of that deck and the source of the preloaded history. Michael isn't an Android developer.
Explain build and install steps plainly and prefer doing things over telling him to.

## Current status (read first)

- **Builds cleanly** (`assembleDebug`). Installed on Michael's phone (Samsung, adb id `R5CY20N34VZ`)
  and in daily trial use as of 2026-10-03. Cloud sync works end to end: phone signs in with Google
  and the web page at https://the-laurios.com/range-app/ shows the same sessions. Photo sync (below)
  was built right after and is not yet confirmed by him.
- Install/update: `adb install -r app/build/outputs/apk/debug/app-debug.apk` (keeps data; Room
  migrations run). Before risky changes copy the DB off the phone with
  `adb exec-out run-as com.laurio.rangelog cat databases/rangelog.db`. A copy from 2026-10-03 is in
  `phone-db-backup/` (git-ignored).
- Toolchain (portable, no Android Studio, not on PATH): JDK 17 at
  `D:\Users\micha\Android\jdk-17`, SDK at `D:\Users\micha\Android\sdk` (platform 35,
  build-tools 35.0.0, platform-tools/adb). `local.properties` points at the SDK. Set
  `JAVA_HOME` before running Gradle, e.g. in PowerShell:
  `$env:JAVA_HOME='D:\Users\micha\Android\jdk-17'; .\gradlew.bat assembleDebug`.
  Python is not installed (needed only for `tools/make_seed.py`).
- Emulator: AVD `RangeLogPhone` (Pixel 7, Android 15 google_apis x86_64, WHPX accel,
  PC keyboard on, emulated back camera). Start it with
  `D:\Users\micha\Android\sdk\emulator\emulator.exe -avd RangeLogPhone` (or the
  `Range Log Emulator.lnk` shortcut in the project root), then
  `adb install -r` as above. The app is installed there, launches, and seeds the history
  (checked Sessions and Guns tabs by screenshot on 2026-09-29).
- If you bump versions, keep them consistent: Kotlin, the compose compiler plugin and
  KSP must match (`2.0.21` / `2.0.21-1.0.28`).

## Cloud sync (added 2026-10-03)

- Firebase project `range-log-88193` (his Google account). Firestore layout:
  `users/{uid}/{guns|sessions|cleanings|photos}/{id}`. A session doc nests its gun entries, strings and
  photo names. `syncId` columns on guns, sessions and cleanings (`MIGRATION_1_2`); `notMine` on guns
  (`MIGRATION_2_3`). DB is at version 3. Rules are in `firestore.rules` (published in the console).
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
    AppDatabase.kt      Room DB "rangelog.db", version 3, migrations, exportSchema=false
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
app/src/main/assets/seed.json   preloaded history (Backup JSON format)
tools/make_seed.py              regenerates seed.json from hand-transcribed records
web/index.html                 desktop web page (Firebase JS), deployed to the-laurios.com/range-app
firestore.rules                 Firestore security rules (own data only)
```

## Data model

- `Gun(id, name, description, notes, cleanInterval?, active, notMine, syncId)`. `name` is the short label
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
history, edit `tools/make_seed.py` and run `python tools/make_seed.py`. It prints per-gun
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
  offline copy. No analytics. Backup is still a user-chosen JSON file (Storage Access Framework). Plain wording; the phone UI and the web page should stay in step.
- Plain wording in the UI ("rds", "yds", "Cleaned it today").
- Verify changes with a build (and on the phone when possible) before saying they work.

## Ideas he may ask for later (not built)

Per-gun ammo type/brand, drill templates (e.g. his silhouette drill), accuracy scoring or
group size, charts over time, photo annotation, including photos in backups (zip), home
screen widget, Google Drive auto-backup, time-based (months) cleaning reminders, arrows on the phone photo viewer, view mode for cleaning entries.
