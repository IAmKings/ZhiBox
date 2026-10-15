package com.zhibox.memory.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SourceEntity::class,
        DocumentEntity::class,
        ChunkEntity::class,
        ChunkFtsEntity::class,
        EmbeddingMetaEntity::class,
        ChunkEmbeddingEntity::class,
        EventEntity::class,
        EntityEntity::class,
        EventEntityRel::class,
        ChunkEntityRel::class,
        IngestJobEntity::class,
        SchemaMigrationEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun documentDao(): DocumentDao
    abstract fun chunkDao(): ChunkDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun ingestJobDao(): IngestJobDao
    abstract fun schemaMigrationDao(): SchemaMigrationDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "zhibox.sqlite3",
                ).build().also { instance = it }
            }
    }
}
