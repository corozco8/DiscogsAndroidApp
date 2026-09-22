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

    fun forCondition(
        condition: String
    ): PriceSuggestionValue? {
        return when (condition) {
            "Mint (M)" -> mint
            "Near Mint (NM or M-)" -> nearMint
            "Very Good Plus (VG+)" -> veryGoodPlus
            "Very Good (VG)" -> veryGood
            "Good Plus (G+)" -> goodPlus
            "Good (G)" -> good
            "Fair (F)" -> fair
            "Poor (P)" -> poor
            else -> null
        }
    }
}

private const val MIN_TRUSTED_LIVE_LISTINGS = 2

data class ReleasePriceSummary(
    val low: Double? = null,
    val median: Double? = null,
    val high: Double? = null,
    val currency: String = "USD",
    val lastSold: String? = null,
    val numForSale: Int = 0,
    val lowestAskingPrice: Double? = null,
    val priceSuggestions: PriceSuggestions? = null,
    val activeNearMintPrice: Double? = null,
    val activeNearMintPremiumSleevePrice: Double? = null,
    val activeMintPrice: Double? = null,
    val activeMintSleevePrice: Double? = null,

    // Lowest current asking price from live Discogs marketplace listings,
    // keyed by the exact Goldmine media condition string.
    val activeMediaLowestPrices: Map<String, Double> = emptyMap(),

    // Lowest current asking price for an exact media + sleeve combination.
    // Key format is: "<media condition>||<sleeve condition>"
    val activeMediaSleeveLowestPrices: Map<String, Double> = emptyMap(),

    // Number of qualifying USD marketplace rows seen for each media grade.
    // Live pricing is only trusted when at least two comparable listings exist.
    val activeMediaListingCounts: Map<String, Int> = emptyMap(),

    // Number of USD marketplace rows seen for each exact media+sleeve pair.
    val activeMediaSleeveListingCounts: Map<String, Int> = emptyMap(),

    // Discogs release format: only an explicitly tagged Album uses our
    // sleeve-quality exclusions. DJ singles / 12-inch singles / EPs may
    // normally ship in a generic sleeve or without a picture cover.
    val isAlbumRelease: Boolean = true
) {
    /**
     * Lowest live asking price for the selected media grade.
     *
     * Normal recommendation rules:
     * - M / NM: ignore marketplace copies with G+ or worse sleeves.
     * - VG+ / VG: ignore marketplace copies with G or worse sleeves.
     * - G+ and below: no sleeve-quality filter.
     *
     * If the seller intentionally selects a sleeve that would normally be
     * rejected (for example NM media with an F sleeve), the special filter is
     * disabled for that task and the raw lowest price for the media grade is
     * used instead.
     */
    fun currentListingPriceFor(
        condition: String,
        sleeveCondition: String? = null
    ): Double? = comparableListingPriceFor(condition, condition, sleeveCondition)

    // Use the TARGET grade's sleeve policy even when sourcing another media grade.
    private fun comparableListingPriceFor(
        condition: String,
        targetCondition: String,
        sleeveCondition: String?
    ): Double? {
        val minimumSleeveRank =
            minimumRecommendedSleeveRankFor(targetCondition)

        fun rawMediaPrice(): Double? {
            val comparableCount = activeMediaListingCounts[condition] ?: 0
            if (comparableCount < MIN_TRUSTED_LIVE_LISTINGS) return null

            return activeMediaLowestPrices[condition]
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?.let(::roundPrice)
        }

        // Non-albums are compared on media grade alone. The Discogs format
        // designation, not record size or RPM, controls this exception.
        // Keep the two-comparable USD minimum and existing fallback behavior.
        if (!isAlbumRelease || minimumSleeveRank == null) {
            return rawMediaPrice()
        }

        /*
         * If the seller deliberately chooses a sleeve below the normal
         * threshold (or No Cover / Not Graded / Generic), disable the special
         * sleeve filter for this one task. We still require two USD comparables
         * before calling the live number trustworthy.
         */
        if (!sleeveCondition.isNullOrBlank()) {
            val selectedSleeveRank = sleeveGradeRank(sleeveCondition)
            if (
                selectedSleeveRank == null ||
                selectedSleeveRank < minimumSleeveRank
            ) {
                return rawMediaPrice()
            }
        }

        val prefix = "$condition||"
        var qualifyingCount = 0
        var lowestQualifyingPrice: Double? = null

        activeMediaSleeveLowestPrices.forEach { (key, value) ->
            if (!key.startsWith(prefix)) return@forEach

            val sleeve = key.removePrefix(prefix)
            val sleeveRank = sleeveGradeRank(sleeve) ?: return@forEach
            if (sleeveRank < minimumSleeveRank) return@forEach
            if (!value.isFinite() || value <= 0.0) return@forEach

            qualifyingCount += activeMediaSleeveListingCounts[key] ?: 0
            if (lowestQualifyingPrice == null || value < lowestQualifyingPrice!!) {
                lowestQualifyingPrice = value
            }
        }

        if (qualifyingCount < MIN_TRUSTED_LIVE_LISTINGS) return null

        return lowestQualifyingPrice?.let(::roundPrice)
    }

    /** Estimate only from observed NM/VG+/VG listings, never from another estimate. */
    fun liveGradeEstimateFor(condition: String, sleeveCondition: String? = null): LiveGradeEstimate? {
        val grades = listOf("Near Mint (NM or M-)", "Very Good Plus (VG+)", "Very Good (VG)")
        val ratios = listOf(1.0, 0.5, 0.25)
        val target = grades.indexOf(condition)
        if (target < 0 || currentListingPriceFor(condition, sleeveCondition) != null) return null
        // Closest grade first; prefer the better grade when equally close.
        for (source in grades.indices.filter { it != target }.sortedBy { kotlin.math.abs(it - target) }) {
            val sourcePrice = comparableListingPriceFor(grades[source], condition, sleeveCondition) ?: continue
            val estimate = roundPrice(sourcePrice * ratios[target] / ratios[source])
            if (!estimate.isFinite() || estimate <= 0.0) continue
            return LiveGradeEstimate(estimate, grades[source], sourcePrice)
        }
        return null
    }

    /**
     * The app's original seller-pricing algorithm. This is the silent
     * fallback whenever a live marketplace price cannot be obtained.
     */
    fun fallbackRecommendedPriceFor(
        condition: String,
        sleeveCondition: String? = null
    ): Double? {
        val vgPlusAnchor =
            originalRecommendationFor(
                "Very Good Plus (VG+)"
            )

        if (
            vgPlusAnchor != null &&
            vgPlusAnchor > 0.0
        ) {
            when (condition) {
                "Very Good Plus (VG+)" -> {
                    val multiplier =
                        if (
                            sleeveCondition ==
                            "Very Good Plus (VG+)" ||
                            sleeveCondition ==
                            "Near Mint (NM or M-)" ||
                            sleeveCondition ==
                            "Mint (M)"
                        ) {
                            1.15
                        } else {
                            1.0
                        }

                    return roundPrice(
                        vgPlusAnchor * multiplier
                    )
                }

                "Near Mint (NM or M-)" -> {
                    val multiplier =
                        if (
                            sleeveCondition ==
                            "Near Mint (NM or M-)" ||
                            sleeveCondition ==
                            "Mint (M)"
                        ) {
                            2.5
                        } else {
                            2.0
                        }

                    return roundPrice(
                        vgPlusAnchor * multiplier
                    )
                }

                "Mint (M)" -> {
                    val multiplier =
                        if (
                            sleeveCondition ==
                            "Mint (M)"
                        ) {
                            5.0
                        } else {
                            4.0
                        }

                    return roundPrice(
                        vgPlusAnchor * multiplier
                    )
                }
            }
        }

        return originalRecommendationFor(
            condition
        )
    }

    /**
     * Prefer matching listings, then an estimate from another live grade,
     * then the original seller-pricing algorithm.
     */
    fun recommendedPriceFor(
        condition: String,
        sleeveCondition: String? = null
    ): Double? {
        return currentListingPriceFor(
            condition = condition,
            sleeveCondition = sleeveCondition
        )
            ?: liveGradeEstimateFor(condition, sleeveCondition)?.price
            ?: fallbackRecommendedPriceFor(
                condition = condition,
                sleeveCondition = sleeveCondition
            )
    }

    /**
     * Minimum sleeve grade used when choosing a comparable current listing.
     * Null means this media grade has no special sleeve-quality filter.
     */
    fun minimumComparableSleeveFor(
        mediaCondition: String
    ): String? {
        if (!isAlbumRelease) return null

        return when (mediaCondition) {
            "Mint (M)",
            "Near Mint (NM or M-)" -> "Very Good (VG)"

            "Very Good Plus (VG+)",
            "Very Good (VG)" -> "Good Plus (G+)"

            else -> null
        }
    }

    /**
     * True when the normal sleeve-quality comparison rule is being applied.
     * Selecting a sleeve below the normal threshold intentionally disables
     * the rule for that one listing task.
     */
    fun isSleeveQualityFilterActive(
        mediaCondition: String,
        selectedSleeveCondition: String?
    ): Boolean {
        val minimum = minimumRecommendedSleeveRankFor(mediaCondition)
            ?: return false

        if (selectedSleeveCondition.isNullOrBlank()) {
            return true
        }

        val selectedRank = sleeveGradeRank(selectedSleeveCondition)
            ?: return false

        return selectedRank >= minimum
    }

    private fun minimumRecommendedSleeveRankFor(
        mediaCondition: String
    ): Int? {
        return minimumComparableSleeveFor(mediaCondition)
            ?.let(::sleeveGradeRank)
    }

    private fun sleeveGradeRank(
        sleeveCondition: String
    ): Int? {
        return when (sleeveCondition) {
            "Poor (P)" -> 0
            "Fair (F)" -> 1
            "Good (G)" -> 2
            "Good Plus (G+)" -> 3
            "Very Good (VG)" -> 4
            "Very Good Plus (VG+)" -> 5
            "Near Mint (NM or M-)" -> 6
            "Mint (M)" -> 7
            else -> null
        }
    }


    private fun originalRecommendationFor(
        condition: String
    ): Double? {
        val suggestions = priceSuggestions ?: return null
        val targetRank = conditionRank(condition) ?: return null
        val points = suggestions.conditionPricePoints()

        val directPrice =
            suggestions
                .forCondition(condition)
                ?.value
                ?.takeIf { it.isFinite() && it > 0.0 }

        // When the exact grade is absent, estimate it from the neighboring
        // Discogs condition suggestions instead of abandoning the fallback.
        val curvePrice =
            predictConditionPrice(
                points = points,
                targetRank = targetRank
            )
                ?.takeIf { it.isFinite() && it > 0.0 }

        val historicalEstimate =
            when {
                directPrice != null && curvePrice != null -> {
                    val ratio = directPrice / curvePrice
                    val directWeight =
                        if (ratio < 0.55 || ratio > 1.80) 0.35 else 0.75

                    geometricBlend(
                        first = directPrice,
                        second = curvePrice,
                        firstWeight = directWeight
                    )
                }

                directPrice != null -> directPrice
                curvePrice != null -> curvePrice
                else -> return null
            }

        val currentLow = lowestAskingPrice?.takeIf { it.isFinite() && it > 0.0 }
        if (currentLow == null) return roundPrice(historicalEstimate)

        val marketWeight =
            when {
                numForSale >= 25 -> 0.55
                numForSale >= 10 -> 0.50
                numForSale >= 5 -> 0.40
                numForSale >= 2 -> 0.30
                else -> 0.20
            }

        val boundedMarketLow =
            currentLow.coerceIn(
                historicalEstimate * 0.10,
                historicalEstimate * 3.00
            )

        return roundPrice(
            geometricBlend(
                first = historicalEstimate,
                second = boundedMarketLow,
                firstWeight = 1.0 - marketWeight
            )
        )
    }

    private fun conditionRank(
        condition: String
    ): Int? {
        return when (condition) {
            "Poor (P)" -> 0
            "Fair (F)" -> 1
            "Good (G)" -> 2
            "Good Plus (G+)" -> 3
            "Very Good (VG)" -> 4
            "Very Good Plus (VG+)" -> 5
            "Near Mint (NM or M-)" -> 6
            "Mint (M)" -> 7
            else -> null
        }
    }

    private fun PriceSuggestions.conditionPricePoints():
            List<Pair<Int, Double>> {

        return listOfNotNull(
            poor?.value
                ?.takeIf { it > 0.0 }
                ?.let { 0 to it },

            fair?.value
                ?.takeIf { it > 0.0 }
                ?.let { 1 to it },

            good?.value
                ?.takeIf { it > 0.0 }
                ?.let { 2 to it },

            goodPlus?.value
                ?.takeIf { it > 0.0 }
                ?.let { 3 to it },

            veryGood?.value
                ?.takeIf { it > 0.0 }
                ?.let { 4 to it },

            veryGoodPlus?.value
                ?.takeIf { it > 0.0 }
                ?.let { 5 to it },

            nearMint?.value
                ?.takeIf { it > 0.0 }
                ?.let { 6 to it },

            mint?.value
                ?.takeIf { it > 0.0 }
                ?.let { 7 to it }
        )
    }

    /**
     * Fits a simple straight line to log(price) across all available
     * condition grades. Predicting in log space makes the curve resistant
     * to the huge dollar spreads common with collectible records.
     */
    private fun predictConditionPrice(
        points: List<Pair<Int, Double>>,
        targetRank: Int
    ): Double? {
        if (points.size < 2) {
            return null
        }

        val xs =
            points.map { it.first.toDouble() }

        val ys =
            points.map {
                kotlin.math.ln(
                    it.second
                )
            }

        val meanX =
            xs.average()

        val meanY =
            ys.average()

        var numerator = 0.0
        var denominator = 0.0

        for (index in xs.indices) {
            val dx =
                xs[index] - meanX

            numerator +=
                dx * (ys[index] - meanY)

            denominator +=
                dx * dx
        }

        if (denominator == 0.0) {
            return null
        }

        val slope =
            numerator / denominator

        val intercept =
            meanY - slope * meanX

        return kotlin.math.exp(
            intercept +
                    slope * targetRank.toDouble()
        )
    }

    private fun geometricBlend(
        first: Double,
        second: Double,
        firstWeight: Double
    ): Double {
        val safeWeight =
            firstWeight.coerceIn(
                0.0,
                1.0
            )

        val secondWeight =
            1.0 - safeWeight

        return kotlin.math.exp(
            safeWeight *
                    kotlin.math.ln(first) +
                    secondWeight *
                    kotlin.math.ln(second)
        )
    }

    private fun roundPrice(
        value: Double
    ): Double {
        return kotlin.math.round(
            value * 100.0
        ) / 100.0
    }
}

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
data class DiscogsCommunity(
    val have: Int = 0,
    val want: Int = 0
)

