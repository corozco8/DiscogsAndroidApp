package com.example.discogsandroidapp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- MARKETPLACE & PRICE SUMMARY MODELS ---

@Serializable
data class PriceValue(
    val value: Double? = 0.0,
    val currency: String? = "USD"
)

@Serializable
data class MarketplaceStatsResponse(
    @SerialName("lowest_price") val lowestPrice: PriceValue? = null,
    @SerialName("num_for_sale") val numForSale: Int? = 0,
    val blocked: Boolean? = false
)

@Serializable
data class PriceSuggestionValue(
    val value: Double? = 0.0,
    val currency: String? = "USD"
)

// ONLY ONE PriceSuggestions class now!
@Serializable
data class PriceSuggestions(
    @SerialName("Very Good (VG)") val veryGood: PriceSuggestionValue? = null,
    @SerialName("Very Good Plus (VG+)") val veryGoodPlus: PriceSuggestionValue? = null,
    @SerialName("Near Mint (NM or M-)") val nearMint: PriceSuggestionValue? = null,
    @SerialName("Mint (M)") val mint: PriceSuggestionValue? = null,
    @SerialName("Good Plus (G+)") val goodPlus: PriceSuggestionValue? = null,
    @SerialName("Good (G)") val good: PriceSuggestionValue? = null,
    @SerialName("Fair (F)") val fair: PriceSuggestionValue? = null,
    @SerialName("Poor (P)") val poor: PriceSuggestionValue? = null
) {
    // 1. Gather all non-null pricing values and sort them from lowest to highest
    private val allValues: List<Double>
        get() = listOfNotNull(
            poor?.value,
            fair?.value,
            good?.value,
            goodPlus?.value,
            veryGood?.value,
            veryGoodPlus?.value,
            nearMint?.value,
            mint?.value
        ).sorted()

    // 2. Grab the absolute minimum value
    val low: Double? get() = allValues.firstOrNull()

    // 3. Grab the absolute maximum value
    val high: Double? get() = allValues.lastOrNull()

    // 4. Calculate the true statistical median of the dataset
    val median: Double? get() {
        val values = allValues
        if (values.isEmpty()) return null

        val size = values.size
        return if (size % 2 != 0) {
            // Odd number of values: take the exact middle
            values[size / 2]
        } else {
            // Even number of values: average the two middle values
            (values[(size - 1) / 2] + values[size / 2]) / 2.0
        }
    }
}

data class ReleasePriceSummary(
    val low: Double? = null,
    val median: Double? = null,
    val high: Double? = null,
    val currency: String = "USD",
    val lastSold: String? = null,
    val numForSale: Int = 0,
    val lowestAskingPrice: Double? = null
)

// --- RELEASE DETAILS MODELS ---

@Serializable
data class ListingRelease(
    val id: Long = 0L,
    val description: String = "",
    val thumbnail: String = "",
    val title: String = "",
    val artist: String = ""
)

@Serializable
data class DiscogsRelease(
    val id: Long? = null,
    val title: String? = null,
    val year: Int? = null,
    val thumb: String? = null,
    val country: String? = null,
    val released: String? = null,
    @SerialName("last_sold") val last_sold: String? = null,
    @SerialName("price_suggestions") val price_suggestions: PriceSuggestions? = null,
    val genres: List<String>? = emptyList(),
    val styles: List<String>? = emptyList(),
    val images: List<DiscogsImage>? = emptyList(),
    val artists: List<DiscogsArtist>? = emptyList(),
    val labels: List<DiscogsLabel>? = emptyList(),
    val formats: List<DiscogsFormat>? = emptyList(),
    val tracklist: List<DiscogsTrack>? = emptyList()
)

@Serializable
data class DiscogsSearchResponse(
    val results: List<SearchResult>
)

@Serializable
data class SearchResult(
    val id: Int,
    val title: String,
    val type: String = "release",
    val year: String = "",
    val thumb: String? = null,
    val country: String? = null,
    val format: List<String>? = emptyList(),
    val catno: String? = null
)

@Serializable
data class DiscogsProfile(
    val username: String,
    @SerialName("avatar_url") val avatarUrl: String = "",
    @SerialName("seller_rating") val sellerRating: Double = 0.0,
    @SerialName("seller_num_ratings") val sellerNumRatings: Int = 0,
    @SerialName("buyer_rating") val buyerRating: Double = 0.0,
    @SerialName("buyer_num_ratings") val buyerNumRatings: Int = 0,
    val registered: String = "",
    val profile: String = ""
)

@Serializable
data class InventoryResponse(
    val pagination: Pagination,
    val listings: List<InventoryListing>
)

@Serializable
data class Pagination(
    val items: Int,
    val page: Int,
    val pages: Int,
    @SerialName("per_page") val perPage: Int
)

@Serializable
data class InventoryListing(
    val id: Long,
    val status: String,
    val condition: String,
    @SerialName("sleeve_condition") val sleeve_condition: String = "Not Graded",
    val comments: String = "",
    val price: Price? = null,
    val release: ListingRelease
)

@Serializable
data class Price(
    val value: Double,
    val currency: String
)

@Serializable
data class DiscogsImage(
    val uri: String? = null
)

@Serializable
data class DiscogsArtist(
    val name: String? = null
)

@Serializable
data class DiscogsLabel(
    val name: String? = null,
    val catno: String? = null
)

@Serializable
data class DiscogsFormat(
    val name: String? = null
)

@Serializable
data class DiscogsTrack(
    val position: String? = null,
    val title: String? = null,
    val duration: String? = null
)

// --- ORDERS & EVALUATIONS MODELS ---

@Serializable
data class DiscogsOrdersResponse(
    val orders: List<DiscogsOrder>? = emptyList(),
    val pagination: DiscogsPagination? = null
)

@Serializable
data class DiscogsOrder(
    val id: String? = null,
    val status: String? = null,
    val created: String? = null,
    val last_activity: String? = null,
    val buyer: BuyerInfo? = null,
    val total: OrderPrice? = null,
    val items: List<OrderItem>? = emptyList()
)

@Serializable
data class BuyerInfo(
    val id: Long? = null,
    val username: String? = null
)

@Serializable
data class OrderPrice(
    val value: Double? = null,
    val currency: String? = null
)

@Serializable
data class OrderItem(
    val id: Long? = null,
    val id_string: String? = null,
    val price: OrderPrice? = null,
    val release: OrderReleaseInfo? = null,
    val condition: String? = null,
    val media_condition: String? = null,
    val sleeve_condition: String? = null,
    val posted: String? = null,
    val date_added: String? = null
)

@Serializable
data class OrderReleaseInfo(
    val id: Long? = null,
    val title: String? = null,
    val description: String? = null,
    val thumbnail: String? = null
)

@Serializable
data class DiscogsPagination(
    val page: Int? = 1,
    val pages: Int? = 1,
    val per_page: Int? = 50,
    val items: Int? = 0
)

@Serializable
data class DiscogsEvaluationsResponse(
    val feedback: List<DiscogsEvaluation>? = emptyList(),
    val pagination: DiscogsPagination? = null
)

@Serializable
data class DiscogsEvaluation(
    val id: Long? = null,
    val rating: Int? = null,
    val comment: String? = null,
    val date: String? = null,
    val eval_from: EvaluationUser? = null,
    val role: String? = null
)

@Serializable
data class EvaluationUser(
    val username: String? = null
)