package com.laurio.rangelog.data

import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

/** Editable, in-memory versions of a session used by the editor screen. */
private val keys = AtomicLong(1)
fun nextKey(): Long = keys.getAndIncrement()

data class StringDraft(
    val key: Long = nextKey(),
    val rounds: String = "",
    val yards: String = "",
    val notes: String = "",
)

data class GunEntryDraft(
    val key: Long = nextKey(),
    val gunId: Long? = null,
    val rounds: String = "",
    val notes: String = "",
    val strings: List<StringDraft> = emptyList(),
) {
    /** Sum of the distance strings, used when the rounds box is left empty. */
    val stringTotal: Int get() = strings.sumOf { it.rounds.toIntOrNull() ?: 0 }

    val hasContent: Boolean
        get() = rounds.isNotBlank() || notes.isNotBlank() ||
            strings.any { it.rounds.isNotBlank() || it.yards.isNotBlank() || it.notes.isNotBlank() }
}

data class PhotoDraft(
    val fileName: String,
    val caption: String = "",
)

data class SessionDraft(
    val id: Long = 0,
    val date: LocalDate = LocalDate.now(),
    val location: String = "",
    val notes: String = "",
    val guns: List<GunEntryDraft> = listOf(GunEntryDraft()),
    val photos: List<PhotoDraft> = emptyList(),
)

fun SessionFull.toDraft(): SessionDraft = SessionDraft(
    id = session.id,
    date = LocalDate.ofEpochDay(session.date),
    location = session.location,
    notes = session.notes,
    guns = guns.map { g ->
        GunEntryDraft(
            gunId = g.entry.gunId,
            rounds = g.entry.rounds?.toString() ?: "",
            notes = g.entry.notes,
            strings = g.strings.sortedBy { it.id }.map { s ->
                StringDraft(rounds = s.rounds.toString(), yards = formatYards(s.yards), notes = s.notes)
            },
        )
    }.ifEmpty { listOf(GunEntryDraft()) },
    photos = photos.sortedBy { it.id }.map { PhotoDraft(it.fileName, it.caption) },
)

fun formatYards(yards: Double): String =
    if (yards == Math.floor(yards)) yards.toLong().toString() else yards.toString()
