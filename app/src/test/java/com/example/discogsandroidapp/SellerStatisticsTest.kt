package com.example.discogsandroidapp

import java.util.Calendar
import java.util.TimeZone

class SellerStatisticsTest {
@org.junit.Test
fun reportCalculationsHandleCoverageAndComparisons() {
    var checks = 0
    fun verify(ok: Boolean) { check(ok) { "Statistics regression ${checks + 1} failed" }; checks++ }
    val utc = TimeZone.getTimeZone("UTC")
    fun date(y: Int, m: Int, d: Int) = Calendar.getInstance(utc).apply { clear(); set(y, m - 1, d) }.timeInMillis
    val now = date(2026, 7, 23)
    fun order(id: String, status: String = "Shipped", amount: Double? = 20.0, buyer: String = "Alice", time: Long = date(2026, 2, 1), currency: String = "USD", country: String? = "US") =
        LocalOrderEntity(id, status, null, time, null, time, null, buyer, 5.0, 2.0, amount, currency, now, country)
    fun item(order: String, key: String, listed: Long? = date(2026, 1, 1), listing: Long = key.toLong()) =
        LocalOrderItemEntity(order, key, listing, 123, "Record", "", "VG+", "VG+", null, 10.0, "USD", listed)
    fun inventory(id: Long, exact: Boolean = true, status: String = "For Sale", currency: String = "USD") =
        LocalInventoryListingEntity(id, 123, "Artist", "Title", "", status, "VG+", "VG+", "", 12.0, currency, null, date(2026, 1, 1), exact, now, now)
    val rows = listOf(order("a"), order("b", amount = 40.0, country = "GB"),
        order("c", status = "Cancelled (Item Unavailable)"), order("d", status = "Refund Sent"),
        order("e", status = "Invoice Sent"), order("other", currency = "EUR", amount = 900.0),
        order("old", time = date(2025, 2, 1), amount = 30.0), order("future", time = date(2026, 8, 1)),
        order("oldLater", time = date(2025, 8, 1), amount = 999.0), order("undated", time = 0))
    val items = listOf(item("a", "1"), item("a", "2", date(2026, 1, 12)), item("b", "3", date(2026, 3, 1)))
    val stats = calculateSellerStatistics(rows + rows[0], items + items[0], listOf(inventory(1), inventory(4), inventory(5, false), inventory(6, status = "Sold"), inventory(7, currency = "EUR")), 2026, "USD", "US", now, utc)
    verify(stats.current.orders == 2)
    verify(stats.current.revenue == 60.0)
    verify(stats.current.averageValue == 30.0)
    verify(stats.previous.revenue == 30.0) // No August-to-YTD comparison.
    verify(stats.current.items == 3 && stats.current.averageSize == 1.5)
    verify(stats.current.repeatBuyers == 1 && stats.current.repeatRate == 100.0)
    verify(stats.current.allOrders == 5 && stats.current.cancellations == 1)
    verify(stats.current.cancellationRate == 20.0)
    verify(kotlin.math.abs(stats.current.refundStatusRate!! - 100.0 / 3) < .0001)
    verify(stats.activeListings == 4 && stats.inventoryValue == 36.0 && stats.pricedListings == 3)
    verify(stats.knownNewListings == 5) // Duplicate sold/active listing ID counted once; inexact date excluded.
    verify(stats.saleDateSamples == 2 && stats.medianDaysToSale == 25.5) // Negative interval omitted.
    verify(stats.domestic == 1 && stats.international == 1 && stats.unknownCountry == 0)
    verify(stats.excludedCurrencyOrders == 1 && stats.undatedOrders == 1)
    verify(stats.countries.sumOf { it.orders } == 2 && stats.regions.size == 2)
    verify(stats.months.size == 7 && stats.months[1].current.orders == 2)
    verify(stats.monthlyNewListings.sumOf { it.second } == 5)
    verify(stats.destinationMonths[1].domestic == 1 && stats.destinationMonths[1].international == 1)
    verify(stats.destinationMonths.sumOf { it.unknown } == 0)
    val empty = calculateSellerStatistics(emptyList(), emptyList(), emptyList(), 2026, "USD", "US", now, utc)
    verify(empty.current.orders == 0 && empty.current.cancellationRate == null && empty.current.averageSize == null)
    verify(empty.current.repeatRate == null && empty.medianDaysToSale == null && empty.countries.isEmpty())
    val missing = calculateSellerStatistics(listOf(order("missing", amount = null, buyer = "Unknown buyer", country = null)), emptyList(), emptyList(), 2026, "USD", "US", now, utc)
    verify(missing.current.revenue == null && missing.current.revenueOrders == 0 && missing.current.averageValue == null)
    verify(missing.current.repeatRate == null && missing.current.identifiedOrders == 0 && missing.unknownCountry == 1)
    val invalid = calculateSellerStatistics(listOf(order("nan", amount = Double.NaN), order("negative", amount = -10.0), order("zero", amount = 0.0)), emptyList(), emptyList(), 2026, "USD", "US", now, utc)
    verify(invalid.current.revenueOrders == 1 && invalid.current.revenue == 0.0)
    verify(statisticsCountryFromAddress("Name\nStreet\nUnited States") == "US")
    verify(statisticsCountryFromAddress("Name\nStreet\nUnited Kingdom\n") == "GB")
    verify(statisticsCountryFromAddress("Name\nStreet\nCA") == null)
    verify(statisticsCountryFromAddress("Name\nLos Angeles, CA 90001") == null)
    verify(statisticsCountryFromAddress(null) == null)
    verify(statisticsCountryFromAddress("Street\nGERMANY") == "DE")
    verify(statisticsRegion("JP") == "East & Southeast Asia" && statisticsRegion("ZZ") == "Other regions")
    verify(statisticsGrowth(60.0, 30.0) == 100.0 && statisticsGrowth(20.0, 0.0) == null)
    verify(statisticsRevenueScore(5.0) == "Strong" && statisticsRevenueScore(-5.0) == "Stable")
    verify(statisticsRevenueScore(-9.9) == "At risk" && statisticsRevenueScore(-10.0) == "Off track")
    val leap = statisticsPeriod(2024, date(2024, 2, 29), utc, comparison = true)
    verify(leap.endExclusive == date(2023, 2, 28))
    val full = statisticsPeriod(2025, now, utc)
    verify(full.start == date(2025, 1, 1) && full.endExclusive == date(2026, 1, 1))
    verify(!full.contains(full.endExclusive) && full.contains(full.start))
    val la = TimeZone.getTimeZone("America/Los_Angeles")
    verify(statisticsPeriod(2025, now, la).start == date(2025, 1, 1) + 8 * 3_600_000L)
    println("$checks seller-statistics regression checks passed")
}

}
