package com.example.discogsandroidapp.pricing

import org.junit.Assert.*
import org.junit.Test

class MarketplaceTrafficReportTest {
    @Test fun separatesLoadsReuseAndResourcesAndExpiresTheRollingMinute() {
        var now = 0L
        val log = MarketplaceTrafficLog { now }
        log.pageLoad(123)
        log.pageReuse(123)
        log.priceReuse(456)
        repeat(5) { log.resource("www.discogs.com") }
        val initial = log.report(12)
        assertTrue(initial.contains("API requests in last 60 seconds: 12"))
        assertTrue(initial.contains("Marketplace page loads: 1 total, 1 in last 60 seconds"))
        assertTrue(initial.contains("Cached page reuses: 1; cached price reuses: 1"))
        assertTrue(initial.contains("5 total, 5 in last 60 seconds"))
        now = 60_000
        val expired = log.report(0)
        assertTrue(expired.contains("Marketplace page loads: 1 total, 0 in last 60 seconds"))
        assertTrue(expired.contains("5 total, 0 in last 60 seconds"))
    }
    @Test fun concurrentResourceObservationsAreCountedAndPageHistoryIsBounded() {
        val log = MarketplaceTrafficLog { 0L }
        val threads = List(4) { Thread { repeat(250) { log.resource("www.discogs.com") } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        repeat(45) { log.pageLoad(it.toLong()) }
        val report = log.report(0)
        assertTrue(report.contains("1000 total, 1000 in last 60 seconds"))
        assertFalse(report.contains("Load release 0,"))
        assertTrue(report.contains("Load release 44,"))
    }
}
