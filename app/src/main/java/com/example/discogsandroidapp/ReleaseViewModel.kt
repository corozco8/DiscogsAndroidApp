package com.example.discogsandroidapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import android.util.Log

sealed interface ReleaseUiState {
    object Idle : ReleaseUiState
    object Loading : ReleaseUiState
    object StoreLoading : ReleaseUiState
    object OrdersLoading : ReleaseUiState
    object AiSearch : ReleaseUiState
    data class StoreSuccess(val listings: List<InventoryListing>, val totalItems: Int, val isFetchingMore: Boolean) : ReleaseUiState
    data class OrdersSuccess(
        val orders: List<DiscogsOrder>,
        val totalItems: Int,
        val isFetchingMore: Boolean = false,
        val hasMore: Boolean = false
    ) : ReleaseUiState
    data class SearchSuccess(val results: List<SearchResult>) : ReleaseUiState
    data class ReleaseSuccess(
        val release: DiscogsRelease,
        val priceSummary: ReleasePriceSummary? = null
    ) : ReleaseUiState

    data class MasterVersionsLoading(
        val masterId: Long
    ) : ReleaseUiState

    data class MasterVersionsSuccess(
        val masterId: Long,
        val versions: List<MasterVersion>,
        val totalItems: Int
    ) : ReleaseUiState

    data class MasterVersionsError(
        val masterId: Long,
        val message: String
    ) : ReleaseUiState

    data class Error(val message: String) : ReleaseUiState
    object Inventory : ReleaseUiState
    object Offers : ReleaseUiState
    data class OrderDetails(val order: DiscogsOrder) : ReleaseUiState
    data class RatingsWebView(val username: String, val ratingType: String) : ReleaseUiState
}

sealed interface ProfileUiState {
    object Loading : ProfileUiState
    data class Success(val profile: DiscogsProfile) : ProfileUiState
    data class Error(val message: String) : ProfileUiState
}


sealed interface OrderMessagesUiState {
    data object Idle : OrderMessagesUiState
    data object Loading : OrderMessagesUiState

    data class Success(
        val messages: List<DiscogsOrderMessage>
    ) : OrderMessagesUiState

    data class Error(
        val message: String
    ) : OrderMessagesUiState
}

class ReleaseViewModel : ViewModel() {

    private var aiCacheRefreshJob: Job? = null
    private var aiCacheRefreshRequested = false


    private val _uiState = MutableStateFlow<ReleaseUiState>(ReleaseUiState.Idle)
    val uiState: StateFlow<ReleaseUiState> = _uiState

    private val _profileUiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val profileUiState: StateFlow<ProfileUiState> = _profileUiState


    private val _orderMessagesUiState =
        MutableStateFlow<OrderMessagesUiState>(
            OrderMessagesUiState.Idle
        )

    val orderMessagesUiState: StateFlow<OrderMessagesUiState> =
        _orderMessagesUiState

    private var currentUsername: String = ""
    private var currentReleaseId: Long? = null   // Store the release ID for suggestions

    private var releaseBeforeMasterVersions:
            ReleaseUiState.ReleaseSuccess? = null



    // ------------------------------------------------------------
    // Keep existing navigation & fetching methods unchanged
    // ------------------------------------------------------------

    fun navigateToOrders(token: String) {
        _uiState.value = ReleaseUiState.OrdersLoading
        fetchOrders(token)
    }

    fun navigateToOrderDetails(
        order: DiscogsOrder,
        token: String
    ) {
        // Show the row data immediately, then replace it with the complete
        // order resource so shipping address, instructions, fees and
        // next_status are authoritative.
        _uiState.value =
            ReleaseUiState.OrderDetails(order)

        val orderId = order.id

        if (orderId != null) {
            loadOrderMessages(
                orderId = orderId,
                token = token
            )

            viewModelScope.launch {
                try {
                    val fullOrder =
                        RetrofitClient.apiService.getOrder(
                            orderId = orderId,
                            authHeader = "Discogs token=$token"
                        )

                    val current =
                        _uiState.value as? ReleaseUiState.OrderDetails

                    if (current?.order?.id == orderId) {
                        _uiState.value =
                            ReleaseUiState.OrderDetails(
                                fullOrder
                            )
                    }
                } catch (e: Exception) {
                    // The list response is still usable. Keep it on screen and
                    // let message loading/reporting handle its own errors.
                    Log.e(
                        "ORDER_DETAILS",
                        "Failed to refresh full order $orderId",
                        e
                    )
                }
            }
        } else {
            _orderMessagesUiState.value =
                OrderMessagesUiState.Error(
                    "This order does not have an ID."
                )
        }
    }

    fun navigateToAiSearch() {
        _uiState.value = ReleaseUiState.AiSearch
    }

