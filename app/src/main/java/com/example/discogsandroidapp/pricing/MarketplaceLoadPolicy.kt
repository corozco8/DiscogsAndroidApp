package com.example.discogsandroidapp.pricing

import android.content.Context

/** Space automatic price checks; an explicit user request can always load immediately. */
internal class MarketplaceLoadPolicy(private val now: () -> Long = System::currentTimeMillis) {
    private var nextLoadAt = 0L
    var automaticChecksSuspended: Boolean = false
        private set

    fun recordBlock() { automaticChecksSuspended = true }
    fun recordManualSuccess() { automaticChecksSuspended = false }

    fun waitForLoad(): Long = (nextLoadAt - now()).coerceAtLeast(0)

    fun reserveLoad(explicit: Boolean = false): Boolean {
        if (!explicit && (automaticChecksSuspended || waitForLoad() > 0)) return false
        nextLoadAt = now() + 10_000L
        return true
    }
}

/** Shared automatic spacing, with no saved failure timers or retry lockouts. */
internal object MarketplaceTraffic {
    val policy = MarketplaceLoadPolicy()
    private var initialized = false
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        // Discard cooldowns saved by older app versions, including hour-long waits.
        context.applicationContext.getSharedPreferences("marketplace_traffic_v1", Context.MODE_PRIVATE)
            .edit().remove("blocked_until").remove("failures").apply()
    }
}
