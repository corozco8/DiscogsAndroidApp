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
    @Test fun honorsServerPauseBeyondOneMinute() {
        policy.limited("300")
        clock += 180_000
        assertEquals(120_000, policy.blockedFor())
        assertFalse(policy.reserveLoad())
        clock += 120_000
        assertTrue(policy.reserveLoad())
    }
    @Test fun repeatedLimitsBackOffWithoutExtendingTheTimerByReadingIt() {
        policy.limited(null)
        assertEquals(60_000, policy.blockedFor())
        clock += 60_000
        assertEquals(0, policy.blockedFor())
        assertEquals(0, policy.blockedFor())
        policy.limited(null)
        assertEquals(120_000, policy.blockedFor())
        clock += 120_000
        policy.limited(null)
        assertEquals(240_000, policy.blockedFor())
    }
    @Test fun restartingPreservesTheRemainingPause() {
        val until = policy.limited(null)
        clock += 20_000
        val restored = MarketplaceLoadPolicy { clock }
        restored.restore(until, policy.failures)
        assertEquals(40_000, restored.blockedFor())
        assertFalse(restored.reserveLoad())
    }
    @Test fun aSuccessfulPageResetsTheBackoff() {
        policy.limited(null)
        clock += 60_000
        policy.limited(null)
        policy.succeeded()
        assertEquals(0, policy.blockedFor())
        policy.limited(null)
        assertEquals(60_000, policy.blockedFor())
    }
    @Test fun repeatedFailuresHaveABoundedFallbackPause() {
        repeat(20) { policy.limited(null); clock = policy.blockedUntil }
        policy.limited(null)
        assertEquals(15 * 60_000L, policy.blockedFor())
    }
}