    fun updateOrderStatus(
        orderId: String,
        newStatus: String,
        token: String
    ) {
        if (orderId.isBlank() || newStatus.isBlank()) return

        viewModelScope.launch {
            try {
                val authHeader = "Discogs token=$token"

                // Prefer Discogs' order-update endpoint. If "In Progress" is
                // rejected there by an older API path, fall back to the order
                // message endpoint, which also supports changing status.
                val directResponse =
                    RetrofitClient.apiService.updateOrderStatus(
                        orderId = orderId,
                        authHeader = authHeader,
                        status = newStatus
                    )

                var statusChangeSucceeded =
                    directResponse.isSuccessful

                var lastErrorDetails =
                    if (directResponse.isSuccessful) {
                        null
                    } else {
                        directResponse.errorBody()
                            ?.string()
                            ?.takeIf { it.isNotBlank() }
                            ?: "HTTP ${directResponse.code()}"
                    }

                if (
                    !statusChangeSucceeded &&
                    newStatus.equals(
                        "In Progress",
                        ignoreCase = true
                    )
                ) {
                    val fallbackResponse =
                        RetrofitClient.apiService
                            .updateOrderStatusViaMessage(
                                orderId = orderId,
                                authHeader = authHeader,
                                request =
                                    AddOrderMessageRequest(
                                        status = "In Progress"
                                    )
                            )

                    statusChangeSucceeded =
                        fallbackResponse.isSuccessful

                    if (!fallbackResponse.isSuccessful) {
                        lastErrorDetails =
                            fallbackResponse.errorBody()
                                ?.string()
                                ?.takeIf { it.isNotBlank() }
                                ?: "HTTP ${fallbackResponse.code()}"
                    }
                }

                if (!statusChangeSucceeded) {
                    throw IllegalStateException(
                        "Discogs rejected $newStatus: " +
                                (lastErrorDetails ?: "Unknown error")
                    )
                }

                // Read the order back from Discogs rather than assuming the
                // write succeeded. This also refreshes next_status.
                val updatedOrder =
                    RetrofitClient.apiService.getOrder(
                        orderId = orderId,
                        authHeader = authHeader
                    )

                if (
                    !updatedOrder.status.equals(
                        newStatus,
                        ignoreCase = true
                    )
                ) {
                    Log.w(
                        "ORDER_STATUS",
                        "Discogs accepted the request, but the refreshed " +
                                "order still reports ${updatedOrder.status}."
                    )
                }

                _uiState.value =
                    ReleaseUiState.OrderDetails(
                        updatedOrder
                    )

                loadOrderMessages(
                    orderId = orderId,
                    token = token
                )

                Log.d(
                    "ORDER_STATUS",
                    "Order $orderId updated to ${updatedOrder.status}"
                )
            } catch (e: Exception) {
                Log.e(
                    "ORDER_STATUS",
                    "Failed to update order $orderId to $newStatus",
                    e
                )

                _uiState.value =
                    ReleaseUiState.Error(
                        "Failed to update order status: " +
                                (e.localizedMessage
                                    ?: "Unknown error")
                    )
            }
        }
    }

    fun navigateToInventory() {
        _uiState.value = ReleaseUiState.Inventory
    }

    fun navigateToOffers() {
        _uiState.value = ReleaseUiState.Offers
    }

    fun fetchUserProfile(token: String) {
        viewModelScope.launch {
            _profileUiState.value = ProfileUiState.Loading
            try {
                val authHeader = "Discogs token=$token"
                val identity = RetrofitClient.apiService.getIdentity(authHeader)

                currentUsername = identity.username

                val profile =
                    RetrofitClient.apiService.getUserProfile(identity.username, authHeader)
                _profileUiState.value = ProfileUiState.Success(profile)
            } catch (e: Exception) {
                _profileUiState.value =
                    ProfileUiState.Error(e.localizedMessage ?: "Failed to load profile")
            }
        }
    }

    private var currentPage = 1
    private var totalPages = 1
    private var isFetchingNextPage = false
    private val currentListings = mutableListOf<InventoryListing>()

    private var currentSort = "listed"
    private var currentSortOrder = "desc"
    private var currentStoreQuery = ""

    private var storeFetchJob: Job? = null
    private var storeRequestGeneration = 0

