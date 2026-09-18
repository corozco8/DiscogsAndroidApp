package com.example.discogsandroidapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

private const val DAY_MS = 24L * 60L * 60L * 1000L

private fun formatMoney(
    value: Double,
    currency: String = "USD"
): String {
    val prefix =
        when (currency.uppercase(Locale.US)) {
            "USD" -> "$"
            "EUR" -> "€"
            "GBP" -> "£"
            "CAD" -> "C$"
            "AUD" -> "A$"
            else -> "$currency "
        }

    return prefix +
            String.format(
                Locale.US,
                "%,.2f",
                value
            )
}

private fun formatWholeNumber(value: Int): String {
    return String.format(
        Locale.US,
        "%,d",
        value
    )
}

private fun formatSyncTime(epochMs: Long?): String {
    if (epochMs == null || epochMs <= 0L) {
        return "Not synced yet"
    }

    return SimpleDateFormat(
        "MMM d, h:mm a",
        Locale.getDefault()
    ).format(Date(epochMs))
}

private fun ageDays(
    listedAtEpochMs: Long,
    now: Long = System.currentTimeMillis()
): Long {
    return max(
        0L,
        (now - listedAtEpochMs) / DAY_MS
    )
}

private fun orderCountsAsSale(status: String): Boolean {
    return when (status.trim().lowercase(Locale.US)) {
        "payment received",
        "in progress",
        "shipped" -> true
        else -> false
    }
}

@Composable
private fun SellerToolHeader(
    title: String,
    subtitle: String,
    onBackClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = 8.dp,
                vertical = 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBackClick) {
            Icon(
                imageVector =
                    Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back"
            )
        }

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant
            )
        }

        IconButton(onClick = onRefreshClick) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Sync now"
            )
        }
    }
}

