package com.example.discogsandroidapp.orders

import com.example.discogsandroidapp.data.AddOrderMessageRequest
import com.example.discogsandroidapp.data.DiscogsOrder
import com.example.discogsandroidapp.data.SellerLocalDatabase
import com.example.discogsandroidapp.network.RetrofitClient
import com.example.discogsandroidapp.pricing.isFresh
import com.example.discogsandroidapp.releases.OrderMessagesUiState
import com.example.discogsandroidapp.releases.ReleaseUiState
import com.example.discogsandroidapp.releases.ReleaseViewModel

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


fun ReleaseViewModel.navigateToOrders(token: String) {
    invalidateNavigationRequests()
    fetchOrders(token)
}

internal data class OrdersReturnState(
    val orderId: String?,
    val orders: List<DiscogsOrder>,
    val status: String,
    val sortOrder: String,
    val page: Int,
    val pages: Int,
    val total: Int
)

private fun ReleaseViewModel.restoreOrdersAfterUpdate(order: DiscogsOrder): Boolean {
    val saved = ordersReturnState?.takeIf { it.orderId == order.id } ?: return false
    ordersReturnState = null
    invalidateNavigationRequests()
    ordersRequestGeneration++
    ordersFetchJob?.cancel()
    isFetchingMoreOrders = false
    currentOrdersStatus = saved.status
    currentOrdersSortOrder = saved.sortOrder
    val removed = !matchesOrderStatus(order.status, saved.status)
    currentOrders.clear()
    currentOrders.addAll(saved.orders.mapNotNull {
        if (it.id != order.id) it else if (removed) null else order
    })
    // Removing a row shifts server page boundaries. Re-read the last page
    // before advancing, using the existing ID deduplication to avoid gaps.
    currentOrdersPage = if (removed && saved.page < saved.pages)
        (saved.page - 1).coerceAtLeast(0) else saved.page
    totalOrdersPages = saved.pages
    totalOrdersItems = (saved.total - if (removed) 1 else 0).coerceAtLeast(0)
    _uiState.value = ReleaseUiState.OrdersSuccess(
        orders = currentOrders.toList(),
        totalItems = totalOrdersItems,
        hasMore = currentOrdersPage < totalOrdersPages
    )
    cacheCurrentOrders()
    return true
}

private fun ReleaseViewModel.applyOrdersSnapshot(saved: SavedOrdersList) {
    currentOrders.clear()
    currentOrders.addAll(saved.orders)
    currentOrdersPage = saved.page
    totalOrdersPages = saved.pages
    totalOrdersItems = saved.total
}

private fun ReleaseViewModel.cacheCurrentOrders() {
    val key = activeOrdersCacheKey ?: return
    ordersCache.memory.put(SavedOrdersList(key, currentOrders.toList(), currentOrdersPage, totalOrdersPages, totalOrdersItems))
    viewModelScope.launch { ordersCache.persist() }
}

fun ReleaseViewModel.refreshOrders(token: String) = fetchOrdersPage(token, reset = true)

private suspend fun ReleaseViewModel.loadSavedOrderDetails(order: DiscogsOrder): DiscogsOrder {
    val dao = SellerLocalDatabase.getInstance(getApplication<android.app.Application>()).sellerDao()
    val cachedItems = order.id?.let { dao.getOrderItemsSnapshots(listOf(it)) }.orEmpty()
    val cachedByListing = cachedItems.filter { it.listingId != null }.associateBy { it.listingId }
    val cachedByKey = cachedItems.associateBy { it.itemKey }
    val inventory = dao.getInventorySnapshot().associateBy { it.listingId }
    val savedDetails = OrderItemDetailsCache(getApplication<android.app.Application>()).read(
        order.items.orEmpty().mapNotNull { it.id ?: it.id_string?.toLongOrNull() }
    )
    val updated = order.items.orEmpty().map { original ->
        val item = mergeSavedOrderItem(original, savedDetails[original.id ?: original.id_string?.toLongOrNull()])
        val id = item.id ?: item.id_string?.toLongOrNull()
        val cached = cachedByListing[id] ?: cachedByKey[item.id_string]
        val listing = inventory[id]
        val date = cached?.listedAtEpochMs ?: listing?.takeIf { it.listedDateIsExact }?.listedAtEpochMs
        val cachedDate = date?.takeIf { it > 0 }?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).format(java.util.Date(it))
        }
        item.copy(
            posted = item.posted?.takeIf { it.isNotBlank() },
            date_added = item.date_added?.takeIf { it.isNotBlank() } ?: cachedDate,
            comments = item.comments?.takeIf { it.isNotBlank() }
                ?: cached?.comments?.takeIf { it.isNotBlank() }
                ?: listing?.comments?.takeIf { it.isNotBlank() }
        )
    }
    return order.copy(items = updated)
}

