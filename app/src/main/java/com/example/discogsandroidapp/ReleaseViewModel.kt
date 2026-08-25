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
    data class StoreSuccess(val listings: List<InventoryListing>, val totalItems: Int, val isFetchingMore: Boolean) : ReleaseUiState
    data class OrdersSuccess(val orders: List<DiscogsOrder>) : ReleaseUiState
    data class SearchSuccess(val results: List<SearchResult>) : ReleaseUiState
    data class ReleaseSuccess(
        val release: DiscogsRelease,
        val priceSummary: ReleasePriceSummary? = null
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

class ReleaseViewModel : ViewModel() {


    private val _uiState = MutableStateFlow<ReleaseUiState>(ReleaseUiState.Idle)
    val uiState: StateFlow<ReleaseUiState> = _uiState

    private val _profileUiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val profileUiState: StateFlow<ProfileUiState> = _profileUiState

    private var currentUsername: String = ""
    private var currentReleaseId: Long? = null   // Store the release ID for suggestions



    // ------------------------------------------------------------
    // Keep existing navigation & fetching methods unchanged
    // ------------------------------------------------------------

    fun navigateToOrders(token: String) {
        _uiState.value = ReleaseUiState.OrdersLoading
        fetchOrders(token)
    }

    fun navigateToOrderDetails(order: DiscogsOrder) {
        _uiState.value = ReleaseUiState.OrderDetails(order)
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
                    lowestAskingPrice = lowestPrice
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

    fun deleteListing(listingId: Long, token: String) {
        viewModelScope.launch {
            try {
                _uiState.value = ReleaseUiState.StoreLoading
                val response =
                    RetrofitClient.apiService.deleteListing(listingId, "Discogs token=$token")

                if (response.isSuccessful) {
                    fetchStoreInventory(token, currentSort, currentSortOrder, reset = true)
                } else {
                    _uiState.value =
                        ReleaseUiState.Error("Failed to delete. HTTP Code: ${response.code()}")
                }
            } catch (e: Exception) {
                _uiState.value = ReleaseUiState.Error("Network error: ${e.message}")
            }
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