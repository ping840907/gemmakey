package com.example.gemmakey.dict

import android.content.Context
import android.util.Log

/**
 * High-level API for the custom-noun dictionary.
 *
 * The dictionary persists across app restarts (Room) but is intentionally kept
 * small — top-50 terms are surfaced as hints to the AI engine so the model can
 * apply correct spelling for rare proper nouns detected in previous sessions.
 */
class CustomDictionary(context: Context) {

    private val TAG = "CustomDictionary"
    private val dao = DictionaryDatabase.getInstance(context).dictionaryDao()

    /** Records a list of nouns detected this session (upsert with frequency). */
    suspend fun recordNouns(terms: List<String>) {
        for (term in terms.map { it.trim() }.filter { it.length > 1 }) {
            if (dao.exists(term) > 0) {
                dao.incrementFrequency(term)
            } else {
                dao.insertIgnore(DictionaryEntry(term = term))
            }
            Log.d(TAG, "Recorded noun: $term")
        }
    }

    /** Returns the top-N most-frequent terms as hints for the prompt. */
    suspend fun getHints(limit: Int = 50): List<String> =
        dao.getTopTermStrings(limit)

    /** Returns all dictionary entries for inspection (e.g., settings UI). */
    suspend fun getAll(): List<DictionaryEntry> = dao.getAll()

    /** Removes a term (user-initiated deletion from settings). */
    suspend fun delete(term: String) = dao.delete(term)
}
