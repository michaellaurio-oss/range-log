package com.laurio.rangelog.data

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.withContext
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.room.withTransaction
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import java.util.UUID
import com.google.firebase.firestore.Source
import com.laurio.rangelog.BuildConfig
import com.laurio.rangelog.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** One document changed by an undoable step: what it looked like before and after (null = absent). */
data class UndoOp(val c: String, val id: String, val before: Map<String, Any?>?, val after: Map<String, Any?>?)

/** One undoable step, shared by every device through users/{uid}/undo/{id}. */
data class UndoEntry(val id: String, val label: String, val time: Long, val device: String, val ops: List<UndoOp>)

data class SyncStatus(
    val email: String? = null,
    val text: String = "Not signed in",
    /** True when both this phone and the cloud already hold data and the user must pick one. */
    val needsChoice: Boolean = false,
)

/**
 * Keeps the local database and Firestore in step.
 *
 * Cloud layout: users/{uid}/{guns|sessions|cleanings}/{syncId}. A session document holds its
 * gun entries, strings and photo names inside it, so it is always written as one unit.
 *
 * Local changes are written to Firestore right after they hit the database (Firestore queues
 * them while offline). Remote changes arrive as snapshot events and are applied to the database;
 * events caused by our own writes are skipped. Photo image files are not synced.
 */
