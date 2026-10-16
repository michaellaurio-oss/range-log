package com.laurio.rangelog.ui

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.laurio.rangelog.RangeLogApp
import com.laurio.rangelog.data.GunSummary
import com.laurio.rangelog.data.Repository
import com.laurio.rangelog.data.SyncStatus
import com.laurio.rangelog.data.SessionFull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = (app as RangeLogApp).repository

    val sessions: StateFlow<List<SessionFull>> =
        repo.sessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val gunSummaries: StateFlow<List<GunSummary>> =
        repo.gunSummaries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val syncStatus: StateFlow<SyncStatus> = repo.sync.status

    fun signIn(activity: Activity) = viewModelScope.launch {
        runCatching { repo.sync.signIn(activity) }.onFailure {
            _messages.tryEmit("Sign-in failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    fun signOut() = repo.sync.signOut()
    fun useThisPhone() = repo.sync.useThisPhone()
    fun useCloud() = repo.sync.useCloud()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    init {
        viewModelScope.launch {
            repo.seedIfFirstLaunch()
            repo.sync.start()
        }
        viewModelScope.launch { repo.notices.collect { _messages.tryEmit(it) } }
        // On open, say which guns are at or past their cleaning limit.
        viewModelScope.launch {
            val due = repo.gunSummaries.first { list -> list.isNotEmpty() }.filter { it.cleaningDue }
            if (due.isNotEmpty()) _cleanReminder.value = due
        }
    }

    // Guns to nag about on open; stays up until dismissed.
    private val _cleanReminder = MutableStateFlow<List<GunSummary>?>(null)
    val cleanReminder: StateFlow<List<GunSummary>?> = _cleanReminder
    fun dismissCleanReminder() { _cleanReminder.value = null }

    val undoItems = repo.undoEntries

    /** Set when the newest step's items were edited again since; the UI asks before overwriting. */
    private val _undoConflict = MutableStateFlow<Repository.UndoOutcome.Conflict?>(null)
    val undoConflict: StateFlow<Repository.UndoOutcome.Conflict?> = _undoConflict

    /** Undoes the newest step, or the step with [entryId] when an Undo offer names one. */
    fun undo(force: Boolean = false, entryId: String? = null) = viewModelScope.launch {
        _undoConflict.value = null
        val outcome = runCatching { repo.undoLast(force, entryId) }.getOrElse {
            _messages.tryEmit("Undo failed: ${it.message}")
            return@launch
        }
        when (outcome) {
            Repository.UndoOutcome.NothingToUndo ->
                _messages.tryEmit(
                    when {
                        repo.sync.status.value.email == null -> "Sign in to use undo"
                        entryId != null -> "That change was already undone"
                        else -> "Nothing to undo"
                    }
                )
            is Repository.UndoOutcome.Conflict -> _undoConflict.value = outcome
            is Repository.UndoOutcome.Done -> _messages.tryEmit("Undone: ${outcome.label}")
        }
    }

    fun dismissUndoConflict() { _undoConflict.value = null }

    fun markCleanedToday(gunId: Long) = viewModelScope.launch {
        repo.logCleaning(gunId, LocalDate.now(), "")
        _messages.tryEmit("Cleaning logged")
    }

    fun exportTo(uri: Uri) = viewModelScope.launch {
        val result = runCatching {
            // Build the zip first; the chosen file is only opened (and emptied) once that has worked.
            val (zip, pictures) = repo.exportZip()
            try {
                withContext(Dispatchers.IO) {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?: error("Couldn't open the file")
                    out.use { o -> zip.inputStream().use { it.copyTo(o) } }
                }
            } finally {
                zip.delete()
            }
            pictures
        }
        _messages.tryEmit(
            result.fold(
                onSuccess = { n -> "Backup saved with $n picture${if (n == 1) "" else "s"}" },
                onFailure = { "Backup failed: ${it.message}" },
            )
        )
    }

    fun importPhotos(uris: List<Uri>) = viewModelScope.launch {
        val result = runCatching { repo.importPhotosByName(uris) }
        _messages.tryEmit(
            result.fold(
                onSuccess = { r ->
                    buildString {
                        append("Added ${r.added} photo${if (r.added == 1) "" else "s"}")
                        if (r.duplicates > 0) append(", ${r.duplicates} already there")
                        if (r.unmatched.isNotEmpty()) append(". No session for: ${r.unmatched.joinToString(", ")}")
                    }
                },
                onFailure = { "Photo import failed: ${it.message}" },
            )
        )
    }

    fun importFrom(uri: Uri) = viewModelScope.launch {
        val result = runCatching {
            val input = getApplication<Application>().contentResolver.openInputStream(uri)
                ?: error("Couldn't open the file")
            input.use { repo.importBackup(it) }
        }
        _messages.tryEmit(
            result.fold(
                onSuccess = { n -> "Backup restored" + if (n > 0) " with $n picture${if (n == 1) "" else "s"}" else "" },
                onFailure = { "Restore failed: ${it.message}" },
            )
        )
    }
}
