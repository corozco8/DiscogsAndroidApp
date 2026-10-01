package com.example.discogsandroidapp.releases

import com.example.discogsandroidapp.data.DiscogsSearchResponse
import com.example.discogsandroidapp.network.RetrofitClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Suggestions and submitted searches share results; switching to results avoids a second call. */
internal class ReleaseSearchCache(private val now: () -> Long = System::currentTimeMillis) {
    private class SearchGate(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val lock = Any()
    private val pending = mutableMapOf<Pair<String, String>, SearchGate>()
    private val cached = linkedMapOf<Pair<String, String>, Pair<Long, DiscogsSearchResponse>>()

    fun peek(query: String, token: String): DiscogsSearchResponse? = synchronized(lock) {
        cached[token to normalizedSearchQuery(query)]
            ?.takeIf { now() - it.first in 0 until 3 * 60_000L }?.second
    }

    suspend fun search(query: String, token: String, load: suspend () -> DiscogsSearchResponse): DiscogsSearchResponse {
        val key = token to normalizedSearchQuery(query)
        val gate = synchronized(lock) {
            peek(query, token)?.let { return it }
            pending.getOrPut(key) { SearchGate() }.also { it.users++ }
        }
        try {
            // Only equal queries wait for each other. A slow old query must not hold up a new one.
            return gate.mutex.withLock {
                peek(query, token)?.let { return@withLock it }
                load().also { response ->
                    synchronized(lock) {
                        cached.remove(key)
                        cached[key] = now() to response
                        while (cached.size > 32) cached.remove(cached.keys.first())
                    }
                }
            }
        } finally {
            synchronized(lock) { if (--gate.users == 0) pending.remove(key) }
        }
    }
}

internal object ReleaseSearchRepository {
    private val cache = ReleaseSearchCache()
    fun peek(query: String, token: String): DiscogsSearchResponse? = cache.peek(query, token)
    suspend fun search(query: String, token: String): DiscogsSearchResponse = cache.search(query, token) {
        RetrofitClient.apiService.searchDatabase(query.trim(), authHeader = "Discogs token=$token")
    }
}