    fun fetchStoreInventory(
        token: String,
        sort: String = "listed",
        sortOrder: String = "desc",
        reset: Boolean = true
    ) {
        if (currentUsername.isEmpty()) return
        if (!reset && isFetchingNextPage) return

        if (reset) {
            storeRequestGeneration++
            storeFetchJob?.cancel()
            isFetchingNextPage = false

            currentPage = 1
            totalPages = 1
            currentListings.clear()
            currentSort = sort
            currentSortOrder = sortOrder

            _uiState.value = ReleaseUiState.StoreLoading
        } else {
            isFetchingNextPage = true
            _uiState.value = ReleaseUiState.StoreSuccess(
                listings = currentListings.toList(),
                totalItems =
                    (_uiState.value as? ReleaseUiState.StoreSuccess)
                        ?.totalItems
                        ?: currentListings.size,
                isFetchingMore = true
            )
        }

        val generation = storeRequestGeneration
        val requestedPage =
            if (reset) {
                1
            } else {
                currentPage + 1
            }

        storeFetchJob = viewModelScope.launch {
            try {
                val authHeader = "Discogs token=$token"

                val response =
                    RetrofitClient.apiService.getInventory(
                        username = currentUsername,
                        authHeader = authHeader,
                        status = "For Sale",
                        searchString =
                            currentStoreQuery
                                .takeIf { it.isNotBlank() },
                        sort = currentSort,
                        sortOrder = currentSortOrder,
                        page = requestedPage,
                        perPage = 50
                    )

                // A newer store search/sort request replaced this one.
                if (generation != storeRequestGeneration) {
                    return@launch
                }

                if (
                    _uiState.value !is ReleaseUiState.StoreLoading &&
                    _uiState.value !is ReleaseUiState.StoreSuccess
                ) {
                    // The seller navigated away while the request was in
                    // flight. Do not pull them back to My Store.
                    return@launch
                }

                if (reset) {
                    currentListings.clear()
                }

                currentListings.addAll(response.listings)

                // Only commit pagination progress after a successful response.
                currentPage = requestedPage
                totalPages = response.pagination.pages

                _uiState.value =
                    ReleaseUiState.StoreSuccess(
                        listings = currentListings.toList(),
                        totalItems = response.pagination.items,
                        isFetchingMore = false
                    )

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != storeRequestGeneration) {
                    return@launch
                }

                // If a later page fails, keep the pages already loaded instead
                // of replacing the whole store with an error screen.
                if (!reset && currentListings.isNotEmpty()) {
                    _uiState.value =
                        ReleaseUiState.StoreSuccess(
                            listings = currentListings.toList(),
                            totalItems =
                                (_uiState.value as? ReleaseUiState.StoreSuccess)
                                    ?.totalItems
                                    ?: currentListings.size,
                            isFetchingMore = false
                        )

                    Log.e(
                        "STORE_PAGING",
                        "Failed to load inventory page $requestedPage",
                        e
                    )
                } else {
                    _uiState.value =
                        ReleaseUiState.Error(
                            e.localizedMessage
                                ?: "Failed to load store inventory"
                        )
                }
            } finally {
                if (generation == storeRequestGeneration) {
                    isFetchingNextPage = false
                }
            }
        }
    }

    fun searchStoreInventory(
        query: String,
        token: String
    ) {
        currentStoreQuery = query.trim()

        fetchStoreInventory(
            token = token,
            sort = currentSort,
            sortOrder = currentSortOrder,
            reset = true
        )
    }

    fun clearStoreSearch(token: String) {
        currentStoreQuery = ""

        fetchStoreInventory(
            token = token,
            sort = currentSort,
            sortOrder = currentSortOrder,
            reset = true
        )
    }

    fun loadNextPage(token: String) {
        if (
            currentPage < totalPages &&
            !isFetchingNextPage
        ) {
            // fetchStoreInventory computes currentPage + 1 and commits the
            // new page number only after the request succeeds.
            fetchStoreInventory(
                token = token,
                sort = currentSort,
                sortOrder = currentSortOrder,
                reset = false
            )
        }
    }

    fun search(query: String, token: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _uiState.value = ReleaseUiState.Loading
            try {
                val authHeader = "Discogs token=$token"
                val response =
                    RetrofitClient.apiService.searchDatabase(query = query, authHeader = authHeader)
                _uiState.value = ReleaseUiState.SearchSuccess(response.results)
            } catch (e: Exception) {
                _uiState.value = ReleaseUiState.Error(e.localizedMessage ?: "Search failed")
            }
        }
    }

    fun fetchRelease(releaseId: Long, token: String) {
        viewModelScope.launch {
            _uiState.value = ReleaseUiState.Loading
            try {
                val authHeader = "Discogs token=$token"

                // Store releaseId for later suggestions
                currentReleaseId = releaseId

                // 1. Main Release Details
                val releaseResponse = RetrofitClient.apiService.getRelease(
                    releaseId = releaseId,
                    authHeader = authHeader
                )

                // 2. Marketplace Stats
                var numForSale = 0
                var lowestPrice: Double? = null
                var debugMsg = "No Data"

                try {
                    val stats = RetrofitClient.apiService.getMarketplaceStats(
                        releaseId = releaseId,
                        authHeader = authHeader
                    )
                    numForSale = stats.numForSale ?: 0
                    lowestPrice = stats.lowestPrice?.value
                } catch (e: Exception) {
                    Log.e("DiscogsDebug", "Marketplace Stats Failed!", e)
                    debugMsg = "StatsErr: ${e.javaClass.simpleName}"
                }

                // 3. Price Suggestions
                var suggestions: PriceSuggestions? = null
                try {
                    suggestions = RetrofitClient.apiService.getPriceSuggestions(
                        releaseId = releaseId,
                        authHeader = authHeader
                    )
                } catch (e: Exception) {
                    Log.e("DiscogsDebug", "Price Suggestions Failed!", e)
                    val priceErr = "PriceErr: ${e.javaClass.simpleName}"
                    debugMsg = if (debugMsg == "No Data") priceErr else "$debugMsg | $priceErr"
                }

                val summary = ReleasePriceSummary(
                    low = suggestions?.low,
                    median = suggestions?.median,
                    high = suggestions?.high,
                    currency = "USD",
                    lastSold = debugMsg,
                    numForSale = numForSale,
                    lowestAskingPrice = lowestPrice,
                    priceSuggestions = suggestions
                )

                _uiState.value = ReleaseUiState.ReleaseSuccess(
                    release = releaseResponse,
                    priceSummary = summary
                )
            } catch (e: Exception) {
                _uiState.value = ReleaseUiState.Error(e.localizedMessage ?: "Failed to fetch details")
            }
        }
    }

    fun fetchMasterVersions(
        masterId: Long,
        token: String
    ) {
        val currentState = _uiState.value

        if (currentState is ReleaseUiState.ReleaseSuccess) {
            releaseBeforeMasterVersions = currentState
        }

        viewModelScope.launch {
            _uiState.value =
                ReleaseUiState.MasterVersionsLoading(
                    masterId = masterId
                )

            try {
                val authHeader =
                    "Discogs token=$token"

                val firstPage =
                    RetrofitClient.apiService
                        .getMasterVersions(
                            masterId = masterId,
                            authHeader = authHeader,
                            page = 1,
                            perPage = 100
                        )

                val allVersions =
                    firstPage.versions.toMutableList()

                val totalPages =
                    firstPage.pagination?.pages ?: 1

                if (totalPages > 1) {
                    for (page in 2..totalPages) {
                        val response =
                            RetrofitClient.apiService
                                .getMasterVersions(
                                    masterId = masterId,
                                    authHeader = authHeader,
                                    page = page,
                                    perPage = 100
                                )

                        allVersions.addAll(
                            response.versions
                        )
                    }
                }

                _uiState.value =
                    ReleaseUiState.MasterVersionsSuccess(
                        masterId = masterId,
                        versions = allVersions,
                        totalItems =
                            firstPage.pagination?.items
                                ?: allVersions.size
                    )

            } catch (e: Exception) {
                Log.e(
                    "MASTER_VERSIONS",
                    "Failed to load master versions",
                    e
                )

                _uiState.value =
                    ReleaseUiState.MasterVersionsError(
                        masterId = masterId,
                        message =
                            e.localizedMessage
                                ?: "Failed to load versions"
                    )
            }
        }
    }

    fun returnFromMasterVersions() {
        _uiState.value =
            releaseBeforeMasterVersions
                ?: ReleaseUiState.Idle
    }

    private fun removeFromAiCacheInBackground(
        listingIds: Collection<Long>
    ) {
        val ids = listingIds.distinct()

        if (ids.isEmpty()) return

        viewModelScope.launch {
            try {
                BackendRetrofitClient.apiService
                    .removeFromInventoryCache(
                        RemoveInventoryCacheRequest(
                            listingIds = ids
                        )
                    )
            } catch (cacheError: Exception) {
                AiCacheSyncTracker.markDirty()
                // The seller-facing Discogs operation has already succeeded.
                // Do not block or fail that workflow just because the optional
                // local AI backend is offline.
                Log.w(
                    "INVENTORY_CACHE",
                    "Could not prune the optional AI cache. " +
                            "It will refresh the next time the backend syncs.",
                    cacheError
                )
            }
        }
    }

    private fun refreshAiCacheInBackground() {
        // Mark that a full refresh is needed. If one is already running, do
        // not launch another request. The active worker will perform at most
        // one follow-up refresh if another invalidation arrives mid-download.
        AiCacheSyncTracker.markDirty()
        aiCacheRefreshRequested = true

        if (aiCacheRefreshJob?.isActive == true) {
            return
        }

        aiCacheRefreshJob = viewModelScope.launch {
            // Combine rapid create/delete bursts before the first download.
            delay(750)

            while (aiCacheRefreshRequested) {
                aiCacheRefreshRequested = false

                try {
                    BackendRetrofitClient.apiService
                        .syncInventory()

                    // Only call the cache fully current when no newer
                    // mutation arrived while this download was in flight.
                    // If another invalidation is pending, keep the dirty bit
                    // set until the follow-up refresh finishes.
                    if (!aiCacheRefreshRequested) {
                        AiCacheSyncTracker
                            .markFullSyncComplete()
                    }
                } catch (cacheError: Exception) {
                    AiCacheSyncTracker.markDirty()
                    // AI search is optional. Keep the normal seller workflow
                    // usable, but leave a clear log that synchronization did
                    // not complete. A later mutation/request will retry.
                    Log.w(
                        "INVENTORY_CACHE",
                        "Could not refresh the optional AI inventory cache.",
                        cacheError
                    )
                    break
                }

                if (aiCacheRefreshRequested) {
                    // Small settle window for another burst that happened
                    // while the previous network refresh was in flight.
                    delay(250)
                }
            }
        }
    }

    private suspend fun updateAiCacheListingAfterEdit(
        listingId: Long,
        price: Double,
        condition: String,
        sleeveCondition: String,
        comments: String
    ): Boolean {
        return try {
            val response =
                BackendRetrofitClient.apiService
                    .updateInventoryCacheListing(
                        UpdateInventoryCacheListingRequest(
                            listingId = listingId,
                            price = price,
                            condition = condition,
                            sleeveCondition = sleeveCondition,
                            comments = comments
                        )
                    )

            if (
                response.updated &&
                !AiCacheSyncTracker.needsFullSync()
            ) {
                true
            } else {
                // The row may be missing OR an earlier mutation may have
                // failed to reach the cache. A full synchronization is the
                // only safe point at which AI results can be called current.
                BackendRetrofitClient.apiService
                    .syncInventory()

                AiCacheSyncTracker
                    .markFullSyncComplete()

                true
            }

        } catch (cacheError: Exception) {
            // The Discogs edit itself already succeeded. Do not pretend the
            // AI cache is synchronized: callers that are waiting for cache
            // consistency receive false and must not rerun stale AI results.
            AiCacheSyncTracker.markDirty()

            Log.w(
                "INVENTORY_CACHE",
                "Could not patch the optional AI inventory cache.",
                cacheError
            )

            // Queue one best-effort full refresh for when the backend is
            // reachable again / a later request succeeds.
            refreshAiCacheInBackground()
            false
        }
    }

    private fun updateAiCacheListingInBackground(
        listingId: Long,
        price: Double,
        condition: String,
        sleeveCondition: String,
        comments: String
    ) {
        viewModelScope.launch {
            updateAiCacheListingAfterEdit(
                listingId = listingId,
                price = price,
                condition = condition,
                sleeveCondition = sleeveCondition,
                comments = comments
            )
        }
    }

    fun deleteListing(listingId: Long, token: String) {
        viewModelScope.launch {
            try {
                _uiState.value = ReleaseUiState.StoreLoading

                val response =
                    RetrofitClient.apiService.deleteListing(
                        listingId,
                        "Discogs token=$token"
                    )

                if (response.isSuccessful || response.code() == 404) {

                    // 404 means the listing is already gone from Discogs.
                    // In either case, prune any stale copy from the optional
                    // AI cache without delaying the seller-facing workflow.
                    removeFromAiCacheInBackground(
                        listOf(listingId)
                    )

                    fetchStoreInventory(
                        token,
                        currentSort,
                        currentSortOrder,
                        reset = true
                    )

                } else {
                    _uiState.value =
                        ReleaseUiState.Error(
                            "Failed to delete. HTTP Code: ${response.code()}"
                        )
                }

            } catch (e: Exception) {
                _uiState.value =
                    ReleaseUiState.Error(
                        "Network error: ${e.message}"
                    )
            }
        }
    }

    /**
     * Batch delete from the live My Store inventory.
     *
     * Discogs remains the source of truth for My Store, but the optional AI
     * backend also keeps its own inventory snapshot. Successful deletions are
     * therefore pruned from that cache in the background as well.
     */
    fun deleteListingsFromStore(
        listingIds: List<Long>,
        token: String
    ) {
        if (listingIds.isEmpty()) return

        viewModelScope.launch {
            val authHeader = "Discogs token=$token"
            val uniqueIds = listingIds.distinct()
            val deletedIds = mutableSetOf<Long>()
            var failedCount = 0

            for (listingId in uniqueIds) {
                try {
                    val response =
                        RetrofitClient.apiService.deleteListing(
                            listingId = listingId,
                            authHeader = authHeader
                        )

                    if (response.isSuccessful || response.code() == 404) {
                        deletedIds += listingId
                        currentListings.removeAll { it.id == listingId }

                        val previousState =
                            _uiState.value as? ReleaseUiState.StoreSuccess

                        _uiState.value = ReleaseUiState.StoreSuccess(
                            listings = currentListings.toList(),
                            totalItems =
                                (previousState?.totalItems ?: currentListings.size)
                                    .minus(1)
                                    .coerceAtLeast(0),
                            isFetchingMore = false
                        )
                    } else {
                        failedCount++
                        Log.e(
                            "STORE_BULK_DELETE",
                            "Failed to delete $listingId. HTTP ${response.code()}"
                        )
                    }
                } catch (e: Exception) {
                    failedCount++
                    Log.e(
                        "STORE_BULK_DELETE",
                        "Network error deleting $listingId",
                        e
                    )
                }
            }

            if (deletedIds.isNotEmpty()) {
                removeFromAiCacheInBackground(
                    deletedIds
                )
            }

            // Refresh once at the end so the visible inventory exactly matches
            // Discogs after all successful deletions.
            fetchStoreInventory(
                token = token,
                sort = currentSort,
                sortOrder = currentSortOrder,
                reset = true
            )

            Log.d(
                "STORE_BULK_DELETE",
                "Deleted ${deletedIds.size}; failed $failedCount"
            )
        }
    }

    /**
     * Deletes one or more listings while keeping the user on the AI Search screen.
     *
     * Unlike deleteListing(), this does NOT switch the main UI to StoreLoading
     * and does NOT refresh the regular Store screen after every deletion.
     *
     * Requests are performed sequentially to avoid hammering the Discogs API.
     */
    fun deleteListingsFromAiSearch(
        listingIds: List<Long>,
        token: String,
        onListingDeleted: (Long) -> Unit = {},
        onComplete: (deletedCount: Int, failedCount: Int) -> Unit = { _, _ -> }
    ) {
        if (listingIds.isEmpty()) {
            onComplete(0, 0)
            return
        }

        viewModelScope.launch {
            val uniqueIds = listingIds.distinct()
            val authHeader = "Discogs token=$token"

            val successfullyDeletedIds = mutableListOf<Long>()
            var failedCount = 0

            for (listingId in uniqueIds) {
                try {
                    val response =
                        RetrofitClient.apiService.deleteListing(
                            listingId = listingId,
                            authHeader = authHeader
                        )

                    if (
                        response.isSuccessful ||
                        response.code() == 404
                    ) {
                        successfullyDeletedIds.add(listingId)

                        // A 404 here means this is a stale cached listing that
                        // was already removed from Discogs. Either way, remove
                        // it from the Android results and backend cache.
                        onListingDeleted(listingId)

                        Log.d(
                            "AI_BULK_DELETE",
                            if (response.code() == 404) {
                                "Listing $listingId was already gone from Discogs. Pruning stale cache."
                            } else {
                                "Deleted listing $listingId from Discogs"
                            }
                        )

                    } else {
                        failedCount++

                        Log.e(
                            "AI_BULK_DELETE",
                            "Failed to delete listing $listingId. HTTP ${response.code()}"
                        )
                    }

                } catch (e: Exception) {
                    failedCount++

                    Log.e(
                        "AI_BULK_DELETE",
                        "Network error deleting listing $listingId",
                        e
                    )
                }
            }

            // Remove every successfully deleted listing from FastAPI's
            // in-memory inventory cache AND inventory_cache.json.
            if (successfullyDeletedIds.isNotEmpty()) {
                try {
                    val cacheResponse =
                        BackendRetrofitClient.apiService.removeFromInventoryCache(
                            RemoveInventoryCacheRequest(
                                listingIds = successfullyDeletedIds
                            )
                        )

                    Log.d(
                        "AI_BULK_DELETE",
                        "Backend cache removed ${cacheResponse.removed} listing(s). " +
                                "${cacheResponse.cachedItems} cached listing(s) remain."
                    )

                } catch (cacheError: Exception) {
                    AiCacheSyncTracker.markDirty()

                    Log.e(
                        "AI_BULK_DELETE",
                        "Discogs deletes succeeded, but backend inventory cache sync failed",
                        cacheError
                    )
                }
            }

            // Do not force navigation when the network work completes.
            // If the seller left AI Search while deletes were running, keep
            // their current destination.
            onComplete(
                successfullyDeletedIds.size,
                failedCount
            )
        }
    }

    fun editListing(
        listingId: Long,
        price: Double,
        condition: String,
        sleeveCondition: String,
        comments: String,
        token: String,
        refreshStoreAfterSuccess: Boolean = true,
        waitForAiCacheSync: Boolean = false,
        onAiCacheSyncResult: (Boolean) -> Unit = {},
        onSuccess: () -> Unit = {}
    ) {
        if (!price.isFinite() || price <= 0.0) {
            _uiState.value =
                ReleaseUiState.Error(
                    "Price must be a valid amount greater than $0.00."
                )
            return
        }

        viewModelScope.launch {
            try {
                if (refreshStoreAfterSuccess) {
                    _uiState.value =
                        ReleaseUiState.StoreLoading
                }

                val requestBody =
                    EditListingRequest(
                        price = price,
                        condition = condition,
                        sleeve_condition = sleeveCondition,
                        status = "For Sale",
                        comments = comments
                    )

                val response =
                    RetrofitClient.apiService.editListing(
                        listingId = listingId,
                        authHeader = "Discogs token=$token",
                        request = requestBody
                    )

                if (response.isSuccessful) {
                    // Patch only this cached AI listing. This avoids a full
                    // inventory download for every edit. When the caller is
                    // waiting for consistency (AI Search edit), report whether
                    // synchronization really succeeded before it reruns search.
                    if (waitForAiCacheSync) {
                        val cacheSynced =
                            updateAiCacheListingAfterEdit(
                                listingId = listingId,
                                price = price,
                                condition = condition,
                                sleeveCondition = sleeveCondition,
                                comments = comments
                            )

                        onAiCacheSyncResult(cacheSynced)
                    } else {
                        updateAiCacheListingInBackground(
                            listingId = listingId,
                            price = price,
                            condition = condition,
                            sleeveCondition = sleeveCondition,
                            comments = comments
                        )
                    }

                    if (refreshStoreAfterSuccess) {
                        fetchStoreInventory(
                            token = token,
                            sort = currentSort,
                            sortOrder = currentSortOrder,
                            reset = true
                        )
                    }

                    onSuccess()
                } else {
                    _uiState.value =
                        ReleaseUiState.Error(
                            "Failed to edit. HTTP Code: ${response.code()}"
                        )
                }
            } catch (e: Exception) {
                _uiState.value =
                    ReleaseUiState.Error(
                        "Network error: ${e.message}"
                    )
            }
        }
    }

    fun createListing(
        releaseId: Int,
        price: Double,
        condition: String,
        sleeveCondition: String,
        comments: String,
        token: String,
        onSuccess: () -> Unit
    ) {
        Log.d(
            "CREATE_LISTING",
            ">>> createListing releaseId=$releaseId price=$price condition=$condition sleeve=$sleeveCondition"
        )

        if (!price.isFinite() || price <= 0.0) {
            _uiState.value =
                ReleaseUiState.Error(
                    "Price must be a valid amount greater than $0.00."
                )
            return
        }

        viewModelScope.launch {
            try {
                val requestBody = CreateListingRequest(
                    release_id = releaseId,
                    condition = condition,
                    sleeve_condition = sleeveCondition,
                    price = price,
                    comments = comments,
                    status = "For Sale"
                )

                val response = RetrofitClient.apiService.createListing(
                    authHeader = "Discogs token=$token",
                    request = requestBody
                )

                if (response.isSuccessful) {
                    // The operation itself does not mutate navigation state, so
                    // the seller stays wherever they are even if they navigated
                    // while the network request was running.
                    refreshAiCacheInBackground()
                    onSuccess()
                } else {
                    val errorBody = response.errorBody()?.string()
                    _uiState.value = ReleaseUiState.Error(
                        "Failed to list item. HTTP Code: ${response.code()} – " +
                                (errorBody ?: "no details")
                    )
                }
            } catch (e: Exception) {
                Log.e("CREATE_LISTING", "Listing creation failed", e)
                _uiState.value = ReleaseUiState.Error(
                    "Network error: ${e.message}"
                )
            }
        }
    }

    fun resetToIdle() {
        _uiState.value = ReleaseUiState.Idle
    }

    private val currentOrders =
        mutableListOf<DiscogsOrder>()

    private var currentOrdersPage = 1
    private var totalOrdersPages = 1
    private var totalOrdersItems = 0
    private var isFetchingMoreOrders = false
    private var currentOrdersStatus = "Payment Received"
    private var currentOrdersSortOrder = "asc"
    private var ordersFetchJob: Job? = null
    private var ordersRequestGeneration = 0

    fun fetchOrders(token: String) {
        currentOrdersStatus = "Payment Received"
        currentOrdersSortOrder = "asc"

        fetchOrdersPage(
            token = token,
            reset = true
        )
    }

    fun fetchOrdersByStatus(
        status: String,
        token: String
    ) {
        currentOrdersStatus =
            if (status == "All Orders") {
                "All"
            } else {
                status
            }

        currentOrdersSortOrder = "desc"

        fetchOrdersPage(
            token = token,
            reset = true
        )
    }

    fun loadNextOrdersPage(token: String) {
        if (
            currentOrdersPage < totalOrdersPages &&
            !isFetchingMoreOrders
        ) {
            fetchOrdersPage(
                token = token,
                reset = false
            )
        }
    }

    private fun fetchOrdersPage(
        token: String,
        reset: Boolean
    ) {
        if (!reset && isFetchingMoreOrders) return

        if (reset) {
            ordersRequestGeneration++
            ordersFetchJob?.cancel()

            currentOrdersPage = 1
            totalOrdersPages = 1
            totalOrdersItems = 0
            currentOrders.clear()
            isFetchingMoreOrders = false

            _uiState.value =
                ReleaseUiState.OrdersLoading
        } else {
            isFetchingMoreOrders = true

            _uiState.value =
                ReleaseUiState.OrdersSuccess(
                    orders = currentOrders.toList(),
                    totalItems = totalOrdersItems,
                    isFetchingMore = true,
                    hasMore =
                        currentOrdersPage <
                                totalOrdersPages
                )
        }

        val generation = ordersRequestGeneration
        val requestedPage =
            if (reset) {
                1
            } else {
                currentOrdersPage + 1
            }

        ordersFetchJob = viewModelScope.launch {
            try {
                val response =
                    RetrofitClient.apiService.getOrders(
                        token = "Discogs token=$token",
                        status = currentOrdersStatus,
                        page = requestedPage,
                        perPage = 100,
                        sortOrder = currentOrdersSortOrder
                    )

                if (generation != ordersRequestGeneration) {
                    return@launch
                }

                if (
                    _uiState.value !is ReleaseUiState.OrdersLoading &&
                    _uiState.value !is ReleaseUiState.OrdersSuccess
                ) {
                    // The seller left Orders while this page was loading.
                    return@launch
                }

                if (reset) {
                    currentOrders.clear()
                }

                val existingIds =
                    currentOrders
                        .mapNotNull { it.id }
                        .toMutableSet()

                response.orders
                    .orEmpty()
                    .forEach { order ->
                        val id = order.id

                        if (
                            id == null ||
                            existingIds.add(id)
                        ) {
                            currentOrders.add(order)
                        }
                    }

                currentOrdersPage = requestedPage
                totalOrdersPages =
                    response.pagination?.pages
                        ?.coerceAtLeast(1)
                        ?: 1

                totalOrdersItems =
                    response.pagination?.items
                        ?: currentOrders.size

                isFetchingMoreOrders = false

                _uiState.value =
                    ReleaseUiState.OrdersSuccess(
                        orders = currentOrders.toList(),
                        totalItems = totalOrdersItems,
                        isFetchingMore = false,
                        hasMore =
                            currentOrdersPage <
                                    totalOrdersPages
                    )

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != ordersRequestGeneration) {
                    return@launch
                }

                isFetchingMoreOrders = false

                if (!reset && currentOrders.isNotEmpty()) {
                    _uiState.value =
                        ReleaseUiState.OrdersSuccess(
                            orders = currentOrders.toList(),
                            totalItems = totalOrdersItems,
                            isFetchingMore = false,
                            hasMore =
                                currentOrdersPage <
                                        totalOrdersPages
                        )

                    Log.e(
                        "ORDER_PAGING",
                        "Failed to load order page $requestedPage",
                        e
                    )
                } else {
                    _uiState.value =
                        ReleaseUiState.Error(
                            e.localizedMessage
                                ?: "Failed to load orders"
                        )
                }
            }
        }
    }


    fun loadOrderMessages(
        orderId: String,
        token: String
    ) {
        viewModelScope.launch {
            _orderMessagesUiState.value =
                OrderMessagesUiState.Loading

            try {
                val authHeader =
                    "Discogs token=$token"

                val firstPage =
                    RetrofitClient.apiService
                        .getOrderMessages(
                            orderId = orderId,
                            authHeader = authHeader,
                            page = 1,
                            perPage = 100
                        )

                val allMessages =
                    firstPage.messages.toMutableList()

                val totalPages =
                    firstPage.pagination?.pages ?: 1

                if (totalPages > 1) {
                    for (page in 2..totalPages) {
                        val response =
                            RetrofitClient.apiService
                                .getOrderMessages(
                                    orderId = orderId,
                                    authHeader = authHeader,
                                    page = page,
                                    perPage = 100
                                )

                        allMessages.addAll(
                            response.messages
                        )
                    }
                }

                _orderMessagesUiState.value =
                    OrderMessagesUiState.Success(
                        messages = allMessages
                    )

            } catch (e: Exception) {
                Log.e(
                    "ORDER_MESSAGES",
                    "Failed to load order messages",
                    e
                )

                _orderMessagesUiState.value =
                    OrderMessagesUiState.Error(
                        e.localizedMessage
                            ?: "Failed to load messages"
                    )
            }
        }
    }

    fun sendOrderMessage(
        orderId: String,
        message: String,
        token: String
    ) {
        val cleanMessage = message.trim()

        if (cleanMessage.isEmpty()) {
            return
        }

        viewModelScope.launch {
            try {
                val authHeader =
                    "Discogs token=$token"

                RetrofitClient.apiService
                    .sendOrderMessage(
                        orderId = orderId,
                        authHeader = authHeader,
                        request = AddOrderMessageRequest(
                            message = cleanMessage
                        )
                    )

                // Reload the conversation so the new message
                // appears exactly as Discogs stored it.
                loadOrderMessages(
                    orderId = orderId,
                    token = token
                )

            } catch (e: Exception) {
                Log.e(
                    "ORDER_MESSAGES",
                    "Failed to send order message",
                    e
                )

                _orderMessagesUiState.value =
                    OrderMessagesUiState.Error(
                        e.localizedMessage
                            ?: "Failed to send message"
                    )
            }
        }
    }

    fun openRatings(username: String, ratingType: String) {
        _uiState.value = ReleaseUiState.RatingsWebView(username, ratingType)
    }

}
