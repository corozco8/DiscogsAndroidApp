package com.example.discogsandroidapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormatSymbols
import java.text.NumberFormat
import java.util.Calendar
import java.util.Currency
import java.util.Locale

@Composable
fun SellerStatisticsScreen(
    orders: List<LocalOrderEntity>, orderItems: List<LocalOrderItemEntity>,
    inventory: List<LocalInventoryListingEntity>, syncState: LocalSyncStateEntity?,
    inventorySyncState: LocalSyncStateEntity?, profile: DiscogsProfile?,
    onBackClick: () -> Unit, onRefreshClick: () -> Unit
) {
    var showFinancialDetails by rememberSaveable { mutableStateOf(false) }
    if (showFinancialDetails) {
        BackHandler { showFinancialDetails = false }
        SalesAnalyticsScreen(orders, orderItems, syncState, { showFinancialDetails = false }, onRefreshClick)
        return
    }
    val now = remember(orders, inventory) { System.currentTimeMillis() }
    val thisYear = Calendar.getInstance().get(Calendar.YEAR)
    val years = remember(orders, thisYear) {
        (orders.filter { it.createdAtEpochMs > 0 }.map {
            Calendar.getInstance().apply { timeInMillis = it.createdAtEpochMs }.get(Calendar.YEAR)
        }.filter { it <= thisYear } + thisYear).distinct().sortedDescending()
    }
    var year by rememberSaveable { mutableStateOf(thisYear) }
    val currencies = remember(orders, inventory) {
        (orders.map { it.currency } + inventory.map { it.currency } + "USD")
            .map { it.trim().uppercase(Locale.US) }.filter { it.isNotBlank() }.distinct().sorted()
    }
    var currency by rememberSaveable { mutableStateOf("USD") }
    var section by rememberSaveable { mutableStateOf("Sales") }
    var homeCountry by rememberSaveable { mutableStateOf("US") }
    val homeCountries = remember(orders) {
        (listOf("US") + orders.mapNotNull { it.buyerCountryCode }).distinct().sortedBy { statisticsCountryName(it) }
    }
    val statistics by produceState<SellerStatistics?>(null, orders, orderItems, inventory, year, currency, homeCountry, now) {
        value = null
        value = withContext(Dispatchers.Default) {
            calculateSellerStatistics(orders, orderItems, inventory, year, currency, homeCountry, now)
        }
    }
    val complete = syncState != null && syncState.nextBackfillPage == null
    fun money(value: Double?) = value?.let {
        runCatching { NumberFormat.getCurrencyInstance(Locale.US).apply { this.currency = Currency.getInstance(currency) }.format(it) }
            .getOrElse { _ -> "$currency ${String.format(Locale.US, "%.2f", it)}" }
    } ?: "Not available"
    val stats = statistics
    val revenueComparable = complete && stats != null &&
        stats.current.revenueOrders == stats.current.orders && stats.previous.revenueOrders == stats.previous.orders
    val sizeComparable = complete && stats != null &&
        stats.current.itemOrders == stats.current.orders && stats.previous.itemOrders == stats.previous.orders
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBackClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Seller Statistics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefreshClick) { Icon(Icons.Default.Refresh, "Sync seller data") }
            }
            Text("Inspired by your Discogs seller report", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                StatisticsSelector(if (year == thisYear) "$year YTD" else "$year", years.map { it.toString() }) { year = it.toInt() }
                StatisticsSelector(currency, currencies) { currency = it }
            }
            Text(if (year == thisYear) "Compared with the same dates in ${year - 1}." else "Compared with calendar year ${year - 1}.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            StatisticsNote(buildString {
                append(if (complete) "Based on cached order history. " else "Partial history: results may change as older orders sync. ")
                syncState?.note?.let { append(it); append(". ") }
                append("Last orders sync: ")
                append(syncState?.lastSuccessfulSyncAtEpochMs?.takeIf { it > 0 }?.let {
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it))
                } ?: "Not yet synced")
            })
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf("Sales", "Operations", "Inventory", "Buyers").forEach { name ->
                    FilterChip(selected = section == name, onClick = { section = name }, label = { Text(name) })
                }
            }
        }
        if (stats == null) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        } else {
            if (stats.excludedCurrencyOrders > 0 || stats.undatedOrders > 0) item {
                StatisticsNote("${stats.excludedCurrencyOrders} orders in other/unknown currencies excluded from this year's order metrics. ${stats.undatedOrders} undated orders excluded from date comparisons. No currency conversion is applied.")
            }
            when (section) {
                "Sales" -> {
                    item { StatisticsMetric("Order revenue", money(stats.current.revenue), statisticsComparison(stats.current.revenue, stats.previous.revenue, revenueComparable)) }
                    item { StatisticsMetric("Paid orders", stats.current.orders.toString(), statisticsComparison(stats.current.orders.toDouble(), stats.previous.orders.toDouble(), complete)) }
                    item { StatisticsMetric("Average order value", money(stats.current.averageValue), statisticsComparison(stats.current.averageValue, stats.previous.averageValue, revenueComparable)) }
                    item { StatisticsMetric("Average order size", stats.current.averageSize?.let { statsNumber(it) + " items" } ?: "Not available", statisticsComparison(stats.current.averageSize, stats.previous.averageSize, sizeComparable)) }
                    item { StatisticsMetric("Items sold", stats.current.items.toString(), "Item details available for ${stats.current.itemOrders} of ${stats.current.orders} paid orders.") }
                    item { StatisticsMetric("Revenue health", if (revenueComparable) statisticsRevenueScore(statisticsGrowth(stats.current.revenue, stats.previous.revenue)) else "Waiting for complete totals", "Report thresholds: strong +5% or more; stable -5% to +5%; at risk below -5%; off track -10% or worse.") }
                    item { StatisticsNote("Paid = Payment Received, In Progress or Shipped. Revenue and average value use Discogs order totals, not profit; ${stats.current.revenueOrders} of ${stats.current.orders} totals available. Orders are grouped by creation date. Discogs' internal report may use different accounting rules.") }
                    item { OutlinedButton(onClick = { showFinancialDetails = true }, modifier = Modifier.fillMaxWidth()) { Text("Open sales & profit breakdown") } }
                    item { StatisticsHeading("Monthly revenue & paid orders") }
                    items(stats.months) { month ->
                        val name = DateFormatSymbols(Locale.US).months[month.month]
                        val maxRevenue = stats.months.flatMap { listOfNotNull(it.current.revenue, it.previous.revenue) }.maxOrNull() ?: 1.0
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(name, fontWeight = FontWeight.Bold)
                                Text("$year: ${money(month.current.revenue)} · ${month.current.orders} orders")
                                LinearProgressIndicator(progress = { ((month.current.revenue ?: 0.0) / maxRevenue.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                Text("${year - 1}: ${money(month.previous.revenue)} · ${month.previous.orders} orders", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                "Operations" -> {
                    item { StatisticsMetric("Cancellation rate", statsPercent(stats.current.cancellationRate), "${stats.current.cancellations} cancelled / ${stats.current.allOrders} orders created in the period. " + statisticsPointChange(stats.current.cancellationRate, stats.previous.cancellationRate, complete)) }
                    items(stats.cancellationReasons) { (name, count) -> StatisticsMetric(name, count.toString(), statsPercent(statisticsPercent(count, stats.current.cancellations)) + " of cancellations") }
                    item { StatisticsMetric("Refund Sent status rate", statsPercent(stats.current.refundStatusRate), "${stats.current.refunds} orders currently marked Refund Sent / ${stats.current.orders + stats.current.refunds} paid or Refund Sent orders. This is a status-based indicator, not a full refund rate; partial refunds and refunded cancellations may be absent.") }
                    item { StatisticsMetric("Shipped within 48 hours", "Not available", "The cached order data has no verified payment-to-shipment timestamps. Last activity can be a message, so it is not used as the shipping time.") }
                    item { StatisticsMetric("Median days to ship", "Not available", "Requires verified shipment events. A Shipped status alone cannot establish the shipment date.") }
                }
                "Inventory" -> {
                    item { StatisticsMetric("Items currently for sale", if (inventorySyncState == null) "Not yet synced" else stats.activeListings.toString(), "Current inventory snapshot across all currencies; not historical inventory for $year.") }
                    item { StatisticsMetric("Current asking value", money(stats.inventoryValue), "${stats.pricedListings} active $currency listings with known prices; excludes shipping.") }
                    item { StatisticsMetric("Known listings created in $year", stats.knownNewListings.toString(), "Unique listing IDs from current inventory and sold items with exact listing dates. This is a lower bound, not a complete listing history; removed listings and missing dates are excluded. Relisting can affect dates.") }
                    item { StatisticsMetric("Median days to sale", statsNumber(stats.medianDaysToSale), "Based on ${stats.saleDateSamples} sold items with valid listing dates, out of ${stats.current.items} cached sold items. Negative intervals are excluded.") }
                    item { StatisticsMetric("Listings updated in 90 days", "Not available", "Sync time only tells us when the app checked a listing. The original listing update timestamp is not retained, so this percentage cannot be measured reliably.") }
                    item { StatisticsHeading("Known new listings by month") }
                    items(stats.monthlyNewListings) { (month, count) ->
                        StatisticsMetric(DateFormatSymbols(Locale.US).months[month], count.toString())
                    }
                }
                "Buyers" -> {
                    item { StatisticsMetric("Repeat buyer rate", statsPercent(stats.current.repeatRate), "${stats.current.repeatBuyers} of ${stats.current.buyers} identified buyers placed more than one paid order in the selected period. ${stats.current.identifiedOrders} of ${stats.current.orders} orders have buyer identity. " + statisticsPointChange(stats.current.repeatRate, stats.previous.repeatRate, complete)) }
                    item { StatisticsMetric("All-time positive feedback", if (profile != null && profile.sellerNumRatings > 0) statsPercent(profile.sellerRating) else "Not available", "${profile?.sellerNumRatings ?: 0} ratings in the loaded Discogs profile. This is an all-time figure, independent of year and currency.") }
                    item { StatisticsMetric("Feedback health", when {
                        profile == null || profile.sellerNumRatings <= 0 -> "Not available"
                        profile.sellerRating >= 99 -> "Strong"
                        profile.sellerRating >= 97 -> "Stable"
                        profile.sellerRating >= 95 -> "At risk"
                        else -> "Off track"
                    }, "Uses all-time positive feedback: strong 99%+; stable 97%+; at risk 95%+; off track below 95%.") }
                    item { StatisticsNote("Annual feedback ratings, unrated orders and feedback changes require dated, order-linked feedback, which is not in the cached data.") }
                    item {
                        StatisticsHeading("Buyer destinations")
                        StatisticsSelector("Home: ${statisticsCountryName(homeCountry)}", homeCountries.map { statisticsCountryName(it) }) { chosen ->
                            homeCountry = homeCountries.first { statisticsCountryName(it) == chosen }
                        }
                    }
                    item { StatisticsMetric("Domestic / international", "${stats.domestic} / ${stats.international}", "${statsPercent(statisticsPercent(stats.international, stats.domestic + stats.international))} international among known destinations. ${stats.unknownCountry} paid orders have no recognized country. Country comes only from a recognized final shipping-address line, captured during sync.") }
                    item { StatisticsHeading("Top 5 buyer countries") }
                    if (stats.countries.isEmpty()) item { StatisticsNote("No recognized destination countries cached yet. Regular order sync will retain countries when provided; no country is guessed from buyer names or currency.") }
                    items(stats.countries.take(5)) { market -> StatisticsMetric(market.name, "${market.orders} orders", "Revenue ${money(market.revenue)} · Average ${money(market.averageValue)} (${market.pricedOrders} known totals)") }
                    item { StatisticsHeading("Order value by region") }
                    items(stats.regions) { market -> StatisticsMetric(market.name, money(market.averageValue), "${market.orders} orders · Revenue ${money(market.revenue)} · ${market.pricedOrders} known totals") }
                    item { StatisticsNote("Regional groups approximate the report; unassigned destinations appear under Other regions.") }
                    item { StatisticsHeading("Monthly domestic / international orders") }
                    items(stats.destinationMonths) { month ->
                        StatisticsMetric(DateFormatSymbols(Locale.US).months[month.month], "${month.domestic} / ${month.international}",
                            "${statsPercent(statisticsPercent(month.international, month.domestic + month.international))} international among known destinations · ${month.unknown} unknown")
                    }
                }
            }
        }
    }
}

private fun statsNumber(value: Double?) = value?.let { String.format(Locale.US, "%.2f", it) } ?: "Not available"
private fun statsPercent(value: Double?) = value?.let { String.format(Locale.US, "%.1f%%", it) } ?: "Not available"
private fun statisticsComparison(current: Double?, previous: Double?, complete: Boolean): String =
    if (!complete) "Year comparison pending complete history and metric data." else statisticsGrowth(current, previous)?.let {
        String.format(Locale.US, "%+.1f%% vs. matching prior-year period", it)
    } ?: "No comparable prior-year value."
private fun statisticsPointChange(current: Double?, previous: Double?, complete: Boolean): String =
    if (!complete) "Year comparison pending full history." else if (current != null && previous != null)
        String.format(Locale.US, "%+.1f percentage points vs. prior year.", current - previous) else "No comparable prior-year rate."

@Composable
private fun StatisticsHeading(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
@Composable
private fun StatisticsNote(text: String) { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable
private fun StatisticsMetric(title: String, value: String, note: String = "") {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (note.isNotBlank()) StatisticsNote(note)
        }
    }
}
@Composable
private fun StatisticsSelector(label: String, choices: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { expanded = false; onSelect(choice) }) }
        }
    }
}
