package com.example.discogsandroidapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone


private const val STALE_PAYMENT_RECEIVED_DAYS = 30
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

/**
 * Discogs can occasionally leave very old orders stuck in "Payment Received".
 *
 * Keep them out of the app's active order list once they are more than
 * 30 calendar days old. If the date cannot be parsed, keep the order visible
 * rather than accidentally hiding a legitimate order.
 */
fun visibleSellerOrders(
    orders: List<DiscogsOrder>,
    nowMillis: Long = System.currentTimeMillis()
): List<DiscogsOrder> {
    val dateParser =
        SimpleDateFormat(
            "yyyy-MM-dd",
            Locale.US
        ).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }

    val todayText =
        dateParser.format(
            java.util.Date(nowMillis)
        )

    val todayStart =
        dateParser.parse(todayText)
            ?.time
            ?: nowMillis

    return orders.filterNot { order ->
        val normalizedStatus =
            order.status
                ?.trim()
                ?.lowercase(Locale.US)
                .orEmpty()

        val isPaymentReceived =
            normalizedStatus == "payment received" ||
                    normalizedStatus == "payment recieved"

        if (!isPaymentReceived) {
            false
        } else {
            val createdDate =
                order.created
                    ?.takeIf { it.length >= 10 }
                    ?.take(10)
                    ?.let { dateText ->
                        runCatching {
                            dateParser.parse(dateText)
                        }.getOrNull()
                    }

            if (createdDate == null) {
                false
            } else {
                val ageInDays =
                    (todayStart - createdDate.time) /
                            MILLIS_PER_DAY

                ageInDays > STALE_PAYMENT_RECEIVED_DAYS
            }
        }
    }
}

@Composable
fun OrdersScreen(
    orders: List<DiscogsOrder>,
    isFetchingMore: Boolean = false,
    hasMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    onOrderClick: (DiscogsOrder) -> Unit = {}
) {
    val visibleOrders =
        visibleSellerOrders(orders)

    if (visibleOrders.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No active orders found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // The redundant "My Orders" header item was removed from here!

        items(visibleOrders) { order ->
            val safeStatus = order.status ?: "unknown"

            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOrderClick(order) },
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Order #${order.id ?: "N/A"}",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.primary
                        )

                        val statusColor = when (safeStatus.lowercase()) {
                            "payment received" -> MaterialTheme.colorScheme.primaryContainer
                            "in progress" -> MaterialTheme.colorScheme.secondaryContainer
                            "shipped" -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                        val statusTextColor = when (safeStatus.lowercase()) {
                            "payment received" -> MaterialTheme.colorScheme.onPrimaryContainer
                            "in progress" -> MaterialTheme.colorScheme.onSecondaryContainer
                            "shipped" -> MaterialTheme.colorScheme.onTertiaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = statusColor
                        ) {
                            Text(
                                text = safeStatus.uppercase(),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = statusTextColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Buyer: ${order.buyer?.username ?: "Unknown"}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )

                    val safeItems = order.items ?: emptyList()
                    val itemWord = if (safeItems.size == 1) "item" else "items"
                    Text(
                        text = "${safeItems.size} $itemWord ordered",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val currency = order.total?.currency ?: "$"
                        val priceVal = order.total?.value ?: 0.00
                        val formattedPrice = String.format(java.util.Locale.getDefault(), "%s %.2f", currency, priceVal)

                        Text(
                            text = "Total: $formattedPrice",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        val safeDate = order.created ?: ""
                        val displayDate = if (safeDate.length >= 10) safeDate.take(10) else safeDate
                        Text(
                            text = displayDate,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }

        if (hasMore || isFetchingMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isFetchingMore) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp
                        )
                    } else {
                        OutlinedButton(
                            onClick = onLoadMore
                        ) {
                            Text("Load more orders")
                        }
                    }
                }
            }
        }

    }
}
