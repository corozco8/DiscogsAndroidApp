package com.example.discogsandroidapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
fun ReleaseDetails(
    release: DiscogsRelease,
    priceSummary: ReleasePriceSummary? = null,
    onBackClick: () -> Unit,
    onSellConfirm: (price: Double, condition: String, sleeve: String, comments: String) -> Unit,
    onViewListingsClick: (releaseId: Long) -> Unit = {}, // Parameter included
    modifier: Modifier = Modifier
) {
    var showSellDialog by remember { mutableStateOf(false) }

    if (showSellDialog) {
        AddListingDialog(
            onDismiss = { showSellDialog = false },
            onSave = { price, condition, sleeve, comments ->
                showSellDialog = false
                onSellConfirm(price, condition, sleeve, comments)
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        // Large Album Art
        val imageUrl = release.images?.firstOrNull()?.uri
            ?: release.thumb.takeIf { !it.isNullOrBlank() }
            ?: "https://via.placeholder.com/400"

        AsyncImage(
            model = imageUrl,
            contentDescription = "Album Cover",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = release.title ?: "Unknown Title",
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 32.sp
        )

        Text(
            text = release.artists?.joinToString(", ") { it.name ?: "" } ?: "Unknown Artist",
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // REAL-TIME SALES RANGE CARD
        SalesRangeCard(
            summary = priceSummary ?: ReleasePriceSummary(),
            onListingsClick = { release.id?.let { onViewListingsClick(it) } } // Pass the action down
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Sell a copy button
        Button(
            onClick = { showSellDialog = true },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(imageVector = Icons.Default.Sell, contentDescription = "Sell a copy", modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = "Sell a copy", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                @Composable
                fun InfoRow(label: String, value: String) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(text = label, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.35f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(text = value, modifier = Modifier.weight(0.65f), color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                val labelText = release.labels?.firstOrNull()?.name?.let {
                    "$it ${release.labels.firstOrNull()?.catno?.let { cat -> "– $cat" } ?: ""}"
                } ?: "Unknown"

                InfoRow("Label:", labelText)
                InfoRow("Format:", release.formats?.firstOrNull()?.name ?: "Vinyl")
                InfoRow("Country:", release.country ?: "Unknown")
                InfoRow("Released:", release.year?.toString() ?: "Unknown")
                InfoRow("Genre:", release.genres?.joinToString(", ") ?: "Unknown")
                InfoRow("Style:", release.styles?.joinToString(", ") ?: "Unknown")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddListingDialog(
    onDismiss: () -> Unit,
    onSave: (Double, String, String, String) -> Unit
) {
    var price by remember { mutableStateOf("") }
    var condition by remember { mutableStateOf("Not Graded") }
    var sleeveCondition by remember { mutableStateOf("Not Graded") }
    var comments by remember { mutableStateOf("") }

    // Error states
    var conditionError by remember { mutableStateOf(false) }
    var priceError by remember { mutableStateOf(false) }

    val conditions = listOf("Not Graded", "Mint (M)", "Near Mint (NM or M-)", "Very Good Plus (VG+)", "Very Good (VG)", "Good Plus (G+)", "Good (G)", "Fair (F)", "Poor (P)")
    val sleeveConditions = listOf("Not Graded", "Mint (M)", "Near Mint (NM or M-)", "Very Good Plus (VG+)", "Very Good (VG)", "Good Plus (G+)", "Good (G)", "Fair (F)", "Poor (P)")

    var conditionExpanded by remember { mutableStateOf(false) }
    var sleeveExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("List Item For Sale") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // 1. Price Field
                OutlinedTextField(
                    value = price,
                    onValueChange = {
                        price = it
                        if (priceError) priceError = false // clear error as they type
                    },
                    label = { Text("Price (USD)") },
                    singleLine = true,
                    isError = priceError,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        if (priceError) {
                            Text("Price must be greater than $0.00")
                        }
                    }
                )

                // 2. Media Condition Dropdown
                ExposedDropdownMenuBox(
                    expanded = conditionExpanded,
                    onExpandedChange = { conditionExpanded = !conditionExpanded }
                ) {
                    OutlinedTextField(
                        value = condition,
                        onValueChange = {},
                        readOnly = true,
                        isError = conditionError,
                        label = { Text("Media Condition") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = conditionExpanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        supportingText = {
                            if (conditionError) {
                                Text("Media condition is required to list an item.")
                            }
                        }
                    )
                    ExposedDropdownMenu(
                        expanded = conditionExpanded,
                        onDismissRequest = { conditionExpanded = false }
                    ) {
                        conditions.forEach { selectionOption ->
                            DropdownMenuItem(
                                text = { Text(selectionOption) },
                                onClick = {
                                    condition = selectionOption
                                    conditionExpanded = false
                                    if (selectionOption != "Not Graded") conditionError = false // clear error on select
                                }
                            )
                        }
                    }
                }

                // 3. Sleeve Condition Dropdown (No validation needed)
                ExposedDropdownMenuBox(
                    expanded = sleeveExpanded,
                    onExpandedChange = { sleeveExpanded = !sleeveExpanded }
                ) {
                    OutlinedTextField(
                        value = sleeveCondition,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Sleeve Condition") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sleeveExpanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = sleeveExpanded,
                        onDismissRequest = { sleeveExpanded = false }
                    ) {
                        sleeveConditions.forEach { selectionOption ->
                            DropdownMenuItem(
                                text = { Text(selectionOption) },
                                onClick = {
                                    sleeveCondition = selectionOption
                                    sleeveExpanded = false
                                }
                            )
                        }
                    }
                }

                // 4. Comments Field
                OutlinedTextField(
                    value = comments,
                    onValueChange = { comments = it },
                    label = { Text("Description / Comments") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                // Strip out currency symbols or letters just in case they typed "$15.00"
                val cleanPriceString = price.replace(Regex("[^0-9.]"), "")
                val parsedPrice = cleanPriceString.toDoubleOrNull() ?: 0.0

                var hasError = false

                // Check Media Condition
                if (condition == "Not Graded") {
                    conditionError = true
                    hasError = true
                } else {
                    conditionError = false
                }

                // Check Price
                if (parsedPrice <= 0.0) {
                    priceError = true
                    hasError = true
                } else {
                    priceError = false
                }

                // Save if everything is valid
                if (!hasError) {
                    onSave(parsedPrice, condition, sleeveCondition, comments)
                }
            }) { Text("List Item") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun SalesRangeCard(
    summary: ReleasePriceSummary,
    onListingsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Helper function to format prices or return N/A
    fun formatPrice(price: Double?): String {
        return if (price != null && price > 0.0) "$${String.format(java.util.Locale.getDefault(), "%.2f", price)}" else "N/A"
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Suggested Value",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Based on current market",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Low / Median / High Price Columns
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Low
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text("Low", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Text(
                            text = formatPrice(summary.low),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(if (summary.low != null && summary.low > 0) summary.currency else "", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    }

                    HorizontalDivider(
                        modifier = Modifier.weight(0.5f).padding(horizontal = 2.dp),
                        thickness = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )

                    // Median
                    Column(
                        modifier = Modifier.weight(1.2f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Median", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Text(
                            text = formatPrice(summary.median),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                        Text(if (summary.median != null && summary.median > 0) summary.currency else "", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    }

                    HorizontalDivider(
                        modifier = Modifier.weight(0.5f).padding(horizontal = 2.dp),
                        thickness = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )

                    // High
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text("High", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Text(
                            text = formatPrice(summary.high),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(if (summary.high != null && summary.high > 0) summary.currency else "", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Only show this row if there are actually copies for sale and a valid asking price
        if (summary.numForSale > 0 && summary.lowestAskingPrice != null && summary.lowestAskingPrice > 0.0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onListingsClick() }
                    .padding(vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ShoppingBag,
                    contentDescription = "For Sale",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${summary.numForSale} copies for sale from ",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "$${String.format(java.util.Locale.getDefault(), "%.2f", summary.lowestAskingPrice)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}
