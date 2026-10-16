package com.laurio.rangelog.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import kotlinx.serialization.Serializable
import java.util.UUID

/** A firearm you own or have shot. */
@Serializable
@Entity(tableName = "guns")
data class Gun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Short name used everywhere in the app, e.g. "SP01". */
    val name: String,
    /** Full make/model, e.g. "CZ 75 SP-01". */
    val description: String = "",
    /** Upgrades, modifications and other notes. */
    val notes: String = "",
    /** Optional reminder: clean after this many rounds. */
    val cleanInterval: Int? = null,
    /** Optional reminder: clean after this many months since the last cleaning. */
    val cleanMonths: Int? = null,
    /** Name of the gun's picture file in the photos folder, or blank. */
    @ColumnInfo(defaultValue = "''") val photo: String = "",
    /** Retired guns are hidden from the picker when logging a new session. */
    val active: Boolean = true,
    /** Rented or borrowed: no cleaning log or reminders. */
    @ColumnInfo(defaultValue = "0") val notMine: Boolean = false,
    /** Stable id shared across devices (the cloud document id). */
    @ColumnInfo(defaultValue = "''") val syncId: String = UUID.randomUUID().toString(),
    /**
     * Deleted by the user but kept (hidden everywhere) because sessions still refer to it.
     * Those sessions show the name followed by "(deleted)".
     */
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

/** The name as shown next to a logged session: flags guns that are archived or deleted. */
fun Gun.displayName(): String = name + when {
    deleted -> " (deleted)"
    !active -> " (archived)"
    else -> ""
}

/** One trip to the range. Dates are stored as LocalDate.toEpochDay(). */
@Serializable
@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long,
    val location: String = "",
    val notes: String = "",
    @ColumnInfo(defaultValue = "''") val syncId: String = UUID.randomUUID().toString(),
)

/** A gun shot during a session and how many rounds went through it. */
@Serializable
@Entity(
    tableName = "session_guns",
    indices = [Index("sessionId"), Index("gunId")],
)
data class SessionGun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val gunId: Long,
    /** Null when the round count wasn't recorded. */
    val rounds: Int? = null,
    val notes: String = "",
)

/** A string of fire: N rounds at a distance, e.g. "30 @ 15 yds". */
@Serializable
@Entity(tableName = "strings", indices = [Index("sessionGunId")])
data class DrillString(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionGunId: Long,
    val rounds: Int,
    val yards: Double,
    val notes: String = "",
)

@Serializable
@Entity(tableName = "cleanings", indices = [Index("gunId")])
data class Cleaning(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gunId: Long,
    val date: Long,
    val notes: String = "",
    @ColumnInfo(defaultValue = "''") val syncId: String = UUID.randomUUID().toString(),
)

/** A target photo. The image file lives in the app's private "photos" folder. */
@Serializable
@Entity(tableName = "photos", indices = [Index("sessionId")])
data class Photo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val fileName: String,
    val caption: String = "",
)

data class SessionGunFull(
    @Embedded val entry: SessionGun,
    @Relation(parentColumn = "gunId", entityColumn = "id")
    val gun: Gun?,
    @Relation(parentColumn = "id", entityColumn = "sessionGunId")
    val strings: List<DrillString>,
)

data class SessionFull(
    @Embedded val session: Session,
    @Relation(entity = SessionGun::class, parentColumn = "id", entityColumn = "sessionId")
    val guns: List<SessionGunFull>,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val photos: List<Photo>,
) {
    val totalRounds: Int get() = guns.sumOf { it.entry.rounds ?: 0 }
}

data class RoundRow(
    val sessionId: Long,
    val gunId: Long,
    val rounds: Int?,
    val date: Long,
)

/** Everything in the database, used for backup/restore and the preloaded history. */
@Serializable
data class Backup(
    val format: String = "rangelog-backup",
    val version: Int = 1,
    val guns: List<Gun> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val sessionGuns: List<SessionGun> = emptyList(),
    val strings: List<DrillString> = emptyList(),
    val cleanings: List<Cleaning> = emptyList(),
    val photos: List<Photo> = emptyList(),
)
