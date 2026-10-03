package com.example.discogsandroidapp.data

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
    // Gather only usable USD guide values. This keeps the Low / Median / High
    // fallback card from silently treating an invalid or non-USD suggestion as
    // a dollar amount.
    private val allValues: List<Double>
        get() = listOf(
            poor,
            fair,
            good,
            goodPlus,
            veryGood,
            veryGoodPlus,
            nearMint,
            mint
        ).mapNotNull { suggestion ->
            val value = suggestion?.value?.takeIf { it.isFinite() && it > 0.0 }
            val isUsd = suggestion?.currency?.equals("USD", ignoreCase = true) ?: true
            value?.takeIf { isUsd }
        }.sorted()

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
private const val FALLBACK_GRADE_STEP_RATIO = 0.78
private const val FALLBACK_EXACT_HIGH_TRUST_RATIO = 1.35
private const val FALLBACK_EXACT_HIGH_REJECT_RATIO = 2.00
private const val FALLBACK_EXACT_LOW_TRUST_RATIO = 0.60
private const val FALLBACK_EXACT_LOW_REJECT_RATIO = 0.45

/** Lowest qualifying ask in the retrieved sample, not the whole marketplace. */
internal fun lowestComparableAskingPrice(
    prices: List<Double>,
    minimumListingCount: Int = MIN_TRUSTED_LIVE_LISTINGS
): Double? {
    val valid = prices.filter { it.isFinite() && it > 0.0 }
    if (valid.size < minimumListingCount) return null
    return valid.minOrNull()
}

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
    val activeMediaPriceSamples: Map<String, List<Double>> = emptyMap(),
    val activeMediaSleevePriceSamples: Map<String, List<Double>> = emptyMap(),

    // Discogs release format: only an explicitly tagged Album uses our
    // sleeve-quality exclusions. DJ singles / 12-inch singles / EPs may
    // normally ship in a generic sleeve or without a picture cover.
    val isAlbumRelease: Boolean = true
) {
    /**
     * Lowest qualifying asking price for the selected media grade in our first-page sample.
     *
     * Normal recommendation rules:
     * - M / NM: ignore marketplace copies with G+ or worse sleeves.
     * - VG+ / VG: ignore marketplace copies with G or worse sleeves.
     * - G+ and below: no sleeve-quality filter.
     *
     * If the seller intentionally selects a sleeve that would normally be
     * rejected (for example NM media with an F sleeve), the special filter is
     * disabled for that task and comparable asks for the media grade are
     * used instead.
     */
    fun currentListingPriceFor(
        condition: String,
        sleeveCondition: String? = null
    ): Double? {
        val price = comparableListingPriceFor(condition, condition, sleeveCondition) ?: return null
        return price.takeUnless { conflictsWithHigherGrade(condition, sleeveCondition, it) }
    }

    // A lower grade within 10% of any trusted higher-grade price is not a
    // useful live recommendation. Compare under the same target sleeve policy.
    private fun conflictsWithHigherGrade(
        condition: String,
        sleeveCondition: String?,
        price: Double,
        minimumListingCount: Int = MIN_TRUSTED_LIVE_LISTINGS
    ): Boolean {
        val rank = conditionRank(condition) ?: return false
        val grades = listOf(
            "Poor (P)", "Fair (F)", "Good (G)", "Good Plus (G+)",
            "Very Good (VG)", "Very Good Plus (VG+)", "Near Mint (NM or M-)", "Mint (M)"
        )
        return grades.drop(rank + 1).any { higherGrade ->
            val higherPrice = comparableListingPriceFor(higherGrade, condition, sleeveCondition, minimumListingCount)
            higherPrice != null && price >= roundPrice(higherPrice * 0.90)
        }
    }

    // Use the TARGET grade's sleeve policy even when sourcing another media grade.
    private fun comparableListingPriceFor(
        condition: String,
        targetCondition: String,
        sleeveCondition: String?,
        minimumListingCount: Int = MIN_TRUSTED_LIVE_LISTINGS
    ): Double? {
        val minimumSleeveRank =
            minimumRecommendedSleeveRankFor(targetCondition)

        fun rawMediaPrice(): Double? {
            activeMediaPriceSamples[condition]?.let { samples ->
                return lowestComparableAskingPrice(samples, minimumListingCount)?.let(::roundPrice)
            }
            val comparableCount = activeMediaListingCounts[condition] ?: 0
            if (comparableCount < minimumListingCount) return null

            return activeMediaLowestPrices[condition]
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?.let(::roundPrice)
        }

        // Non-albums do not exclude low-grade or generic sleeves; compare media grade.
        // The caller controls the comparable count; observed prices still require two.
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
        val qualifyingSamples = activeMediaSleevePriceSamples.filterKeys { key ->
            key.startsWith(prefix) && (sleeveGradeRank(key.removePrefix(prefix)) ?: -1) >= minimumSleeveRank
        }.values.flatten()
        if (qualifyingSamples.isNotEmpty()) return lowestComparableAskingPrice(qualifyingSamples, minimumListingCount)?.let(::roundPrice)
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

        if (qualifyingCount < minimumListingCount) return null

        return lowestQualifyingPrice?.let(::roundPrice)
    }

    /** Estimate from observed listings only; includes the G+ price guard. */
    fun liveGradeEstimateFor(condition: String, sleeveCondition: String? = null): LiveGradeEstimate? {
        // A conflicting G+ ask is replaced with 60% of the cheapest trusted
        // better-grade ask. Keep it an estimate, not an observed G+ listing.
        if (condition == "Good Plus (G+)") {
            val matching = comparableListingPriceFor(condition, condition, sleeveCondition)
            if (matching != null && conflictsWithHigherGrade(condition, sleeveCondition, matching)) {
                val source = listOf("Very Good (VG)", "Very Good Plus (VG+)", "Near Mint (NM or M-)", "Mint (M)")
                    .mapNotNull { grade ->
                        comparableListingPriceFor(grade, condition, sleeveCondition)?.let { grade to it }
                    }
                    .minByOrNull { it.second } ?: return null
                val estimate = roundPrice(source.second * 0.60)
                return estimate.takeIf { it > 0.0 }?.let { LiveGradeEstimate(it, source.first, source.second) }
            }
        }
        // Rejected matching live prices must go straight to the original fallback.
        val matchingPrice = comparableListingPriceFor(condition, condition, sleeveCondition)
        if (matchingPrice != null && conflictsWithHigherGrade(condition, sleeveCondition, matchingPrice)) return null
        val grades = listOf("Near Mint (NM or M-)", "Very Good Plus (VG+)", "Very Good (VG)")
        // Keep 60% per grade step (40% reduction), using unrounded anchors.
        val ratios = listOf(1.0, 0.60, 0.36)
        val target = grades.indexOf(condition)
        if (target < 0 || currentListingPriceFor(condition, sleeveCondition) != null) return null
        // A single matching ask is more relevant than extrapolating another grade.
        // Keep the weaker comparison labelled as an estimate.
        comparableListingPriceFor(condition, condition, sleeveCondition, 1)?.let { price ->
            if (conflictsWithHigherGrade(condition, sleeveCondition, price, 1)) return null
            return LiveGradeEstimate(price, condition, price)
        }
        // Prefer evidence from at least two listings. Only when none exists do we
        // accept a single qualifying listing, still returned as an estimate.
        val minimumListingCount = if (grades.any { grade ->
                comparableListingPriceFor(grade, condition, sleeveCondition) != null
            }) MIN_TRUSTED_LIVE_LISTINGS else 1
        // Fill a missing grade between available asks, using the same target sleeve policy.
        val better = (target - 1 downTo 0).firstNotNullOfOrNull { index ->
            comparableListingPriceFor(grades[index], condition, sleeveCondition, minimumListingCount)?.let { Triple(index, grades[index], it) }
        }
        val worse = (target + 1 until grades.size).firstNotNullOfOrNull { index ->
            comparableListingPriceFor(grades[index], condition, sleeveCondition, minimumListingCount)?.let { Triple(index, grades[index], it) }
        }
        if (better != null && worse != null) {
            if (better.third <= worse.third) return null
            val fraction = (target - better.first).toDouble() / (worse.first - better.first)
            val estimate = roundPrice(better.third * (1.0 - fraction) + worse.third * fraction)
            if (conflictsWithHigherGrade(condition, sleeveCondition, estimate, minimumListingCount)) return null
            return LiveGradeEstimate(estimate, better.second, better.third, worse.second, worse.third)
        }
        // Closest grade first; prefer the better grade when equally close.
        for (source in grades.indices.filter { it != target }.sortedBy { kotlin.math.abs(it - target) }) {
            val sourcePrice = comparableListingPriceFor(grades[source], condition, sleeveCondition, minimumListingCount) ?: continue
            val estimate = roundPrice(sourcePrice * ratios[target] / ratios[source])
            if (!estimate.isFinite() || estimate <= 0.0) continue
            if (conflictsWithHigherGrade(condition, sleeveCondition, estimate, minimumListingCount)) return null
            return LiveGradeEstimate(estimate, grades[source], sourcePrice)
        }
        return null
    }

    /** Robust API estimate with a small, explicit sleeve adjustment. */
    fun fallbackRecommendedPriceFor(
        condition: String,
        sleeveCondition: String? = null
    ): Double? {
        val gradePrice = apiRecommendationFor(condition) ?: return null
        // VG+ remains neutral. Very damaged album jackets get a materially larger
        // deduction than the old 20-25% haircut, which badly overvalued P/F covers.
        val sleeveMultiplier = if (!isAlbumRelease) 1.0 else when (sleeveCondition) {
            "Mint (M)" -> 1.10
            "Near Mint (NM or M-)" -> 1.05
            "Very Good Plus (VG+)" -> 1.00
            "Very Good (VG)" -> 0.95
            "Good Plus (G+)" -> 0.90
            "Good (G)" -> 0.75
            "Fair (F)" -> 0.55
            "Poor (P)" -> 0.30
            "No Cover" -> 0.25
            else -> 1.0
        }
        return roundPrice(gradePrice * sleeveMultiplier).takeIf { it.isFinite() && it > 0.0 }
    }

    /**
     * Prefer matching listings, then an estimate from another live grade,
     * then the grade-specific API estimate.
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


    private fun apiRecommendationFor(condition: String): Double? {
        val targetRank = conditionRank(condition) ?: return null
        // All listing inputs are USD. Never silently interpret another currency as dollars.
        if (!currency.equals("USD", ignoreCase = true)) return null
        val points = priceSuggestions?.conditionPricePoints().orEmpty()
        if (points.isEmpty()) {
            // The historical price-guide median is still a last-resort anchor when
            // Discogs returns no usable condition suggestions at all. Treat it as
            // roughly VG+ and use the gentler 78% grade curve.
            val anchor = median?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            return projectFallbackGrade(anchor, sourceRank = 5, targetRank = targetRank)
                .let(::roundPrice)
                .takeIf { it.isFinite() && it > 0.0 }
        }

        val exact = points.firstOrNull { it.first == targetRank }?.second
        val lower = points.lastOrNull { it.first < targetRank }
        val upper = points.firstOrNull { it.first > targetRank }

        // If the exact grade is missing and Discogs gives coherent values on both
        // sides, use release-specific interpolation before a generic grade ratio.
        if (exact == null && lower != null && upper != null) {
            if (upper.second < lower.second) {
                // With only contradictory anchors there is no defensible curve.
                if (points.size <= 2) return null
            } else {
                val fraction =
                    (targetRank - lower.first).toDouble() / (upper.first - lower.first)
                val interpolated =
                    lower.second * (1.0 - fraction) + upper.second * fraction

                // When several other grades are available, guard the local pair
                // against another hidden spike before accepting the interpolation.
                val projectedConsensus = fallbackPeerConsensus(points, targetRank, null)
                val guarded = if (projectedConsensus != null) {
                    val ratio = interpolated / projectedConsensus
                    if (ratio !in 0.50..1.75) projectedConsensus else interpolated
                } else {
                    interpolated
                }

                return roundPrice(guarded).takeIf { it.isFinite() && it > 0.0 }
            }
        }

        val peerConsensus = fallbackPeerConsensus(points, targetRank, exact)

        // Missing grades use the robust consensus projected from every usable
        // neighboring grade. This replaces the old 60% per-step extrapolation.
        if (exact == null) {
            val estimate = peerConsensus ?: return null
            return roundPrice(estimate).takeIf { it.isFinite() && it > 0.0 }
        }

        // A lone exact suggestion is still the best evidence available. Once other
        // grades exist, however, compare it with their reconstructed target value so
        // a single stale/high historical sale cannot dominate the recommendation.
        if (peerConsensus == null) {
            return roundPrice(exact).takeIf { it.isFinite() && it > 0.0 }
        }

        val exactToConsensus = exact / peerConsensus
        val estimate = when {
            exactToConsensus in FALLBACK_EXACT_LOW_TRUST_RATIO..FALLBACK_EXACT_HIGH_TRUST_RATIO -> exact

            exactToConsensus >= FALLBACK_EXACT_HIGH_REJECT_RATIO -> peerConsensus

            exactToConsensus > FALLBACK_EXACT_HIGH_TRUST_RATIO -> {
                // Smoothly reduce the exact value's influence from 100% at 1.35x
                // to 0% at 2x. There is no abrupt price cliff near the threshold.
                val correction =
                    ((exactToConsensus - FALLBACK_EXACT_HIGH_TRUST_RATIO) /
                            (FALLBACK_EXACT_HIGH_REJECT_RATIO - FALLBACK_EXACT_HIGH_TRUST_RATIO))
                        .coerceIn(0.0, 1.0)
                exact * (1.0 - correction) + peerConsensus * correction
            }

            exactToConsensus <= FALLBACK_EXACT_LOW_REJECT_RATIO -> peerConsensus

            else -> {
                // Be a little more tolerant of unexpectedly cheap historical data,
                // while still protecting against a catastrophic low outlier.
                val correction =
                    ((FALLBACK_EXACT_LOW_TRUST_RATIO - exactToConsensus) /
                            (FALLBACK_EXACT_LOW_TRUST_RATIO - FALLBACK_EXACT_LOW_REJECT_RATIO))
                        .coerceIn(0.0, 1.0)
                exact * (1.0 - correction) + peerConsensus * correction
            }
        }

        return roundPrice(estimate).takeIf { it.isFinite() && it > 0.0 }
    }

    /**
     * Re-express every other Discogs condition suggestion as an equivalent price
     * for [targetRank], then use a median so one wild condition does not drag the
     * whole release upward. The optional exact value is excluded on purpose: it
     * is the observation being validated.
     */
    private fun fallbackPeerConsensus(
        points: List<Pair<Int, Double>>,
        targetRank: Int,
        exactValue: Double?
    ): Double? {
        val projected = points.mapNotNull { (rank, value) ->
            if (rank == targetRank && exactValue != null) return@mapNotNull null
            projectFallbackGrade(value, sourceRank = rank, targetRank = targetRank)
                .takeIf { it.isFinite() && it > 0.0 }
        }

        if (projected.isEmpty()) return null

        val firstMedian = medianValue(projected) ?: return null
        if (projected.size < 3) return firstMedian

        // Remove peer estimates that are themselves extreme relative to the
        // group, then take the median again. This makes the consensus robust even
        // when Discogs has more than one noisy condition value.
        val trimmed = projected.filter { candidate ->
            val ratio = candidate / firstMedian
            ratio in 0.50..2.00
        }

        return medianValue(trimmed.takeIf { it.size >= 2 } ?: projected)
    }

    private fun projectFallbackGrade(
        value: Double,
        sourceRank: Int,
        targetRank: Int
    ): Double {
        return value * Math.pow(
            FALLBACK_GRADE_STEP_RATIO,
            (sourceRank - targetRank).toDouble()
        )
    }

    private fun medianValue(values: List<Double>): Double? {
        val sorted = values.filter { it.isFinite() && it > 0.0 }.sorted()
        if (sorted.isEmpty()) return null
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
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

    private fun PriceSuggestions.conditionPricePoints(): List<Pair<Int, Double>> {
        return listOf(poor, fair, good, goodPlus, veryGood, veryGoodPlus, nearMint, mint)
            .mapIndexedNotNull { rank, suggestion ->
                val amount = suggestion?.value?.takeIf { it.isFinite() && it > 0.0 }
                val isUsd = suggestion?.currency?.equals("USD", ignoreCase = true) ?: true
                if (amount != null && isUsd) rank to amount else null
            }
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
    val catno: String? = null,
    @SerialName("cover_image") val coverImage: String? = null
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


/** USD estimate from observed asking prices, using nearby grades; shipping excluded. */
data class LiveGradeEstimate(
    val price: Double,
    val sourceCondition: String,
    val sourcePrice: Double,
    val secondSourceCondition: String? = null,
    val secondSourcePrice: Double? = null
)
