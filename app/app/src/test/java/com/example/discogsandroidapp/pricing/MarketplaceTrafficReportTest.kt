package com.example.discogsandroidapp.pricing

import org.junit.Assert.*
import org.junit.Test

class MarketplaceTrafficReportTest {
    @Test fun separatesLoadsReuseAndResourcesAndTracksRollingHour() {
        var now = 0L
        val log = MarketplaceTrafficLog { now }
        log.pageLoad(123)
        log.pageReuse(123)
        log.priceReuse(456)
        repeat(5) { log.resource("www.discogs.com") }

        val initial = log.report(12)
        assertTrue(initial.contains("API requests in last 60 seconds: 12"))
        assertTrue(initial.contains("Marketplace page loads: 1 this app session, 1 in last 60 seconds, 1 in last 60 minutes"))
        assertTrue(initial.contains("Cached page reuses: 1; cached price reuses: 1"))
        assertTrue(initial.contains("5 total, 5 in last 60 seconds"))

        now = 60_000
        val minuteExpired = log.report(0)
        assertTrue(minuteExpired.contains("Marketplace page loads: 1 this app session, 0 in last 60 seconds, 1 in last 60 minutes"))
        assertTrue(minuteExpired.contains("5 total, 0 in last 60 seconds"))

        now = 3_600_000
        val hourExpired = log.report(0)
        assertTrue(hourExpired.contains("Marketplace page loads: 1 this app session, 0 in last 60 seconds, 0 in last 60 minutes"))
    }

    @Test fun concurrentResourceObservationsAreCountedAndPageHistoryIsBounded() {
        val log = MarketplaceTrafficLog { 0L }
        val threads = List(4) { Thread { repeat(250) { log.resource("www.discogs.com") } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        repeat(45) { log.pageLoad(it.toLong()) }
        val report = log.report(0)
        assertTrue(report.contains("1000 total, 1000 in last 60 seconds"))
        assertTrue(report.contains("Marketplace page loads: 45 this app session, 45 in last 60 seconds, 45 in last 60 minutes"))
        assertFalse(report.contains("Load release 0,"))
        assertTrue(report.contains("Load release 44,"))
    }
}
