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
    @Test fun explicitRetryCanLoadImmediatelyDuringAutomaticSpacing() {
        assertTrue(policy.reserveLoad())
        assertEquals(10_000L, policy.waitForLoad())

        assertTrue(policy.reserveLoad(explicit = true))
        assertFalse(policy.reserveLoad())
        assertEquals(10_000L, policy.waitForLoad())
    }
    @Test fun repeatedExplicitRetriesNeverCreateALockout() {
        assertTrue(policy.reserveLoad())
        repeat(20) { assertTrue(policy.reserveLoad(explicit = true)) }

        assertFalse(policy.reserveLoad())
        clock += 10_000
        assertTrue(policy.reserveLoad())
    }
    @Test fun explicitLoadRestartsAutomaticSpacingFromItsOwnTime() {
        assertTrue(policy.reserveLoad())
        clock += 7_000
        assertEquals(3_000L, policy.waitForLoad())

        assertTrue(policy.reserveLoad(explicit = true))
        assertEquals(10_000L, policy.waitForLoad())
        clock += 9_999
        assertFalse(policy.reserveLoad())
        clock++
        assertTrue(policy.reserveLoad())
    }
}
