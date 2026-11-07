package com.p2pchat.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 端末内の DB。テーブルを足すときは entities に加え、version を上げる。
 * 開発中はスキーマを書き出さない（exportSchema = false）。公開前にマイグレーションの方針を決める。
 */
@Database(entities = [ContactEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "p2p-chat.db")
                .fallbackToDestructiveMigration(true)
                .build()
    }
}
