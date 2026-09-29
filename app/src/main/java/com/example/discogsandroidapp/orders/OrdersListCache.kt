package com.example.discogsandroidapp.orders

import com.example.discogsandroidapp.data.DiscogsOrder
import com.example.discogsandroidapp.releases.ReleaseUiState

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Locale

@Serializable
internal data class OrdersCacheKey(val account: String, val status: String, val sortOrder: String)

internal fun ordersCacheKey(token: String, status: String, sortOrder: String) = OrdersCacheKey(
    MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) },
    if (status.equals("All Orders", true)) "all" else status.trim().lowercase(Locale.ROOT),
    sortOrder.lowercase(Locale.ROOT)
)

internal fun mergeOrdersPage(existing: List<DiscogsOrder>, page: List<DiscogsOrder>, status: String): List<DiscogsOrder> {
    val result = existing.toMutableList()
    val positions = result.mapIndexedNotNull { index, order -> order.id?.let { it to index } }.toMap().toMutableMap()
    page.filter { matchesOrderStatus(it.status, status) }.forEach { order ->
        val position = order.id?.let(positions::get)
        if (position != null) result[position] = order else {
            order.id?.let { positions[it] = result.size }
            result.add(order)
        }
    }
    return result
}

@Serializable
internal data class SavedOrdersList(
    val key: OrdersCacheKey,
    val orders: List<DiscogsOrder>,
    val page: Int,
    val pages: Int,
    val total: Int
) {
    fun screen(refreshing: Boolean = false, error: String? = null) = ReleaseUiState.OrdersSuccess(
        orders, total, hasMore = page < pages, isRefreshing = refreshing, refreshError = error
    )

    fun withUpdatedOrder(order: DiscogsOrder): SavedOrdersList {
        val id = order.id ?: return this
        val included = orders.any { it.id == id }
        val matches = matchesOrderStatus(order.status, key.status)
        if (!included && !matches) return this
        val allOrders = key.status == "all"
        val changedMembership = !allOrders && included != matches
        val updated = orders.filterNot { it.id == id }.let { if (matches) it + order else it }
        val sorted = if (key.sortOrder == "asc") updated.sortedBy { it.created.orEmpty() }
            else updated.sortedByDescending { it.created.orEmpty() }
        return copy(
            orders = sorted,
            total = if (allOrders) total else (total + (if (matches) 1 else 0) - (if (included) 1 else 0)).coerceAtLeast(0),
            // Membership changes shift page boundaries; overlap before advancing.
            page = if (changedMembership && page < pages) (page - 1).coerceAtLeast(0) else page
        )
    }

    fun forDisk(): SavedOrdersList = if (orders.size <= 1_000) this else
        // A truncated cache must restart pagination, not skip uncached rows.
        copy(orders = orders.take(1_000), page = 0)
}

/** Bounded snapshots are isolated by account, filter and sort order. */
internal class OrdersMemoryCache {
    private val entries = linkedMapOf<OrdersCacheKey, SavedOrdersList>()
    @Synchronized fun get(key: OrdersCacheKey) = entries[key]
    @Synchronized fun put(snapshot: SavedOrdersList) {
        entries.remove(snapshot.key)
        entries[snapshot.key] = snapshot
        while (entries.size > 16) entries.remove(entries.keys.first())
    }
    @Synchronized fun restore(snapshots: List<SavedOrdersList>) {
        val newer = entries.values.toList()
        entries.clear()
        snapshots.takeLast(16).forEach(::put)
        newer.forEach(::put)
    }
    @Synchronized fun updateOrder(account: String, order: DiscogsOrder) {
        entries.entries.forEach { (key, value) ->
            if (key.account == account) entries[key] = value.withUpdatedOrder(order)
        }
    }
    @Synchronized fun savedEntries() = entries.values.toList()
}

/** Disk I/O and JSON work stay off the main thread; screen revisits use memory. */
internal class OrdersListCache(context: Context) {
    val memory = OrdersMemoryCache()
    private val preferences = context.applicationContext.getSharedPreferences("orders_list_v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var initialized = false

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!initialized) {
                val restored = runCatching {
                    preferences.getString("snapshots", null)?.let { json.decodeFromString<List<SavedOrdersList>>(it) }
                }.getOrNull().orEmpty()
                memory.restore(restored)
                initialized = true
            }
        }
    }

    suspend fun persist() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val snapshots = memory.savedEntries().map { it.forDisk() }
            runCatching { preferences.edit().putString("snapshots", json.encodeToString(snapshots)).apply() }
        }
    }
}