@Serializable
data class DiscogsRelease(
    val id: Long? = null,
    val title: String? = null,
    val year: Int? = null,
    val thumb: String? = null,
    val country: String? = null,
    val released: String? = null,
    val community: DiscogsCommunity? = null,

    @SerialName("master_id")
    val masterId: Long? = null,

    val notes: String? = null,

    @SerialName("last_sold")
    val last_sold: String? = null,

    @SerialName("price_suggestions")
    val price_suggestions: PriceSuggestions? = null,

    val genres: List<String>? = emptyList(),
    val styles: List<String>? = emptyList(),
    val images: List<DiscogsImage>? = emptyList(),
    val artists: List<DiscogsArtist>? = emptyList(),
    val labels: List<DiscogsLabel>? = emptyList(),
    val formats: List<DiscogsFormat>? = emptyList(),
    val tracklist: List<DiscogsTrack>? = emptyList(),

    val companies: List<DiscogsCompany>? = emptyList(),

    @SerialName("extraartists")
    val extraArtists: List<DiscogsCredit>? = emptyList(),

    val identifiers: List<DiscogsIdentifier>? = emptyList()
)

/**
 * An Album designation comes from Discogs release format descriptions.
 * A 12-inch record at 33 1/3 RPM is not automatically an album.
 */
