package com.example.discogsandroidapp

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

internal fun statisticsPaid(status: String) = status.trim().lowercase(Locale.US) in
    setOf("payment received", "in progress", "shipped")

internal data class StatisticsPeriod(val start: Long, val endExclusive: Long) {
    fun contains(time: Long) = time > 0 && time >= start && time < endExclusive
}

/** Current year is YTD; its comparison ends at the same local date/time last year. */
internal fun statisticsPeriod(year: Int, now: Long, zone: TimeZone, comparison: Boolean = false): StatisticsPeriod {
    val today = Calendar.getInstance(zone).apply { timeInMillis = now }
    val start = Calendar.getInstance(zone).apply { clear(); set(year, Calendar.JANUARY, 1) }
    val end = if (year == today.get(Calendar.YEAR)) today.clone() as Calendar else
        (start.clone() as Calendar).apply { add(Calendar.YEAR, 1) }
    if (comparison) { start.add(Calendar.YEAR, -1); end.add(Calendar.YEAR, -1) }
    return StatisticsPeriod(start.timeInMillis, end.timeInMillis)
}

internal data class StatisticsTotals(
    val orders: Int,
    val revenue: Double?,
    val revenueOrders: Int,
    val averageValue: Double?,
    val items: Int,
    val itemOrders: Int,
    val averageSize: Double?,
    val buyers: Int,
    val repeatBuyers: Int,
    val identifiedOrders: Int,
    val allOrders: Int,
    val cancellations: Int,
    val refunds: Int
) {
    val repeatRate get() = statisticsPercent(repeatBuyers, buyers)
    val cancellationRate get() = statisticsPercent(cancellations, allOrders)
    val refundStatusRate get() = statisticsPercent(refunds, orders + refunds)
}

internal data class StatisticsMonth(val month: Int, val current: StatisticsTotals, val previous: StatisticsTotals)
internal data class StatisticsDestinationMonth(val month: Int, val domestic: Int, val international: Int, val unknown: Int)
internal data class StatisticsMarket(val name: String, val orders: Int, val revenue: Double?, val pricedOrders: Int) {
    val averageValue get() = if (pricedOrders > 0) revenue?.div(pricedOrders) else null
}
internal data class SellerStatistics(
    val current: StatisticsTotals,
    val previous: StatisticsTotals,
    val months: List<StatisticsMonth>,
    val cancellationReasons: List<Pair<String, Int>>,
    val activeListings: Int,
    val inventoryValue: Double?,
    val pricedListings: Int,
    val knownNewListings: Int,
    val monthlyNewListings: List<Pair<Int, Int>>,
    val medianDaysToSale: Double?,
    val saleDateSamples: Int,
    val countries: List<StatisticsMarket>,
    val regions: List<StatisticsMarket>,
    val destinationMonths: List<StatisticsDestinationMonth>,
    val domestic: Int,
    val international: Int,
    val unknownCountry: Int,
    val excludedCurrencyOrders: Int,
    val undatedOrders: Int
)

internal fun statisticsPercent(numerator: Int, denominator: Int): Double? =
    if (denominator > 0) numerator * 100.0 / denominator else null

internal fun statisticsGrowth(current: Double?, previous: Double?): Double? =
    if (current != null && previous != null && previous > 0) (current - previous) * 100 / previous else null

internal fun statisticsRevenueScore(growth: Double?): String = when {
    growth == null -> "Not enough comparable data"
    growth >= 5 -> "Strong"
    growth >= -5 -> "Stable"
    growth > -10 -> "At risk"
    else -> "Off track"
}