internal suspend fun ReleaseViewModel.enrichOrderWithListingDates(
    order: DiscogsOrder,
    token: String,
    onPartial: (DiscogsOrder) -> Unit = {}
): DiscogsOrder = supervisorScope {
    val cache = OrderItemDetailsCache(getApplication<android.app.Application>())
    val updated = loadSavedOrderDetails(order).items.orEmpty().toMutableList()
    val saved = cache.read(updated.mapNotNull { it.id ?: it.id_string?.toLongOrNull() })
    onPartial(order.copy(items = updated.toList()))
    val permits = Semaphore(2)
    updated.toList().mapIndexed { index, item ->
        async {
            val id = item.id ?: item.id_string?.toLongOrNull()
            if (id == null || saved[id]?.isFresh(System.currentTimeMillis()) == true) return@async
            if ((!item.posted.isNullOrBlank() || !item.date_added.isNullOrBlank()) && !item.comments.isNullOrBlank()) {
                cache.save(SavedOrderItemDetails(id, item.posted, item.date_added, item.comments, System.currentTimeMillis()))
                return@async
            }
            permits.withPermit {
                try {
                    val listing = RetrofitClient.apiService.getMarketplaceListing(
                        listingId = id, authHeader = "Discogs token=$token"
                    )
                    updated[index] = item.copy(
                        posted = item.posted?.takeIf { it.isNotBlank() } ?: listing.posted?.takeIf { it.isNotBlank() },
                        date_added = item.date_added?.takeIf { it.isNotBlank() } ?: listing.dateAdded?.takeIf { it.isNotBlank() },
                        comments = item.comments?.takeIf { it.isNotBlank() } ?: listing.comments.takeIf { it.isNotBlank() }
                    )
                    // Publish each completed lookup; large orders need not wait
                    // for every listing before showing the first item's details.
                    onPartial(order.copy(items = updated.toList()))
                    val completed = updated[index]
                    cache.save(SavedOrderItemDetails(id, completed.posted, completed.date_added,
                        completed.comments, System.currentTimeMillis()))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("ORDER_DETAILS", "Could not load details for listing $id", e)
                }
            }
        }
    }.awaitAll()
    order.copy(items = updated.toList())
}

