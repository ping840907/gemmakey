package com.example.gemmakey.dict

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DictionaryDao {

    @Query("SELECT * FROM dictionary ORDER BY frequency DESC LIMIT :limit")
    suspend fun getTopTerms(limit: Int = 50): List<DictionaryEntry>

    @Query("SELECT term FROM dictionary ORDER BY frequency DESC LIMIT :limit")
    suspend fun getTopTermStrings(limit: Int = 50): List<String>

    /** Insert new term; if already present, do nothing (upsert handled separately). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entry: DictionaryEntry): Long

    /** Increment frequency and update timestamp for an existing term. */
    @Query("""
        UPDATE dictionary
        SET frequency = frequency + 1,
            lastSeenMs = :nowMs
        WHERE term = :term
    """)
    suspend fun incrementFrequency(term: String, nowMs: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM dictionary WHERE term = :term")
    suspend fun exists(term: String): Int

    @Query("DELETE FROM dictionary WHERE term = :term")
    suspend fun delete(term: String)

    @Query("SELECT * FROM dictionary ORDER BY frequency DESC")
    suspend fun getAll(): List<DictionaryEntry>
}
