package com.example.discogsandroidapp.pricing

import org.junit.Assert.*
import org.junit.Test

class MarketplaceLoadPolicyTest {
    private var clock = 1_000_000L
    private val policy = MarketplaceLoadPolicy { clock }

    @Test fun rapidVisitsWaitBetweenPages() {
        assertTrue(policy.reserveLoad())
        assertFalse(policy.reserveLoad())
        clock += 9_999
        assertFalse(policy.reserveLoad())
        clock++
        assertTrue(policy.reserveLoad())
    }

    @Test fun explicitRetryObeysNormalSpacing() {
        assertTrue(policy.reserveLoad())
        assertEquals(10_000L, policy.waitForLoad())
        assertFalse(policy.reserveLoad(explicit = true))

        clock += 10_000L
        assertTrue(policy.reserveLoad(explicit = true))
        assertEquals(10_000L, policy.waitForLoad())
    }

    @Test fun blockStopsBothAutomaticAndManualLoadsUntilCooldownExpires() {
        policy.recordBlock()
        assertTrue(policy.automaticChecksSuspended)
        assertFalse(policy.reserveLoad())
        assertFalse(policy.reserveLoad(explicit = true))
        assertEquals(MarketplaceLoadPolicy.DEFAULT_BLOCK_COOLDOWN_MS, policy.waitForLoad())

        clock += MarketplaceLoadPolicy.DEFAULT_BLOCK_COOLDOWN_MS
        assertFalse(policy.reserveLoad())
        assertTrue(policy.reserveLoad(explicit = true))
    }

    @Test fun serverCooldownCannotBeShorterThanTheDefaultProtectionWindow() {
        policy.recordBlock(cooldownMillis = 1_000L)
        assertEquals(MarketplaceLoadPolicy.DEFAULT_BLOCK_COOLDOWN_MS, policy.waitForLoad())
    }

    @Test fun aSuccessfulManualCheckResumesAutomaticChecksWithNormalSpacing() {
        policy.recordBlock()
        clock += MarketplaceLoadPolicy.DEFAULT_BLOCK_COOLDOWN_MS
        assertTrue(policy.reserveLoad(explicit = true))
        policy.recordManualSuccess()
        assertFalse(policy.automaticChecksSuspended)
        assertFalse(policy.reserveLoad())
        clock += MarketplaceLoadPolicy.REQUEST_SPACING_MS
        assertTrue(policy.reserveLoad())
    }
}
