package com.example.discogsandroidapp.pricing

import com.example.discogsandroidapp.data.ReleasePriceSummary
import kotlin.math.max
import kotlin.math.min

/**
 * Low and High remain the raw Discogs API condition-guide extremes.
 *
 * The middle value is intentionally NOT taken from MarketplaceListingsScreen.
 * It uses only data that Release Details already receives from normal Discogs API calls:
 *   - the historical condition-guide midpoint (`summary.median`)
 *   - the current overall lowest ask (`summary.lowestAskingPrice`)
 *   - the current number of copies for sale (`summary.numForSale`)
 *   - the release community have/want counts already loaded by Release Details
 *
 * Discogs' condition-guide midpoint can be badly stale relative to the active market.
 * The model therefore changes how strongly it trusts the current floor based on market
 * depth, and uses have/want only as a conservative tie-breaker when supply is limited.
 * No MarketplaceListingsScreen/WebView data is used here.
 */
internal fun apiOnlyTypicalPrice(
    summary: ReleasePriceSummary,
    haveCount: Int = 0,
    wantCount: Int = 0
): Double? {
    val historical = summary.median
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?: return null

    val currentFloor = summary.lowestAskingPrice
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?: return historical

    val copiesForSale = summary.numForSale.coerceAtLeast(0)
    val floorToHistorical = currentFloor / historical
    val historicalToFloor = historical / currentFloor
    val demandRatio = if (haveCount > 0 && wantCount >= 0) {
        wantCount.toDouble() / haveCount.toDouble()
    } else {
        0.0
    }

    val adjusted = when {
        // With a deep active market the cheapest ask is much less likely to be a
        // one-off oddball. Use it as a stronger anchor, but still never raise a
        // sensible historical center. 2.5-2.75x roughly maps the floor to the
        // middle of a large listing pool in our calibration set.
        copiesForSale >= 50 -> {
            val floorMultiplier = if (currentFloor < 10.0) 2.75 else 2.50
            min(historical, currentFloor * floorMultiplier)
        }

        // A medium-sized market plus a historical center over 2.5x the current
        // floor is a strong stale-high signal (for example Dokken).
        copiesForSale in 35..49 && historicalToFloor >= 2.50 -> {
            min(historical, currentFloor * 1.20)
        }

        // If even the cheapest active copy is above the historical center, the
        // historical guide is stale-low. Raise it conservatively from that floor.
        floorToHistorical > 1.00 -> max(historical, currentFloor * 1.25)

        // When the current floor is only a few percent of the guide, treat it as
        // a likely damaged/odd copy rather than collapsing the whole estimate.
        floorToHistorical < 0.05 -> historical * 0.58

        // Moderate disagreement: split the difference between active-market floor
        // and historical guide instead of applying a fixed percentage haircut.
        floorToHistorical < 0.30 -> (historical + currentFloor) / 2.0

        // A scarce market whose cheapest copy already sits near the historical
        // center is best represented by the midpoint of those two API signals.
        floorToHistorical >= 0.75 -> (historical + currentFloor) / 2.0

        // Strong demand with a relatively small active supply can make a historical
        // guide lag upward. This uses only normal release/community API data.
        floorToHistorical >= 0.45 && copiesForSale in 1..30 && demandRatio >= 0.45 -> {
            max(historical, currentFloor * 3.0)
        }

        // Otherwise preserve the historical center. This is important for releases
        // such as Aja where the original API midpoint was already representative.
        else -> historical
    }

    val high = summary.high?.takeIf { it.isFinite() && it > 0.0 }
    return if (high != null) min(adjusted, high) else adjusted
}

internal data class MarketplacePageRange(val lowest: Double, val average: Double, val highest: Double, val count: Int)

/** Prices are already normalized to USD by the page reader; no requests here. */
internal fun marketplacePageRange(prices: List<Double>): MarketplacePageRange? {
    val valid = prices.take(250).filter { it.isFinite() && it > 0.0 }
    if (valid.isEmpty()) return null
    // Divide before summing to avoid overflowing on large values.
    return MarketplacePageRange(valid.min(), valid.sumOf { it / valid.size }, valid.max(), valid.size)
}

internal data class PriceGuideValues(
    val low: Double?, val middle: Double?, val high: Double?,
    val lowLabel: String, val middleLabel: String, val highLabel: String,
    val source: String, val currency: String
)

/**
 * Release Details price guide.
 *
 * IMPORTANT: this deliberately ignores livePrices / MarketplaceListingsScreen. The card is
 * API-only so merely viewing a release can never require a Discogs marketplace webpage load.
 * The parameters remain in the signature for compatibility with existing callers/tests.
 */
internal fun priceGuideValues(
    summary: ReleasePriceSummary,
    @Suppress("UNUSED_PARAMETER") livePrices: List<Double>,
    @Suppress("UNUSED_PARAMETER") pricingInfo: ListingPricingInfo?,
    haveCount: Int = 0,
    wantCount: Int = 0
): PriceGuideValues {
    val typical = apiOnlyTypicalPrice(summary, haveCount, wantCount)
    val adjusted = typical != null && summary.median != null && kotlin.math.abs(typical - summary.median) >= 0.01
    val source = if (adjusted) {
        "Discogs API guide · Typical adjusted from current market signals"
    } else {
        "Discogs API price guide"
    }

    return PriceGuideValues(
        low = summary.low,
        middle = typical,
        high = summary.high,
        lowLabel = "Low",
        middleLabel = "Typical",
        highLabel = "High",
        source = source,
        currency = summary.currency
    )
}