fun ReleaseViewModel.navigateToOrderDetails(
    order: DiscogsOrder,
    token: String
) {
    val previous = _uiState.value as? ReleaseUiState.OrdersSuccess
    ordersReturnState = previous?.let {
        OrdersReturnState(order.id, it.orders.toList(), currentOrdersStatus,
            currentOrdersSortOrder, currentOrdersPage, totalOrdersPages, totalOrdersItems)
    }
    if (previous != null) {
        ordersRequestGeneration++
        ordersFetchJob?.cancel()
        isFetchingMoreOrders = false
    }
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
                val savedOrder = loadSavedOrderDetails(order)
                val visible = _uiState.value as? ReleaseUiState.OrderDetails
                if (visible?.order?.id != orderId || visible.order.status != order.status) return@launch
                _uiState.value = ReleaseUiState.OrderDetails(savedOrder)
                val responseOrder =
                    RetrofitClient.apiService.getOrder(
                        orderId = orderId,
                        authHeader = "Discogs token=$token"
                    )

                val fullOrder = loadSavedOrderDetails(responseOrder)

                fun publishDetails(details: DiscogsOrder) {
                    val current = _uiState.value as? ReleaseUiState.OrderDetails
                    if (current?.order?.id == orderId && current.order.status == order.status) {
                        _uiState.value = ReleaseUiState.OrderDetails(details)
                    }
                }
                // Show full order comments immediately, before listing lookups.
                publishDetails(fullOrder)
                val enrichedOrder =
                    enrichOrderWithListingDates(
                        order = fullOrder,
                        token = token,
                        onPartial = { details ->
                            val current = _uiState.value as? ReleaseUiState.OrderDetails
                            if (current?.order?.id == orderId && current.order.status == fullOrder.status) {
                                _uiState.value = ReleaseUiState.OrderDetails(details)
                            }
                        }
                    )

                val current =
                    _uiState.value as? ReleaseUiState.OrderDetails

                if (current?.order?.id == orderId && current.order.status == fullOrder.status) {
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

fun ReleaseViewModel.updateOrderStatus(
    orderId: String,
    newStatus: String,
    token: String
) {
    if (orderId.isBlank() || newStatus.isBlank() || !orderStatusUpdateTracker.begin(orderId, newStatus)) return
    val previousOrderStatus = (_uiState.value as? ReleaseUiState.OrderDetails)?.order
        ?.takeIf { it.id == orderId }?.status

    viewModelScope.launch {
        var updateError: String? = null
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

            ordersCache.initialize()
            val account = ordersCacheKey(token, currentOrdersStatus, currentOrdersSortOrder).account
            ordersCache.memory.updateOrder(account, updatedOrder)
            viewModelScope.launch { ordersCache.persist() }
            if (_uiState.value is ReleaseUiState.OrdersSuccess && activeOrdersCacheKey?.account == account) {
                // The seller returned to the list while the write was pending.
                ordersRequestGeneration++
                ordersFetchJob?.cancel()
                activeOrdersCacheKey?.let { ordersCache.memory.get(it) }?.let {
                    applyOrdersSnapshot(it)
                    _uiState.value = it.screen()
                }
                refreshOrders(token)
            }

            if (!updatedOrder.status.equals(newStatus, ignoreCase = true)) {
                throw IllegalStateException("Discogs still reports ${updatedOrder.status ?: "an unknown status"}. Please check before retrying.")
            }

            if ((_uiState.value as? ReleaseUiState.OrderDetails)?.order?.id != orderId) return@launch
            if (shouldReturnToPaymentReceivedOrders(previousOrderStatus, newStatus, updatedOrder.status, ordersReturnState?.status) &&
                restoreOrdersAfterUpdate(updatedOrder)) return@launch

            val enrichedUpdatedOrder =
                enrichOrderWithListingDates(
                    order = updatedOrder,
                    token = token
                )

            if ((_uiState.value as? ReleaseUiState.OrderDetails)?.order?.id != orderId) return@launch

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(
                "ORDER_STATUS",
                "Failed to update order $orderId to $newStatus",
                e
            )

            updateError = "Couldn't set $newStatus. ${e.localizedMessage ?: "Please try again."}"
        } finally {
            orderStatusUpdateTracker.finish(orderId, updateError)
        }
    }
}

fun ReleaseViewModel.fetchOrders(token: String) {
    invalidateNavigationRequests()
    currentOrdersStatus = "Payment Received"
    currentOrdersSortOrder = "asc"

    fetchOrdersPage(
        token = token,
        reset = true
    )
}

fun ReleaseViewModel.fetchOrdersByStatus(
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

fun ReleaseViewModel.loadNextOrdersPage(token: String) {
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

internal fun ReleaseViewModel.fetchOrdersPage(token: String, reset: Boolean) {
    if (!reset && isFetchingMoreOrders) return
    val key = ordersCacheKey(token, currentOrdersStatus, currentOrdersSortOrder)
    if (reset && activeOrdersCacheKey == key && ordersFetchJob?.isActive == true &&
        (_uiState.value is ReleaseUiState.OrdersLoading ||
            (_uiState.value as? ReleaseUiState.OrdersSuccess)?.isRefreshing == true)) return

    var cached = if (reset) ordersCache.memory.get(key) else null
    if (reset) {
        ordersRequestGeneration++
        ordersFetchJob?.cancel()
        activeOrdersCacheKey = key
        if (cached != null) applyOrdersSnapshot(cached!!) else {
            currentOrders.clear()
            currentOrdersPage = 0
            totalOrdersPages = 1
            totalOrdersItems = 0
        }
        _uiState.value = cached?.screen(refreshing = true) ?: ReleaseUiState.OrdersLoading
    } else {
        _uiState.value = ReleaseUiState.OrdersSuccess(
            currentOrders.toList(), totalOrdersItems, isFetchingMore = true,
            hasMore = currentOrdersPage < totalOrdersPages
        )
    }
    // Also prevents pagination racing a first-page refresh.
    isFetchingMoreOrders = true
    val generation = ordersRequestGeneration
    val requestedPage = if (reset) 1 else currentOrdersPage + 1
    val requestedStatus = currentOrdersStatus
    val requestedSort = currentOrdersSortOrder
    fun stillShowingOrders() = generation == ordersRequestGeneration && activeOrdersCacheKey == key &&
        (_uiState.value is ReleaseUiState.OrdersLoading || _uiState.value is ReleaseUiState.OrdersSuccess)

    ordersFetchJob = viewModelScope.launch {
        try {
            // Network and a possible cold-cache read run together.
            val request = async {
                try {
                    Result.success(RetrofitClient.apiService.getOrders(
                        token = "Discogs token=$token", status = requestedStatus.takeUnless { it == "All" },
                        page = requestedPage, perPage = 100, sortOrder = requestedSort
                    ))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Result.failure(e) }
            }
            if (reset && cached == null) {
                ordersCache.initialize()
                if (!stillShowingOrders()) { request.cancel(); return@launch }
                cached = ordersCache.memory.get(key)
                cached?.let { applyOrdersSnapshot(it); _uiState.value = it.screen(refreshing = true) }
            }
            val response = request.await().getOrThrow()
            if (!stillShowingOrders()) return@launch

            val merged = mergeOrdersPage(if (reset) emptyList() else currentOrders,
                response.orders.orEmpty(), requestedStatus)
            currentOrders.clear()
            currentOrders.addAll(merged)
            currentOrdersPage = requestedPage
            totalOrdersPages = response.pagination?.pages?.coerceAtLeast(1) ?: 1
            totalOrdersItems = response.pagination?.items ?: currentOrders.size
            isFetchingMoreOrders = false
            cacheCurrentOrders()
            _uiState.value = ReleaseUiState.OrdersSuccess(
                currentOrders.toList(), totalOrdersItems, hasMore = currentOrdersPage < totalOrdersPages
            )
            if (currentOrders.isEmpty() && currentOrdersPage < totalOrdersPages) loadNextOrdersPage(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!stillShowingOrders()) return@launch
            isFetchingMoreOrders = false
            // Failed refreshes never replace an available list with an error screen.
            _uiState.value = ReleaseUiState.OrdersSuccess(
                currentOrders.toList(), totalOrdersItems,
                hasMore = currentOrdersPage < totalOrdersPages,
                refreshError = if (reset) "Couldn't refresh orders. Tap Retry."
                    else "Couldn't load more orders. Tap Retry."
            )
            Log.e("ORDER_PAGING", "Failed to load order page $requestedPage", e)
        }
    }
}
fun ReleaseViewModel.loadOrderMessages(
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

fun ReleaseViewModel.sendOrderMessage(
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