class CloudSync(
    private val context: Context,
    private val db: AppDatabase,
    private val photoFile: (String) -> File,
    /** Pictures a change from the cloud no longer uses. Never deleted here: the repository removes them once no undo step needs them. */
    private val dropPhotos: (Set<String>) -> Unit,
) {
    private val dao = db.dao()
    // Lazy, so the share build (no Firebase at all) never touches them: with CLOUD_SYNC off,
    // nobody signs in, uid stays null and every push/undo below quietly does nothing.
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val fs by lazy { FirebaseFirestore.getInstance() }
    private val prefs = context.getSharedPreferences("rangelog", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _undo = MutableStateFlow<List<UndoEntry>>(emptyList())
    /** The last [UNDO_MAX] changes from any device, newest first. */
    val undoEntries: StateFlow<List<UndoEntry>> = _undo.asStateFlow()

    private var registrations: List<ListenerRegistration> = emptyList()
    private val health = mutableMapOf<String, Boolean>() // collection -> in step with the server
    private val deferredSessions = mutableMapOf<String, Map<String, Any?>>()
    private val deferredCleanings = mutableMapOf<String, Map<String, Any?>>()
    private var started = false

    private val uid: String? get() = if (ENABLED) auth.currentUser?.uid else null

    private fun ref(collection: String, id: String): DocumentReference? =
        uid?.let { fs.collection("users").document(it).collection(collection).document(id) }

    private fun collection(name: String) = fs.collection("users").document(uid!!).collection(name)

    // ---------------- Sign-in ----------------

    fun start() {
        if (started || !ENABLED) return
        started = true
        auth.addAuthStateListener { a ->
            val user = a.currentUser
            if (user == null) {
                detach()
                _status.value = SyncStatus()
            } else {
                scope.launch { attach(user.uid, user.email) }
            }
        }
    }

    suspend fun signIn(activity: Activity) {
        check(ENABLED) { "Cloud sync isn't part of this version of the app" }
        val option = GetSignInWithGoogleOption.Builder(context.getString(R.string.default_web_client_id)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        require(
            credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) { "Unexpected sign-in response" }
        val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
        auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
    }

    fun signOut() {
        if (!ENABLED) return
        detach()
        auth.signOut()
    }

    private fun detach() {
        registrations.forEach { it.remove() }
        registrations = emptyList()
        health.clear()
        _undo.value = emptyList()
    }

    // ---------------- Starting up after sign-in ----------------

    private suspend fun attach(uid: String, email: String?) {
        detach()
        _status.value = SyncStatus(email, "Connecting…")
        try {
            if (prefs.getString("syncedUid", null) != uid) {
                if (!firstSync(uid, email)) return
            } else if (prefs.getBoolean("localAuthoritative", false)) {
                mutex.withLock { pushAll(replaceRemote = true) }
                prefs.edit().putBoolean("localAuthoritative", false).apply()
            }
            listen(email)
            syncPhotos()
        } catch (e: Exception) {
            _status.value = SyncStatus(email, "Can't reach the cloud yet. It will retry next time the app opens.")
        }
    }

    /** First time this phone meets this account. Returns true once it is safe to start listening. */
    private suspend fun firstSync(uid: String, email: String?): Boolean {
        val remoteEmpty = COLLECTIONS.all { collection(it).limit(1).get(Source.SERVER).await().isEmpty }
        val localEmpty = dao.allGuns().isEmpty() && dao.allSessions().isEmpty()
        when {
            remoteEmpty -> mutex.withLock { pushAll(replaceRemote = false) }
            localEmpty -> {}
            else -> {
                _status.value = SyncStatus(
                    email, "The cloud already has data, and so does this phone. Choose which to keep.",
                    needsChoice = true,
                )
                return false
            }
        }
        markSynced(uid)
        return true
    }

    private fun markSynced(uid: String) {
        prefs.edit().putString("syncedUid", uid).putBoolean("localAuthoritative", false).apply()
    }

    /** Resolves the "both have data" case by keeping what's on this phone. */
    fun useThisPhone() = scope.launch {
        val u = uid ?: return@launch
        val email = auth.currentUser?.email
        mutex.withLock { pushAll(replaceRemote = true) }
        markSynced(u)
        listen(email)
        syncPhotos()
    }

    /** Resolves the "both have data" case by replacing this phone's data with the cloud's. */
    fun useCloud() = scope.launch {
        val u = uid ?: return@launch
        val email = auth.currentUser?.email
        mutex.withLock {
            db.withTransaction {
                dao.clearPhotos(); dao.clearStrings(); dao.clearSessionGuns()
                dao.clearCleanings(); dao.clearSessions(); dao.clearGuns()
            }
        }
        markSynced(u)
        listen(email)
        syncPhotos()
    }

    // ---------------- Cloud -> phone ----------------

    private fun listen(email: String?) {
        detach()
        registrations = registrations + collection("undo")
            .orderBy("time", Query.Direction.DESCENDING).limit(UNDO_MAX.toLong())
            .addSnapshotListener { snap, _ ->
                if (snap != null) _undo.value = snap.documents.mapNotNull { parseUndo(it.id, it.data) }
            }
        COLLECTIONS.forEach { name ->
            health[name] = false
            registrations = registrations + collection(name).addSnapshotListener(
                com.google.firebase.firestore.MetadataChanges.INCLUDE
            ) { snap, error ->
                if (error != null) {
                    _status.value = SyncStatus(email, "Sync problem: ${error.message}")
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                scope.launch { handle(name, snap, email) }
            }
        }
    }

    private suspend fun handle(name: String, snap: QuerySnapshot, email: String?) = mutex.withLock {
        for (change in snap.documentChanges) {
            // Changes we just wrote ourselves are already in the database.
            if (change.document.metadata.hasPendingWrites() && change.type != DocumentChange.Type.REMOVED) continue
            val id = change.document.id
            val data = change.document.data
            try {
                when (name) {
                    "guns" -> if (change.type == DocumentChange.Type.REMOVED) dao.deleteGunBySync(id) else {
                        applyGun(id, data)
                        retryDeferred()
                    }
                    "sessions" -> if (change.type == DocumentChange.Type.REMOVED) removeSession(id) else applySession(id, data)
                    "cleanings" -> if (change.type == DocumentChange.Type.REMOVED) dao.deleteCleaningBySync(id) else applyCleaning(id, data)
                }
            } catch (e: Exception) {
                _status.value = SyncStatus(email, "Couldn't apply a change from the cloud: ${e.message}")
            }
        }
        health[name] = !snap.metadata.isFromCache && !snap.metadata.hasPendingWrites()
        if (health.size == COLLECTIONS.size && health.values.all { it }) _status.value = SyncStatus(email, "Synced")
        else if (_status.value.text.startsWith("Synced") || _status.value.text.startsWith("Connecting"))
            _status.value = SyncStatus(email, "Waiting for connection – changes will sync when you're online")
    }

    private suspend fun retryDeferred() {
        deferredSessions.toMap().forEach { (id, d) -> applySession(id, d) }
        deferredCleanings.toMap().forEach { (id, d) -> applyCleaning(id, d) }
    }

    private suspend fun applyGun(syncId: String, d: Map<String, Any?>) {
        val existing = dao.gunBySync(syncId)
        val gun = Gun(
            id = existing?.id ?: 0,
            name = d.str("name"),
            description = d.str("description"),
            notes = d.str("notes"),
            cleanInterval = d.num("cleanInterval")?.toInt(),
            cleanMonths = d.num("cleanMonths")?.toInt(),
            photo = d.str("photo"),
            active = d["active"] as? Boolean ?: true,
            notMine = d["notMine"] as? Boolean ?: false,
            deleted = d["deleted"] as? Boolean ?: false,
            syncId = syncId,
        )
        if (existing == null) dao.insertGun(gun) else if (existing != gun) dao.updateGun(gun)
        if (gun.photo.isNotBlank()) scope.launch { downloadPhoto(gun.photo) }
        if (existing != null && existing.photo.isNotBlank() && existing.photo != gun.photo) {
            dropPhotos(setOf(existing.photo))
        }
    }

    private suspend fun applyCleaning(syncId: String, d: Map<String, Any?>) {
        val gunId = dao.gunBySync(d.str("gun"))?.id
        if (gunId == null) { deferredCleanings[syncId] = d; return }
        deferredCleanings.remove(syncId)
        val existing = dao.cleaningBySync(syncId)
        val c = Cleaning(
            id = existing?.id ?: 0, gunId = gunId, date = d.num("date") ?: 0,
            notes = d.str("notes"), syncId = syncId,
        )
        if (existing == null) dao.insertCleaning(c) else if (existing != c) dao.updateCleaning(c)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun applySession(syncId: String, d: Map<String, Any?>) {
        val entries = (d["guns"] as? List<Map<String, Any?>>).orEmpty()
        val gunIds = entries.map { dao.gunBySync(it.str("gun"))?.id }
        if (gunIds.any { it == null }) { deferredSessions[syncId] = d; return }
        deferredSessions.remove(syncId)

        val existing = dao.sessionBySync(syncId)
        val current = existing?.let { dao.getSession(it.id) }
        if (current != null && sessionDoc(current) == d) return // nothing changed

        val photos = (d["photos"] as? List<Map<String, Any?>>).orEmpty()
        db.withTransaction {
            val session = Session(
                id = existing?.id ?: 0, date = d.num("date") ?: 0,
                location = d.str("location"), notes = d.str("notes"), syncId = syncId,
            )
            val sid = if (existing == null) dao.insertSession(session) else { dao.updateSession(session); existing.id }
            dao.deleteStringsForSession(sid)
            dao.deleteSessionGuns(sid)
            dao.deleteSessionPhotos(sid)
            entries.forEachIndexed { i, e ->
                val entryId = dao.insertSessionGun(
                    SessionGun(sessionId = sid, gunId = gunIds[i]!!, rounds = e.num("rounds")?.toInt(), notes = e.str("notes"))
                )
                val strings = (e["strings"] as? List<Map<String, Any?>>).orEmpty().map {
                    DrillString(
                        sessionGunId = entryId, rounds = it.num("rounds")?.toInt() ?: 0,
                        yards = (it["yards"] as? Number)?.toDouble() ?: 0.0, notes = it.str("notes"),
                    )
                }
                if (strings.isNotEmpty()) dao.insertStrings(strings)
            }
            if (photos.isNotEmpty()) {
                dao.insertPhotos(photos.map { Photo(sessionId = sid, fileName = it.str("fileName"), caption = it.str("caption")) })
            }
        }
        photos.forEach { scope.launch { downloadPhoto(it.str("fileName")) } }
        val kept = photos.map { it.str("fileName") }.toSet()
        dropPhotos(current?.photos.orEmpty().map { it.fileName }.filter { it !in kept }.toSet())
    }

    private suspend fun removeSession(syncId: String) {
        val s = dao.sessionBySync(syncId) ?: return
        val photos = dao.getSession(s.id)?.photos.orEmpty()
        db.withTransaction {
            dao.deleteStringsForSession(s.id)
            dao.deleteSessionGuns(s.id)
            dao.deleteSessionPhotos(s.id)
            dao.deleteSessionRow(s.id)
        }
        dropPhotos(photos.map { it.fileName }.toSet())
    }

    // ---------------- Phone -> cloud ----------------

    /**
     * Runs a local database change and its cloud write together, so a remote event can't
     * land in between. Call the push functions below from inside [block].
     */
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    private fun dirtyIfSignedOut() {
        if (uid == null) prefs.edit().putBoolean("localAuthoritative", true).apply()
    }

    fun pushGun(g: Gun) {
        val r = ref("guns", g.syncId) ?: return dirtyIfSignedOut()
        r.set(gunDoc(g)).report()
        if (g.photo.isNotBlank()) scope.launch { uploadPhoto(g.photo) }
    }

    fun deleteGun(syncId: String) {
        (ref("guns", syncId) ?: return dirtyIfSignedOut()).delete().report()
    }

    suspend fun pushSession(id: Long) {
        val s = dao.getSession(id) ?: return
        (ref("sessions", s.session.syncId) ?: return dirtyIfSignedOut()).set(sessionDoc(s)).report()
        s.photos.forEach { p -> scope.launch { uploadPhoto(p.fileName) } }
    }

    fun deleteSession(syncId: String) {
        (ref("sessions", syncId) ?: return dirtyIfSignedOut()).delete().report()
    }

    suspend fun pushCleaning(c: Cleaning) {
        val gun = dao.getGun(c.gunId) ?: return
        (ref("cleanings", c.syncId) ?: return dirtyIfSignedOut()).set(cleaningDoc(c, gun.syncId)).report()
    }

    fun deleteCleaning(syncId: String) {
        (ref("cleanings", syncId) ?: return dirtyIfSignedOut()).delete().report()
    }

    // ---------------- Undo history (shared with the web page) ----------------

    /** Every synced document as it would be written to the cloud right now, keyed "collection/syncId". */
    suspend fun docState(): Map<String, Map<String, Any?>> {
        val guns = dao.allGuns()
        val gunSync = guns.associate { it.id to it.syncId }
        return buildMap {
            guns.forEach { put("guns/${it.syncId}", gunDoc(it)) }
            dao.allSessionsFull().forEach { put("sessions/${it.session.syncId}", sessionDoc(it)) }
            dao.allCleanings().forEach { c ->
                gunSync[c.gunId]?.let { put("cleanings/${c.syncId}", cleaningDoc(c, it)) }
            }
        }
    }

    /**
     * Records the difference between two [docState]s as one undo step. Returns the new step, or null if
     * nothing was recorded (no change, signed out, or too many documents for one cloud document).
     */
    fun recordUndo(
        label: String,
        before: Map<String, Map<String, Any?>>,
        after: Map<String, Map<String, Any?>>,
    ): UndoEntry? {
        if (uid == null) return null
        val ops = (before.keys + after.keys).filter { !sameDoc(before[it], after[it]) }.map { key ->
            val (c, id) = key.split("/", limit = 2)
            UndoOp(c, id, before[key], after[key])
        }
        // A cloud document holds 1 MB; a change touching hundreds of documents isn't recorded.
        if (ops.isEmpty() || ops.size > 250) return null
        val entry = UndoEntry(UUID.randomUUID().toString(), label, System.currentTimeMillis(), "phone", ops)
        collection("undo").document(entry.id).set(
            mapOf(
                "label" to label, "time" to entry.time, "device" to entry.device,
                "ops" to ops.map { mapOf("c" to it.c, "id" to it.id, "before" to it.before, "after" to it.after) },
            )
        ).report()
        // Show it at once (the cloud's own update arrives a moment later), then keep only the newest UNDO_MAX.
        val all = (listOf(entry) + _undo.value.filter { it.id != entry.id }).sortedByDescending { it.time }
        _undo.value = all.take(UNDO_MAX)
        all.drop(UNDO_MAX).forEach { collection("undo").document(it.id).delete().report() }
        return entry
    }

    /** How many documents in [e] were changed again after it happened (so undoing would overwrite that). */
    suspend fun undoConflicts(e: UndoEntry): Int {
        val now = docState()
        return e.ops.count { !sameDoc(now["${it.c}/${it.id}"], it.after) }
    }

    /** Puts every document in [e] back as it was, here and in the cloud, and removes the step. */
    suspend fun applyUndo(e: UndoEntry) = mutex.withLock {
        val order = listOf("cleanings", "sessions", "guns")
        // Remove what the step created (children first), then restore what it changed or deleted (parents first).
        for (c in order) e.ops.filter { it.c == c && it.before == null }.forEach {
            val keptGun = if (c == "guns") dao.gunBySync(it.id)?.takeIf { g -> dao.sessionCountForGun(g.id) > 0 } else null
            if (keptGun != null) {
                // Sessions never get deleted: a gun they still use is hidden as "deleted" instead of removed.
                val hidden = keptGun.copy(deleted = true, active = false)
                dao.updateGun(hidden)
                ref("guns", it.id)?.set(gunDoc(hidden))?.report()
                return@forEach
            }
            when (c) {
                "guns" -> dao.deleteGunBySync(it.id)
                "sessions" -> removeSession(it.id)
                else -> dao.deleteCleaningBySync(it.id)
            }
            ref(c, it.id)?.delete()?.report()
        }
        for (c in order.reversed()) e.ops.filter { it.c == c && it.before != null }.forEach {
            when (c) {
                "guns" -> applyGun(it.id, it.before!!)
                "sessions" -> applySession(it.id, it.before!!)
                else -> applyCleaning(it.id, it.before!!)
            }
            ref(c, it.id)?.set(it.before!!)?.report()
        }
        retryDeferred()
        ref("undo", e.id)?.delete()?.report()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseUndo(id: String, d: Map<String, Any?>?): UndoEntry? {
        d ?: return null
        val ops = (d["ops"] as? List<Map<String, Any?>>).orEmpty().map {
            UndoOp(it.str("c"), it.str("id"), it["before"] as? Map<String, Any?>, it["after"] as? Map<String, Any?>)
        }
        return UndoEntry(id, d.str("label"), d.num("time") ?: 0, d.str("device"), ops)
    }

    /** After a restore from a backup file: the phone's data becomes the truth. */
    suspend fun afterRestore(restore: suspend () -> Unit) = mutex.withLock {
        restore()
        if (uid == null) dirtyIfSignedOut() else pushAll(replaceRemote = true)
    }

    /** Writes everything local to the cloud; with [replaceRemote], removes cloud documents that aren't local. */
    private suspend fun pushAll(replaceRemote: Boolean) {
        val gunList = dao.allGuns()
        val gunSync = gunList.associate { it.id to it.syncId }
        val sessionList = dao.allSessionsFull()
        val cleaningList = dao.allCleanings()

        val writes = buildList<Pair<DocumentReference, Map<String, Any?>>> {
            gunList.forEach { add(collection("guns").document(it.syncId) to gunDoc(it)) }
            sessionList.forEach { add(collection("sessions").document(it.session.syncId) to sessionDoc(it)) }
            cleaningList.forEach { c ->
                gunSync[c.gunId]?.let { add(collection("cleanings").document(c.syncId) to cleaningDoc(c, it)) }
            }
        }
        val keep = writes.map { it.first.path }.toSet()
        val deletes = if (!replaceRemote) emptyList() else COLLECTIONS.flatMap { name ->
            collection(name).get().await().documents.map { it.reference }.filter { it.path !in keep }
        }
        (writes.map { it.first to it.second } + deletes.map { it to null }).chunked(400).forEach { chunk ->
            val batch = fs.batch()
            chunk.forEach { (r, data) -> if (data == null) batch.delete(r) else batch.set(r, data) }
            batch.commit().report()
        }
    }

    // ---------------- Photos ----------------
    // Each photo is shrunk to a web-sized JPEG and stored as a base64 string in
    // users/{uid}/photos/{fileName} (Firestore documents max out at 1 MB).

    private fun uploadedPhotos(): MutableSet<String> =
        prefs.getStringSet("uploadedPhotos", emptySet())!!.toMutableSet()

    private suspend fun uploadPhoto(name: String) {
        val r = ref("photos", name) ?: return
        if (name in uploadedPhotos()) return
        val file = photoFile(name)
        if (!file.exists()) return
        val data = withContext(Dispatchers.IO) { shrink(file) } ?: return
        r.set(mapOf("data" to data)).addOnSuccessListener {
            synchronized(prefs) {
                prefs.edit().putStringSet("uploadedPhotos", uploadedPhotos().apply { add(name) }).apply()
            }
        }.report()
    }

    private suspend fun downloadPhoto(name: String) {
        val file = photoFile(name)
        if (file.exists()) return
        val r = ref("photos", name) ?: return
        try {
            val data = r.get().await().getString("data") ?: return
            withContext(Dispatchers.IO) { file.writeBytes(Base64.decode(data, Base64.DEFAULT)) }
            synchronized(prefs) {
                prefs.edit().putStringSet("uploadedPhotos", uploadedPhotos().apply { add(name) }).apply()
            }
        } catch (_: Exception) {
            // Not uploaded yet or offline; picked up on a later start.
        }
    }

    /** Uploads local photos the cloud doesn't have yet and fetches ones that exist only in the cloud. */
    fun syncPhotos() {
        scope.launch {
            val names = dao.allPhotos().map { it.fileName } + dao.allGuns().map { it.photo }.filter { it.isNotBlank() }
            names.distinct().forEach { name ->
                if (photoFile(name).exists()) uploadPhoto(name) else downloadPhoto(name)
            }
        }
    }

    private fun shrink(file: File): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2560) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val degrees = when (
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val scale = minOf(1f, 1280f / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply { postRotate(degrees); postScale(scale, scale) }
        val bmp = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        for (quality in listOf(80, 65, 50, 35)) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= 600_000) return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
        return null
    }

    private fun com.google.android.gms.tasks.Task<Void>.report() {
        addOnFailureListener { e ->
            _status.value = _status.value.copy(text = "Upload problem: ${e.message}")
        }
    }

    companion object {
        /** False in the share build: no sign-in, sync or undo; the log lives only on the phone. */
        val ENABLED = BuildConfig.CLOUD_SYNC
        const val UNDO_MAX = 10
        private val COLLECTIONS = listOf("guns", "sessions", "cleanings")

        /** Same document contents? Numbers compare by value, and missing, null and "" are alike. */
        fun sameDoc(a: Any?, b: Any?): Boolean = when {
            a is Number && b is Number -> a.toDouble() == b.toDouble()
            a is Map<*, *> && b is Map<*, *> -> (a.keys + b.keys).all { sameDoc(a[it], b[it]) }
            a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { sameDoc(a[it], b[it]) }
            else -> (if (a == "") null else a) == (if (b == "") null else b)
        }

        /** Picture file names mentioned by a gun or session document. */
        @Suppress("UNCHECKED_CAST")
        fun photosIn(doc: Map<String, Any?>?): List<String> {
            doc ?: return emptyList()
            val session = (doc["photos"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { it["fileName"] as? String }
            return (session + listOfNotNull(doc["photo"] as? String)).filter { it.isNotBlank() }
        }

        private fun Map<String, Any?>.str(key: String) = this[key] as? String ?: ""
        private fun Map<String, Any?>.num(key: String) = (this[key] as? Number)?.toLong()

        fun gunDoc(g: Gun): Map<String, Any?> = mapOf(
            "name" to g.name, "description" to g.description, "notes" to g.notes,
            "cleanInterval" to g.cleanInterval?.toLong(), "cleanMonths" to g.cleanMonths?.toLong(),
            "photo" to g.photo, "active" to g.active, "notMine" to g.notMine,
        ) + if (g.deleted) mapOf("deleted" to true) else emptyMap() // only written when set, so older documents still match

        fun cleaningDoc(c: Cleaning, gunSyncId: String): Map<String, Any?> = mapOf(
            "gun" to gunSyncId, "date" to c.date, "notes" to c.notes,
        )

        fun sessionDoc(s: SessionFull): Map<String, Any?> = mapOf(
            "date" to s.session.date,
            "location" to s.session.location,
            "notes" to s.session.notes,
            "guns" to s.guns.filter { it.gun != null }.map { e ->
                mapOf(
                    "gun" to e.gun!!.syncId,
                    "rounds" to e.entry.rounds?.toLong(),
                    "notes" to e.entry.notes,
                    "strings" to e.strings.map {
                        mapOf("rounds" to it.rounds.toLong(), "yards" to it.yards, "notes" to it.notes)
                    },
                )
            },
            "photos" to s.photos.map { mapOf("fileName" to it.fileName, "caption" to it.caption) },
        )
    }
}
