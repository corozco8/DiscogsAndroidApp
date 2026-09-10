package com.example.discogsandroidapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailScreen(
    order: DiscogsOrder,
    messageState: OrderMessagesUiState,
    onBackClick: () -> Unit,
    onStatusChange: (String) -> Unit,
    onItemClick: (Int) -> Unit,
    onSendMessage: (String) -> Unit
) {
    var messageText by remember(order.id) {
        mutableStateOf("")
    }
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

            // 5. Items List
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

                // 6. Financial Totals
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

                // 7. Buyer Info
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

                // 8. Messages Header
                item {
                    val messageCount =
                        (messageState as? OrderMessagesUiState.Success)
                            ?.messages
                            ?.size
                            ?: 0

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Messages",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )

                        if (messageCount > 0) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = "$messageCount",
                                    modifier = Modifier.padding(
                                        horizontal = 10.dp,
                                        vertical = 4.dp
                                    ),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }

                // 9. Message Composer - above message history
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        elevation = CardDefaults.cardElevation(
                            defaultElevation = 2.dp
                        ),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp)
                        ) {
                            Text(
                                text = "Message buyer",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            Spacer(
                                modifier = Modifier.height(8.dp)
                            )

                            OutlinedTextField(
                                value = messageText,
                                onValueChange = {
                                    messageText = it
                                },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = {
                                    Text("Type a message...")
                                },
                                minLines = 2,
                                maxLines = 5,
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(
                                    imeAction = ImeAction.Send
                                ),
                                keyboardActions = KeyboardActions(
                                    onSend = {
                                        val message =
                                            messageText.trim()

                                        if (message.isNotEmpty()) {
                                            onSendMessage(message)
                                            messageText = ""
                                        }
                                    }
                                )
                            )

                            Spacer(
                                modifier = Modifier.height(10.dp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Button(
                                    onClick = {
                                        val message =
                                            messageText.trim()

                                        if (message.isNotEmpty()) {
                                            onSendMessage(message)
                                            messageText = ""
                                        }
                                    },
                                    enabled = messageText.isNotBlank(),
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text("Send")
                                }
                            }
                        }
                    }
                }

                // 10. Message History - newest first
                when (messageState) {
                    OrderMessagesUiState.Idle,
                    OrderMessagesUiState.Loading -> {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(20.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )

                                    Spacer(
                                        modifier = Modifier.width(12.dp)
                                    )

                                    Text(
                                        text = "Loading conversation...",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    is OrderMessagesUiState.Error -> {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Text(
                                    text = "Could not load messages: ${messageState.message}",
                                    modifier = Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    is OrderMessagesUiState.Success -> {
                        // Newest message at the top, oldest at the bottom.
                        val messages =
                            messageState.messages.sortedByDescending { message ->
                                message.timestamp ?: ""
                            }

                        if (messages.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "No messages yet",
                                            fontWeight = FontWeight.SemiBold
                                        )

                                        Spacer(
                                            modifier = Modifier.height(4.dp)
                                        )

                                        Text(
                                            text = "Start the conversation with the buyer below.",
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            items(
                                items = messages,
                                key = { message ->
                                    message.id
                                        ?: "${message.timestamp}-${message.message}-${message.subject}"
                                }
                            ) { orderMessage ->

                                val buyerUsername =
                                    order.buyer?.username

                                val senderUsername =
                                    orderMessage.from?.username

                                val isBuyer =
                                    !senderUsername.isNullOrBlank() &&
                                            senderUsername == buyerUsername

                                val isSeller =
                                    !senderUsername.isNullOrBlank() &&
                                            senderUsername != buyerUsername

                                val body =
                                    orderMessage.message
                                        ?.takeIf { it.isNotBlank() }
                                        ?: orderMessage.subject
                                        ?: "Order update"

                                if (!isBuyer && !isSeller) {
                                    // Discogs/system order event.
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(
                                                    horizontal = 14.dp,
                                                    vertical = 8.dp
                                                ),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Text(
                                                    text = body,
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )

                                                orderMessage.timestamp?.let { timestamp ->
                                                    Spacer(
                                                        modifier = Modifier.height(2.dp)
                                                    )

                                                    Text(
                                                        text = formatOrderMessageTimestamp(timestamp),
                                                        fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.outline
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement =
                                            if (isBuyer) {
                                                Arrangement.Start
                                            } else {
                                                Arrangement.End
                                            }
                                    ) {
                                        Card(
                                            modifier = Modifier.fillMaxWidth(0.84f),
                                            shape =
                                                if (isBuyer) {
                                                    RoundedCornerShape(
                                                        topStart = 6.dp,
                                                        topEnd = 18.dp,
                                                        bottomStart = 18.dp,
                                                        bottomEnd = 18.dp
                                                    )
                                                } else {
                                                    RoundedCornerShape(
                                                        topStart = 18.dp,
                                                        topEnd = 6.dp,
                                                        bottomStart = 18.dp,
                                                        bottomEnd = 18.dp
                                                    )
                                                },
                                            colors = CardDefaults.cardColors(
                                                containerColor =
                                                    if (isBuyer) {
                                                        MaterialTheme.colorScheme.surfaceVariant
                                                    } else {
                                                        MaterialTheme.colorScheme.primaryContainer
                                                    }
                                            )
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(14.dp)
                                            ) {
                                                Text(
                                                    text =
                                                        if (isBuyer) {
                                                            senderUsername ?: "Buyer"
                                                        } else {
                                                            "You"
                                                        },
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                    color =
                                                        if (isBuyer) {
                                                            MaterialTheme.colorScheme.onSurfaceVariant
                                                        } else {
                                                            MaterialTheme.colorScheme.onPrimaryContainer
                                                        }
                                                )

                                                Spacer(
                                                    modifier = Modifier.height(5.dp)
                                                )

                                                Text(
                                                    text = body,
                                                    fontSize = 14.sp,
                                                    lineHeight = 20.sp
                                                )

                                                orderMessage.timestamp?.let { timestamp ->
                                                    Spacer(
                                                        modifier = Modifier.height(7.dp)
                                                    )

                                                    Text(
                                                        text = formatOrderMessageTimestamp(timestamp),
                                                        fontSize = 10.sp,
                                                        color =
                                                            if (isBuyer) {
                                                                MaterialTheme.colorScheme.outline
                                                            } else {
                                                                MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                                                    alpha = 0.7f
                                                                )
                                                            }
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

                // 11. Action Buttons
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

private fun formatOrderMessageTimestamp(
    timestamp: String
): String {
    // Discogs timestamps are ISO-style strings. This keeps the UI
    // readable without requiring extra date/time dependencies.
    val cleaned = timestamp
        .replace("T", " ")
        .replace("Z", "")

    return if (cleaned.length >= 16) {
        cleaned.take(16)
    } else {
        cleaned
    }
}

