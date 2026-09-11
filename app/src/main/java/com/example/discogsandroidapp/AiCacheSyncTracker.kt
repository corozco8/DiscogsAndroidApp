package com.example.discogsandroidapp

/**
 * Tracks whether a successful Discogs mutation failed to reach the optional
 * local AI inventory cache during this app process.
 *
 * When dirty, AI Search performs a full inventory sync before answering rather
 * than knowingly querying an outdated cache.
 */
object AiCacheSyncTracker {

    @Volatile
    private var dirty: Boolean = false

    fun markDirty() {
        dirty = true
    }

    fun markFullSyncComplete() {
        dirty = false
    }

    fun needsFullSync(): Boolean = dirty
}
