package com.example.discogsandroidapp.releases

import com.example.discogsandroidapp.data.DiscogsSearchResponse
import com.example.discogsandroidapp.network.RetrofitClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Suggestions and submitted searches share results; switching to results avoids a second call. */
internal class ReleaseSearchCache(private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private val cached = linkedMapOf<Pair<String, String>, Pair<Long, DiscogsSearchResponse>>()
    suspend fun search(query: String, token: String, load: suspend () -> DiscogsSearchResponse): DiscogsSearchResponse = mutex.withLock {
        val key = token to normalizedSearchQuery(query)
        cached[key]?.takeIf { now() - it.first in 0 until 3 * 60_000L }?.let { return@withLock it.second }
        load().also {
            cached.remove(key)
            cached[key] = now() to it
            while (cached.size > 32) cached.remove(cached.keys.first())
        }
    }
}

internal object ReleaseSearchRepository {
    private val cache = ReleaseSearchCache()
    suspend fun search(query: String, token: String): DiscogsSearchResponse = cache.search(query, token) {
        RetrofitClient.apiService.searchDatabase(query.trim(), authHeader = "Discogs token=$token")
    }
}
