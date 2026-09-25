package com.example.discogsandroidapp

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

enum class MarketplaceUiPriceStatus { LOADING, CACHED, FRESH, PARTIAL, NO_MATCH, FAILED, BLOCKED, RATE_LIMITED, NETWORK, UNREADABLE, TIMEOUT }

@Serializable
data class MarketplacePriceSnapshot(
    val releaseId: Long,
    val currency: String = "USD",
    val prices: ActiveMarketplaceConditionPrices,
    val updatedAtMillis: Long,
    val pagesChecked: Set<Int> = setOf(1),
    val complete: Boolean = true
)

internal fun MarketplacePriceSnapshot.isFresh(now: Long = System.currentTimeMillis()) =
    now - updatedAtMillis in 0 until 15 * 60_000L

/** Older observations remain available for display for a day, explicitly labelled as cached. */
internal fun MarketplacePriceSnapshot.isUsable(now: Long = System.currentTimeMillis()) =
    now - updatedAtMillis in 0 until 24 * 60 * 60_000L

internal object MarketplacePricingCache {
    private val entries = linkedMapOf<String, MarketplacePriceSnapshot>()
    private val ioMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var file: AtomicFile? = null
    private var loaded = false
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (loaded) return@withLock
            file = AtomicFile(File(context.applicationContext.filesDir, "marketplace-prices-v2.json"))
            val restored = runCatching {
                json.decodeFromString<List<MarketplacePriceSnapshot>>(file!!.openRead().bufferedReader().use { it.readText() })
            }.getOrDefault(emptyList())
            synchronized(entries) {
                restored.filter { it.isUsable() }.takeLast(128).forEach { snapshot ->
                    entries.putIfAbsent(key(snapshot.releaseId, snapshot.currency), snapshot)
                }
            }
            loaded = true
        }
    }
    private fun key(id: Long, currency: String) = "$id:${currency.uppercase(java.util.Locale.ROOT)}"
    fun get(id: Long, currency: String): MarketplacePriceSnapshot? = synchronized(entries) {
        entries[key(id, currency)]?.takeIf { it.isUsable() }
    }
    fun put(snapshot: MarketplacePriceSnapshot) {
        synchronized(entries) {
            entries.remove(key(snapshot.releaseId, snapshot.currency))
            entries[key(snapshot.releaseId, snapshot.currency)] = snapshot
            while (entries.size > 128) entries.remove(entries.keys.first())
        }
        scope.launch {
            ioMutex.withLock {
                val target = file ?: return@withLock
                val snapshots = synchronized(entries) { entries.values.filter { it.isUsable() } }
                var stream: java.io.FileOutputStream? = null
                try {
                    stream = target.startWrite()
                    stream.write(json.encodeToString(snapshots).toByteArray(Charsets.UTF_8))
                    target.finishWrite(stream)
                } catch (_: Exception) { target.failWrite(stream) }
            }
        }
    }
}

fun getCachedMarketplacePriceSnapshot(releaseId: Long, currency: String = "USD") = MarketplacePricingCache.get(releaseId, currency)
fun getCachedMarketplaceConditionPrices(releaseId: Long, currency: String = "USD") = getCachedMarketplacePriceSnapshot(releaseId, currency)?.prices
fun cacheMarketplaceConditionPrices(
    releaseId: Long, prices: ActiveMarketplaceConditionPrices, currency: String = "USD",
    pagesChecked: Set<Int> = setOf(1), complete: Boolean = true
): MarketplacePriceSnapshot = MarketplacePriceSnapshot(releaseId, currency, prices, System.currentTimeMillis(), pagesChecked, complete)
    .also { MarketplacePricingCache.put(it) }

internal fun pricingHttpStatus(code: Int) = when (code) {
    401, 403 -> MarketplaceUiPriceStatus.BLOCKED
    429 -> MarketplaceUiPriceStatus.RATE_LIMITED
    else -> MarketplaceUiPriceStatus.FAILED
}
