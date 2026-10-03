# Range Log

An Android app for tracking range sessions, round counts and gun cleaning.
Built with Kotlin, Jetpack Compose and Room. Everything is stored on the phone.

## What it does

- **Sessions:** date, location, guns shot with round counts, strings of fire
  ("30 @ 15 yds"), notes per gun and per session, and target photos (camera or gallery).
  Leave a gun's round box empty and it adds up the distance strings for you.
- **Guns:** lifetime rounds, sessions, last shot date, and **rounds since last
  cleaning**. Tap "Cleaned it today" or log a cleaning with a date. You can set an
  optional "clean every N rounds" reminder that turns the card red when a cleaning is due.
  Upgrades and mods go in each gun's notes. Retire a gun to hide it from the picker
  and keep its history.
- **Stats:** totals, rounds by year, rounds by gun.
- **Backup:** export everything to a JSON file (for example to Google Drive) and
  restore it on a new phone. Photos aren't included in the backup.

It comes preloaded with the history from `LAURIO_Shooting_Records` (35 sessions,
April 2024 – September 2026) and the cleaning dates from the cleaning table.

## Build and install

1. Install **Android Studio** (free) from developer.android.com/studio.
2. Unzip this folder and choose **File → Open** in Android Studio, then pick the
   `RangeLog` folder. Let the Gradle sync finish. The first time takes a few minutes
   while it downloads the build tools.
3. On your phone: **Settings → About phone** → tap **Build number** 7 times to
   unlock Developer options. Then open **Settings → System → Developer options**
   and turn on **USB debugging**.
4. Plug the phone into your PC with USB and accept the "Allow USB debugging?" prompt.
5. In Android Studio, pick your phone in the device dropdown at the top and press
   the green **Run ▶** button. The app installs and opens.

To get an APK file instead: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
The file lands in `app/build/outputs/apk/debug/`. Copy it to the phone and open it
there. You'll need to allow installing from that source.

If Android Studio offers to upgrade the Android Gradle Plugin or libraries, it's
fine to accept.

## Project layout

```
app/src/main/java/com/laurio/rangelog/
  data/   Entities.kt (tables), RangeDao.kt (queries), Repository.kt (logic,
          round counts, backup), Drafts.kt (editor state)
  ui/     Nav.kt (bottom tabs), SessionsScreen, SessionEditScreen, GunsScreen,
          GunDetailScreen, StatsScreen
app/src/main/assets/seed.json   your existing records, loaded on first launch
```

## Notes on the imported history

- Slides with the same date were combined into one session.
- Where a round count wasn't written down, the session is kept with no count.
  The Guns tab shows those as "not counted" rather than guessing.
- The 7/15/25 "Glock 19" session is the **Gen 2 (G19.2)** (confirmed).
- There are two separate **9/10/24** sessions. They're kept apart, and both dates are correct.
- Target photos from the slides weren't carried over. Add them to any session with
  "Add from gallery".
