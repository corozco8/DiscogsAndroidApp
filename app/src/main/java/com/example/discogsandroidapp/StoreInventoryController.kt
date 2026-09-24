package com.example.discogsandroidapp

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class StoreQuery(val text: String = "", val sort: String = "listed", val order: String = "desc")
internal data class LocalStoreState(
    val ready: Boolean = false,
    val query: StoreQuery = StoreQuery(),
    val listings: List<InventoryListing> = emptyList(),
    val message: String = "Loading saved inventory…",
    val error: String? = null
)

/** One prepared search string per listing per database update, never per keystroke. */
internal class StoreSearchIndex(inventory: List<LocalInventoryListingEntity>) {
    private val entries = inventory.filter { it.status.equals("For Sale", true) }.map { row ->
        row to listOf(row.listingId, row.releaseId, row.artist, row.title, row.comments,
            row.mediaCondition, row.sleeveCondition, row.priceValue).joinToString(" ").lowercase(java.util.Locale.ROOT)
    }
    fun search(query: StoreQuery): List<InventoryListing> {
        val terms = query.text.trim().lowercase(java.util.Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val matches = entries.asSequence().filter { (_, text) -> terms.all { it in text } }.map { it.first }.toList()
        val comparator = when (query.sort) {
            "price" -> compareBy<LocalInventoryListingEntity> { it.priceValue ?: Double.POSITIVE_INFINITY }
            "title", "item" -> compareBy { it.title.lowercase(java.util.Locale.ROOT) }
            "artist" -> compareBy { it.artist.lowercase(java.util.Locale.ROOT) }
            else -> compareBy { it.listedAtEpochMs }
        }.thenBy { it.listingId }
        return matches.sortedWith(if (query.order == "desc") comparator.reversed() else comparator).map { row ->
            InventoryListing(id = row.listingId, status = row.status, condition = row.mediaCondition,
                sleeve_condition = row.sleeveCondition, comments = row.comments, posted = row.postedRaw,
                price = row.priceValue?.let { Price(value = it, currency = row.currency) },
                release = ListingRelease(id = row.releaseId, description = "${row.artist} - ${row.title}",
                    thumbnail = row.thumbnail, title = row.title, artist = row.artist))
        }
    }
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
internal class StoreInventoryController(
    context: Context,
    private val repository: SellerLocalRepository,
    private val scope: CoroutineScope
) {
    private val query = MutableStateFlow(StoreQuery())
    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private var refreshJob: Job? = null
    private var refreshAgain = false
    private val indexed = repository.inventory.map { StoreSearchIndex(it) }.flowOn(Dispatchers.Default)

    val state = combine(indexed, query.debounce(150), repository.inventorySyncState, refreshing, error) {
        index, query, sync, refreshing, error ->
        LocalStoreState(
            ready = sync != null,
            query = query,
            listings = index.search(query),
            message = when {
                error != null -> "Showing saved inventory. Refresh failed; tap Refresh to retry."
                refreshing -> "Refreshing inventory…"
                sync != null -> "Saved inventory • updated " + java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(sync.lastSuccessfulSyncAtEpochMs))
                else -> "Loading inventory for the first time…"
            },
            error = error
        )
    }.flowOn(Dispatchers.Default).catch {
        emit(LocalStoreState(error = "Could not read saved inventory. Please reopen My Store and retry."))
    }.stateIn(scope, SharingStarted.WhileSubscribed(0), LocalStoreState())

    fun setQuery(text: String, sort: String, order: String) { query.value = StoreQuery(text, sort, order) }

    fun refresh(token: String, force: Boolean) {
        if (refreshJob?.isActive == true) {
            // A listing created during a sync needs one more pass after that sync ends.
            if (force) refreshAgain = true
            return
        }
        refreshJob = scope.launch {
            refreshing.value = true
            error.value = null
            try {
                repository.syncInventory(token, force)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = "Could not load inventory. Check your connection and try again." }
            finally {
                refreshing.value = false
                refreshJob = null
                if (refreshAgain && scope.isActive) {
                    refreshAgain = false
                    refresh(token, force = true)
                }
            }
        }
    }
}
