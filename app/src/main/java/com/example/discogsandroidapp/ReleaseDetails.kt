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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import android.util.Log

private const val TAG = "ReleaseDetails"

@Composable
fun ReleaseDetails(
    release: DiscogsRelease,
    priceSummary: ReleasePriceSummary? = null,
    priceSuggestion: Double? = null,
    onBackClick: () -> Unit,
    onSellConfirm: (price: Double, condition: String, sleeve: String, comments: String) -> Unit,
    onViewListingsClick: (releaseId: Long) -> Unit = {},
    onConditionsChanged: (condition: String, sleeveCondition: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var showSellDialog by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    if (showSellDialog) {
        AddListingDialog(
            onDismiss = { showSellDialog = false },
            onSave = { price, condition, sleeve, comments ->
                showSellDialog = false
                Log.d(TAG, "Sell confirmation: price=$price, condition='$condition', sleeve='$sleeve', comments='$comments'")
                onSellConfirm(price, condition, sleeve, comments)
            },
            priceSuggestion = priceSuggestion,
            onConditionsChanged = onConditionsChanged
        )
    }

    SwipeRefreshWrapper(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            coroutineScope.launch {
                kotlinx.coroutines.delay(1000)
                isRefreshing = false
            }
        },
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // Large Album Art Carousel
            val imageUrls = if (!release.images.isNullOrEmpty()) {
                release.images.mapNotNull { it.uri }
            } else {
                listOf(release.thumb.takeIf { !it.isNullOrBlank() } ?: "https://via.placeholder.com/400")
            }

            val pagerState = rememberPagerState(pageCount = { imageUrls.size })

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) { page ->
                AsyncImage(
                    model = imageUrls[page],
                    contentDescription = "Album Cover ${page + 1}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Thumbnail Preview Row
            if (imageUrls.size > 1) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(imageUrls.size) { index ->
                        val isSelected = pagerState.currentPage == index

                        AsyncImage(
                            model = imageUrls[index],
                            contentDescription = "Thumbnail ${index + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .alpha(if (isSelected) 1f else 0.5f)
                                .border(
                                    width = if (isSelected) 2.dp else 0.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(index)
                                    }
                                }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left side: Title and Artist
                Column(
                    modifier = Modifier.weight(1f)
                ) {
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
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Right side: Sleek, compact Sell button
                FilledTonalButton(
                    onClick = { showSellDialog = true },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Sell,
                        contentDescription = "Sell",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Sell", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // REAL-TIME SALES RANGE CARD
            SalesRangeCard(
                summary = priceSummary ?: ReleasePriceSummary(),
                onListingsClick = { release.id?.let { onViewListingsClick(it) } }
            )

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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddListingDialog(
    onDismiss: () -> Unit,
    onSave: (Double, String, String, String) -> Unit,
    priceSuggestion: Double? = null,
    onConditionsChanged: (String, String) -> Unit = { _, _ -> }
) {
    var price by remember { mutableStateOf("") }
    var condition by remember { mutableStateOf("") }
    var sleeveCondition by remember { mutableStateOf("") }
    var comments by remember { mutableStateOf("") }

    var conditionError by remember { mutableStateOf(false) }
    var priceError by remember { mutableStateOf(false) }

    LaunchedEffect(condition, sleeveCondition) {
        if (condition.isNotBlank() && condition != "Not Graded") {
            onConditionsChanged(condition, sleeveCondition)
        }
    }

    val conditions = listOf("Mint (M)", "Near Mint (NM or M-)", "Very Good Plus (VG+)", "Very Good (VG)", "Good Plus (G+)", "Good (G)", "Fair (F)", "Poor (P)", "Not Graded")
    val sleeveConditions = listOf("Mint (M)", "Near Mint (NM or M-)", "Very Good Plus (VG+)", "Very Good (VG)", "Good Plus (G+)", "Good (G)", "Fair (F)", "Poor (P)", "Not Graded")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("List Item For Sale") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                // 1. Media Condition Scrollable Row
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Media Condition",
                        fontSize = 12.sp,
                        color = if (conditionError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(count = conditions.size) { index ->
                            val fullGrade = conditions[index]
                            val isSelected = condition == fullGrade
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clickable {
                                        condition = fullGrade
                                        conditionError = false
                                    }
                                    .border(
                                        width = 1.dp,
                                        color = if (conditionError && !isSelected) MaterialTheme.colorScheme.error else Color.Transparent,
                                        shape = RoundedCornerShape(16.dp)
                                    )
                            ) {
                                Text(
                                    text = getShortGrade(fullGrade),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    if (conditionError) {
                        Text(
                            text = "Media condition is required to list an item.",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp, start = 16.dp)
                        )
                    }
                }

                // 2. Sleeve Condition Scrollable Row
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Sleeve Condition",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(count = sleeveConditions.size) { index ->
                            val fullGrade = sleeveConditions[index]
                            val isSelected = sleeveCondition == fullGrade
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { sleeveCondition = fullGrade }
                            ) {
                                Text(
                                    text = getShortGrade(fullGrade),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // 3. Price Field with Suggestion Row
                Column {
                    OutlinedTextField(
                        value = price,
                        onValueChange = {
                            price = it
                            if (priceError) priceError = false
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

                    if (condition.isNotBlank() && condition != "Not Graded") {
                        when {
                            priceSuggestion == null -> {
                                Text(
                                    text = "Fetching market price for $condition...",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                                )
                            }
                            priceSuggestion > 0.0 -> {
                                Text(
                                    text = "💡 Suggested Price: $${String.format("%.2f", priceSuggestion)} (based on $condition listings)",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                                )
                            }
                            else -> {
                                Text(
                                    text = "No active $condition listings found to suggest a price.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                                )
                            }
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
                val cleanPriceString = price.replace(Regex("[^0-9.]"), "")
                val parsedPrice = cleanPriceString.toDoubleOrNull() ?: 0.0

                var hasError = false

                if (condition.isBlank() || condition == "Not Graded") {
                    conditionError = true
                    hasError = true
                } else {
                    conditionError = false
                }

                if (parsedPrice <= 0.0) {
                    priceError = true
                    hasError = true
                } else {
                    priceError = false
                }

                if (!hasError) {
                    val finalSleeve = if (sleeveCondition.isBlank()) "Not Graded" else sleeveCondition
                    onSave(parsedPrice, condition, finalSleeve, comments)
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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

