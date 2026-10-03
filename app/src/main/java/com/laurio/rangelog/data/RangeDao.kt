package com.laurio.rangelog.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RangeDao {
    // ---- Guns ----
    @Query("SELECT * FROM guns ORDER BY active DESC, name COLLATE NOCASE")
    fun observeGuns(): Flow<List<Gun>>

    @Query("SELECT * FROM guns WHERE id = :id")
    suspend fun getGun(id: Long): Gun?

    @Insert
    suspend fun insertGun(gun: Gun): Long

    @Update
    suspend fun updateGun(gun: Gun)

    @Query("DELETE FROM guns WHERE id = :id")
    suspend fun deleteGun(id: Long)

    @Query("SELECT COUNT(*) FROM session_guns WHERE gunId = :gunId")
    suspend fun sessionCountForGun(gunId: Long): Int

    // ---- Sessions ----
    @Transaction
    @Query("SELECT * FROM sessions ORDER BY date DESC, id DESC")
    fun observeSessions(): Flow<List<SessionFull>>

    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSession(id: Long): SessionFull?

    @Insert
    suspend fun insertSession(session: Session): Long

    @Update
    suspend fun updateSession(session: Session)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSessionRow(id: Long)

    @Query("DELETE FROM strings WHERE sessionGunId IN (SELECT id FROM session_guns WHERE sessionId = :sessionId)")
    suspend fun deleteStringsForSession(sessionId: Long)

    @Query("DELETE FROM session_guns WHERE sessionId = :sessionId")
    suspend fun deleteSessionGuns(sessionId: Long)

    @Query("DELETE FROM photos WHERE sessionId = :sessionId")
    suspend fun deleteSessionPhotos(sessionId: Long)

    @Insert
    suspend fun insertSessionGun(entry: SessionGun): Long

    @Insert
    suspend fun insertStrings(strings: List<DrillString>)

    @Insert
    suspend fun insertPhotos(photos: List<Photo>)

    @Query(
        "SELECT sg.sessionId AS sessionId, sg.gunId AS gunId, sg.rounds AS rounds, s.date AS date " +
            "FROM session_guns sg INNER JOIN sessions s ON s.id = sg.sessionId"
    )
    fun observeRoundRows(): Flow<List<RoundRow>>

    // ---- Cleanings ----
    @Query("SELECT * FROM cleanings ORDER BY date DESC, id DESC")
    fun observeCleanings(): Flow<List<Cleaning>>

    @Insert
    suspend fun insertCleaning(cleaning: Cleaning): Long

    @Query("SELECT * FROM guns WHERE syncId = :syncId LIMIT 1")
    suspend fun gunBySync(syncId: String): Gun?

    @Query("SELECT * FROM sessions WHERE syncId = :syncId LIMIT 1")
    suspend fun sessionBySync(syncId: String): Session?

    @Query("SELECT * FROM cleanings WHERE syncId = :syncId LIMIT 1")
    suspend fun cleaningBySync(syncId: String): Cleaning?

    @Query("DELETE FROM cleanings WHERE syncId = :syncId")
    suspend fun deleteCleaningBySync(syncId: String)

    @Query("DELETE FROM guns WHERE syncId = :syncId")
    suspend fun deleteGunBySync(syncId: String)

    @Update
    suspend fun updateCleaning(cleaning: Cleaning)

    @Delete
    suspend fun deleteCleaning(cleaning: Cleaning)

    @Query("DELETE FROM cleanings WHERE gunId = :gunId")
    suspend fun deleteCleaningsForGun(gunId: Long)

    @Transaction
    @Query("SELECT * FROM sessions")
    suspend fun allSessionsFull(): List<SessionFull>

    // ---- Backup / restore ----
    @Query("SELECT * FROM guns") suspend fun allGuns(): List<Gun>
    @Query("SELECT * FROM sessions") suspend fun allSessions(): List<Session>
    @Query("SELECT * FROM session_guns") suspend fun allSessionGuns(): List<SessionGun>
    @Query("SELECT * FROM strings") suspend fun allStrings(): List<DrillString>
    @Query("SELECT * FROM cleanings") suspend fun allCleanings(): List<Cleaning>
    @Query("SELECT * FROM photos") suspend fun allPhotos(): List<Photo>

    @Insert suspend fun insertGuns(items: List<Gun>)
    @Insert suspend fun insertSessions(items: List<Session>)
    @Insert suspend fun insertSessionGuns(items: List<SessionGun>)
    @Insert suspend fun insertCleanings(items: List<Cleaning>)

    @Query("DELETE FROM photos") suspend fun clearPhotos()
    @Query("DELETE FROM strings") suspend fun clearStrings()
    @Query("DELETE FROM session_guns") suspend fun clearSessionGuns()
    @Query("DELETE FROM cleanings") suspend fun clearCleanings()
    @Query("DELETE FROM sessions") suspend fun clearSessions()
    @Query("DELETE FROM guns") suspend fun clearGuns()
}
