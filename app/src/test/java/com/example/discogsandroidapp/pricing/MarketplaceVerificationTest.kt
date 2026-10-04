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

    @Test fun automaticVerificationOnlyOpensOnceUntilAccessSucceeds() {
        val gate = MarketplaceVerificationGate()
        val challenge = MarketplaceUiPriceStatus.VERIFICATION_REQUIRED
        assertTrue(gate.shouldOpen(challenge, automatic = true))
        assertFalse(gate.shouldOpen(challenge, automatic = true))
        assertTrue(gate.shouldOpen(challenge, automatic = false))
        gate.verified()
        assertTrue(gate.shouldOpen(challenge, automatic = true))
    }

    @Test fun rateLimitingNeverOpensVerificationOrPreventsALaterChallenge() {
        val gate = MarketplaceVerificationGate()
        assertFalse(gate.shouldOpen(MarketplaceUiPriceStatus.RATE_LIMITED, automatic = true))
        assertFalse(gate.shouldOpen(MarketplaceUiPriceStatus.RATE_LIMITED, automatic = false))
        assertTrue(gate.shouldOpen(MarketplaceUiPriceStatus.VERIFICATION_REQUIRED, automatic = true))
        assertFalse(gate.shouldOpen(MarketplaceUiPriceStatus.VERIFICATION_REQUIRED, automatic = true))
        assertTrue(gate.shouldOpen(MarketplaceUiPriceStatus.VERIFICATION_REQUIRED, automatic = false))
    }

    @Test fun estimatesAndCachedPricesHaveDistinctSources() {
        assertEquals("Algorithm", listingPriceSource(false, false, false))
        assertEquals("Saved listings", listingPriceSource(true, false, true))
        assertEquals("Based on listings", listingPriceSource(true, false, false))
        assertEquals("Estimated", listingPriceSource(false, true, false))
    }
}