private fun Double?.validAmount() = this?.takeIf { it.isFinite() && it >= 0 }
private fun currencyMatches(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

internal fun calculateSellerStatistics(
    orders: List<LocalOrderEntity>, items: List<LocalOrderItemEntity>,
    inventory: List<LocalInventoryListingEntity>, year: Int, currency: String,
    homeCountry: String, now: Long, zone: TimeZone = TimeZone.getDefault()
): SellerStatistics {
    val uniqueOrders = orders.distinctBy { it.orderId }
    val itemsByOrder = items.distinctBy { it.orderId to it.itemKey }.groupBy { it.orderId }
    val period = statisticsPeriod(year, now, zone)
    val priorPeriod = statisticsPeriod(year, now, zone, comparison = true)
    val dated = uniqueOrders.filter { period.contains(it.createdAtEpochMs) }
    val currentOrders = dated.filter { currencyMatches(it.currency, currency) }
    val previousOrders = uniqueOrders.filter { priorPeriod.contains(it.createdAtEpochMs) && currencyMatches(it.currency, currency) }
    fun totals(rows: List<LocalOrderEntity>): StatisticsTotals {
        val paid = rows.filter { statisticsPaid(it.status) }
        val amounts = paid.mapNotNull { it.totalValue.validAmount() }
        val withItems = paid.mapNotNull { itemsByOrder[it.orderId]?.takeIf { rows -> rows.isNotEmpty() } }
        val buyers = paid.mapNotNull { order ->
            order.buyerId?.let { "id:$it" } ?: order.buyerUsername.trim()
                .takeIf { it.isNotBlank() && !it.equals("Unknown buyer", true) }
                ?.let { "name:${it.lowercase(Locale.US)}" }
        }
        val buyerCounts = buyers.groupingBy { it }.eachCount()
        return StatisticsTotals(paid.size, if (paid.isEmpty()) 0.0 else amounts.takeIf { it.isNotEmpty() }?.sum(), amounts.size,
            amounts.takeIf { it.isNotEmpty() }?.average(), withItems.sumOf { it.size }, withItems.size,
            withItems.takeIf { it.isNotEmpty() }?.map { it.size }?.average(),
            buyerCounts.size, buyerCounts.count { it.value > 1 }, buyers.size, rows.size,
            rows.count { it.status.trim().startsWith("Cancelled", true) },
            rows.count { it.status.trim().equals("Refund Sent", true) })
    }
    fun month(time: Long) = Calendar.getInstance(zone).apply { timeInMillis = time }.get(Calendar.MONTH)
    val currentByMonth = currentOrders.groupBy { month(it.createdAtEpochMs) }
    val previousByMonth = previousOrders.groupBy { month(it.createdAtEpochMs) }
    val months = (0..month(period.endExclusive - 1)).map {
        StatisticsMonth(it, totals(currentByMonth[it].orEmpty()), totals(previousByMonth[it].orEmpty()))
    }
    val paid = currentOrders.filter { statisticsPaid(it.status) }
    val allPaidIds = uniqueOrders.filter { statisticsPaid(it.status) }.map { it.orderId }.toSet()
    val knownListings = mutableMapOf<Long, Long>()
    fun rememberListing(id: Long?, date: Long?) {
        if (id != null && date != null && date > 0) knownListings[id] = minOf(knownListings[id] ?: date, date)
    }
    val active = inventory.distinctBy { it.listingId }.filter { it.status.equals("For Sale", true) }
    active.filter { it.listedDateIsExact }.forEach { rememberListing(it.listingId, it.listedAtEpochMs) }
    items.filter { it.orderId in allPaidIds }.forEach { rememberListing(it.listingId, it.listedAtEpochMs) }
    val newDates = knownListings.values.filter { period.contains(it) }
    val newByMonth = newDates.groupingBy { month(it) }.eachCount()
    val saleDays = paid.flatMap { order ->
        itemsByOrder[order.orderId].orEmpty().mapNotNull { item ->
            item.listedAtEpochMs?.takeIf { it > 0 && it <= order.createdAtEpochMs }
                ?.let { (order.createdAtEpochMs - it) / 86_400_000.0 }
        }
    }.sorted()
    val median = if (saleDays.isEmpty()) null else
        (saleDays[(saleDays.size - 1) / 2] + saleDays[saleDays.size / 2]) / 2
    val inventoryAmounts = active.filter { currencyMatches(it.currency, currency) }.mapNotNull { it.priceValue.validAmount() }
    val withCountry = paid.filter { !it.buyerCountryCode.isNullOrBlank() }
    fun market(rows: List<LocalOrderEntity>, name: String): StatisticsMarket {
        val amounts = rows.mapNotNull { it.totalValue.validAmount() }
        return StatisticsMarket(name, rows.size, amounts.takeIf { it.isNotEmpty() }?.sum(), amounts.size)
    }
    return SellerStatistics(totals(currentOrders), totals(previousOrders), months,
        currentOrders.filter { it.status.trim().startsWith("Cancelled", true) }
            .groupingBy { it.status.trim() }.eachCount().toList().sortedByDescending { it.second },
        active.size, inventoryAmounts.takeIf { it.isNotEmpty() }?.sum(), inventoryAmounts.size,
        newDates.size, months.map { it.month to (newByMonth[it.month] ?: 0) }, median, saleDays.size,
        withCountry.groupBy { it.buyerCountryCode!! }.map { (country, rows) -> market(rows, statisticsCountryName(country)) }
            .sortedByDescending { it.orders },
        withCountry.groupBy { statisticsRegion(it.buyerCountryCode!!) }.map { (region, rows) -> market(rows, region) }
            .sortedByDescending { it.orders },
        months.map { bucket ->
            val rows = currentByMonth[bucket.month].orEmpty().filter { statisticsPaid(it.status) }
            val known = rows.filter { !it.buyerCountryCode.isNullOrBlank() }
            StatisticsDestinationMonth(bucket.month,
                known.count { it.buyerCountryCode.equals(homeCountry, true) },
                known.count { !it.buyerCountryCode.equals(homeCountry, true) }, rows.size - known.size)
        },
        withCountry.count { it.buyerCountryCode.equals(homeCountry, true) },
        withCountry.count { !it.buyerCountryCode.equals(homeCountry, true) },
        paid.size - withCountry.size, dated.size - currentOrders.size,
        uniqueOrders.count { it.createdAtEpochMs <= 0 })
}

internal fun statisticsCountryName(code: String): String = Locale("", code).getDisplayCountry(Locale.US)

// Only accept a full country name on the final address line. Two-letter strings
// could be state/province abbreviations and must not be guessed as countries.
internal fun statisticsCountryFromAddress(address: String?): String? {
    val last = address?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.lastOrNull() ?: return null
    return countryNames[last.lowercase(Locale.US)]
}
private val countryNames: Map<String, String> by lazy {
    Locale.getISOCountries().associateBy { statisticsCountryName(it).lowercase(Locale.US) } + mapOf(
        "usa" to "US", "u.s.a." to "US", "united states of america" to "US",
        "uk" to "GB", "great britain" to "GB", "england" to "GB", "scotland" to "GB",
        "wales" to "GB", "northern ireland" to "GB", "south korea" to "KR", "russia" to "RU"
    )
}
internal fun statisticsRegion(code: String): String = when (code) {
    "US", "CA" -> "USA & Canada"
    "GB", "IE" -> "UK & Ireland"
    "DK", "FI", "IS", "NO", "SE", "FO", "GL", "AX" -> "Nordic countries"
    "AT", "BE", "CH", "DE", "ES", "FR", "IT", "LI", "LU", "MC", "NL", "PT", "AD", "SM", "VA" -> "Western Europe"
    "AL", "BA", "BG", "BY", "CZ", "EE", "GR", "HR", "HU", "LT", "LV", "MD", "ME", "MK", "PL", "RO", "RS", "RU", "SI", "SK", "UA" -> "Eastern Europe & Russia"
    "AU", "NZ", "FJ", "PG", "NC", "PF", "WS", "TO", "VU" -> "Oceania"
    "AR", "BO", "BR", "CL", "CO", "CR", "CU", "DO", "EC", "GT", "HN", "MX", "NI", "PA", "PE", "PR", "PY", "SV", "UY", "VE" -> "Latin America"
    "CN", "HK", "ID", "JP", "KH", "KR", "LA", "MO", "MY", "PH", "SG", "TH", "TW", "VN" -> "East & Southeast Asia"
    "AE", "IL", "SA", "TR", "EG", "ZA", "MA", "NG", "KE", "TN", "DZ", "GH", "JO", "LB", "QA", "KW", "BH", "OM" -> "Middle East & Africa"
    else -> "Other regions"
}
