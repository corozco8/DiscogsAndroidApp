package com.example.discogsandroidapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import android.util.Log


data class SellerConversationSummary(
    val order: DiscogsOrder,
    val latestMessage: DiscogsOrderMessage,
    val messageCount: Int
)

sealed interface ReleaseUiState {
    object Idle : ReleaseUiState
    object Loading : ReleaseUiState
    object StoreLoading : ReleaseUiState
    object OrdersLoading : ReleaseUiState
    data object SellerInboxLoading : ReleaseUiState
    data class SellerInboxSuccess(
        val conversations: List<SellerConversationSummary>
    ) : ReleaseUiState
    data class SellerInboxError(
        val message: String
    ) : ReleaseUiState
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
    object SalesAnalytics : ReleaseUiState
    object CustomerHistory : ReleaseUiState
    data class OrderDetails(val order: DiscogsOrder) : ReleaseUiState
    data class RatingsWebView(val username: String, val ratingType: String) : ReleaseUiState

    data class DiscogsWebView(
        val title: String,
        val url: String,
        val returnOrder: DiscogsOrder? = null,
        val returnToSellerInbox: Boolean = false,
        val hideNewOrderNotifications: Boolean = false
    ) : ReleaseUiState
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

    // Search and release-detail requests share navigation state. A slower old
    // request must never overwrite a screen the user opened afterward.
    private var navigationRequestJob: Job? = null
    private var navigationRequestGeneration: Long = 0L

    // Order-message state is shared by the visible order-detail screen, so
    // protect it with both cancellation and order/request identity checks.
    private var orderMessagesJob: Job? = null
    private var orderMessagesGeneration: Long = 0L
    private var activeOrderMessagesOrderId: String? = null

    private var sellerInboxJob: Job? = null
    private var sellerInboxGeneration: Long = 0L

    private var releaseBeforeMasterVersions:
            ReleaseUiState.ReleaseSuccess? = null

    private fun invalidateNavigationRequests() {
        navigationRequestGeneration++
        navigationRequestJob?.cancel()
        navigationRequestJob = null
    }


    // ------------------------------------------------------------
    // Keep existing navigation & fetching methods unchanged
    // ------------------------------------------------------------

    fun navigateToOrders(token: String) {
        invalidateNavigationRequests()
        _uiState.value = ReleaseUiState.OrdersLoading
        fetchOrders(token)
    }

