package com.laurio.rangelog.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import com.laurio.rangelog.CleaningReminders
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

data class GunSummary(
    val gun: Gun,
    val totalRounds: Int,
    val sessionCount: Int,
    val lastShot: LocalDate?,
    val lastCleaned: LocalDate?,
    val roundsSinceCleaning: Int,
    /** Sessions since the last cleaning where the round count wasn't recorded. */
    val uncountedSinceCleaning: Int,
) {
    private val roundsDue: Boolean
        get() = !gun.notMine && gun.cleanInterval != null && roundsSinceCleaning >= gun.cleanInterval

    /** Months since the last cleaning, but only if the gun has been shot since (an unused gun stays clean). */
    private val monthsSinceCleaning: Long?
        get() = lastCleaned?.takeIf { roundsSinceCleaning > 0 || uncountedSinceCleaning > 0 }
            ?.let { ChronoUnit.MONTHS.between(it, LocalDate.now()) }

    private val monthsDue: Boolean
        get() = !gun.notMine && gun.cleanMonths != null && (monthsSinceCleaning ?: -1) >= gun.cleanMonths

    val cleaningDue: Boolean get() = roundsDue || monthsDue

    /** Why cleaning is due, for the reminders. */
    val dueDetail: String
        get() = buildList {
            if (roundsDue) {
                val over = roundsSinceCleaning - (gun.cleanInterval ?: 0)
                add("$roundsSinceCleaning rds since cleaning" + if (over > 0) " ($over over)" else "")
            }
            if (monthsDue) add("last cleaned $monthsSinceCleaning months ago")
        }.joinToString(", ")
}

data class PhotoImportResult(val added: Int, val duplicates: Int, val unmatched: List<String>)

class Repository(private val context: Context, private val db: AppDatabase) {
    private val dao = db.dao()
    val sync = CloudSync(context, db) { name -> photoFile(name) }

    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    val undo = UndoLog(context, json)
    /** Labels of destructive changes, so the UI can offer an Undo right away. */
    val undoOffers = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val guns: Flow<List<Gun>> = dao.observeGuns()
    val sessions: Flow<List<SessionFull>> = dao.observeSessions()
    val cleanings: Flow<List<Cleaning>> = dao.observeCleanings()
    val roundRows: Flow<List<RoundRow>> = dao.observeRoundRows()

    val gunSummaries: Flow<List<GunSummary>> =
        combine(guns, roundRows, cleanings) { gunList, rows, cleaningList ->
            gunList.map { gun ->
                val mine = rows.filter { it.gunId == gun.id }
                val lastCleaned = cleaningList.filter { it.gunId == gun.id }.maxOfOrNull { it.date }
                // Sessions on the day of a cleaning are assumed to come before it.
                val since = mine.filter { lastCleaned == null || it.date > lastCleaned }
                GunSummary(
                    gun = gun,
                    totalRounds = mine.sumOf { it.rounds ?: 0 },
                    sessionCount = mine.map { it.sessionId }.distinct().size,
                    lastShot = mine.maxOfOrNull { it.date }?.let(LocalDate::ofEpochDay),
                    lastCleaned = lastCleaned?.let(LocalDate::ofEpochDay),
                    roundsSinceCleaning = since.sumOf { it.rounds ?: 0 },
                    uncountedSinceCleaning = since.count { it.rounds == null },
                )
            }
        }

    // ---------------- Guns ----------------

    suspend fun getGun(id: Long): Gun? = dao.getGun(id)

    suspend fun saveGun(gun: Gun): Long =
        undoable(if (gun.id == 0L) "Added gun ${gun.name}" else "Edited gun ${gun.name}") {
            sync.exclusive {
                if (gun.id == 0L) {
                    val id = dao.insertGun(gun)
                    sync.pushGun(gun.copy(id = id))
                    id
                } else {
                    // The editor builds a fresh Gun, so keep the stored cloud id.
                    val saved = gun.copy(syncId = dao.getGun(gun.id)?.syncId ?: gun.syncId)
                    dao.updateGun(saved)
                    sync.pushGun(saved)
                    gun.id
                }
            }
        }

