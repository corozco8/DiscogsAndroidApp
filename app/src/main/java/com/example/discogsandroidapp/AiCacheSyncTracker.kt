package com.example.discogsandroidapp

import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks AI inventory-cache invalidation with monotonically increasing
 * revisions. A full sync may acknowledge only the revision that existed when
 * that sync began, so a newer mutation can never be cleared accidentally.
 */
object AiCacheSyncTracker {

    private val mutationRevision = AtomicLong(0L)
    private val synchronizedRevision = AtomicLong(0L)

    /** Marks the cache stale and returns the new mutation revision. */
    fun markDirty(): Long = mutationRevision.incrementAndGet()

    /** Capture immediately before starting a full inventory synchronization. */
    fun captureRevision(): Long = mutationRevision.get()

    /**
     * Acknowledge only the revision represented by the completed sync. If a
     * mutation happened while the sync was running, needsFullSync() remains
     * true because mutationRevision will be newer than synchronizedRevision.
     */
    fun markFullSyncComplete(revisionAtStart: Long) {
        while (true) {
            val current = synchronizedRevision.get()
            if (revisionAtStart <= current) return
            if (synchronizedRevision.compareAndSet(current, revisionAtStart)) {
                return
            }
        }
    }

    fun needsFullSync(): Boolean =
        synchronizedRevision.get() < mutationRevision.get()
}
