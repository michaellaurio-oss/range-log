package com.laurio.rangelog.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Gun::class, Session::class, SessionGun::class, DrillString::class, Cleaning::class, Photo::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): RangeDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "rangelog.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** Adds a stable cross-device id to guns, sessions and cleanings (for cloud sync). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("guns", "sessions", "cleanings")) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                    db.execSQL("UPDATE $table SET syncId = lower(hex(randomblob(16)))")
                }
            }
        }
    }
}