@Composable
fun InventoryAgingScreen(
    inventory: List<LocalInventoryListingEntity>,
    syncState: LocalSyncStateEntity?,
    onBackClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    var query by remember {
        mutableStateOf("")
    }
    var minimumAgeDays by remember {
        mutableStateOf(30)
    }
    var oldestFirst by remember {
        mutableStateOf(true)
    }

    val now = System.currentTimeMillis()

    val filteredBase =
        inventory
            .filter { listing ->
                ageDays(
                    listing.listedAtEpochMs,
                    now
                ) >= minimumAgeDays
            }
            .filter { listing ->
                if (query.isBlank()) {
                    true
                } else {
                    val needle =
                        query.trim()
                            .lowercase(Locale.US)
                    listOf(
                        listing.artist,
                        listing.title,
                        listing.comments,
                        listing.listingId.toString(),
                        listing.releaseId.toString()
                    ).any {
                        it.lowercase(Locale.US)
                            .contains(needle)
                    }
                }
            }

    val filtered =
        if (oldestFirst) {
            filteredBase.sortedBy {
                it.listedAtEpochMs
            }
        } else {
            filteredBase.sortedByDescending {
                it.listedAtEpochMs
            }
        }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        SellerToolHeader(
            title = "Inventory Aging",
            subtitle =
                "Last inventory sync: ${formatSyncTime(syncState?.lastSuccessfulSyncAtEpochMs)}",
            onBackClick = onBackClick,
            onRefreshClick = onRefreshClick
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement =
                Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                30 to "30+ days",
                90 to "90+",
                180 to "180+",
                365 to "1+ year"
            ).forEach { (days, label) ->
                FilterChip(
                    selected =
                        minimumAgeDays == days,
                    onClick = {
                        minimumAgeDays = days
                    },
                    label = {
                        Text(label)
                    }
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = {
                Text("Search aged inventory")
            },
            singleLine = true,
            trailingIcon = {
                IconButton(
                    onClick = {
                        oldestFirst = !oldestFirst
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Sort,
                        contentDescription =
                            if (oldestFirst) {
                                "Sorted oldest first. Tap for newest first."
                            } else {
                                "Sorted newest first. Tap for oldest first."
                            },
                        modifier = Modifier.rotate(
                            if (oldestFirst) 0f else 180f
                        )
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 16.dp,
                    vertical = 8.dp
                )
        )

        Text(
            text =
                "${formatWholeNumber(filtered.size)} of ${formatWholeNumber(inventory.size)} active listings",
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = 4.dp
            ),
            color =
                MaterialTheme.colorScheme
                    .onSurfaceVariant,
            fontSize = 13.sp
        )

        if (inventory.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No local inventory yet. Tap refresh to sync.",
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    androidx.compose.foundation.layout.PaddingValues(
                        16.dp
                    ),
                verticalArrangement =
                    Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = filtered,
                    key = { it.listingId }
                ) { listing ->
                    val days =
                        ageDays(
                            listing.listedAtEpochMs,
                            now
                        )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation =
                            CardDefaults.cardElevation(
                                defaultElevation = 2.dp
                            )
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp)
                        ) {
                            Text(
                                text =
                                    if (listing.artist.isBlank()) {
                                        listing.title
                                    } else {
                                        "${listing.artist} - ${listing.title}"
                                    },
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(
                                modifier = Modifier.height(6.dp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement =
                                    Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text =
                                        "${listing.mediaCondition} / ${listing.sleeveCondition}",
                                    fontSize = 12.sp,
                                    color =
                                        MaterialTheme.colorScheme
                                            .onSurfaceVariant
                                )

                                listing.priceValue?.let {
                                    Text(
                                        text =
                                            formatMoney(
                                                it,
                                                listing.currency
                                            ),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Spacer(
                                modifier = Modifier.height(8.dp)
                            )

                            Text(
                                text =
                                    if (listing.listedDateIsExact) {
                                        "Listed $days days ago"
                                    } else {
                                        "In local cache for at least $days days"
                                    },
                                color =
                                    if (days >= 365) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class AnalyticsWindow(
    val label: String,
    val days: Int? = null,
    val yearToDate: Boolean = false
)

@Composable
fun SalesAnalyticsScreen(
    orders: List<LocalOrderEntity>,
    orderItems: List<LocalOrderItemEntity>,
    syncState: LocalSyncStateEntity?,
    onBackClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    val windows =
        listOf(
            AnalyticsWindow("30d", days = 30),
            AnalyticsWindow("90d", days = 90),
            AnalyticsWindow("1y", days = 365),
            AnalyticsWindow("YTD", yearToDate = true),
            AnalyticsWindow("All")
        )

    var selectedWindow by remember {
        mutableStateOf(windows[1])
    }

    val now = System.currentTimeMillis()
    val startOfCurrentYear =
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    val selectedDays = selectedWindow.days
    val cutoff =
        when {
            selectedWindow.yearToDate -> startOfCurrentYear
            selectedDays != null ->
                now - selectedDays * DAY_MS
            else -> null
        }

    val salesOrders =
        orders.filter { order ->
            orderCountsAsSale(order.status) &&
                    (
                            cutoff == null ||
                                    order.createdAtEpochMs >= cutoff
                            )
        }

    val itemsByOrder =
        orderItems.groupBy { it.orderId }

    fun itemSalesFor(order: LocalOrderEntity): Double {
        val itemValues =
            itemsByOrder[order.orderId]
                .orEmpty()
                .mapNotNull { it.priceValue }

        if (itemValues.isNotEmpty()) {
            return itemValues.sum()
        }

        return max(
            0.0,
            (order.totalValue ?: 0.0) -
                    (order.shippingValue ?: 0.0)
        )
    }

    val itemSales =
        salesOrders.sumOf { itemSalesFor(it) }
    val shipping =
        salesOrders.sumOf {
            it.shippingValue ?: 0.0
        }
    val fees =
        salesOrders.sumOf {
            it.feeValue ?: 0.0
        }
    val grossIncome =
        itemSales + shipping
    val netAfterDiscogsFees =
        grossIncome - fees
    val averageOrder =
        if (salesOrders.isEmpty()) {
            0.0
        } else {
            salesOrders.sumOf {
                it.totalValue ?: 0.0
            } / salesOrders.size
        }

    val currency =
        salesOrders
            .firstOrNull {
                it.currency.isNotBlank()
            }
            ?.currency
            ?: "USD"

    val monthly =
        salesOrders
            .filter { it.createdAtEpochMs > 0L }
            .groupBy { order ->
                SimpleDateFormat(
                    "yyyy-MM",
                    Locale.US
                ).format(Date(order.createdAtEpochMs))
            }
            .mapValues { (_, monthOrders) ->
                monthOrders.sumOf {
                    itemSalesFor(it)
                }
            }
            .toList()
            .sortedByDescending { it.first }
            .take(6)
            .reversed()

    val maxMonth =
        monthly.maxOfOrNull { it.second }
            ?.coerceAtLeast(1.0)
            ?: 1.0

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            androidx.compose.foundation.layout.PaddingValues(
                bottom = 24.dp
            )
    ) {
        item {
            SellerToolHeader(
                title = "Sales & Profit Analytics",
                subtitle =
                    buildString {
                        append("Last orders sync: ")
                        append(
                            formatSyncTime(
                                syncState?.lastSuccessfulSyncAtEpochMs
                            )
                        )
                        syncState?.note
                            ?.takeIf { it.isNotBlank() }
                            ?.let {
                                append(" • ")
                                append(it)
                            }
                    },
                onBackClick = onBackClick,
                onRefreshClick = onRefreshClick
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                windows.forEach { window ->
                    FilterChip(
                        selected =
                            selectedWindow == window,
                        onClick = {
                            selectedWindow = window
                        },
                        label = {
                            Text(window.label)
                        }
                    )
                }
            }
        }

        item {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement =
                    Arrangement.spacedBy(10.dp)
            ) {
                AnalyticsMetricCard(
                    label = "Orders",
                    value = formatWholeNumber(salesOrders.size)
                )
                AnalyticsMetricCard(
                    label = "Item sales",
                    value = formatMoney(
                        itemSales,
                        currency
                    )
                )
                AnalyticsMetricCard(
                    label = "Shipping collected",
                    value = formatMoney(
                        shipping,
                        currency
                    )
                )
                AnalyticsMetricCard(
                    label = "Gross income",
                    value = formatMoney(
                        grossIncome,
                        currency
                    )
                )
                AnalyticsMetricCard(
                    label = "Discogs fees",
                    value = formatMoney(
                        fees,
                        currency
                    )
                )
                AnalyticsMetricCard(
                    label = "Net after Discogs fees",
                    value = formatMoney(
                        netAfterDiscogsFees,
                        currency
                    ),
                    emphasized = true
                )
                AnalyticsMetricCard(
                    label = "Average order",
                    value = formatMoney(
                        averageOrder,
                        currency
                    )
                )
            }
        }

        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                color =
                    MaterialTheme.colorScheme
                        .surfaceVariant.copy(alpha = 0.45f)
            ) {
                Text(
                    text =
                        "Gross income is item sales plus shipping collected, before Discogs fees. " +
                                "Net after Discogs fees is not true accounting profit yet. " +
                                "It does not subtract record acquisition cost, postage, packing supplies, taxes, or payment-processing costs.",
                    modifier = Modifier.padding(14.dp),
                    fontSize = 12.sp,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "Recent monthly item sales",
                modifier = Modifier.padding(
                    horizontal = 16.dp
                ),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        items(monthly) { (monthKey, value) ->
            val parsedMonth =
                runCatching {
                    SimpleDateFormat(
                        "yyyy-MM",
                        Locale.US
                    ).parse(monthKey)
                }.getOrNull()

            val label =
                parsedMonth?.let {
                    SimpleDateFormat(
                        "MMM yyyy",
                        Locale.getDefault()
                    ).format(it)
                } ?: monthKey

            Column(
                modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 6.dp
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.SpaceBetween
                ) {
                    Text(label)
                    Text(
                        formatMoney(
                            value,
                            currency
                        ),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                LinearProgressIndicator(
                    progress = {
                        (value / maxMonth)
                            .toFloat()
                            .coerceIn(
                                0f,
                                1f
                            )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                )
            }
        }

        item {
            if (salesOrders.isEmpty()) {
                Text(
                    text =
                        "No locally synced paid orders in this period yet. Tap refresh to sync recent orders.",
                    modifier = Modifier.padding(16.dp),
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AnalyticsMetricCard(
    label: String,
    value: String,
    emphasized: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            if (emphasized) {
                CardDefaults.cardColors(
                    containerColor =
                        MaterialTheme.colorScheme
                            .primaryContainer
                )
            } else {
                CardDefaults.cardColors()
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement =
                Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant
            )
            Text(
                text = value,
                fontSize =
                    if (emphasized) {
                        19.sp
                    } else {
                        17.sp
                    },
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private data class CustomerSummary(
    val username: String,
    val orders: List<LocalOrderEntity>,
    val orderCount: Int,
    val totalValue: Double,
    val lastOrderAt: Long,
    val currency: String
)

@Composable
fun CustomerHistoryScreen(
    orders: List<LocalOrderEntity>,
    orderItems: List<LocalOrderItemEntity>,
    syncState: LocalSyncStateEntity?,
    onBackClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    var query by remember {
        mutableStateOf("")
    }
    var selectedCustomer by remember {
        mutableStateOf<String?>(null)
    }

    BackHandler(
        enabled = selectedCustomer != null
    ) {
        selectedCustomer = null
    }

    val summaries =
        orders
            .filter {
                it.buyerUsername.isNotBlank() &&
                        it.buyerUsername != "Unknown buyer"
            }
            .groupBy {
                it.buyerUsername
            }
            .map { (username, customerOrders) ->
                CustomerSummary(
                    username = username,
                    orders =
                        customerOrders.sortedByDescending {
                            it.createdAtEpochMs
                        },
                    orderCount = customerOrders.size,
                    totalValue =
                        customerOrders.sumOf {
                            it.totalValue ?: 0.0
                        },
                    lastOrderAt =
                        customerOrders.maxOfOrNull {
                            it.createdAtEpochMs
                        } ?: 0L,
                    currency =
                        customerOrders
                            .firstOrNull {
                                it.currency.isNotBlank()
                            }
                            ?.currency
                            ?: "USD"
                )
            }
            .filter {
                query.isBlank() ||
                        it.username.contains(
                            query.trim(),
                            ignoreCase = true
                        )
            }
            .sortedWith(
                compareByDescending<CustomerSummary> {
                    it.orderCount
                }.thenByDescending {
                    it.lastOrderAt
                }
            )

    val itemsByOrder =
        orderItems.groupBy { it.orderId }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        SellerToolHeader(
            title = "Customer History",
            subtitle =
                buildString {
                    append("${summaries.size} buyers • Last sync ")
                    append(
                        formatSyncTime(
                            syncState?.lastSuccessfulSyncAtEpochMs
                        )
                    )
                    syncState?.note
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            append(" • ")
                            append(it)
                        }
                },
            onBackClick =
                if (selectedCustomer != null) {
                    { selectedCustomer = null }
                } else {
                    onBackClick
                },
            onRefreshClick = onRefreshClick
        )

        val customer =
            selectedCustomer?.let { username ->
                summaries.firstOrNull {
                    it.username == username
                } ?: orders
                    .filter {
                        it.buyerUsername == username
                    }
                    .let { customerOrders ->
                        if (customerOrders.isEmpty()) {
                            null
                        } else {
                            CustomerSummary(
                                username = username,
                                orders =
                                    customerOrders.sortedByDescending {
                                        it.createdAtEpochMs
                                    },
                                orderCount = customerOrders.size,
                                totalValue =
                                    customerOrders.sumOf {
                                        it.totalValue ?: 0.0
                                    },
                                lastOrderAt =
                                    customerOrders.maxOfOrNull {
                                        it.createdAtEpochMs
                                    } ?: 0L,
                                currency =
                                    customerOrders
                                        .firstOrNull()
                                        ?.currency
                                        ?: "USD"
                            )
                        }
                    }
            }

        if (customer != null) {
            CustomerDetail(
                customer = customer,
                itemsByOrder = itemsByOrder
            )
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = {
                    Text("Search buyer username")
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 16.dp,
                        vertical = 8.dp
                    )
            )

            if (orders.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text =
                            "No local order history yet. Tap refresh to sync recent orders.",
                        color =
                            MaterialTheme.colorScheme
                                .onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        androidx.compose.foundation.layout.PaddingValues(
                            16.dp
                        ),
                    verticalArrangement =
                        Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        summaries,
                        key = { it.username }
                    ) { summary ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedCustomer =
                                        summary.username
                                },
                            elevation =
                                CardDefaults.cardElevation(
                                    defaultElevation = 2.dp
                                )
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement =
                                        Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = summary.username,
                                        fontWeight = FontWeight.Bold,
                                        color =
                                            MaterialTheme.colorScheme
                                                .primary
                                    )
                                    Text(
                                        text =
                                            "${summary.orderCount} order${if (summary.orderCount == 1) "" else "s"}",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Spacer(
                                    modifier = Modifier.height(6.dp)
                                )

                                Text(
                                    text =
                                        "Order total history: ${formatMoney(summary.totalValue, summary.currency)}",
                                    fontSize = 13.sp
                                )

                                if (summary.lastOrderAt > 0L) {
                                    Text(
                                        text =
                                            "Last order: ${SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(summary.lastOrderAt))}",
                                        fontSize = 12.sp,
                                        color =
                                            MaterialTheme.colorScheme
                                                .onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomerDetail(
    customer: CustomerSummary,
    itemsByOrder: Map<String, List<LocalOrderItemEntity>>
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            androidx.compose.foundation.layout.PaddingValues(
                16.dp
            ),
        verticalArrangement =
            Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = customer.username,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text =
                    "${customer.orderCount} orders • ${formatMoney(customer.totalValue, customer.currency)} total order value",
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        items(
            customer.orders,
            key = { it.orderId }
        ) { order ->
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Order #${order.orderId}",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text =
                                order.totalValue?.let {
                                    formatMoney(
                                        it,
                                        order.currency
                                    )
                                } ?: "",
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Text(
                        text = order.status,
                        fontSize = 12.sp,
                        color =
                            MaterialTheme.colorScheme
                                .primary
                    )

                    if (order.createdAtEpochMs > 0L) {
                        Text(
                            text =
                                SimpleDateFormat(
                                    "MMM d, yyyy",
                                    Locale.getDefault()
                                ).format(
                                    Date(
                                        order.createdAtEpochMs
                                    )
                                ),
                            fontSize = 12.sp,
                            color =
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant
                        )
                    }

                    val items =
                        itemsByOrder[order.orderId]
                            .orEmpty()

                    if (items.isNotEmpty()) {
                        Spacer(
                            modifier = Modifier.height(8.dp)
                        )
                        HorizontalDivider()
                        Spacer(
                            modifier = Modifier.height(8.dp)
                        )

                        items.forEach { item ->
                            Text(
                                text = item.title,
                                fontSize = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
