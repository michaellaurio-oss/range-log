package com.laurio.rangelog.ui

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.laurio.rangelog.RangeLogApp
import com.laurio.rangelog.data.GunSummary
import com.laurio.rangelog.data.SyncStatus
import com.laurio.rangelog.data.SessionFull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
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
        // On open, say which guns are at or past their cleaning limit.
        viewModelScope.launch {
            val due = repo.gunSummaries.first { list -> list.isNotEmpty() }.filter { it.cleaningDue }
            if (due.isEmpty()) return@launch
            _messages.subscriptionCount.first { it > 0 }
            val names = due.joinToString(", ") { it.gun.name }
            _messages.emit("Time to clean: $names")
        }
    }

    fun markCleanedToday(gunId: Long) = viewModelScope.launch {
        repo.logCleaning(gunId, LocalDate.now(), "")
        _messages.tryEmit("Cleaning logged")
    }

    fun exportTo(uri: Uri) = viewModelScope.launch {
        val result = runCatching {
            val text = repo.exportJson()
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(text.toByteArray())
                } ?: error("Couldn't open the file")
            }
        }
        _messages.tryEmit(if (result.isSuccess) "Backup saved" else "Backup failed: ${result.exceptionOrNull()?.message}")
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
            val text = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader().readText()
                } ?: error("Couldn't open the file")
            }
            repo.importJson(text)
        }
        _messages.tryEmit(if (result.isSuccess) "Backup restored" else "Restore failed: ${result.exceptionOrNull()?.message}")
    }
}
