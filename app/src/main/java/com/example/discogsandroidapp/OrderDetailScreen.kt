package com.example.discogsandroidapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.foundation.clickable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailScreen(
    order: DiscogsOrder,
    onBackClick: () -> Unit,
    onStatusChange: (String) -> Unit,
    onItemClick: (Int) -> Unit // <-- ADD THIS PARAMETER
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Order #${order.id ?: "N/A"}", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Order Header Info
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val safeItems = order.items ?: emptyList()
                    val itemWord = if (safeItems.size == 1) "item" else "items"
                    Text(
                        text = "${safeItems.size} $itemWord",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = (order.status ?: "Unknown").uppercase(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Created: ${order.created ?: "N/A"}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 2. Items List
            val items = order.items ?: emptyList()
            if (items.isNotEmpty()) {
                item {
                    Text("Order Items", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                items(items) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Trigger the network call when tapped!
                                item.release?.id?.let { releaseId ->
                                    onItemClick(releaseId.toInt())
                                }
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = item.release?.thumbnail
                                    ?: "https://via.placeholder.com/150",
                                contentDescription = "Thumbnail",
                                modifier = Modifier.size(60.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))

                            // UPDATED: Expanded Column to hold conditions and dates
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.release?.description ?: item.release?.title
                                    ?: "Unknown Item",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))

                                // Grades & ID
                                val recordGrade = item.media_condition ?: item.condition
                                recordGrade?.let {
                                    Text(
                                        text = "Media: $it",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                item.sleeve_condition?.let {
                                    Text(
                                        text = "Sleeve: $it",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Text(
                                    text = "ID: ${item.id ?: "N/A"}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                // Date Listed
                                val dateListed = item.posted ?: item.date_added
                                dateListed?.let { date ->
                                    val displayDate = if (date.length >= 10) date.take(10) else date
                                    Text(
                                        text = "Listed: $displayDate",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }


                                val currency = item.price?.currency ?: "$"
                                val priceVal = item.price?.value ?: 0.00
                                val formattedItemPrice = String.format(
                                    java.util.Locale.getDefault(),
                                    "%s %.2f",
                                    currency,
                                    priceVal
                                )

                                Text(
                                    text = formattedItemPrice,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                // 3. Financial Totals
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    val currency = order.total?.currency ?: "$"
                    val totalVal = order.total?.value ?: 0.00
                    val formattedTotal =
                        String.format(java.util.Locale.getDefault(), "%s %.2f", currency, totalVal)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Total", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        Text(
                            formattedTotal,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // 4. Buyer Info
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Buyer Information",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Username: ${order.buyer?.username ?: "Unknown"}",
                                fontSize = 14.sp
                            )
                            // Note: To get the actual shipping address string, you may need to add a `shipping_address`
                            // field to your DiscogsOrder data class if the API provides it!
                        }
                    }
                }

                // 5. Action Buttons
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onStatusChange("In Progress") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("In Progress")
                        }
                        Button(
                            onClick = { onStatusChange("Shipped") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Mark Shipped")
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}