fun DiscogsRelease.isAlbumFormat(): Boolean =
    formats.orEmpty().any { format ->
        format.name?.trim()?.equals("Album", ignoreCase = true) == true ||
                format.descriptions.orEmpty().any { description ->
                    description.trim().equals("Album", ignoreCase = true)
                }
    }

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

    // Discogs marketplace listing creation timestamp.
    // This is the field used by the local Room inventory-aging feature.
    val posted: String? = null,

    // Kept as a compatibility/fallback field for local inventory data.
    // Discogs' listing payload normally exposes `posted`; if `date_added`
    // is absent this simply stays null.
    @SerialName("date_added")
    val dateAdded: String? = null,

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
    val name: String? = null,
    val qty: String? = null,
    val text: String? = null,
    val descriptions: List<String>? = emptyList()
)

@Serializable
data class DiscogsCredit(
    val id: Long? = null,
    val name: String? = null,
    val anv: String? = null,
    val role: String? = null,
    val tracks: String? = null
)

@Serializable
data class DiscogsTrack(
    val position: String? = null,
    val title: String? = null,
    val duration: String? = null,

    @SerialName("extraartists")
    val extraArtists: List<DiscogsCredit>? = emptyList()
)

@Serializable
data class DiscogsCompany(
    val id: Long? = null,
    val name: String? = null,
    val catno: String? = null,

    @SerialName("entity_type_name")
    val entityTypeName: String? = null
)

