package com.example.discogsandroidapp.pricing

import android.content.Context
import android.content.SharedPreferences
import com.example.discogsandroidapp.network.discogsRetryDelay

/** The marketplace website has its own limits, independent of the authenticated API. */
internal class MarketplaceLoadPolicy(private val now: () -> Long = System::currentTimeMillis) {
    var blockedUntil = 0L
        private set
    var failures = 0
        private set
    private var nextLoadAt = 0L

    fun restore(until: Long, previousFailures: Int) {
        blockedUntil = until.coerceAtLeast(0)
        failures = previousFailures.coerceIn(0, 5)
    }

    fun blockedFor(): Long = (blockedUntil - now()).coerceAtLeast(0)
    fun waitForLoad(): Long = (maxOf(blockedUntil, nextLoadAt) - now()).coerceAtLeast(0)

    fun reserveLoad(): Boolean {
        if (waitForLoad() > 0) return false
        nextLoadAt = now() + 10_000L
        return true
    }

    fun limited(retryAfter: String?): Long {
        failures = (failures + 1).coerceAtMost(5)
        // Honor the server's pause; repeated limits without a header back off.
        val delay = retryAfter?.takeIf { it.isNotBlank() }?.let { discogsRetryDelay(it, now()) }
            ?: (60_000L * (1L shl (failures - 1))).coerceAtMost(15 * 60_000L)
        blockedUntil = maxOf(blockedUntil, now() + delay)
        return blockedUntil
    }

    fun succeeded() { blockedUntil = 0; failures = 0 }
}

/** Shared and persisted so switching releases or restarting cannot create a retry loop. */
internal object MarketplaceTraffic {
    val policy = MarketplaceLoadPolicy()
    private var preferences: SharedPreferences? = null
    fun initialize(context: Context) {
        if (preferences != null) return
        preferences = context.applicationContext.getSharedPreferences("marketplace_traffic_v1", Context.MODE_PRIVATE)
        policy.restore(preferences!!.getLong("blocked_until", 0), preferences!!.getInt("failures", 0))
    }
    fun limited(retryAfter: String?): Long = policy.limited(retryAfter).also { save() }
    fun succeeded() { policy.succeeded(); save() }
    private fun save() {
        preferences?.edit()?.putLong("blocked_until", policy.blockedUntil)
            ?.putInt("failures", policy.failures)?.apply()
    }
}
