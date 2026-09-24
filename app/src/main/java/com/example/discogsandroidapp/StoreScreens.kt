package com.example.discogsandroidapp

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalView
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.discogsandroidapp.ui.theme.DiscogsAndroidAppTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Store
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.filled.QrCodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import androidx.compose.material.icons.filled.Clear
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.FilterList
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.ChevronRight
import kotlinx.coroutines.launch

@Composable
fun StoreScreen(
    listings: List<InventoryListing>,
    token: String,
    listState: LazyListState,
    totalItems: Int,
    isFetchingMore: Boolean,
    onSortChanged: (sort: String, sortOrder: String) -> Unit,
    onLoadMore: () -> Unit,
    onDeleteListing: (Long) -> Unit,
    onDeleteSelected: (List<Long>) -> Unit,
    onEditListing: (Long, Double, String, String, String) -> Unit,
    onListingClick: (InventoryListing) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    var listingToEdit by remember { mutableStateOf<InventoryListing?>(null) }
    var listingToView by remember { mutableStateOf<InventoryListing?>(null) }
    var selectedListingIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    val selectionMode = selectedListingIds.isNotEmpty()

    fun toggleSelection(listingId: Long) {
        selectedListingIds =
            if (listingId in selectedListingIds) {
                selectedListingIds - listingId
            } else {
                selectedListingIds + listingId
            }
    }

    listingToEdit?.let { listing ->
        EditListingDialog(
            listing = listing,
            onDismiss = { listingToEdit = null },
            onSave = { price, condition, sleeveCondition, comments ->
                onEditListing(
                    listing.id,
                    price,
                    condition,
                    sleeveCondition,
                    comments
                )
                listingToEdit = null
            }
        )
    }

    listingToView?.let { listing ->
        ListingDetailsDialog(
            listing = listing,
            token = token,
            onDismiss = { listingToView = null },
            onEditClick = {
                listingToView = null
                listingToEdit = listing
            },
            onViewClick = {
                listingToView = null
                onListingClick(listing)
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Column {
                    Text(
                        text = "${selectedListingIds.size} selected",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Tap more items to add them",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { selectedListingIds = emptySet() }
                    ) {
                        Text("Cancel")
                    }

                    TextButton(
                        onClick = {
                            val ids = selectedListingIds.toList()
                            if (ids.isNotEmpty()) {
                                onDeleteSelected(ids)
                            }
                            selectedListingIds = emptySet()
                        }
                    ) {
                        Text(
                            text = "Delete",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            } else {
                Column {
                    Text(
                        text = "My Store",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "${java.text.NumberFormat.getIntegerInstance(java.util.Locale.getDefault()).format(totalItems)} active listings",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box {
                    FilledTonalIconButton(
                        onClick = { expanded = true },
                        modifier = Modifier.size(42.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sort,
                            contentDescription = "Sort Options",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(text = { Text("Date Listed: Newest") }, onClick = { onSortChanged("listed", "desc"); expanded = false })
                        DropdownMenuItem(text = { Text("Date Listed: Oldest") }, onClick = { onSortChanged("listed", "asc"); expanded = false })
                        DropdownMenuItem(text = { Text("Price: High to Low") }, onClick = { onSortChanged("price", "desc"); expanded = false })
                        DropdownMenuItem(text = { Text("Price: Low to High") }, onClick = { onSortChanged("price", "asc"); expanded = false })
                        DropdownMenuItem(text = { Text("Title: A-Z") }, onClick = { onSortChanged("title", "asc"); expanded = false })
                        DropdownMenuItem(text = { Text("Title: Z-A") }, onClick = { onSortChanged("title", "desc"); expanded = false })
                        DropdownMenuItem(text = { Text("Artist: A-Z") }, onClick = { onSortChanged("artist", "asc"); expanded = false })
                        DropdownMenuItem(text = { Text("Artist: Z-A") }, onClick = { onSortChanged("artist", "desc"); expanded = false })
                    }
                }
            }
        }

        if (listings.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No inventory results found.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                itemsIndexed(
                    items = listings,
                    key = { _, listing -> listing.id }
                ) { index, listing ->
                    if (index == listings.lastIndex && !selectionMode) {
                        LaunchedEffect(listing.id) { onLoadMore() }
                    }

                    val isSelected = listing.id in selectedListingIds

                    InventoryItemCard(
                        listing = listing,
                        isSelected = isSelected,
                        selectionMode = selectionMode,
                        onClick = {
                            if (selectionMode) {
                                toggleSelection(listing.id)
                            } else {
                                listingToView = listing
                            }
                        },
                        onLongClick = { toggleSelection(listing.id) },
                        onEditClick = { listingToEdit = listing },
                        onDeleteClick = { onDeleteListing(listing.id) }
                    )
                }

                if (isFetchingMore) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InventoryItemCard(
    listing: InventoryListing,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(16.dp)
                    )
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor =
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
                } else {
                    MaterialTheme.colorScheme.surface
                }
        )
    ) {
        Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = listing.release.thumbnail.takeIf { it.isNotBlank() }
                    ?: "https://via.placeholder.com/150",
                contentDescription = "Cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp))
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = listing.release.description,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = listing.status.uppercase(),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    val media = getShortGrade(listing.condition)
                    val sleeve = getShortGrade(listing.sleeve_condition)
                    val gradeText =
                        if (sleeve == "Not Graded") media else "$media / $sleeve"

                    Text(
                        text = gradeText,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.height(5.dp))

                val priceText = listing.price?.let {
                    String.format(
                        java.util.Locale.getDefault(),
                        "%s %,.2f",
                        it.currency,
                        it.value
                    )
                } ?: "N/A"

                Text(
                    text = priceText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (!selectionMode) {
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Item Options",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit Listing") },
                            onClick = {
                                menuExpanded = false
                                onEditClick()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Delete Listing",
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDeleteClick()
                            }
                        )
                    }
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditListingDialog(
    listing: InventoryListing,
    onDismiss: () -> Unit,
    onSave: (price: Double, condition: String, sleeveCondition: String, comments: String) -> Unit
) {
    var price by remember { mutableStateOf(listing.price?.value?.toString() ?: "") }
    var priceError by remember { mutableStateOf<String?>(null) }
    var condition by remember { mutableStateOf(listing.condition) }
    var sleeveCondition by remember { mutableStateOf(listing.sleeve_condition) }
    var comments by remember { mutableStateOf(listing.comments) }

    val conditions = listOf("Mint (M)", "Near Mint (NM or M-)", "Very Good Plus (VG+)", "Very Good (VG)", "Good Plus (G+)", "Good (G)", "Fair (F)", "Poor (P)")
    val sleeveConditions = listOf(
        "Mint (M)",
        "Near Mint (NM or M-)",
        "Very Good Plus (VG+)",
        "Very Good (VG)",
        "Good Plus (G+)",
        "Good (G)",
        "Fair (F)",
        "Poor (P)",
        "Not Graded",
        "No Cover",
        "Generic"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Listing") },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {

                // 1. Price Field
                OutlinedTextField(
                    value = price,
                    onValueChange = {
                        price = it
                        priceError = null
                    },
                    label = { Text("Price (${listing.price?.currency ?: "USD"})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    ),
                    isError = priceError != null,
                    supportingText =
                        priceError?.let { message ->
                            {
                                Text(message)
                            }
                        },
                    modifier = Modifier.keyboardInputArea().fillMaxWidth()
                )

                // 2. Media Condition Scrollable Row
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Media Condition",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(conditions) { fullGrade ->
                            val isSelected = condition == fullGrade
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { condition = fullGrade }
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

                // 3. Sleeve Condition Scrollable Row
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
                        items(sleeveConditions) { fullGrade ->
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

                // 4. Comments Field
                OutlinedTextField(
                    value = comments,
                    onValueChange = { comments = it },
                    label = { Text("Description / Comments") },
                    maxLines = 3,
                    modifier = Modifier.keyboardInputArea().fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanPrice = price.trim()
                    val parsedPrice = cleanPrice.toDoubleOrNull()
                    val hasValidPrecision =
                        Regex("^\\d+(\\.\\d{1,2})?$")
                            .matches(cleanPrice)

                    if (
                        parsedPrice == null ||
                        !parsedPrice.isFinite() ||
                        parsedPrice <= 0.0 ||
                        !hasValidPrecision
                    ) {
                        priceError =
                            "Enter a valid price greater than $0.00 with up to 2 decimals."
                    } else {
                        priceError = null
                        onSave(
                            parsedPrice,
                            condition,
                            sleeveCondition,
                            comments
                        )
                    }
                }
            ) {
                Text("Save Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

fun getShortGrade(grade: String): String {
    return when (grade) {
        "Mint (M)" -> "M"
        "Near Mint (NM or M-)" -> "NM"
        "Very Good Plus (VG+)" -> "VG+"
        "Very Good (VG)" -> "VG"
        "Good Plus (G+)" -> "G+"
        "Good (G)" -> "G"
        "Fair (F)" -> "F"
        "Poor (P)" -> "P"
        else -> grade
    }
}

@Composable
fun ListingDetailsDialog(
    listing: InventoryListing,
    token: String,
    onDismiss: () -> Unit,
    onEditClick: () -> Unit,
    onViewClick: () -> Unit
) {
    var highResolutionImageUrl by remember(listing.release.id) {
        mutableStateOf<String?>(null)
    }

    // Inventory payloads only include Discogs' small thumbnail. Fetch the
    // release once when the preview opens so the dialog can use the original
    // release image instead of stretching the low-resolution thumbnail.
    LaunchedEffect(listing.release.id, token) {
        highResolutionImageUrl =
            try {
                RetrofitClient.apiService
                    .getRelease(
                        releaseId = listing.release.id,
                        authHeader = "Discogs token=$token"
                    )
                    .images
                    ?.firstOrNull()
                    ?.uri
                    ?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
    }

    SearchAccessibleDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
                Column(
                    modifier = Modifier.weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                AsyncImage(
                    model =
                        highResolutionImageUrl
                            ?: listing.release.thumbnail.takeIf { it.isNotBlank() }
                            ?: "https://via.placeholder.com/600",
                    contentDescription = "Cover",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = listing.release.description,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    val priceText = listing.price?.let {
                        String.format(java.util.Locale.getDefault(), "%s %.2f", it.currency, it.value)
                    } ?: "Price N/A"

                    Text(
                        text = priceText,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (listing.comments.isNotBlank()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(text = "Comments / Description:", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(text = listing.comments, fontSize = 14.sp)
                    }

                }
                }

                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onEditClick,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Edit")
                        }
                        Button(
                            onClick = onViewClick,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("View")
                        }
                    }
            }
        }
    }
}
