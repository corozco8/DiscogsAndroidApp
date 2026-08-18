package com.example.discogsandroidapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Inventory
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
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.filled.QrCodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import androidx.compose.material.icons.filled.Clear
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Sell
import android.content.Intent
import android.net.Uri

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: ReleaseViewModel = viewModel()
            // Your API Token
            val token = "DKKLTsjxfrIOKuConcaqMLylNNaDIcxpypyQWDpG"

            // Fetch the user profile as soon as the app opens!
            LaunchedEffect(Unit) {
                viewModel.fetchUserProfile(token)
            }

            DiscogsAndroidAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val uiState by viewModel.uiState.collectAsState()
                    val profileUiState by viewModel.profileUiState.collectAsState()
                    val suggestedPrice by viewModel.conditionPriceSuggestion.collectAsState()

                    var searchQuery by remember { mutableStateOf("") }
                    var marketplaceReleaseId by remember { mutableStateOf<Long?>(null) }

                    // "Smart Back" logic to prevent going all the way home
                    val performSmartBack = {
                        when (uiState) {
                            is ReleaseUiState.ReleaseSuccess -> {
                                if (searchQuery.isNotEmpty()) {
                                    viewModel.search(searchQuery, token) // Go back to search results
                                } else {
                                    viewModel.resetToIdle() // Go home
                                }
                            }
                            is ReleaseUiState.OrderDetails -> {
                                viewModel.navigateToOrders(token) // Go back to order list
                            }
                            else -> viewModel.resetToIdle() // Default fallback
                        }
                    }

                    // If a user clicked "View Listings", overlay the Marketplace screen!
                    if (marketplaceReleaseId != null) {
                        MarketplaceListingsScreen(
                            releaseId = marketplaceReleaseId!!,
                            viewModel = viewModel,
                            token = token,
                            onBackClick = { marketplaceReleaseId = null }
                        )
                    } else {
                        // STANDARD APP CONTENT
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            // 1. ALWAYS VISIBLE Search Bar Header
                            val context = LocalContext.current
                            val keyboardController = LocalSoftwareKeyboardController.current
                            val scanner = remember { GmsBarcodeScanning.getClient(context) }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (uiState !is ReleaseUiState.Idle && uiState !is ReleaseUiState.Loading) {
                                    IconButton(onClick = { performSmartBack() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                }

                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    label = {
                                        Text("Search...", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(
                                        onSearch = {
                                            viewModel.search(searchQuery, token)
                                            keyboardController?.hide()
                                        }
                                    ),
                                    trailingIcon = {
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { searchQuery = "" }) {
                                                Icon(
                                                    imageVector = Icons.Default.Clear,
                                                    contentDescription = "Clear Search"
                                                )
                                            }
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))

                                Button(
                                    onClick = {
                                        scanner.startScan()
                                            .addOnSuccessListener { barcode ->
                                                barcode.rawValue?.let { scannedValue ->
                                                    searchQuery = scannedValue
                                                    viewModel.search(scannedValue, token)
                                                    keyboardController?.hide()
                                                }
                                            }
                                    },
                                    contentPadding = PaddingValues(12.dp)
                                ) {
                                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan Barcode")
                                }
                            }

                            // 2. Dynamic Content Area
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                when (val state = uiState) {
                                    // THIS WAS THE MISSING BLOCK!
                                    is ReleaseUiState.Idle -> {
                                        when (val pState = profileUiState) {
                                            is ProfileUiState.Loading -> CircularProgressIndicator()
                                            is ProfileUiState.Success -> {
                                                ProfileDashboard(
                                                    profile = pState.profile,
                                                    onStoreClick = { viewModel.fetchStoreInventory(token) },
                                                    onOrdersClick = { viewModel.navigateToOrders(token) },
                                                    onInventoryClick = { viewModel.navigateToInventory() },
                                                    onOffersClick = { viewModel.navigateToOffers() },
                                                    onSellerRatingClick = {
                                                        val currentUsername = pState.profile.username ?: "kingchapstick"
                                                        viewModel.openRatings(username = currentUsername, ratingType = "seller")
                                                    },
                                                    onBuyerRatingClick = {
                                                        val currentUsername = pState.profile.username ?: "kingchapstick"
                                                        viewModel.openRatings(username = currentUsername, ratingType = "buyer")
                                                    }
                                                )
                                            }
                                            is ProfileUiState.Error -> Text("Error: ${pState.message}", color = MaterialTheme.colorScheme.error)
                                        }
                                    }

                                    is ReleaseUiState.RatingsWebView -> {
                                        RatingsWebScreen(
                                            username = state.username,
                                            ratingType = state.ratingType,
                                            onBackClick = { viewModel.resetToIdle() }
                                        )
                                    }

                                    is ReleaseUiState.StoreLoading -> CircularProgressIndicator()

                                    is ReleaseUiState.StoreSuccess -> StoreScreen(
                                        listings = state.listings,
                                        totalItems = state.totalItems,
                                        isFetchingMore = state.isFetchingMore,
                                        onSortChanged = { sortField, sortOrder ->
                                            viewModel.fetchStoreInventory(token, sortField, sortOrder, reset = true)
                                        },
                                        onLoadMore = { viewModel.loadNextPage(token) },
                                        onDeleteListing = { listingId -> viewModel.deleteListing(listingId, token) },
                                        onEditListing = { id, price, condition, sleeveCondition, comments ->
                                            viewModel.editListing(id, price, condition, sleeveCondition, comments, token)
                                        },
                                        onListingClick = { /* ... */ }
                                    )

                                    is ReleaseUiState.Loading -> CircularProgressIndicator()

                                    is ReleaseUiState.SearchSuccess -> {
                                        val localContext = LocalContext.current

                                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                                            items(state.results) { result ->
                                                SearchResultRow(result = result) {
                                                    when (result.type) {
                                                        "release" -> {
                                                            viewModel.fetchRelease(releaseId = result.id.toLong(), token = token)
                                                        }
                                                        "master" -> {
                                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.discogs.com/master/${result.id}"))
                                                            localContext.startActivity(intent)
                                                        }
                                                        "artist" -> {
                                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.discogs.com/artist/${result.id}"))
                                                            localContext.startActivity(intent)
                                                        }
                                                        "label" -> {
                                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.discogs.com/label/${result.id}"))
                                                            localContext.startActivity(intent)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    is ReleaseUiState.ReleaseSuccess -> {
                                        ReleaseDetails(
                                            release = state.release,
                                            priceSummary = state.priceSummary,
                                            priceSuggestion = suggestedPrice,
                                            onBackClick = { performSmartBack() },
                                            onSellConfirm = { price, condition, sleeve, comments ->
                                                viewModel.createListing(
                                                    releaseId = state.release.id?.toInt() ?: 0,
                                                    price = price,
                                                    condition = condition,
                                                    sleeveCondition = sleeve,
                                                    comments = comments,
                                                    token = token,
                                                    onSuccess = {
                                                        // show toast here
                                                    }
                                                )
                                            },
                                            onViewListingsClick = { releaseId ->
                                                marketplaceReleaseId = releaseId
                                            },
                                            onConditionsChanged = { condition, sleeveCondition ->
                                                state.release.id?.let { releaseId ->
                                                    viewModel.fetchPriceSuggestionForCondition(
                                                        releaseId = releaseId,
                                                        condition = condition,
                                                        token = token,
                                                        sleeveCondition = sleeveCondition
                                                    )
                                                }
                                            }
                                        )
                                    }

                                    is ReleaseUiState.Error -> {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "API Error: ${state.message}",
                                                color = MaterialTheme.colorScheme.error,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.padding(16.dp)
                                            )
                                        }
                                    }

                                    is ReleaseUiState.OrdersLoading -> CircularProgressIndicator()

                                    is ReleaseUiState.OrdersSuccess -> {
                                        var selectedStatus by remember { mutableStateOf("Payment Received") }
                                        var filterExpanded by remember { mutableStateOf(false) }
                                        val orderStatuses = listOf("Payment Received", "Invoice Sent", "In Progress", "Cancelled", "Shipped")

                                        Column(modifier = Modifier.fillMaxSize()) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "My Orders (${state.orders.size})",
                                                    fontSize = 20.sp,
                                                    fontWeight = FontWeight.Bold
                                                )

                                                Box {
                                                    IconButton(onClick = { filterExpanded = true }) {
                                                        Icon(Icons.Default.FilterList, contentDescription = "Filter Orders")
                                                    }

                                                    DropdownMenu(
                                                        expanded = filterExpanded,
                                                        onDismissRequest = { filterExpanded = false }
                                                    ) {
                                                        orderStatuses.forEach { status ->
                                                            DropdownMenuItem(
                                                                text = {
                                                                    Text(
                                                                        text = status,
                                                                        fontWeight = if (status == selectedStatus) FontWeight.Bold else FontWeight.Normal
                                                                    )
                                                                },
                                                                onClick = {
                                                                    selectedStatus = status
                                                                    filterExpanded = false
                                                                    viewModel.fetchOrdersByStatus(status, token)
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            OrdersScreen(
                                                orders = state.orders,
                                                onOrderClick = { selectedOrder ->
                                                    viewModel.navigateToOrderDetails(selectedOrder)
                                                }
                                            )
                                        }
                                    }

                                    is ReleaseUiState.OrderDetails -> {
                                        OrderDetailScreen(
                                            order = state.order,
                                            onBackClick = { viewModel.resetToIdle() },
                                            onStatusChange = { newStatus ->
                                                state.order.id?.let { orderId ->
                                                    viewModel.updateOrderStatus(orderId, newStatus, token)
                                                }
                                            },
                                            onItemClick = { releaseId -> viewModel.fetchRelease(releaseId = releaseId.toLong(), token = token) }
                                        )
                                    }

                                    is ReleaseUiState.Inventory -> PlaceholderScreen("Inventory List")

                                    is ReleaseUiState.Offers -> PlaceholderScreen("Pending Offers")

                                    else -> {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text("Loading screen...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Below are the dashboard sub-components to keep the file fully intact
@Composable
fun ProfileDashboard(
    profile: DiscogsProfile,
    onStoreClick: () -> Unit,
    onOrdersClick: () -> Unit,
    onInventoryClick: () -> Unit,
    onOffersClick: () -> Unit,
    onSellerRatingClick: () -> Unit,
    onBuyerRatingClick: () -> Unit
) {
    val formattedSeller = String.format(java.util.Locale.getDefault(), "%.1f", profile.sellerRating / 20.0)
    val formattedBuyer = String.format(java.util.Locale.getDefault(), "%.1f", profile.buyerRating / 20.0)
    val memberYear = if (profile.registered.length >= 4) profile.registered.take(4) else "Unknown"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            shadowElevation = 8.dp,
            border = BorderStroke(3.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        ) {
            AsyncImage(
                model = profile.avatarUrl,
                contentDescription = "User Avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(120.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = profile.username,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp
        )
        Text(
            text = "Member since $memberYear",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ElevatedCard(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSellerRatingClick() },
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Star, contentDescription = "Star", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "$formattedSeller (${profile.sellerNumRatings})", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Seller rating", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                }
            }

            ElevatedCard(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onBuyerRatingClick() },
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Star, contentDescription = "Star", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "$formattedBuyer (${profile.buyerNumRatings})", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Buyer rating", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Selling",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp, start = 4.dp),
            textAlign = TextAlign.Start
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MenuBrick(title = "My Store", icon = Icons.Default.Store, onClick = onStoreClick)
            MenuBrick(title = "My Orders", icon = Icons.Default.Receipt, onClick = onOrdersClick)
            MenuBrick(title = "My Offers", icon = Icons.Default.LocalOffer, onClick = onOffersClick)
        }
    }
}

@Composable
fun MenuBrick(title: String, icon: ImageVector, onClick: () -> Unit = {}) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun SearchResultRow(result: SearchResult, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = result.thumb.takeIf { !it.isNullOrBlank() } ?: "https://via.placeholder.com/150",
                contentDescription = "Cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (!result.format.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = result.format.joinToString(", "),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = result.type.uppercase(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    if (!result.country.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = result.country,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (result.year.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (!result.country.isNullOrBlank()) "•  ${result.year}" else "Released: ${result.year}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    if (!result.catno.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        val prefix = if (result.country.isNullOrBlank() && result.year.isEmpty()) "Cat#: " else "•  "
                        Text(
                            text = "$prefix${result.catno}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlaceholderScreen(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun StoreScreen(
    listings: List<InventoryListing>,
    totalItems: Int,
    isFetchingMore: Boolean,
    onSortChanged: (sort: String, sortOrder: String) -> Unit,
    onLoadMore: () -> Unit,
    onDeleteListing: (Long) -> Unit,
    onEditListing: (Long, Double, String, String, String) -> Unit,
    onListingClick: (InventoryListing) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    var listingToEdit by remember { mutableStateOf<InventoryListing?>(null) }
    var listingToView by remember { mutableStateOf<InventoryListing?>(null) }

    listingToEdit?.let { listing ->
        EditListingDialog(
            listing = listing,
            onDismiss = { listingToEdit = null },
            onSave = { price, condition, sleeveCondition, comments ->
                onEditListing(listing.id, price, condition, sleeveCondition, comments)
                listingToEdit = null
            }
        )
    }

    listingToView?.let { listing ->
        ListingDetailsDialog(
            listing = listing,
            onDismiss = { listingToView = null },
            onEditClick = {
                listingToView = null
                listingToEdit = listing
            }
        )
    }

    if (listings.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Your store is currently empty.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp, start = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "My Store ($totalItems Items)",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(imageVector = Icons.Default.Sort, contentDescription = "Sort Options")
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

        itemsIndexed(listings) { index, listing ->
            if (index == listings.lastIndex) {
                LaunchedEffect(Unit) { onLoadMore() }
            }

            InventoryItemCard(
                listing = listing,
                onClick = { listingToView = listing },
                onEditClick = { listingToEdit = listing },
                onDeleteClick = { onDeleteListing(listing.id) }
            )
        }
    }
}

@Composable
fun InventoryItemCard(
    listing: InventoryListing,
    onClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = listing.release.thumbnail.takeIf { it.isNotBlank() } ?: "https://via.placeholder.com/150",
                contentDescription = "Cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = listing.release.description, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)

                Surface(modifier = Modifier.padding(vertical = 4.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(text = listing.status.uppercase(), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val priceText = listing.price?.let {
                        String.format(java.util.Locale.getDefault(), "%s %.2f", it.currency, it.value)
                    } ?: "N/A"

                    Text(text = priceText, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)

                    Spacer(modifier = Modifier.width(8.dp))

                    val media = getShortGrade(listing.condition)
                    val sleeve = getShortGrade(listing.sleeve_condition)
                    val gradeText = if (sleeve == "Not Graded") media else "$media / $sleeve"

                    Text(text = "• $gradeText", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Item Options")
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
                        text = { Text("Delete Listing", color = MaterialTheme.colorScheme.error) },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditListingDialog(
    listing: InventoryListing,
    onDismiss: () -> Unit,
    onSave: (price: Double, condition: String, sleeveCondition: String, comments: String) -> Unit
) {
    var price by remember { mutableStateOf(listing.price?.value?.toString() ?: "") }
    var condition by remember { mutableStateOf(listing.condition) }
    var sleeveCondition by remember { mutableStateOf(listing.sleeve_condition) }
    var comments by remember { mutableStateOf(listing.comments) }

    val conditions = listOf("Mint (M)", "Near Mint (NM or M-)", "Very Good Plus (VG+)", "Very Good (VG)", "Good Plus (G+)", "Good (G)", "Fair (F)", "Poor (P)")
    val sleeveConditions = listOf("Mint (M)", "Near Mint (NM or M-)", "Very Good Plus (VG+)", "Very Good (VG)", "Good Plus (G+)", "Good (G)", "Fair (F)", "Poor (P)", "Generic", "Not Graded", "No Cover")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Listing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                // 1. Price Field
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("Price (USD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
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
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedPrice = price.toDoubleOrNull() ?: 0.0
                onSave(parsedPrice, condition, sleeveCondition, comments)
            }) { Text("Save Changes") }
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
    onDismiss: () -> Unit,
    onEditClick: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = listing.release.thumbnail.takeIf { it.isNotBlank() } ?: "https://via.placeholder.com/300",
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
                        fontSize = 18.sp
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

                    val media = getShortGrade(listing.condition)
                    val sleeve = getShortGrade(listing.sleeve_condition)
                    val gradeText = if (sleeve == "Not Graded" || sleeve.isEmpty()) "Media: $media" else "Media: $media • Sleeve: $sleeve"

                    Text(text = gradeText, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    if (listing.comments.isNotBlank()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(text = "Comments / Description:", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(text = listing.comments, fontSize = 14.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onEditClick,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Edit")
                        }
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }
}