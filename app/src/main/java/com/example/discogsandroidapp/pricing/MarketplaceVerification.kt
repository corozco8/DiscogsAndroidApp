package com.example.discogsandroidapp.pricing

/** Only explicit challenge evidence should ask the seller to verify their session. */
internal fun marketplaceAccessStatus(
    title: String,
    body: String,
    hasChallengeFrame: Boolean = false,
    challengeHeader: Boolean = false,
    httpStatus: Int? = null,
    hasMarketplaceListings: Boolean = false
): MarketplaceUiPriceStatus? {
    val text = (if (hasMarketplaceListings) title else "$title\n$body").lowercase(java.util.Locale.ROOT)
    if (httpStatus == 429 || (!hasMarketplaceListings &&
            Regex("too many requests|rate limit(?:ed| exceeded)|error\\s*1015").containsMatchIn(text))) {
        return MarketplaceUiPriceStatus.RATE_LIMITED
    }
    val challengeText = Regex(
        "verify (?:that )?you(?:'re| are) human|verify you are a human|verifying you are human|" +
            "checking your browser|checking if the site connection is secure|" +
            "performing security verification|enable javascript and cookies to continue"
    ).containsMatchIn(text)
    if (challengeHeader || hasChallengeFrame || (challengeText && text.contains("cloudflare")) ||
        (title.trim().startsWith("Just a moment", ignoreCase = true) && text.contains("cloudflare"))) {
        return MarketplaceUiPriceStatus.VERIFICATION_REQUIRED
    }
    return when {
        httpStatus == 401 || httpStatus == 403 || text.contains("access denied") -> MarketplaceUiPriceStatus.BLOCKED
        httpStatus != null && httpStatus >= 400 -> MarketplaceUiPriceStatus.FAILED
        else -> null
    }
}

/** Closing a challenge suppresses further automatic popups until access succeeds. */
internal class MarketplaceVerificationGate {
    private var offered = false
    fun shouldOpen(status: MarketplaceUiPriceStatus, automatic: Boolean, rateLimited: Boolean): Boolean {
        if (rateLimited || status != MarketplaceUiPriceStatus.VERIFICATION_REQUIRED || (automatic && offered)) return false
        offered = true
        return true
    }
    fun verified() { offered = false }
}

data class ListingPricingInfo(
    val status: MarketplaceUiPriceStatus,
    val message: String,
    val usingSavedPrices: Boolean = false,
    val retryInSeconds: Long = 0
)

internal fun listingPriceSource(isObserved: Boolean, isGradeEstimate: Boolean, usingSavedPrices: Boolean): String = when {
    isObserved && usingSavedPrices -> "Saved listings"
    isObserved -> "Based on listings"
    isGradeEstimate && usingSavedPrices -> "Saved estimate"
    isGradeEstimate -> "Estimated"
    else -> "Algorithm"
}
