package com.example.discogsandroidapp

/** A snapshot from one scan, in USD, excluding shipping. Never merge separate scans. */
data class ActiveMarketplaceConditionPrices(
    val mediaLowest: Map<String, Double> = emptyMap(),
    val mediaSleeveLowest: Map<String, Double> = emptyMap()
) {
    // Legacy convenience accessors retained for the existing ReleasePriceSummary adapter.
    val nearMint: Double?
        get() = mediaLowest["Near Mint (NM or M-)"]

    val nearMintPremiumSleeve: Double?
        get() = listOfNotNull(
            mediaSleeveLowest["Near Mint (NM or M-)||Near Mint (NM or M-)"],
            mediaSleeveLowest["Near Mint (NM or M-)||Mint (M)"]
        ).minOrNull()

    val mint: Double?
        get() = mediaLowest["Mint (M)"]

    val mintSleeve: Double?
        get() = mediaSleeveLowest["Mint (M)||Mint (M)"]

    fun priceFor(media: String, sleeve: String?): Double? =
        (if (sleeve.isNullOrBlank()) mediaLowest[media]
        else mediaSleeveLowest["$media||$sleeve"])?.takeIf { it.isFinite() && it > 0 }

    /** Only for different pages within the same fresh scan. */
    fun plusPage(page: ActiveMarketplaceConditionPrices): ActiveMarketplaceConditionPrices {
        fun minima(a: Map<String, Double>, b: Map<String, Double>) =
            (a.keys + b.keys).associateWith { key -> minOf(a[key] ?: Double.POSITIVE_INFINITY, b[key] ?: Double.POSITIVE_INFINITY) }
        return ActiveMarketplaceConditionPrices(minima(mediaLowest, page.mediaLowest), minima(mediaSleeveLowest, page.mediaSleeveLowest))
    }
}

enum class MarketplacePriceStatus { LOADING, FRESH, CACHED, INCOMPLETE, FAILED }

data class MarketplacePriceState(
    val status: MarketplacePriceStatus = MarketplacePriceStatus.LOADING,
    // Only a fully parsed, exhausted scan is eligible as a recommendation.
    val prices: ActiveMarketplaceConditionPrices? = null,
    val checkedAtMillis: Long? = null,
    val message: String = "Checking current Discogs prices…"
)

internal const val MARKETPLACE_PRICE_CACHE_TTL_MS = 2L * 60L * 1000L

/** Accept equivalent Discogs listing URLs, but never another release, page or host. */
internal fun isMarketplacePriceUrlAllowed(
    scheme: String?, host: String?, path: String?, releaseQuery: String?, pageQuery: String?,
    releaseId: Long, expectedPage: Int
): Boolean {
    if (scheme != "https" || host !in setOf("www.discogs.com", "discogs.com")) return false
    if ((pageQuery ?: "1").toIntOrNull() != expectedPage) return false
    return when (path?.trimEnd('/')) {
        "/sell/list" -> releaseQuery == releaseId.toString()
        "/sell/release/$releaseId" -> releaseQuery == null || releaseQuery == releaseId.toString()
        else -> false
    }
}

/** Bounded cache; updates replace a whole release, including disappeared grades. */
internal class MarketplacePriceCache(private val now: () -> Long = System::currentTimeMillis) {
    private val entries = LinkedHashMap<Long, MarketplacePriceState>()
    fun get(id: Long): MarketplacePriceState? {
        val entry = entries[id] ?: return null
        val age = now() - (entry.checkedAtMillis ?: return null)
        if (age !in 0 until MARKETPLACE_PRICE_CACHE_TTL_MS) {
            entries.remove(id)
            return null
        }
        return entry.copy(status = MarketplacePriceStatus.CACHED)
    }
    fun put(id: Long, prices: ActiveMarketplaceConditionPrices): MarketplacePriceState {
        val entry = MarketplacePriceState(MarketplacePriceStatus.FRESH, prices, now(), "Current Discogs asking prices")
        entries.remove(id)
        entries[id] = entry
        while (entries.size > 64) entries.remove(entries.keys.first())
        return entry
    }
}