    private suspend fun enrichOrderWithListingDates(
        order: DiscogsOrder,
        token: String
    ): DiscogsOrder = supervisorScope {
        val authHeader = "Discogs token=$token"

        val enrichedItems = order.items
            .orEmpty()
            .map { item ->
                async {
                    // Order payloads do not always include the original
                    // marketplace posted timestamp. Fetch the listing only
                    // when that date is missing.
                    if (
                        !item.posted.isNullOrBlank() ||
                        !item.date_added.isNullOrBlank() ||
                        item.id == null
                    ) {
                        return@async item
                    }

                    try {
                        val listing =
                            RetrofitClient.apiService.getMarketplaceListing(
                                listingId = item.id,
                                authHeader = authHeader
                            )

                        item.copy(
                            posted = listing.posted ?: item.posted,
                            date_added = listing.dateAdded ?: item.date_added
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(
                            "ORDER_DETAILS",
                            "Could not load listing date for listing ${item.id}",
                            e
                        )
                        item
                    }
                }
            }
            .awaitAll()

        order.copy(items = enrichedItems)
    }

    fun navigateToOrderDetails(
        order: DiscogsOrder,
        token: String
    ) {
        invalidateNavigationRequests()
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

                    val enrichedOrder =
                        enrichOrderWithListingDates(
                            order = fullOrder,
                            token = token
                        )

                    val current =
                        _uiState.value as? ReleaseUiState.OrderDetails

                    if (current?.order?.id == orderId) {
                        _uiState.value =
                            ReleaseUiState.OrderDetails(
                                enrichedOrder
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
        invalidateNavigationRequests()
        _uiState.value = ReleaseUiState.AiSearch
    }

    fun openSellerInbox(token: String) {
        invalidateNavigationRequests()
        sellerInboxGeneration++
        val generation = sellerInboxGeneration

        sellerInboxJob?.cancel()
        _uiState.value = ReleaseUiState.SellerInboxLoading

        sellerInboxJob = viewModelScope.launch {
            try {
                val authHeader = "Discogs token=$token"

                // Prefer orders with the newest activity so a buyer replying to
                // an older order can still rise to the top of the native inbox.
                val orderResponse =
                    try {
                        RetrofitClient.apiService.getOrders(
                            token = authHeader,
                            status = null,
                            page = 1,
                            perPage = 30,
                            sort = "last_activity",
                            sortOrder = "desc"
                        )
                    } catch (sortError: Exception) {
                        Log.w(
                            "SELLER_INBOX",
                            "last_activity sort unavailable; falling back to created",
                            sortError
                        )

                        RetrofitClient.apiService.getOrders(
                            token = authHeader,
                            status = null,
                            page = 1,
                            perPage = 30,
                            sort = "created",
                            sortOrder = "desc"
                        )
                    }

                val orders =
                    orderResponse.orders
                        .orEmpty()
                        .filter { !it.id.isNullOrBlank() }

                val requestLimiter = Semaphore(6)

                val conversations =
                    supervisorScope {
                        orders.map { order ->
                            async {
                                requestLimiter.withPermit {
                                    loadSellerConversation(
                                        order = order,
                                        authHeader = authHeader
                                    )
                                }
                            }
                        }.awaitAll()
                    }
                        .filterNotNull()
                        .sortedByDescending { conversation ->
                            conversation.latestMessage.timestamp
                                ?: conversation.order.lastActivity
                                ?: conversation.order.created
                                ?: ""
                        }

                if (
                    generation != sellerInboxGeneration ||
                    _uiState.value !is ReleaseUiState.SellerInboxLoading
                ) {
                    return@launch
                }

                _uiState.value =
                    ReleaseUiState.SellerInboxSuccess(
                        conversations = conversations
                    )

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != sellerInboxGeneration) {
                    return@launch
                }

                if (_uiState.value is ReleaseUiState.SellerInboxLoading) {
                    _uiState.value =
                        ReleaseUiState.SellerInboxError(
                            e.localizedMessage
                                ?: "Failed to load seller messages"
                        )
                }

                Log.e(
                    "SELLER_INBOX",
                    "Failed to load seller inbox",
                    e
                )
            }
        }
    }

    private suspend fun loadSellerConversation(
        order: DiscogsOrder,
        authHeader: String
    ): SellerConversationSummary? {
        val orderId = order.id ?: return null

        return try {
            val firstPage =
                RetrofitClient.apiService.getOrderMessages(
                    orderId = orderId,
                    authHeader = authHeader,
                    page = 1,
                    perPage = 100
                )

            val messages = firstPage.messages.toMutableList()
            val pages = firstPage.pagination?.pages ?: 1

            if (pages > 1) {
                for (page in 2..pages) {
                    messages +=
                        RetrofitClient.apiService.getOrderMessages(
                            orderId = orderId,
                            authHeader = authHeader,
                            page = page,
                            perPage = 100
                        ).messages
                }
            }

            val visibleMessages =
                messages.filterNot { message ->
                    isRedundantNewOrderMessage(message)
                }

            val latest =
                visibleMessages.maxByOrNull { message ->
                    message.timestamp.orEmpty()
                }
                    ?: return null

            SellerConversationSummary(
                order = order,
                latestMessage = latest,
                messageCount = visibleMessages.size
            )

        } catch (e: Exception) {
            // One broken/old order should not prevent the rest of the inbox
            // from loading. It simply will not appear in this refresh.
            Log.w(
                "SELLER_INBOX",
                "Skipping messages for order $orderId",
                e
            )
            null
        }
    }

    private fun isRedundantNewOrderMessage(
        message: DiscogsOrderMessage
    ): Boolean {
        val candidates =
            listOf(
                message.subject,
                message.type,
                message.message
            )

        return candidates.any { value ->
            value
                ?.trim()
                ?.startsWith(
                    prefix = "New Order",
                    ignoreCase = true
                ) == true
        }
    }

    fun openDiscogsInbox(
        returnToSellerInbox: Boolean = false
    ) {
        invalidateNavigationRequests()
        _uiState.value =
            ReleaseUiState.DiscogsWebView(
                title = "Private Inbox",
                url = "https://www.discogs.com/messages",
                returnToSellerInbox = returnToSellerInbox,
                hideNewOrderNotifications = true
            )
    }

    fun openBuyerFeedback(
        order: DiscogsOrder
    ) {
        invalidateNavigationRequests()
        val orderId = order.id ?: return

        _uiState.value =
            ReleaseUiState.DiscogsWebView(
                title = "Buyer Feedback",
                url = "https://www.discogs.com/sell/order/$orderId",
                returnOrder = order
            )
    }

    fun closeDiscogsWeb(
        token: String
    ) {
        val webState =
            _uiState.value as? ReleaseUiState.DiscogsWebView

        val returnOrder =
            webState?.returnOrder

        if (returnOrder != null) {
            navigateToOrderDetails(
                order = returnOrder,
                token = token
            )
        } else if (webState?.returnToSellerInbox == true) {
            openSellerInbox(token)
        } else {
            resetToIdle()
        }
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

                if (newStatus.equals("In Progress", ignoreCase = true) &&
                    updatedOrder.status.equals("In Progress", ignoreCase = true)) {
                    fetchOrdersByStatus("In Progress", token)
                    return@launch
                }

                val enrichedUpdatedOrder =
                    enrichOrderWithListingDates(
                        order = updatedOrder,
                        token = token
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
                        enrichedUpdatedOrder
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
        invalidateNavigationRequests()
        _uiState.value = ReleaseUiState.Inventory
    }

    fun navigateToOffers() {
        invalidateNavigationRequests()
        _uiState.value = ReleaseUiState.DiscogsWebView(
            title = "My Offers",
            url = "https://www.discogs.com/sell/orders"
        )
    }

    fun navigateToSalesAnalytics() {
        invalidateNavigationRequests()
        _uiState.value = ReleaseUiState.SalesAnalytics
    }

    fun navigateToCustomerHistory() {
        invalidateNavigationRequests()
        _uiState.value = ReleaseUiState.CustomerHistory
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
    private var currentStoreTotalItems = 0
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
        invalidateNavigationRequests()
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
                    currentStoreTotalItems
                        .takeIf { it > 0 }
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
                currentStoreTotalItems = response.pagination.items

                _uiState.value =
                    ReleaseUiState.StoreSuccess(
                        listings = currentListings.toList(),
                        totalItems = currentStoreTotalItems,
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
                                currentStoreTotalItems
                                    .takeIf { it > 0 }
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

    /**
     * Return to My Store without throwing away the current inventory session.
     *
     * Release details temporarily replaces [uiState], but the ViewModel still
     * owns the already-loaded inventory pages and active sort. Re-publishing
     * that snapshot lets Compose restore the hoisted LazyListState exactly
     * where the seller left it.
     */
    fun restoreStoreInventory(token: String) {
        invalidateNavigationRequests()
        storeFetchJob?.cancel()
        isFetchingNextPage = false

        if (currentListings.isNotEmpty()) {
            _uiState.value =
                ReleaseUiState.StoreSuccess(
                    listings = currentListings.toList(),
                    totalItems =
                        currentStoreTotalItems
                            .takeIf { it > 0 }
                            ?: currentListings.size,
                    isFetchingMore = false
                )
        } else {
            // A process recreation can leave no in-memory snapshot. In that
            // case, reload using the last selected sort instead of defaults.
            fetchStoreInventory(
                token = token,
                sort = currentSort,
                sortOrder = currentSortOrder,
                reset = true
            )
        }
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

        navigationRequestGeneration++
        val generation = navigationRequestGeneration
        navigationRequestJob?.cancel()

        navigationRequestJob = viewModelScope.launch {
            _uiState.value = ReleaseUiState.Loading

            try {
                val authHeader = "Discogs token=$token"
                val response =
                    RetrofitClient.apiService.searchDatabase(
                        query = query,
                        authHeader = authHeader
                    )

                if (generation == navigationRequestGeneration) {
                    _uiState.value =
                        ReleaseUiState.SearchSuccess(response.results)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == navigationRequestGeneration) {
                    _uiState.value =
                        ReleaseUiState.Error(
                            e.localizedMessage ?: "Search failed"
                        )
                }
            }
        }
    }

    fun fetchRelease(releaseId: Long, token: String) {
        navigationRequestGeneration++
        val generation = navigationRequestGeneration
        navigationRequestJob?.cancel()

        navigationRequestJob = viewModelScope.launch {
            _uiState.value = ReleaseUiState.Loading

            try {
                val authHeader = "Discogs token=$token"
                currentReleaseId = releaseId

                val releaseResponse = RetrofitClient.apiService.getRelease(
                    releaseId = releaseId,
                    authHeader = authHeader
                )

                if (generation != navigationRequestGeneration) {
                    return@launch
                }

                // Show Release Details immediately. Its marketplace WebView can
                // begin warming while stats and suggestions load in parallel.
                _uiState.value = ReleaseUiState.ReleaseSuccess(
                    release = releaseResponse,
                    // Publish the format immediately, before optional stats
                    // and price suggestions finish loading.
                    priceSummary = ReleasePriceSummary(
                        isAlbumRelease = releaseResponse.isAlbumFormat()
                    )
                )

                data class StatsResult(
                    val numForSale: Int,
                    val lowestPrice: Double?,
                    val error: String? = null
                )

                val (statsResult, suggestionsResult) = supervisorScope {
                    val statsDeferred = async {
                        try {
                            val stats = RetrofitClient.apiService.getMarketplaceStats(
                                releaseId = releaseId,
                                authHeader = authHeader
                            )
                            StatsResult(
                                numForSale = stats.numForSale ?: 0,
                                lowestPrice = stats.lowestPrice?.value
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("DiscogsDebug", "Marketplace Stats Failed!", e)
                            StatsResult(
                                numForSale = 0,
                                lowestPrice = null,
                                error = "StatsErr: ${e.javaClass.simpleName}"
                            )
                        }
                    }

                    val suggestionsDeferred = async {
                        try {
                            Pair(
                                RetrofitClient.apiService.getPriceSuggestions(
                                    releaseId = releaseId,
                                    authHeader = authHeader
                                ),
                                null as String?
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("DiscogsDebug", "Price Suggestions Failed!", e)
                            Pair(
                                null,
                                "PriceErr: ${e.javaClass.simpleName}"
                            )
                        }
                    }

                    statsDeferred.await() to suggestionsDeferred.await()
                }

                if (generation != navigationRequestGeneration) {
                    return@launch
                }

                val suggestions = suggestionsResult.first
                val debugMsg =
                    listOfNotNull(
                        statsResult.error,
                        suggestionsResult.second
                    ).takeIf { it.isNotEmpty() }
                        ?.joinToString(" | ")
                        ?: "No Data"

                val summary = ReleasePriceSummary(
                    low = suggestions?.low,
                    median = suggestions?.median,
                    high = suggestions?.high,
                    currency = "USD",
                    lastSold = debugMsg,
                    numForSale = statsResult.numForSale,
                    lowestAskingPrice = statsResult.lowestPrice,
                    priceSuggestions = suggestions,
                    isAlbumRelease = releaseResponse.isAlbumFormat()
                )

                _uiState.value = ReleaseUiState.ReleaseSuccess(
                    release = releaseResponse,
                    priceSummary = summary
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == navigationRequestGeneration) {
                    _uiState.value = ReleaseUiState.Error(
                        e.localizedMessage ?: "Failed to fetch details"
                    )
                }
            }
        }
    }

    fun fetchMasterVersions(
        masterId: Long,
        token: String
    ) {
        invalidateNavigationRequests()
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
                    val revisionAtStart =
                        AiCacheSyncTracker.captureRevision()

                    BackendRetrofitClient.apiService
                        .syncInventory()

                    AiCacheSyncTracker
                        .markFullSyncComplete(revisionAtStart)

                    // A mutation that happened during the download remains
                    // dirty because it has a newer revision. Ensure it gets a
                    // follow-up refresh even if a caller forgot to set the
                    // local requested flag.
                    if (AiCacheSyncTracker.needsFullSync()) {
                        aiCacheRefreshRequested = true
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
                val revisionAtStart =
                    AiCacheSyncTracker.captureRevision()

                BackendRetrofitClient.apiService
                    .syncInventory()

                AiCacheSyncTracker
                    .markFullSyncComplete(revisionAtStart)

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
        invalidateNavigationRequests()
        _uiState.value = ReleaseUiState.Idle
    }

    private val currentOrders =
        mutableListOf<DiscogsOrder>()

    private var currentOrdersPage = 1
    private var totalOrdersPages = 1
    private var totalOrdersItems = 0
    private var isFetchingMoreOrders = false
    var currentOrdersStatus = "Payment Received"
        private set
    private var currentOrdersSortOrder = "asc"
    private var ordersFetchJob: Job? = null
    private var ordersRequestGeneration = 0

    fun fetchOrders(token: String) {
        invalidateNavigationRequests()
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
        invalidateNavigationRequests()
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

        val requestedStatus = currentOrdersStatus

        ordersFetchJob = viewModelScope.launch {
            try {
                val response =
                    RetrofitClient.apiService.getOrders(
                        token = "Discogs token=$token",
                        status = requestedStatus.takeUnless { it == "All" },
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
                    .filter { matchesOrderStatus(it.status, requestedStatus) }
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

                if (currentOrders.isEmpty() && currentOrdersPage < totalOrdersPages) {
                    loadNextOrdersPage(token)
                }

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
        orderMessagesGeneration++
        val generation = orderMessagesGeneration
        activeOrderMessagesOrderId = orderId
        orderMessagesJob?.cancel()

        orderMessagesJob = viewModelScope.launch {
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

                if (
                    generation != orderMessagesGeneration ||
                    activeOrderMessagesOrderId != orderId
                ) {
                    return@launch
                }

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

                        if (
                            generation != orderMessagesGeneration ||
                            activeOrderMessagesOrderId != orderId
                        ) {
                            return@launch
                        }

                        allMessages.addAll(
                            response.messages
                        )
                    }
                }

                if (
                    generation == orderMessagesGeneration &&
                    activeOrderMessagesOrderId == orderId
                ) {
                    _orderMessagesUiState.value =
                        OrderMessagesUiState.Success(
                            messages = allMessages
                        )
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(
                    "ORDER_MESSAGES",
                    "Failed to load order messages",
                    e
                )

                if (
                    generation == orderMessagesGeneration &&
                    activeOrderMessagesOrderId == orderId
                ) {
                    _orderMessagesUiState.value =
                        OrderMessagesUiState.Error(
                            e.localizedMessage
                                ?: "Failed to load messages"
                        )
                }
            }
        }
    }

    fun sendOrderMessage(
        orderId: String,
        message: String,
        token: String,
        onResult: (Boolean) -> Unit = {}
    ) {
        val cleanMessage = message.trim()

        if (cleanMessage.isEmpty()) {
            onResult(false)
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

                onResult(true)

                // Only refresh the conversation if this order is still the
                // one represented by the shared message state.
                if (activeOrderMessagesOrderId == orderId) {
                    loadOrderMessages(
                        orderId = orderId,
                        token = token
                    )
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(
                    "ORDER_MESSAGES",
                    "Failed to send order message",
                    e
                )

                if (activeOrderMessagesOrderId == orderId) {
                    _orderMessagesUiState.value =
                        OrderMessagesUiState.Error(
                            e.localizedMessage
                                ?: "Failed to send message"
                        )
                }

                onResult(false)
            }
        }
    }

    fun openRatings(username: String, ratingType: String) {
        invalidateNavigationRequests()
        _uiState.value = ReleaseUiState.RatingsWebView(username, ratingType)
    }

}
