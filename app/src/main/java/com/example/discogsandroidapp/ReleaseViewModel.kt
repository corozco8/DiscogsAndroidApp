package com.example.discogsandroidapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import android.util.Log

sealed interface ReleaseUiState {
    object Idle : ReleaseUiState
    object Loading : ReleaseUiState
    object StoreLoading : ReleaseUiState
    object OrdersLoading : ReleaseUiState
    object AiSearch : ReleaseUiState
    data class StoreSuccess(val listings: List<InventoryListing>, val totalItems: Int, val isFetchingMore: Boolean) : ReleaseUiState
    data class OrdersSuccess(val orders: List<DiscogsOrder>) : ReleaseUiState
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
        _uiState.value = ReleaseUiState.OrderDetails(order)

        val orderId = order.id

        if (orderId != null) {
            loadOrderMessages(
                orderId = orderId,
                token = token
            )
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

    fun updateOrderStatus(orderId: String, newStatus: String, token: String) {
        println("Attempting to update Order $orderId to $newStatus")
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

    fun fetchStoreInventory(
        token: String,
        sort: String = "listed",
        sortOrder: String = "desc",
        reset: Boolean = true
    ) {
        if (currentUsername.isEmpty() || isFetchingNextPage) return

        viewModelScope.launch {
            if (reset) {
                currentPage = 1
                currentListings.clear()
                currentSort = sort
                currentSortOrder = sortOrder
                _uiState.value = ReleaseUiState.StoreLoading
            } else {
                isFetchingNextPage = true
                _uiState.value = ReleaseUiState.StoreSuccess(
                    currentListings,
                    currentListings.size,
                    isFetchingMore = true
                )
            }

            try {
                val authHeader = "Discogs token=$token"
                val response = RetrofitClient.apiService.getInventory(
                    username = currentUsername,
                    authHeader = authHeader,
                    status = "For Sale",
                    sort = currentSort,
                    sortOrder = currentSortOrder,
                    page = currentPage,
                    perPage = 50
                )

                totalPages = response.pagination.pages
                currentListings.addAll(response.listings)

                _uiState.value = ReleaseUiState.StoreSuccess(
                    listings = currentListings.toList(),
                    totalItems = response.pagination.items,
                    isFetchingMore = false
                )
                isFetchingNextPage = false
            } catch (e: Exception) {
                _uiState.value =
                    ReleaseUiState.Error(e.localizedMessage ?: "Failed to load store inventory")
                isFetchingNextPage = false
            }
        }
    }

    fun loadNextPage(token: String) {
        if (currentPage < totalPages && !isFetchingNextPage) {
            currentPage++
            fetchStoreInventory(token, currentSort, currentSortOrder, reset = false)
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
                    // In either case, remove any stale copy from the AI cache.
                    // Keep the FastAPI AI inventory cache in sync with Discogs.
                    try {
                        BackendRetrofitClient.apiService.removeFromInventoryCache(
                            RemoveInventoryCacheRequest(
                                listingIds = listOf(listingId)
                            )
                        )
                    } catch (cacheError: Exception) {
                        Log.e(
                            "INVENTORY_CACHE",
                            "Discogs delete succeeded, but backend cache removal failed for $listingId",
                            cacheError
                        )
                    }

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
                    Log.e(
                        "AI_BULK_DELETE",
                        "Discogs deletes succeeded, but backend inventory cache sync failed",
                        cacheError
                    )
                }
            }

            // Keep the main application on AI Inventory Search.
            _uiState.value = ReleaseUiState.AiSearch

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
        token: String
    ) {
        viewModelScope.launch {
            try {
                _uiState.value = ReleaseUiState.StoreLoading

                val requestBody = EditListingRequest(
                    price = price,
                    condition = condition,
                    sleeve_condition = sleeveCondition,
                    status = "For Sale",
                    comments = comments
                )

                val response = RetrofitClient.apiService.editListing(
                    listingId = listingId,
                    authHeader = "Discogs token=$token",
                    request = requestBody
                )

                if (response.isSuccessful) {
                    fetchStoreInventory(token, currentSort, currentSortOrder, reset = true)
                } else {
                    _uiState.value =
                        ReleaseUiState.Error("Failed to edit. HTTP Code: ${response.code()}")
                }
            } catch (e: Exception) {
                _uiState.value = ReleaseUiState.Error("Network error: ${e.message}")
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
        Log.d("CREATE_LISTING", ">>> createListing CALLED with releaseId=$releaseId, price=$price, condition=$condition, sleeveCondition=$sleeveCondition, comments=$comments")

        viewModelScope.launch {
            try {
                _uiState.value = ReleaseUiState.StoreLoading

                val requestBody = CreateListingRequest(
                    release_id = releaseId,
                    condition = condition,
                    sleeve_condition = sleeveCondition,
                    price = price,
                    comments = comments,
                    status = "For Sale"
                )

                Log.d("CREATE_LISTING", "Sending request: $requestBody")

                val response = RetrofitClient.apiService.createListing(
                    authHeader = "Discogs token=$token",
                    request = requestBody
                )

                Log.d("CREATE_LISTING", "Response code: ${response.code()}")

                if (response.isSuccessful) {
                    Log.d("CREATE_LISTING", "Listing created successfully (201 expected)")
                    onSuccess()
                    fetchStoreInventory(token, currentSort, currentSortOrder, reset = true)
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e("CREATE_LISTING", "Error ${response.code()}: $errorBody")

                    _uiState.value = ReleaseUiState.Error(
                        "Failed to list item. HTTP Code: ${response.code()} – ${errorBody ?: "no details"}"
                    )
                }
            } catch (e: Exception) {
                Log.e("CREATE_LISTING", "Network or serialization error", e)
                _uiState.value = ReleaseUiState.Error("Network error: ${e.message}")
            }
        }
    }

    fun resetToIdle() {
        _uiState.value = ReleaseUiState.Idle
    }

    fun fetchOrders(token: String) {
        _uiState.value = ReleaseUiState.OrdersLoading

        val authHeader = "Discogs token=$token"

        RetrofitClient.apiService.getOrders(
            token = authHeader,
            status = "Payment Received",
            sortOrder = "asc"
        ).enqueue(object : retrofit2.Callback<DiscogsOrdersResponse> {
            override fun onResponse(
                call: retrofit2.Call<DiscogsOrdersResponse>,
                response: retrofit2.Response<DiscogsOrdersResponse>
            ) {
                if (response.isSuccessful) {
                    val ordersList = response.body()?.orders ?: emptyList()
                    _uiState.value = ReleaseUiState.OrdersSuccess(ordersList)
                } else {
                    _uiState.value =
                        ReleaseUiState.Error("Failed to load orders: ${response.code()}")
                }
            }

            override fun onFailure(call: retrofit2.Call<DiscogsOrdersResponse>, t: Throwable) {
                _uiState.value = ReleaseUiState.Error(t.message ?: "Unknown network error")
            }
        })
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

    fun fetchOrdersByStatus(status: String, token: String) {
        _uiState.value = ReleaseUiState.OrdersLoading
        val authHeader = "Discogs token=$token"

        val apiStatus = if (status == "All Orders") "All" else status

        RetrofitClient.apiService.getOrders(
            token = authHeader,
            status = apiStatus,
            sortOrder = "desc"
        ).enqueue(object : retrofit2.Callback<DiscogsOrdersResponse> {
            override fun onResponse(
                call: retrofit2.Call<DiscogsOrdersResponse>,
                response: retrofit2.Response<DiscogsOrdersResponse>
            ) {
                if (response.isSuccessful) {
                    val ordersList = response.body()?.orders ?: emptyList()
                    _uiState.value = ReleaseUiState.OrdersSuccess(ordersList)
                } else {
                    _uiState.value = ReleaseUiState.Error("Failed to load orders: ${response.code()}")
                }
            }

            override fun onFailure(call: retrofit2.Call<DiscogsOrdersResponse>, t: Throwable) {
                _uiState.value = ReleaseUiState.Error(t.message ?: "Unknown network error")
            }
        })
    }
}
