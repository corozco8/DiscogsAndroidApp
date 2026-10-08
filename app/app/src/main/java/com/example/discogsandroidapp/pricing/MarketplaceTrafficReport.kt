package com.example.discogsandroidapp.pricing

import android.content.Context
import android.content.SharedPreferences
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local diagnostics only. Resource observations can include WebView cache hits. */
internal class MarketplaceTrafficLog(private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        private const val HOUR_MS = 60L * 60L * 1_000L
        private const val MINUTE_MS = 60_000L
        private const val PREFS_NAME = "marketplace_page_load_counter_v1"
        private const val KEY_LOAD_TIMESTAMPS = "load_timestamps"
        private const val FUTURE_CLOCK_TOLERANCE_MS = 5L * 60L * 1_000L
    }

    private val events = ArrayDeque<String>()
    private val recentLoads = ArrayDeque<Long>()
    private val recentResources = ArrayDeque<Long>()
    private val hourlyLoads = ArrayDeque<Long>()
    private val mutableHourlyLoadCount = MutableStateFlow(0)
    val hourlyLoadCount = mutableHourlyLoadCount.asStateFlow()

    private var preferences: SharedPreferences? = null
    private var loads = 0
    private var pageHits = 0
    private var priceHits = 0
    private var resources = 0
    private var errors = 0
    private var lastError: String? = null
    private val hosts = linkedMapOf<String, Int>()

    /**
     * Restores top-level marketplace page-load timestamps so the rolling-hour counter remains
     * accurate across app restarts. This only reads local app storage and makes no network call.
     */
    @Synchronized
    fun initialize(context: Context) {
        if (preferences != null) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        preferences = prefs

        val timestamp = now()
        val restored = prefs.getString(KEY_LOAD_TIMESTAMPS, null)
            .orEmpty()
            .split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { it > timestamp - HOUR_MS && it <= timestamp + FUTURE_CLOCK_TOLERANCE_MS }
            .sorted()

        hourlyLoads.clear()
        restored.forEach(hourlyLoads::addLast)
        pruneHourly(timestamp)
        publishHourlyCount()
        persistHourlyLoads()
    }

    /** Count a real top-level Marketplace Listings load immediately before WebView.loadUrl(). */
    @Synchronized
    fun pageLoad(id: Long) {
        val timestamp = now()
        loads++
        recordTime(recentLoads, timestamp)
        pruneHourly(timestamp)
        hourlyLoads.addLast(timestamp)
        publishHourlyCount()
        persistHourlyLoads()
        event("Load release $id, page 1, limit 50")
    }

    @Synchronized fun pageReuse(id: Long) { pageHits++; event("Reuse page for release $id") }
    @Synchronized fun priceReuse(id: Long) { priceHits++; event("Reuse prices for release $id") }

    @Synchronized
    fun resource(host: String) {
        resources++
        recordTime(recentResources, now())
        if (host in hosts || hosts.size < 30) hosts[host] = (hosts[host] ?: 0) + 1
    }

    @Synchronized
    fun error(id: Long, details: String) {
        errors++
        lastError = details
        event("Error for release $id: ${details.lineSequence().firstOrNull().orEmpty()}")
    }

    /** Remove page loads that have aged out of the rolling 60-minute window. */
    @Synchronized
    fun tickHourlyLoads() {
        val previous = hourlyLoads.size
        pruneHourly(now())
        if (hourlyLoads.size != previous) {
            publishHourlyCount()
            persistHourlyLoads()
        }
    }

    private fun recordTime(queue: ArrayDeque<Long>, timestamp: Long) {
        pruneMinute(queue, timestamp)
        queue.addLast(timestamp)
        while (queue.size > 10_000) queue.removeFirst()
    }

    private fun pruneMinute(queue: ArrayDeque<Long>, timestamp: Long) {
        while (queue.isNotEmpty() && timestamp - queue.peekFirst() >= MINUTE_MS) queue.removeFirst()
    }

    private fun pruneHourly(timestamp: Long) {
        while (hourlyLoads.isNotEmpty() && timestamp - hourlyLoads.peekFirst() >= HOUR_MS) {
            hourlyLoads.removeFirst()
        }
    }

    private fun publishHourlyCount() {
        mutableHourlyLoadCount.value = hourlyLoads.size
    }

    private fun persistHourlyLoads() {
        val prefs = preferences ?: return
        prefs.edit().putString(KEY_LOAD_TIMESTAMPS, hourlyLoads.joinToString(",")).apply()
    }

    private fun event(detail: String) {
        events.addLast("${java.time.Instant.ofEpochMilli(now())} $detail")
        while (events.size > 40) events.removeFirst()
    }

    /** Marketplace-only report used by the top-right rolling-hour counter. */
    @Synchronized
    fun marketplaceReport(automaticChecksSuspended: Boolean = false): String {
        val timestamp = now()
        pruneMinute(recentLoads, timestamp)
        pruneMinute(recentResources, timestamp)
        pruneHourly(timestamp)
        publishHourlyCount()

        return buildString {
            appendLine(
                "Marketplace page loads: $loads this app session, " +
                        "${recentLoads.size} in last 60 seconds, ${hourlyLoads.size} in last 60 minutes"
            )
            appendLine("Cached page reuses: $pageHits; cached price reuses: $priceHits")
            appendLine("Browser resource requests observed: $resources total, ${recentResources.size} in last 60 seconds")
            appendLine("Resource observations may include browser cache hits; they are not a server quota counter.")
            appendLine("Marketplace errors: $errors")
            appendLine("Automatic live checks: " + if (automaticChecksSuspended) "paused until a successful manual check" else "enabled")
            appendLine("The hourly page-load count is stored locally and survives app restarts.")
            appendLine("Successful pages/prices stay fresh for 15 minutes.")
            hosts.entries.sortedByDescending { it.value }.forEach { appendLine("${it.key}: ${it.value}") }
            appendLine("\nRecent page activity:")
            events.forEach { appendLine(it) }
            lastError?.let { appendLine("\nLatest error:\n$it") }
        }.trimEnd()
    }

    /** Legacy combined report retained for the existing API counter code, which is currently unused. */
    @Synchronized
    fun report(apiCount: Int, automaticChecksSuspended: Boolean = false): String = buildString {
        appendLine("API requests in last 60 seconds: $apiCount")
        append(marketplaceReport(automaticChecksSuspended))
    }.trimEnd()
}

internal object MarketplaceTrafficReport { val log = MarketplaceTrafficLog() }