@Serializable
data class DiscogsIdentifier(
    val type: String? = null,
    val value: String? = null,
    val description: String? = null
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

    @SerialName("last_activity")
    val lastActivity: String? = null,

    @SerialName("next_status")
    val nextStatus: List<String>? = emptyList(),

    val buyer: BuyerInfo? = null,

    val fee: OrderPrice? = null,
    val shipping: OrderPrice? = null,
    val total: OrderPrice? = null,

    @SerialName("shipping_address")
    val shippingAddress: String? = null,

    @SerialName("additional_instructions")
    val additionalInstructions: String? = null,

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
    val currency: String? = null,
    val method: String? = null
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
    val comments: String? = null,
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
data class DiscogsOrderMessagesResponse(
    val messages: List<DiscogsOrderMessage> = emptyList(),
    val pagination: DiscogsPagination? = null
)

@Serializable
data class DiscogsOrderMessage(
    val id: String? = null,
    val subject: String? = null,
    val message: String? = null,
    val type: String? = null,
    val timestamp: String? = null,
    @SerialName("from") val from: OrderMessageUser? = null,
    val to: OrderMessageUser? = null
)

@Serializable
data class OrderMessageUser(
    val id: Long? = null,
    val username: String? = null,
    @SerialName("resource_url") val resourceUrl: String? = null
)

@Serializable
data class AddOrderMessageRequest(
    val message: String? = null,
    val status: String? = null
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


/** USD estimate from observed listings of the same release; shipping excluded.
 * Ratios follow the vinyl guidance at:
 * https://support.discogs.com/hc/en-us/articles/360001566193-How-To-Grade-Items
 */
data class LiveGradeEstimate(
    val price: Double,
    val sourceCondition: String,
    val sourcePrice: Double
)