    /** Deletes a gun only if it has never been logged in a session. Returns false otherwise. */
    suspend fun deleteGunIfUnused(id: Long): Boolean =
        undoable("Deleted gun ${dao.getGun(id)?.name.orEmpty()}", offer = true, changed = { it }) {
            sync.exclusive {
                if (dao.sessionCountForGun(id) > 0) return@exclusive false
                val gun = dao.getGun(id)
                val cleanings = dao.allCleanings().filter { it.gunId == id }
                db.withTransaction {
                    dao.deleteCleaningsForGun(id)
                    dao.deleteGun(id)
                }
                cleanings.forEach { sync.deleteCleaning(it.syncId) }
                gun?.let { sync.deleteGun(it.syncId) }
                true
            }
        }

    suspend fun logCleaning(gunId: Long, date: LocalDate, notes: String) = undoable("Logged a cleaning") {
        sync.exclusive {
            val cleaning = Cleaning(gunId = gunId, date = date.toEpochDay(), notes = notes.trim())
            dao.insertCleaning(cleaning)
            sync.pushCleaning(cleaning)
        }
    }

    suspend fun deleteCleaning(cleaning: Cleaning) = undoable("Deleted a cleaning entry", offer = true) {
        sync.exclusive {
            dao.deleteCleaning(cleaning)
            sync.deleteCleaning(cleaning.syncId)
        }
    }

    // ---------------- Undo ----------------

    private suspend fun currentBackup(): Backup = db.withTransaction {
        Backup(
            guns = dao.allGuns(),
            sessions = dao.allSessions(),
            sessionGuns = dao.allSessionGuns(),
            strings = dao.allStrings(),
            cleanings = dao.allCleanings(),
            photos = dao.allPhotos(),
        )
    }

    private suspend fun photoNames(): Set<String> =
        (dao.allPhotos().map { it.fileName } + dao.allGuns().map { it.photo }).filter { it.isNotBlank() }.toSet()

    /**
     * Runs a change to the log and remembers how things looked before it. Pictures the change
     * removes are only deleted once no undo step could bring them back.
     */
    private suspend fun <T> undoable(
        label: String,
        offer: Boolean = false,
        changed: (T) -> Boolean = { true },
        block: suspend () -> T,
    ): T {
        val snapshot = currentBackup()
        val before = photoNames()
        val result = block()
        if (changed(result)) {
            undo.push(label, snapshot)
            if (offer) undoOffers.tryEmit(label)
        }
        discardPhotos(before - photoNames())
        return result
    }

    private val trashPrefs get() = context.getSharedPreferences("rangelog", Context.MODE_PRIVATE)

    /** Marks pictures as unwanted, then deletes every unwanted picture that no undo step still needs. */
    private suspend fun discardPhotos(removed: Set<String>) = withContext(Dispatchers.IO) {
        val trash = trashPrefs.getStringSet("trashPhotos", emptySet())!! + removed
        val keep = photoNames() + undo.referencedPhotos()
        trash.filter { it !in keep }.forEach { photoFile(it).delete() }
        trashPrefs.edit().putStringSet("trashPhotos", trash.filter { it in keep }.toSet()).apply()
    }

    /** Puts the log back as it was before the latest change. Returns what was undone, or null. */
    suspend fun undoLast(): String? {
        val entry = undo.pop() ?: return null
        val before = photoNames()
        replaceAll(entry.backup)
        discardPhotos(before - photoNames())
        return entry.label
    }

    // ---------------- Sessions ----------------

    suspend fun getSession(id: Long): SessionFull? = dao.getSession(id)

