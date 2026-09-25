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


fun ReleaseViewModel.navigateToOrders(token: String) {
    invalidateNavigationRequests()
    _uiState.value = ReleaseUiState.OrdersLoading
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
    return true
}

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
                val current = _uiState.value as? ReleaseUiState.OrderDetails
                if (current?.order?.id != orderId) return@launch
                if (!restoreOrdersAfterUpdate(updatedOrder)) {
                    navigateToOrders(token)
                }
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

internal fun ReleaseViewModel.fetchOrdersPage(
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
