package com.example.discogsandroidapp.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiRequestWindowTest {
    @Test fun requestsExpireIndividuallyInsteadOfResettingAtAMinuteBoundary() {
        var now = 59_000L
        val counter = ApiRequestWindow { now }
        counter.record()
        now = 60_000
        counter.record()
        assertEquals(2, counter.count.value)
        now = 118_999
        counter.tick()
        assertEquals(2, counter.count.value)
        now = 119_000
        counter.tick()
        assertEquals(1, counter.count.value)
        now = 120_000
        counter.tick()
        assertEquals(0, counter.count.value)
    }

    @Test fun firstRequestAfterAnIdlePeriodDropsOldRequests() {
        var now = 0L
        val counter = ApiRequestWindow { now }
        repeat(4) { counter.record() }
        now = 90_000
        counter.record()
        assertEquals(1, counter.count.value)
    }

    @Test fun timeWithoutRequestsDoesNotIncreaseTheCount() {
        var now = 0L
        val counter = ApiRequestWindow { now }
        repeat(100) { now += 1_000; counter.tick() }
        assertEquals(0, counter.count.value)
    }

    @Test fun concurrentClientsShareOneCountWithoutLosingRequests() {
        val counter = ApiRequestWindow { 100L }
        val clients = List(4) { Thread { repeat(250) { counter.record() } } }
        clients.forEach { it.start() }
        clients.forEach { it.join() }
        assertEquals(1_000, counter.count.value)
    }
}
