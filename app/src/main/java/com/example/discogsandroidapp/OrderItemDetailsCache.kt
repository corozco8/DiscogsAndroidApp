package com.example.discogsandroidapp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class SavedOrderItemDetails(
    val listingId: Long,
    val posted: String?,
    val dateAdded: String?,
    val comments: String?,
    val checkedAt: Long
) {
    fun isFresh(now: Long): Boolean {
        val ttl = if (posted.isNullOrBlank() && dateAdded.isNullOrBlank()) 5 * 60_000L else 24 * 60 * 60_000L
        return now - checkedAt in 0 until ttl
    }
}

internal fun mergeSavedOrderItem(item: OrderItem, saved: SavedOrderItemDetails?): OrderItem {
    if (saved == null || saved.listingId != (item.id ?: item.id_string?.toLongOrNull())) return item
    return item.copy(
        posted = item.posted?.takeIf { it.isNotBlank() } ?: saved.posted?.takeIf { it.isNotBlank() },
        date_added = item.date_added?.takeIf { it.isNotBlank() } ?: saved.dateAdded?.takeIf { it.isNotBlank() },
        comments = item.comments?.takeIf { it.isNotBlank() } ?: saved.comments?.takeIf { it.isNotBlank() }
    )
}

/** Order metadata only; My Store continues to fetch live inventory. */
internal class OrderItemDetailsCache(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("order_item_details_v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(ids: List<Long>): Map<Long, SavedOrderItemDetails> = withContext(Dispatchers.IO) {
        ids.distinct().mapNotNull { id ->
            runCatching {
                preferences.getString(id.toString(), null)?.let { json.decodeFromString<SavedOrderItemDetails>(it) }
                    ?.takeIf { it.listingId == id }?.let { id to it }
            }.getOrNull()
        }.toMap()
    }

    suspend fun save(details: SavedOrderItemDetails) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val editor = preferences.edit().putString(details.listingId.toString(), json.encodeToString(details))
            val oldEntries = preferences.all.filterKeys { it != details.listingId.toString() }
            if (oldEntries.size >= 2_000) {
                oldEntries.entries.sortedBy { (_, value) ->
                    runCatching { json.decodeFromString<SavedOrderItemDetails>(value as String).checkedAt }.getOrDefault(0)
                }.take(oldEntries.size - 1_999).forEach { editor.remove(it.key) }
            }
            editor.apply()
        }
    }

    companion object { private val lock = Any() }
}
