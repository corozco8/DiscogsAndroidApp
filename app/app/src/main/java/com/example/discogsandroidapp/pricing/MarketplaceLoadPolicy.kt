package com.example.discogsandroidapp.pricing

import android.content.Context

/**
 * Spaces marketplace page loads and protects the shared Discogs session after a block.
 * Manual requests obey the same spacing/cooldown as automatic requests; after a block,
 * automatic checks remain paused until a later manual request succeeds.
 */
internal class MarketplaceLoadPolicy(private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        const val REQUEST_SPACING_MS = 10_000L
        const val DEFAULT_BLOCK_COOLDOWN_MS = 120_000L
    }

    private var nextLoadAt = 0L
    private var blockedUntil = 0L

    var automaticChecksSuspended: Boolean = false
        private set

    fun recordBlock(cooldownMillis: Long = DEFAULT_BLOCK_COOLDOWN_MS) {
        automaticChecksSuspended = true
        val cooldown = cooldownMillis.coerceAtLeast(DEFAULT_BLOCK_COOLDOWN_MS)
        blockedUntil = maxOf(blockedUntil, now() + cooldown)
    }

    fun recordManualSuccess() {
        automaticChecksSuspended = false
        blockedUntil = 0L
    }

    fun waitForLoad(): Long = (maxOf(nextLoadAt, blockedUntil) - now()).coerceAtLeast(0L)

    fun reserveLoad(explicit: Boolean = false): Boolean {
        // An explicit/manual request must not bypass normal spacing or a server block.
        if (waitForLoad() > 0L) return false
        if (!explicit && automaticChecksSuspended) return false

        nextLoadAt = now() + REQUEST_SPACING_MS
        return true
    }
}

/** Shared process-wide marketplace request policy. */
internal object MarketplaceTraffic {
    val policy = MarketplaceLoadPolicy()
    private var initialized = false

    fun initialize(context: Context) {
        MarketplaceTrafficReport.log.initialize(context.applicationContext)
        if (initialized) return
        initialized = true
        // Do not erase legacy cooldown/failure preferences during startup.
    }
}