    private fun dayLabel(epochDay: Long) = LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("MMM d, yyyy"))

    suspend fun saveSession(draft: SessionDraft): Long {
        val label = (if (draft.id == 0L) "Added session " else "Edited session ") + dayLabel(draft.date.toEpochDay())
        val id = undoable(label) {
            sync.exclusive {
                saveSessionRows(draft).also { sync.pushSession(it) }
            }
        }
        // Remind about every gun at or past its limit on each save, until it is cleaned.
        CleaningReminders.notifyDue(context, gunSummaries.first().filter { it.cleaningDue })
        return id
    }

    private suspend fun saveSessionRows(draft: SessionDraft): Long {
        val existing = if (draft.id != 0L) dao.getSession(draft.id) else null
        val existingSyncId = existing?.session?.syncId
        val id = db.withTransaction {
            val session = Session(
                id = draft.id,
                date = draft.date.toEpochDay(),
                location = draft.location.trim(),
                notes = draft.notes.trim(),
                syncId = existingSyncId ?: UUID.randomUUID().toString(),
            )
            val sessionId = if (draft.id == 0L) dao.insertSession(session) else {
                dao.updateSession(session); draft.id
            }
            dao.deleteStringsForSession(sessionId)
            dao.deleteSessionGuns(sessionId)
            dao.deleteSessionPhotos(sessionId)

            for (entry in draft.guns) {
                val gunId = entry.gunId ?: continue
                val strings = entry.strings.mapNotNull { s ->
                    val r = s.rounds.trim().toIntOrNull()
                    val y = s.yards.trim().toDoubleOrNull()
                    if (r == null && y == null && s.notes.isBlank()) null
                    else DrillString(sessionGunId = 0, rounds = r ?: 0, yards = y ?: 0.0, notes = s.notes.trim())
                }
                val rounds = entry.rounds.trim().toIntOrNull()
                    ?: strings.sumOf { it.rounds }.takeIf { it > 0 }
                val entryId = dao.insertSessionGun(
                    SessionGun(sessionId = sessionId, gunId = gunId, rounds = rounds, notes = entry.notes.trim())
                )
                if (strings.isNotEmpty()) dao.insertStrings(strings.map { it.copy(sessionGunId = entryId) })
            }
            if (draft.photos.isNotEmpty()) {
                dao.insertPhotos(draft.photos.map {
                    Photo(sessionId = sessionId, fileName = it.fileName, caption = it.caption.trim())
                })
            }
            sessionId
        }
        return id
    }

    suspend fun deleteSession(id: Long) {
        val existing = dao.getSession(id)
        val label = "Deleted session " + (existing?.let { dayLabel(it.session.date) } ?: "")
        undoable(label, offer = true) {
            sync.exclusive {
                db.withTransaction {
                    dao.deleteStringsForSession(id)
                    dao.deleteSessionGuns(id)
                    dao.deleteSessionPhotos(id)
                    dao.deleteSessionRow(id)
                }
                existing?.let { sync.deleteSession(it.session.syncId) }
            }
        }
    }

    // ---------------- Photos ----------------

    private val photoDir: File get() = File(context.filesDir, "photos").apply { mkdirs() }

    fun photoFile(name: String): File = File(photoDir, name)

    /** Creates an empty file for the camera to write into. */
    fun newCameraTarget(): Pair<String, Uri> {
        val name = "IMG_${UUID.randomUUID()}.jpg"
        val file = photoFile(name)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return name to uri
    }

    /** Puts a copy of a camera photo in the phone's gallery (Pictures/Range Log). Android 10+ only. */
    suspend fun copyToGallery(name: String) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                // Without a taken date, Samsung Gallery files these at the very bottom.
                put(MediaStore.Images.Media.DATE_TAKEN, photoFile(name).lastModified())
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Range Log")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching
            resolver.openOutputStream(target)?.use { out ->
                photoFile(name).inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(target, values, null, null)
        }
    }

    /** Copies a picked image into private storage and returns its file name. */
    suspend fun importPhoto(uri: Uri): String? = withContext(Dispatchers.IO) {
        val name = "IMG_${UUID.randomUUID()}.jpg"
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                photoFile(name).outputStream().use { input.copyTo(it) }
            } ?: return@withContext null
            name
        }.getOrNull()
    }

    fun discardPhoto(name: String) {
        photoFile(name).delete()
    }

    /**
     * Attaches picked images to sessions by file name: YYYYMMDD## (date + order), e.g.
     * 2024121001.JPG. Files already imported (same name on that session) are skipped.
     */
    suspend fun importPhotosByName(uris: List<Uri>): PhotoImportResult =
        undoable("Imported photos", changed = { it.added > 0 }) { importPhotosByNameNow(uris) }

    private suspend fun importPhotosByNameNow(uris: List<Uri>): PhotoImportResult = withContext(Dispatchers.IO) {
        val pattern = Regex("""^(\d{4})(\d{2})(\d{2})(\d{2,})\.jpe?g$""", RegexOption.IGNORE_CASE)
        val sessions = dao.allSessions().sortedBy { it.id }
        var added = 0
        var duplicates = 0
        val unmatched = mutableListOf<String>()

        data class Item(val uri: Uri, val original: String, val date: Long, val order: Int)

        val items = mutableListOf<Item>()
        for (uri in uris) {
            val original = displayName(uri) ?: "unknown"
            val m = pattern.matchEntire(original)
            val date = m?.let {
                runCatching {
                    LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()).toEpochDay()
                }.getOrNull()
            }
            if (m == null || date == null) unmatched += original
            else items += Item(uri, original, date, m.groupValues[4].toInt())
        }

        for ((date, group) in items.groupBy { it.date }) {
            // If two sessions share a date, the first one entered gets the photos.
            val session = sessions.firstOrNull { it.date == date }
            if (session == null) {
                unmatched += group.map { it.original }
                continue
            }
            val existing = dao.getSession(session.id)?.photos.orEmpty().map { it.fileName }.toSet()
            val newPhotos = mutableListOf<Photo>()
            for (item in group.sortedBy { it.order }) {
                val name = item.original.substringBeforeLast('.') + ".jpg"
                if (name in existing) { duplicates++; continue }
                val copied = runCatching {
                    context.contentResolver.openInputStream(item.uri)?.use { input ->
                        photoFile(name).outputStream().use { input.copyTo(it) }
                    } != null
                }.getOrDefault(false)
                if (copied) newPhotos += Photo(sessionId = session.id, fileName = name, caption = "")
                else unmatched += item.original
            }
            if (newPhotos.isNotEmpty()) {
                dao.insertPhotos(newPhotos)
                added += newPhotos.size
                sync.exclusive { sync.pushSession(session.id) }
            }
        }
        PhotoImportResult(added, duplicates, unmatched)
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    // ---------------- Backup ----------------

    suspend fun exportJson(): String = json.encodeToString(Backup.serializer(), currentBackup())

    /** Replaces everything in the database with the contents of a backup file (can be undone). */
    suspend fun importJson(text: String) {
        val backup = json.decodeFromString(Backup.serializer(), text)
        require(backup.format == "rangelog-backup") { "Not a Range Log backup file" }
        undoable("Restored a backup file") { replaceAll(backup) }
    }

    private suspend fun replaceAll(backup: Backup) {
        sync.afterRestore {
            db.withTransaction {
                dao.clearPhotos()
            dao.clearStrings()
            dao.clearSessionGuns()
            dao.clearCleanings()
            dao.clearSessions()
            dao.clearGuns()
            dao.insertGuns(backup.guns)
            dao.insertSessions(backup.sessions)
            dao.insertSessionGuns(backup.sessionGuns)
            dao.insertStrings(backup.strings)
            dao.insertCleanings(backup.cleanings)
                dao.insertPhotos(backup.photos)
            }
        }
    }

    /** On first launch, load the history that came with the app. */
    suspend fun seedIfFirstLaunch() {
        val prefs = context.getSharedPreferences("rangelog", Context.MODE_PRIVATE)
        if (prefs.getBoolean("seeded", false)) return
        if (dao.allGuns().isEmpty() && dao.allSessions().isEmpty()) {
            val text = withContext(Dispatchers.IO) {
                context.assets.open("seed.json").bufferedReader().use { it.readText() }
            }
            replaceAll(json.decodeFromString(Backup.serializer(), text))
        }
        prefs.edit().putBoolean("seeded", true).apply()
    }
}
