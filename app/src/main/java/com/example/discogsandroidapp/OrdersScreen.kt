package com.example.discogsandroidapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
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
private fun OrderItemThumbnailStack(
    items: List<OrderItem>
) {
    val visibleItems = items.take(3)
    val overlapStep = 4
    val imageSize = 60
    val stackExtra = ((visibleItems.size - 1).coerceAtLeast(0) * overlapStep)

    Box(
        modifier = Modifier
            .width((imageSize + stackExtra).dp)
            .height((imageSize + stackExtra).dp),
        contentAlignment = Alignment.TopStart
    ) {
        if (visibleItems.isEmpty()) {
            Surface(
                modifier = Modifier.size(imageSize.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        } else {
            for (index in visibleItems.indices.reversed()) {
                val item = visibleItems[index]
                val offset = (index * overlapStep).dp
                val thumbnail = item.release?.thumbnail?.takeIf { it.isNotBlank() }

                Surface(
                    modifier = Modifier
                        .offset(x = offset, y = offset)
                        .size(imageSize.dp),
                    shape = RoundedCornerShape(6.dp),
                    shadowElevation = if (index == 0) 2.dp else 1.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    if (thumbnail != null) {
                        AsyncImage(
                            model = thumbnail,
                            contentDescription =
                                if (index == 0) "First ordered item cover"
                                else "Additional ordered item cover",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(6.dp))
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Album,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            if (items.size > visibleItems.size) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 1.dp
                ) {
                    Text(
                        text = "+${items.size - visibleItems.size}",
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
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
    val visibleOrders = visibleSellerOrders(orders)

    if (visibleOrders.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No active orders found.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        items(visibleOrders) { order ->
            val safeStatus = order.status ?: "unknown"
            val safeItems = order.items.orEmpty()

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

            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOrderClick(order) },
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OrderItemThumbnailStack(items = safeItems)

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "#${order.id ?: "N/A"}",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = statusColor
                            ) {
                                Text(
                                    text = safeStatus.uppercase(),
                                    modifier = Modifier.padding(
                                        horizontal = 7.dp,
                                        vertical = 3.dp
                                    ),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = statusTextColor,
                                    maxLines = 1
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        val buyer = order.buyer?.username ?: "Unknown"
                        val itemWord = if (safeItems.size == 1) "item" else "items"

                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = buyer,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "  •  ${safeItems.size} $itemWord",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val currency = order.total?.currency ?: "$"
                            val priceVal = order.total?.value ?: 0.00
                            val formattedPrice = String.format(
                                java.util.Locale.getDefault(),
                                "%s %,.2f",
                                currency,
                                priceVal
                            )

                            Text(
                                text = formattedPrice,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold
                            )

                            val safeDate = order.created.orEmpty()
                            val displayDate =
                                if (safeDate.length >= 10) safeDate.take(10) else safeDate

                            Text(
                                text = displayDate,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
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
                        OutlinedButton(onClick = onLoadMore) {
                            Text("Load more orders")
                        }
                    }
                }
            }
        }
    }
}
