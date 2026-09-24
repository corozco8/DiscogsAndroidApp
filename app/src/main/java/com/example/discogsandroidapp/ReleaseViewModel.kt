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


class ReleaseViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {

    private var aiCacheRefreshJob: Job? = null
    private var aiCacheRefreshRequested = false


    internal val _uiState = MutableStateFlow<ReleaseUiState>(ReleaseUiState.Idle)
    val uiState: StateFlow<ReleaseUiState> = _uiState

    private val _profileUiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val profileUiState: StateFlow<ProfileUiState> = _profileUiState


    internal val _orderMessagesUiState =
        MutableStateFlow<OrderMessagesUiState>(
            OrderMessagesUiState.Idle
        )

    val orderMessagesUiState: StateFlow<OrderMessagesUiState> =
        _orderMessagesUiState

    private var currentUsername: String = ""
    internal var currentReleaseId: Long? = null   // Store the release ID for suggestions

    // Search and release-detail requests share navigation state. A slower old
    // request must never overwrite a screen the user opened afterward.
    internal var navigationRequestJob: Job? = null
    internal var navigationRequestGeneration: Long = 0L

    // Order-message state is shared by the visible order-detail screen, so
    // protect it with both cancellation and order/request identity checks.
    internal var orderMessagesJob: Job? = null
    internal var orderMessagesGeneration: Long = 0L
    internal var activeOrderMessagesOrderId: String? = null

    private var sellerInboxJob: Job? = null
    private var sellerInboxGeneration: Long = 0L

    internal var releaseBeforeMasterVersions:
            ReleaseUiState.ReleaseSuccess? = null

    internal fun invalidateNavigationRequests() {
        navigationRequestGeneration++
        navigationRequestJob?.cancel()
        navigationRequestJob = null
    }


    // ------------------------------------------------------------
    // Keep existing navigation & fetching methods unchanged
    // ------------------------------------------------------------

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

    private val localRepository = SellerLocalRepository(getApplication())
    private val storeController = StoreInventoryController(getApplication(), localRepository, viewModelScope)
    private val currentListings = mutableListOf<InventoryListing>()
    private var currentSort = "listed"
    private var currentSortOrder = "desc"
    private var currentStoreQuery = ""

    fun fetchStoreInventory(token: String, sort: String = "listed", sortOrder: String = "desc", reset: Boolean = true) {
        invalidateNavigationRequests()
        currentSort = sort
        currentSortOrder = sortOrder
        storeController.setQuery(currentStoreQuery, sort, sortOrder)
        if (_uiState.value !is ReleaseUiState.StoreSuccess) _uiState.value = ReleaseUiState.StoreLoading
        storeController.refresh(token, force = false)
    }

    suspend fun observeStore() {
            storeController.state.collect { state ->
                if (_uiState.value is ReleaseUiState.StoreLoading || _uiState.value is ReleaseUiState.StoreSuccess) {
                    if (state.ready && state.query == StoreQuery(currentStoreQuery, currentSort, currentSortOrder)) {
                        currentListings.clear()
                        currentListings.addAll(state.listings)
                        _uiState.value = ReleaseUiState.StoreSuccess(state.listings, state.listings.size, false, state.message)
                    } else if (!state.ready && state.error != null) {
                        _uiState.value = ReleaseUiState.Error(state.error)
                    }
                }
            }
    }

    fun setStoreQuery(query: String) {
        currentStoreQuery = query
        storeController.setQuery(query, currentSort, currentSortOrder)
    }
    fun searchStoreInventory(query: String, token: String) {
        currentStoreQuery = query.trim()
        fetchStoreInventory(token, currentSort, currentSortOrder)
    }
    fun clearStoreSearch(token: String) {
        currentStoreQuery = ""
        fetchStoreInventory(token, currentSort, currentSortOrder)
    }
    fun restoreStoreInventory(token: String) = fetchStoreInventory(token, currentSort, currentSortOrder)
    fun refreshStore() = storeController.refresh(BuildConfig.DISCOGS_TOKEN, force = true)
    fun loadNextPage(token: String) = Unit

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
                    localRepository.removeLocalListing(listingId)

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
                    localRepository.removeLocalListing(listingId)
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
                        localRepository.removeLocalListing(listingId)
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
                    localRepository.updateLocalListing(listingId, price, condition, sleeveCondition, comments)
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
                    storeController.refresh(token, force = true)
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

    internal val currentOrders =
        mutableListOf<DiscogsOrder>()

    internal var currentOrdersPage = 1
    internal var totalOrdersPages = 1
    internal var totalOrdersItems = 0
    internal var isFetchingMoreOrders = false
    var currentOrdersStatus = "Payment Received"
        internal set
    internal var currentOrdersSortOrder = "asc"
    internal var ordersFetchJob: Job? = null
    internal var ordersRequestGeneration = 0

    fun openRatings(username: String, ratingType: String) {
        invalidateNavigationRequests()
        _uiState.value = ReleaseUiState.RatingsWebView(username, ratingType)
    }

}
