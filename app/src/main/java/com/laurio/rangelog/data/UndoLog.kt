package com.laurio.rangelog.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** The whole log as it was just before a change, so the change can be undone. */
@Serializable
data class UndoEntry(val label: String, val time: Long, val backup: Backup)

data class UndoItem(val label: String, val time: Long)

/**
 * The last [MAX] changes, each kept as a full copy of the database taken just before the change
 * (the log is small). Stored as files so undo survives closing the app. Picture files are not
 * copied: [Repository] simply doesn't delete a picture while any entry here still refers to it.
 */
class UndoLog(context: Context, private val json: Json) {
    private class Stored(val file: File, val item: UndoItem, val photos: Set<String>)

    private val dir = File(context.filesDir, "undo").apply { mkdirs() }
    private val stored = mutableListOf<Stored>() // oldest first

    private val _items = MutableStateFlow<List<UndoItem>>(emptyList())
    /** Newest first. */
    val items: StateFlow<List<UndoItem>> = _items.asStateFlow()

    init {
        dir.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.forEach { f ->
            runCatching { json.decodeFromString(UndoEntry.serializer(), f.readText()) }
                .onSuccess { stored += Stored(f, UndoItem(it.label, it.time), photosOf(it.backup)) }
                .onFailure { f.delete() }
        }
        publish()
    }

    @Synchronized
    fun push(label: String, backup: Backup) {
        val time = maxOf(System.currentTimeMillis(), (stored.lastOrNull()?.item?.time ?: 0) + 1)
        val file = File(dir, "%015d.json".format(time))
        file.writeText(json.encodeToString(UndoEntry.serializer(), UndoEntry(label, time, backup)))
        stored += Stored(file, UndoItem(label, time), photosOf(backup))
        while (stored.size > MAX) stored.removeAt(0).file.delete()
        publish()
    }

    @Synchronized
    fun pop(): UndoEntry? {
        val last = stored.lastOrNull() ?: return null
        val entry = runCatching { json.decodeFromString(UndoEntry.serializer(), last.file.readText()) }.getOrNull()
        stored.removeAt(stored.lastIndex)
        last.file.delete()
        publish()
        return entry
    }

    /** Picture files that an undo could still bring back. */
    @Synchronized
    fun referencedPhotos(): Set<String> = stored.flatMapTo(mutableSetOf()) { it.photos }

    private fun photosOf(b: Backup): Set<String> =
        (b.photos.map { it.fileName } + b.guns.map { it.photo }).filter { it.isNotBlank() }.toSet()

    private fun publish() {
        _items.value = stored.map { it.item }.reversed()
    }

    companion object {
        const val MAX = 10
    }
}
