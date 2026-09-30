package com.example.discogsandroidapp.releases

import android.content.Context
import com.example.discogsandroidapp.data.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object ReleaseSearchHistory {
    val entries = MutableStateFlow<List<ReleaseSearchHistoryEntry>>(emptyList())
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var initialized = false

    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock { read(context) }
    }
    private fun read(context: Context) {
        if (initialized) return
        val saved = context.applicationContext.getSharedPreferences("release_search_history_v1", Context.MODE_PRIVATE)
            .getString("entries", null)
        entries.value = runCatching { saved?.let { json.decodeFromString<List<ReleaseSearchHistoryEntry>>(it).take(20) } }
            .getOrNull().orEmpty()
        initialized = true
    }
    suspend fun remember(context: Context, query: String = "", release: SearchResult? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            read(context)
            entries.value = updatedSearchHistory(entries.value, ReleaseSearchHistoryEntry(query, release))
            context.applicationContext.getSharedPreferences("release_search_history_v1", Context.MODE_PRIVATE)
                .edit().putString("entries", json.encodeToString(entries.value)).apply()
        }
    }
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            initialized = true
            entries.value = emptyList()
            context.applicationContext.getSharedPreferences("release_search_history_v1", Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
