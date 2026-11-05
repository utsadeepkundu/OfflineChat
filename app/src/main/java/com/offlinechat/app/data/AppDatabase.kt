package com.offlinechat.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, MemberEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "offlinechat.db")
                // While the schema is still changing during development, rebuild instead of crashing.
                // Replace this with real migrations before shipping to people who keep their history.
                .fallbackToDestructiveMigration()
                .build()
    }
}
