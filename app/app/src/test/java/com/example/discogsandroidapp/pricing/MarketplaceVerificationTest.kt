package com.example.discogsandroidapp.pricing

import org.junit.Assert.*
import org.junit.Test

class MarketplaceVerificationTest {
    @Test fun listingCommentsCannotDeclareARateLimitOrAChallenge() {
        assertNull(marketplaceAccessStatus("Discogs Marketplace", "Rate limited edition; too many requests. Cloudflare verify you are human; access denied",
            hasMarketplaceListings = true))
        assertEquals(MarketplaceUiPriceStatus.RATE_LIMITED, marketplaceAccessStatus("Discogs", "",
            httpStatus = 429, hasMarketplaceListings = true))
    }
    @Test fun identifiesAnExplicitCloudflareChallenge() {
        assertEquals(MarketplaceUiPriceStatus.VERIFICATION_REQUIRED,
            marketplaceAccessStatus("Just a moment…", "Cloudflare: Verify you are human", httpStatus = 403))
        assertEquals(MarketplaceUiPriceStatus.VERIFICATION_REQUIRED,
            marketplaceAccessStatus("Discogs", "", challengeHeader = true, httpStatus = 403))
        assertEquals(MarketplaceUiPriceStatus.VERIFICATION_REQUIRED,
            marketplaceAccessStatus("Discogs", "", hasChallengeFrame = true))
    }

    @Test fun actionableChallengeWinsEvenWhenCloudflareUses429() {
        assertEquals(MarketplaceUiPriceStatus.VERIFICATION_REQUIRED,
            marketplaceAccessStatus("Just a moment", "Cloudflare", challengeHeader = true, httpStatus = 429))
        assertEquals(MarketplaceUiPriceStatus.VERIFICATION_REQUIRED,
            marketplaceAccessStatus("Just a moment", "Cloudflare: Verify you are human", httpStatus = 429))
        assertEquals(MarketplaceUiPriceStatus.RATE_LIMITED,
            marketplaceAccessStatus("Access denied", "Cloudflare Error 1015: You are being rate limited"))
    }

    @Test fun ordinaryFailuresAndListingDescriptionsAreNotChallenges() {
        assertEquals(MarketplaceUiPriceStatus.BLOCKED, marketplaceAccessStatus("Forbidden", "", httpStatus = 403))
        assertEquals(MarketplaceUiPriceStatus.FAILED, marketplaceAccessStatus("Error", "", httpStatus = 503))
        assertNull(marketplaceAccessStatus("Discogs Marketplace", "Record description: Verify you are human"))
        assertNull(marketplaceAccessStatus("Discogs Marketplace", "Media: Very Good (VG) US$10.00"))
    }

    @Test fun estimatesAndCachedPricesHaveDistinctSources() {
        assertEquals("Algorithm", listingPriceSource(false, false, false))
        assertEquals("Saved listings", listingPriceSource(true, false, true))
        assertEquals("Based on listings", listingPriceSource(true, false, false))
        assertEquals("Estimated", listingPriceSource(false, true, false))
    }
}
