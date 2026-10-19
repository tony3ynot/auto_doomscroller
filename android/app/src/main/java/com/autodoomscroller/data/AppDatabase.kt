package com.autodoomscroller.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Schema changes must ship with a migration: collected data is not recoverable.
@Database(
    entities = [SessionEntity::class, ReelEntity::class],
    version = 6,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reelDao(): ReelDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "auto_doomscroller.db").build()
    }
}
