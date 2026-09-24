package com.example.discogsandroidapp

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
    data class StoreSuccess(val listings: List<InventoryListing>, val totalItems: Int, val isFetchingMore: Boolean, val syncMessage: String = "") : ReleaseUiState
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

