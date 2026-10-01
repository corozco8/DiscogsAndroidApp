package com.example.discogsandroidapp.pricing

import java.util.ArrayDeque

/** Local diagnostics only. Resource observations can include WebView cache hits. */
internal class MarketplaceTrafficLog(private val now: () -> Long = System::currentTimeMillis) {
    private val events = ArrayDeque<String>()
    private val recentLoads = ArrayDeque<Long>()
    private val recentResources = ArrayDeque<Long>()
    private var loads = 0
    private var pageHits = 0
    private var priceHits = 0
    private var resources = 0
    private var errors = 0
    private var lastError: String? = null
    private val hosts = linkedMapOf<String, Int>()

    @Synchronized fun pageLoad(id: Long) {
        loads++; recordTime(recentLoads)
        event("Load release $id, page 1, limit 50")
    }
    @Synchronized fun pageReuse(id: Long) { pageHits++; event("Reuse page for release $id") }
    @Synchronized fun priceReuse(id: Long) { priceHits++; event("Reuse prices for release $id") }
    @Synchronized fun resource(host: String) {
        resources++; recordTime(recentResources)
        if (host in hosts || hosts.size < 30) hosts[host] = (hosts[host] ?: 0) + 1
    }
    @Synchronized fun error(id: Long, details: String) {
        errors++; lastError = details
        event("Error for release $id: ${details.lineSequence().firstOrNull().orEmpty()}")
    }
    private fun recordTime(queue: ArrayDeque<Long>) {
        prune(queue); queue.addLast(now())
        while (queue.size > 10_000) queue.removeFirst()
    }
    private fun prune(queue: ArrayDeque<Long>) {
        while (queue.isNotEmpty() && now() - queue.peekFirst() >= 60_000) queue.removeFirst()
    }
    private fun event(detail: String) {
        events.addLast("${java.time.Instant.ofEpochMilli(now())} $detail")
        while (events.size > 40) events.removeFirst()
    }
    @Synchronized fun report(apiCount: Int): String {
        prune(recentLoads); prune(recentResources)
        return buildString {
            appendLine("API requests in last 60 seconds: $apiCount")
            appendLine("Marketplace page loads: $loads total, ${recentLoads.size} in last 60 seconds")
            appendLine("Cached page reuses: $pageHits; cached price reuses: $priceHits")
            appendLine("Browser resource requests observed: $resources total, ${recentResources.size} in last 60 seconds")
            appendLine("Resource observations may include browser cache hits; they are not a server quota counter.")
            appendLine("Marketplace errors: $errors")
            appendLine("Counts cover this app session only. Successful pages/prices stay fresh for 15 minutes.")
            hosts.entries.sortedByDescending { it.value }.forEach { appendLine("${it.key}: ${it.value}") }
            appendLine("\nRecent page activity:")
            events.forEach { appendLine(it) }
            lastError?.let { appendLine("\nLatest error:\n$it") }
        }.trimEnd()
    }
}

internal object MarketplaceTrafficReport { val log = MarketplaceTrafficLog() }
