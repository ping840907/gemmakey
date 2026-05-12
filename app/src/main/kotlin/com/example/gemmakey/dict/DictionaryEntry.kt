package com.example.gemmakey.dict

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "dictionary",
    indices = [Index(value = ["term"], unique = true)]
)
data class DictionaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The special noun / proper name / technical term. */
    val term: String,
    /** Number of times this term appeared across transcription sessions. */
    val frequency: Int = 1,
    /** Unix epoch ms of the most recent occurrence. */
    val lastSeenMs: Long = System.currentTimeMillis()
)
