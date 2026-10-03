package com.laurio.rangelog.data

import android.app.Activity
import android.content.Context
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
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
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
    private val deletePhotoFile: (String) -> Unit,
) {
    private val dao = db.dao()
    private val auth = FirebaseAuth.getInstance()
    private val fs = FirebaseFirestore.getInstance()
    private val prefs = context.getSharedPreferences("rangelog", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private var registrations: List<ListenerRegistration> = emptyList()
    private val health = mutableMapOf<String, Boolean>() // collection -> in step with the server
    private val deferredSessions = mutableMapOf<String, Map<String, Any?>>()
    private val deferredCleanings = mutableMapOf<String, Map<String, Any?>>()
    private var started = false

    private val uid: String? get() = auth.currentUser?.uid

    private fun ref(collection: String, id: String): DocumentReference? =
        uid?.let { fs.collection("users").document(it).collection(collection).document(id) }

    private fun collection(name: String) = fs.collection("users").document(uid!!).collection(name)

    // ---------------- Sign-in ----------------

    fun start() {
        if (started) return
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
        detach()
        auth.signOut()
    }

    private fun detach() {
        registrations.forEach { it.remove() }
        registrations = emptyList()
        health.clear()
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
    }

    // ---------------- Cloud -> phone ----------------

    private fun listen(email: String?) {
        detach()
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
            active = d["active"] as? Boolean ?: true,
            syncId = syncId,
        )
        if (existing == null) dao.insertGun(gun) else if (existing != gun) dao.updateGun(gun)
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
        val kept = photos.map { it.str("fileName") }.toSet()
        current?.photos.orEmpty().filter { it.fileName !in kept }.forEach { deletePhotoFile(it.fileName) }
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
        photos.forEach { deletePhotoFile(it.fileName) }
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
    }

    fun deleteGun(syncId: String) {
        (ref("guns", syncId) ?: return dirtyIfSignedOut()).delete().report()
    }

    suspend fun pushSession(id: Long) {
        val s = dao.getSession(id) ?: return
        (ref("sessions", s.session.syncId) ?: return dirtyIfSignedOut()).set(sessionDoc(s)).report()
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

    private fun com.google.android.gms.tasks.Task<Void>.report() {
        addOnFailureListener { e ->
            _status.value = _status.value.copy(text = "Upload problem: ${e.message}")
        }
    }

    companion object {
        private val COLLECTIONS = listOf("guns", "sessions", "cleanings")

        private fun Map<String, Any?>.str(key: String) = this[key] as? String ?: ""
        private fun Map<String, Any?>.num(key: String) = (this[key] as? Number)?.toLong()

        fun gunDoc(g: Gun): Map<String, Any?> = mapOf(
            "name" to g.name, "description" to g.description, "notes" to g.notes,
            "cleanInterval" to g.cleanInterval?.toLong(), "active" to g.active,
        )

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
