package com.example.discogsandroidapp

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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: ReleaseViewModel = viewModel()
            val aiSearchViewModel: AiSearchViewModel = viewModel()
            val sellerInsightsViewModel: SellerInsightsViewModel = viewModel()
            val appContext = LocalContext.current.applicationContext

            // Your API Token
            val token = BuildConfig.DISCOGS_TOKEN

            // Fetch the profile and keep the new local seller database fresh.
            LaunchedEffect(Unit) {
                viewModel.fetchUserProfile(token)
                SellerSyncScheduler.schedulePeriodic(appContext)
                SellerSyncScheduler.enqueueNow(appContext)
            }

            DiscogsAndroidAppTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                val appScope = rememberCoroutineScope()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { innerPadding ->
                    val uiState by viewModel.uiState.collectAsState()
                    val profileUiState by viewModel.profileUiState.collectAsState()
                    val orderMessagesUiState by viewModel.orderMessagesUiState.collectAsState()
                    val localInventory by sellerInsightsViewModel.inventory.collectAsState()
                    val localOrders by sellerInsightsViewModel.orders.collectAsState()
                    val localOrderItems by sellerInsightsViewModel.orderItems.collectAsState()
                    val inventorySyncState by sellerInsightsViewModel.inventorySyncState.collectAsState()
                    val orderSyncState by sellerInsightsViewModel.orderSyncState.collectAsState()

                    var searchQuery by remember { mutableStateOf("") }
                    var marketplaceReleaseId by remember { mutableStateOf<Long?>(null) }
                    var marketplacePriceSummary by remember {
                        mutableStateOf<ReleasePriceSummary?>(null)
                    }
                    var barcodeSearchPending by remember { mutableStateOf(false) }
                    var showBarcodeNoResults by remember { mutableStateOf(false) }

                    // AI search action state
                    var aiListingToEdit by remember {
                        mutableStateOf<AiInventoryResult?>(null)
                    }

                    // Lets the release-details Back button return to the AI search.
                    var returnToAiSearch by remember {
                        mutableStateOf(false)
                    }

                    // Lets the release-details Back button return to My Store
                    // when a release was opened from an inventory listing.
                    var returnToStore by remember {
                        mutableStateOf(false)
                    }

                    // When a release is opened from an order item, Back should
                    // return to that exact order instead of the dashboard.
                    var returnToOrderDetails by remember {
                        mutableStateOf<DiscogsOrder?>(null)
                    }

                    // Orders opened from the native Seller Messages inbox
                    // should return to that inbox instead of My Orders.
                    var returnToSellerInbox by remember {
                        mutableStateOf(false)
                    }

                    LaunchedEffect(uiState, barcodeSearchPending) {
                        if (!barcodeSearchPending) return@LaunchedEffect

                        when (val currentState = uiState) {
                            is ReleaseUiState.SearchSuccess -> {
                                barcodeSearchPending = false
                                if (currentState.results.isEmpty()) {
                                    showBarcodeNoResults = true
                                }
                            }

                            is ReleaseUiState.StoreSuccess -> {
                                barcodeSearchPending = false
                                if (currentState.listings.isEmpty()) {
                                    showBarcodeNoResults = true
                                }
                            }

                            is ReleaseUiState.Error -> {
                                barcodeSearchPending = false
                            }

                            else -> Unit
                        }
                    }

                    // "Smart Back" logic to prevent going all the way home
                    val performSmartBack = {
                        when (uiState) {
                            is ReleaseUiState.ReleaseSuccess -> {
                                when {
                                    returnToOrderDetails != null -> {
                                        val order = returnToOrderDetails
                                        returnToOrderDetails = null

                                        if (order != null) {
                                            viewModel.navigateToOrderDetails(
                                                order = order,
                                                token = token
                                            )
                                        }
                                    }

                                    returnToStore -> {
                                        returnToStore = false
                                        viewModel.fetchStoreInventory(token)
                                    }

                                    returnToAiSearch -> {
                                        returnToAiSearch = false
                                        viewModel.navigateToAiSearch()
                                    }

                                    searchQuery.isNotEmpty() -> {
                                        viewModel.search(searchQuery, token) // Go back to search results
                                    }

                                    else -> {
                                        viewModel.resetToIdle() // Go home
                                    }
                                }
                            }
                            is ReleaseUiState.OrderDetails -> {
                                if (returnToSellerInbox) {
                                    returnToSellerInbox = false
                                    viewModel.openSellerInbox(token)
                                } else {
                                    viewModel.navigateToOrders(token)
                                }
                            }

                            is ReleaseUiState.SellerInboxLoading,
                            is ReleaseUiState.SellerInboxSuccess,
                            is ReleaseUiState.SellerInboxError -> {
                                viewModel.resetToIdle()
                            }

                            else -> viewModel.resetToIdle() // Default fallback
                        }
                    }

                    /*
                     * Handle Android's system Back action, including the
                     * left/right edge swipe gesture.
                     *
                     * Do not intercept Back on the dashboard (Idle). That
                     * lets Android close/minimize the app normally when the
                     * user is already at the root screen.
                     *
                     * Dialogs keep their own normal Back-to-dismiss behavior.
                     */
                    BackHandler(
                        enabled =
                            marketplaceReleaseId != null ||
                                    uiState !is ReleaseUiState.Idle
                    ) {
                        when {
                            // Marketplace listings are an overlay on top of
                            // the current release, so close the overlay first.
                            marketplaceReleaseId != null -> {
                                marketplaceReleaseId = null
                            }

                            // AI search has its own local search state.
                            uiState is ReleaseUiState.AiSearch -> {
                                aiSearchViewModel.clearSearch()
                                viewModel.resetToIdle()
                            }

                            // Master versions must return to the release that
                            // opened the versions screen.
                            uiState is ReleaseUiState.MasterVersionsLoading ||
                                    uiState is ReleaseUiState.MasterVersionsSuccess ||
                                    uiState is ReleaseUiState.MasterVersionsError -> {
                                viewModel.returnFromMasterVersions()
                            }

                            // Order detail returns to whichever list opened it.
                            uiState is ReleaseUiState.OrderDetails -> {
                                if (returnToSellerInbox) {
                                    returnToSellerInbox = false
                                    viewModel.openSellerInbox(token)
                                } else {
                                    viewModel.navigateToOrders(token)
                                }
                            }

                            // Discogs web surfaces return either to the order
                            // that launched them or to the dashboard.
                            uiState is ReleaseUiState.DiscogsWebView -> {
                                viewModel.closeDiscogsWeb(token)
                            }

                            // Release details, store, search results, ratings,
                            // orders, inventory, offers, loading/error screens,
                            // etc. use the app's existing Smart Back behavior.
                            else -> {
                                performSmartBack()
                            }
                        }
                    }

                    // Reuse the existing edit-listing dialog for an AI search result.
                    //
                    // NOTE: AiInventoryResult does not currently include the original
                    // Discogs listing comments, so comments start blank here. We will
                    // add comments to the AI result model/backend next so edits can
                    // preserve them safely.
                    aiListingToEdit?.let { result ->
                        val listingId = result.listingId

                        if (listingId != null) {
                            val listing = InventoryListing(
                                id = listingId,
                                status = "For Sale",
                                condition = result.condition ?: "",
                                sleeve_condition = result.sleeveCondition ?: "Not Graded",
                                comments = result.comments,
                                price = result.price?.let { price ->
                                    Price(
                                        value = price,
                                        currency = result.currency ?: "USD"
                                    )
                                },
                                release = ListingRelease(
                                    id = result.releaseId,
                                    description = "${result.artist} - ${result.title}",
                                    thumbnail = result.thumbnail ?: "",
                                    title = result.title,
                                    artist = result.artist
                                )
                            )

                            EditListingDialog(
                                listing = listing,
                                onDismiss = {
                                    aiListingToEdit = null
                                },
                                onSave = {
                                        price,
                                        condition,
                                        sleeveCondition,
                                        comments ->

                                    viewModel.editListing(
                                        listingId = listingId,
                                        price = price,
                                        condition = condition,
                                        sleeveCondition = sleeveCondition,
                                        comments = comments,
                                        token = token,
                                        refreshStoreAfterSuccess = false,
                                        waitForAiCacheSync = true,
                                        onAiCacheSyncResult = { cacheSynced ->
                                            if (cacheSynced) {
                                                // Re-run the exact query that produced
                                                // these results, not whatever partial
                                                // text may now be in the search field.
                                                aiSearchViewModel
                                                    .rerunLastResultQuery()
                                            } else {
                                                // The Discogs edit succeeded, but the
                                                // optional AI cache did not. Do not
                                                // immediately rerun against stale data.
                                                appScope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        message =
                                                            "Listing updated, but AI cache sync failed. " +
                                                                    "AI results may be stale until the next sync."
                                                    )
                                                }
                                            }
                                        },
                                        onSuccess = {}
                                    )

                                    aiListingToEdit = null
                                }
                            )
                        } else {
                            aiListingToEdit = null
                        }
                    }

                    if (showBarcodeNoResults) {
                        AlertDialog(
                            onDismissRequest = { showBarcodeNoResults = false },
                            title = { Text("No results found") },
                            text = {
                                Text("No Discogs results were found for that barcode.")
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = { showBarcodeNoResults = false }
                                ) {
                                    Text("OK")
                                }
                            }
                        )
                    }

                    // If a user clicked "View Listings", overlay the Marketplace screen!
                    if (marketplaceReleaseId != null) {
                        MarketplaceListingsScreen(
                            releaseId = marketplaceReleaseId!!,
                            priceSummary = marketplacePriceSummary,
                            viewModel = viewModel,
                            token = token,
                            onBackClick = {
                                marketplaceReleaseId = null
                                marketplacePriceSummary = null
                            }
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
                            val searchFocusRequester = remember { FocusRequester() }
                            val scanner = remember { GmsBarcodeScanning.getClient(context) }
                            if (
                                uiState !is ReleaseUiState.AiSearch &&
                                uiState !is ReleaseUiState.OrderDetails &&
                                uiState !is ReleaseUiState.SellerInboxLoading &&
                                uiState !is ReleaseUiState.SellerInboxSuccess &&
                                uiState !is ReleaseUiState.SellerInboxError &&
                                uiState !is ReleaseUiState.DiscogsWebView &&
                                uiState !is ReleaseUiState.InventoryAging &&
                                uiState !is ReleaseUiState.SalesAnalytics &&
                                uiState !is ReleaseUiState.CustomerHistory
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {

                                    if (
                                        uiState !is ReleaseUiState.Idle &&
                                        uiState !is ReleaseUiState.Loading
                                    ) {
                                        IconButton(
                                            onClick = {
                                                performSmartBack()
                                            }
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "Back"
                                            )
                                        }

                                        Spacer(
                                            modifier = Modifier.width(4.dp)
                                        )
                                    }

                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        label = {
                                            Text(
                                                if (
                                                    uiState is ReleaseUiState.StoreSuccess ||
                                                    uiState is ReleaseUiState.StoreLoading
                                                ) {
                                                    "Search My Store..."
                                                } else {
                                                    "Search..."
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .focusRequester(searchFocusRequester),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            imeAction = ImeAction.Search
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onSearch = {
                                                if (
                                                    uiState is ReleaseUiState.StoreSuccess ||
                                                    uiState is ReleaseUiState.StoreLoading
                                                ) {
                                                    viewModel.searchStoreInventory(
                                                        query = searchQuery,
                                                        token = token
                                                    )
                                                } else {
                                                    viewModel.search(
                                                        searchQuery,
                                                        token
                                                    )
                                                }
                                                keyboardController?.hide()
                                            }
                                        ),
                                        trailingIcon = {
                                            if (searchQuery.isNotEmpty()) {
                                                IconButton(
                                                    onClick = {
                                                        searchQuery = ""

                                                        if (
                                                            uiState is ReleaseUiState.StoreSuccess ||
                                                            uiState is ReleaseUiState.StoreLoading
                                                        ) {
                                                            viewModel.clearStoreSearch(token)
                                                        }

                                                        searchFocusRequester.requestFocus()
                                                        appScope.launch {
                                                            kotlinx.coroutines.delay(80)
                                                            keyboardController?.show()
                                                        }
                                                    }
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Clear,
                                                        contentDescription = "Clear Search"
                                                    )
                                                }
                                            }
                                        }
                                    )

                                    Spacer(
                                        modifier = Modifier.width(8.dp)
                                    )

                                    Button(
                                        onClick = {
                                            scanner.startScan()
                                                .addOnSuccessListener { barcode ->
                                                    barcode.rawValue?.let { scannedValue ->

                                                        searchQuery = scannedValue
                                                        barcodeSearchPending = true

                                                        if (
                                                            uiState is ReleaseUiState.StoreSuccess ||
                                                            uiState is ReleaseUiState.StoreLoading
                                                        ) {
                                                            viewModel.searchStoreInventory(
                                                                query = scannedValue,
                                                                token = token
                                                            )
                                                        } else {
                                                            viewModel.search(
                                                                scannedValue,
                                                                token
                                                            )
                                                        }

                                                        keyboardController?.hide()
                                                    }
                                                }
                                        },
                                        contentPadding = PaddingValues(12.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.QrCodeScanner,
                                            contentDescription = "Scan Barcode"
                                        )
                                    }
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
                                    is ReleaseUiState.AiSearch -> {
                                        AiSearchScreen(
                                            viewModel = aiSearchViewModel,

                                            onBackClick = {
                                                aiSearchViewModel.clearSearch()
                                                viewModel.resetToIdle()
                                            },

                                            onReleaseClick = { releaseId ->
                                                returnToAiSearch = true

                                                viewModel.fetchRelease(
                                                    releaseId = releaseId,
                                                    token = token
                                                )
                                            },

                                            onEditClick = { result ->
                                                if (result.listingId != null) {
                                                    aiListingToEdit = result
                                                }
                                            },

                                            // Delete one record from the three-dot menu.
                                            // Remove it from the visible AI results as soon
                                            // as Discogs confirms that specific deletion.
                                            onDeleteClick = { result ->
                                                result.listingId?.let { listingId ->

                                                    viewModel.deleteListingsFromAiSearch(
                                                        listingIds = listOf(listingId),
                                                        token = token,
                                                        onListingDeleted = { deletedListingId ->
                                                            aiSearchViewModel.removeListings(
                                                                listOf(deletedListingId)
                                                            )
                                                        }
                                                    )
                                                }
                                            },

                                            // Delete every record selected by long-press.
                                            // Each card disappears immediately after its
                                            // individual Discogs DELETE succeeds.
                                            onDeleteSelected = { selectedResults ->

                                                val listingIds = selectedResults.mapNotNull { result ->
                                                    result.listingId
                                                }

                                                if (listingIds.isNotEmpty()) {

                                                    viewModel.deleteListingsFromAiSearch(
                                                        listingIds = listingIds,
                                                        token = token,
                                                        onListingDeleted = { deletedListingId ->
                                                            aiSearchViewModel.removeListings(
                                                                listOf(deletedListingId)
                                                            )
                                                        }
                                                    )
                                                }
                                            }
                                        )
                                    }

                                    // THIS WAS THE MISSING BLOCK!
                                    is ReleaseUiState.Idle -> {
                                        when (val pState = profileUiState) {
                                            is ProfileUiState.Loading -> CircularProgressIndicator()
                                            is ProfileUiState.Success -> {
                                                ProfileDashboard(
                                                    profile = pState.profile,

                                                    onStoreClick = {
                                                        searchQuery = ""
                                                        viewModel.clearStoreSearch(token)
                                                    },

                                                    onAiSearchClick = {
                                                        viewModel.navigateToAiSearch()
                                                    },

                                                    onOrdersClick = {
                                                        viewModel.navigateToOrders(token)
                                                    },

                                                    onInboxClick = {
                                                        viewModel.openDiscogsInbox()
                                                    },

                                                    onInventoryClick = {
                                                        viewModel.navigateToInventory()
                                                    },

                                                    onOffersClick = {
                                                        viewModel.navigateToOffers()
                                                    },

                                                    onInventoryAgingClick = {
                                                        viewModel.navigateToInventoryAging()
                                                    },

                                                    onSalesAnalyticsClick = {
                                                        viewModel.navigateToSalesAnalytics()
                                                    },

                                                    onCustomerHistoryClick = {
                                                        viewModel.navigateToCustomerHistory()
                                                    },

                                                    onSellerRatingClick = {
                                                        val currentUsername =
                                                            pState.profile.username ?: "kingchapstick"

                                                        viewModel.openRatings(
                                                            username = currentUsername,
                                                            ratingType = "seller"
                                                        )
                                                    },

                                                    onBuyerRatingClick = {
                                                        val currentUsername =
                                                            pState.profile.username ?: "kingchapstick"

                                                        viewModel.openRatings(
                                                            username = currentUsername,
                                                            ratingType = "buyer"
                                                        )
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

                                    is ReleaseUiState.DiscogsWebView -> {
                                        DiscogsWebScreen(
                                            title = state.title,
                                            url = state.url,
                                            hideNewOrderNotifications =
                                                state.hideNewOrderNotifications,
                                            onBackClick = {
                                                viewModel.closeDiscogsWeb(token)
                                            }
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
                                        onDeleteListing = { listingId ->
                                            viewModel.deleteListing(listingId, token)
                                        },
                                        onDeleteSelected = { listingIds ->
                                            viewModel.deleteListingsFromStore(
                                                listingIds = listingIds,
                                                token = token
                                            )
                                        },
                                        onEditListing = { id, price, condition, sleeveCondition, comments ->
                                            viewModel.editListing(id, price, condition, sleeveCondition, comments, token)
                                        },
                                        onListingClick = { listing ->
                                            returnToStore = true
                                            returnToAiSearch = false
                                            searchQuery = ""
                                            viewModel.fetchRelease(
                                                releaseId = listing.release.id,
                                                token = token
                                            )
                                        }
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
                                                        appScope.launch {
                                                            snackbarHostState.showSnackbar(
                                                                message = "Successfully listed"
                                                            )
                                                        }
                                                    }
                                                )
                                            },
                                            onViewListingsClick = { releaseId ->
                                                marketplacePriceSummary = state.priceSummary
                                                marketplaceReleaseId = releaseId
                                            },

                                            onViewVersionsClick = { masterId ->
                                                viewModel.fetchMasterVersions(
                                                    masterId = masterId,
                                                    token = token
                                                )
                                            }
                                        )
                                    }

                                    is ReleaseUiState.MasterVersionsLoading -> {
                                        MasterVersionsScreen(
                                            masterId = state.masterId,
                                            versions = emptyList(),
                                            totalItems = 0,
                                            isLoading = true,
                                            onBackClick = {
                                                viewModel.returnFromMasterVersions()
                                            }
                                        )
                                    }

                                    is ReleaseUiState.MasterVersionsSuccess -> {
                                        MasterVersionsScreen(
                                            masterId = state.masterId,
                                            versions = state.versions,
                                            totalItems = state.totalItems,
                                            onBackClick = {
                                                viewModel.returnFromMasterVersions()
                                            },
                                            onVersionClick = { releaseId ->
                                                viewModel.fetchRelease(
                                                    releaseId = releaseId,
                                                    token = token
                                                )
                                            }
                                        )
                                    }

                                    is ReleaseUiState.MasterVersionsError -> {
                                        MasterVersionsScreen(
                                            masterId = state.masterId,
                                            versions = emptyList(),
                                            totalItems = 0,
                                            errorMessage = state.message,
                                            onBackClick = {
                                                viewModel.returnFromMasterVersions()
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
                                        val visibleOrders =
                                            visibleSellerOrders(
                                                state.orders
                                            )

                                        var selectedStatus by remember { mutableStateOf("Payment Received") }
                                        var filterExpanded by remember { mutableStateOf(false) }
                                        val orderStatuses = listOf(
                                            "Payment Received",
                                            "In Progress",
                                            "Invoice Sent",
                                            "Shipped",
                                            "Cancelled",
                                            "All Orders"
                                        )

                                        Column(modifier = Modifier.fillMaxSize()) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "My Orders (${visibleOrders.size})",
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
                                                orders = visibleOrders,
                                                isFetchingMore = state.isFetchingMore,
                                                hasMore = state.hasMore,
                                                onLoadMore = {
                                                    viewModel.loadNextOrdersPage(
                                                        token
                                                    )
                                                },
                                                onOrderClick = { selectedOrder ->
                                                    returnToSellerInbox = false
                                                    viewModel.navigateToOrderDetails(
                                                        order = selectedOrder,
                                                        token = token
                                                    )
                                                }
                                            )
                                        }
                                    }

                                    is ReleaseUiState.SellerInboxLoading -> {
                                        SellerInboxScreen(
                                            conversations = emptyList(),
                                            isLoading = true,
                                            errorMessage = null,
                                            onBackClick = {
                                                viewModel.resetToIdle()
                                            },
                                            onRefreshClick = {
                                                viewModel.openSellerInbox(token)
                                            },
                                            onConversationClick = {},
                                            onPrivateInboxClick = {
                                                viewModel.openDiscogsInbox(
                                                    returnToSellerInbox = true
                                                )
                                            }
                                        )
                                    }

                                    is ReleaseUiState.SellerInboxSuccess -> {
                                        SellerInboxScreen(
                                            conversations = state.conversations,
                                            isLoading = false,
                                            errorMessage = null,
                                            onBackClick = {
                                                viewModel.resetToIdle()
                                            },
                                            onRefreshClick = {
                                                viewModel.openSellerInbox(token)
                                            },
                                            onConversationClick = { conversation ->
                                                returnToSellerInbox = true
                                                viewModel.navigateToOrderDetails(
                                                    order = conversation.order,
                                                    token = token
                                                )
                                            },
                                            onPrivateInboxClick = {
                                                viewModel.openDiscogsInbox(
                                                    returnToSellerInbox = true
                                                )
                                            }
                                        )
                                    }

                                    is ReleaseUiState.SellerInboxError -> {
                                        SellerInboxScreen(
                                            conversations = emptyList(),
                                            isLoading = false,
                                            errorMessage = state.message,
                                            onBackClick = {
                                                viewModel.resetToIdle()
                                            },
                                            onRefreshClick = {
                                                viewModel.openSellerInbox(token)
                                            },
                                            onConversationClick = {},
                                            onPrivateInboxClick = {
                                                viewModel.openDiscogsInbox(
                                                    returnToSellerInbox = true
                                                )
                                            }
                                        )
                                    }

                                    is ReleaseUiState.OrderDetails -> {
                                        OrderDetailScreen(
                                            order = state.order,
                                            messageState = orderMessagesUiState,
                                            onBackClick = {
                                                if (returnToSellerInbox) {
                                                    returnToSellerInbox = false
                                                    viewModel.openSellerInbox(token)
                                                } else {
                                                    viewModel.navigateToOrders(token)
                                                }
                                            },
                                            onStatusChange = { newStatus ->
                                                state.order.id?.let { orderId ->
                                                    viewModel.updateOrderStatus(
                                                        orderId,
                                                        newStatus,
                                                        token
                                                    )
                                                }
                                            },
                                            onItemClick = { releaseId ->
                                                returnToOrderDetails = state.order
                                                returnToStore = false
                                                returnToAiSearch = false
                                                searchQuery = ""

                                                viewModel.fetchRelease(
                                                    releaseId = releaseId.toLong(),
                                                    token = token
                                                )
                                            },
                                            onSendMessage = { message ->
                                                state.order.id?.let { orderId ->
                                                    viewModel.sendOrderMessage(
                                                        orderId = orderId,
                                                        message = message,
                                                        token = token
                                                    )
                                                }
                                            },
                                            onLeaveBuyerFeedback = {
                                                viewModel.openBuyerFeedback(
                                                    state.order
                                                )
                                            }
                                        )
                                    }

                                    is ReleaseUiState.InventoryAging -> {
                                        InventoryAgingScreen(
                                            inventory = localInventory,
                                            syncState = inventorySyncState,
                                            onBackClick = {
                                                viewModel.resetToIdle()
                                            },
                                            onRefreshClick = {
                                                sellerInsightsViewModel.refreshNow()
                                            }
                                        )
                                    }

                                    is ReleaseUiState.SalesAnalytics -> {
                                        SalesAnalyticsScreen(
                                            orders = localOrders,
                                            orderItems = localOrderItems,
                                            syncState = orderSyncState,
                                            onBackClick = {
                                                viewModel.resetToIdle()
                                            },
                                            onRefreshClick = {
                                                sellerInsightsViewModel.refreshNow()
                                            }
                                        )
                                    }

                                    is ReleaseUiState.CustomerHistory -> {
                                        CustomerHistoryScreen(
                                            orders = localOrders,
                                            orderItems = localOrderItems,
                                            syncState = orderSyncState,
                                            onBackClick = {
                                                viewModel.resetToIdle()
                                            },
                                            onRefreshClick = {
                                                sellerInsightsViewModel.refreshNow()
                                            }
                                        )
                                    }

                                    is ReleaseUiState.Inventory ->
                                        PlaceholderScreen("Inventory List")

                                    is ReleaseUiState.Offers ->
                                        PlaceholderScreen("Pending Offers")

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
    onAiSearchClick: () -> Unit,
    onOrdersClick: () -> Unit,
    onInboxClick: () -> Unit,
    onInventoryClick: () -> Unit,
    onOffersClick: () -> Unit,
    onInventoryAgingClick: () -> Unit,
    onSalesAnalyticsClick: () -> Unit,
    onCustomerHistoryClick: () -> Unit,
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
        Box(
            modifier = Modifier.size(140.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.size(120.dp),
                shape = CircleShape,
                shadowElevation = 8.dp,
                border = BorderStroke(
                    3.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
            ) {
                AsyncImage(
                    model = profile.avatarUrl,
                    contentDescription = "User Avatar",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Surface(
                modifier = Modifier
                    .size(36.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = (-2).dp, y = 12.dp)
                    .clickable { onInboxClick() },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 8.dp,
                border = BorderStroke(
                    2.dp,
                    MaterialTheme.colorScheme.surface
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mail,
                        contentDescription = "Discogs Inbox",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
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

            MenuBrick(
                title = "My Store",
                icon = Icons.Default.Store,
                onClick = onStoreClick
            )

            MenuBrick(
                title = "AI Inventory Search",
                icon = Icons.Default.AutoAwesome,
                onClick = onAiSearchClick
            )

            MenuBrick(
                title = "My Orders",
                icon = Icons.Default.Receipt,
                onClick = onOrdersClick
            )

            MenuBrick(
                title = "My Offers",
                icon = Icons.Default.LocalOffer,
                onClick = onOffersClick
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "Seller Insights",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    bottom = 12.dp,
                    start = 4.dp
                ),
            textAlign = TextAlign.Start
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MenuBrick(
                title = "Inventory Aging",
                icon = Icons.Default.AccessTime,
                onClick = onInventoryAgingClick
            )

            MenuBrick(
                title = "Sales & Profit Analytics",
                icon = Icons.Default.Insights,
                onClick = onSalesAnalyticsClick
            )

            MenuBrick(
                title = "Customer History",
                icon = Icons.Default.People,
                onClick = onCustomerHistoryClick
            )
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
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
                        text = "Delete (${selectedListingIds.size})",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                Text(
                    text = "My Store ($totalItems Items)",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(
                            imageVector = Icons.Default.Sort,
                            contentDescription = "Sort Options"
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
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
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
                        shape = RoundedCornerShape(12.dp)
                    )
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor =
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surface
                }
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = listing.release.thumbnail.takeIf { it.isNotBlank() }
                    ?: "https://via.placeholder.com/150",
                contentDescription = "Cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp))
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = listing.release.description,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Surface(
                    modifier = Modifier.padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = listing.status.uppercase(),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val priceText = listing.price?.let {
                        String.format(
                            java.util.Locale.getDefault(),
                            "%s %.2f",
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

                    Spacer(modifier = Modifier.width(8.dp))

                    val media = getShortGrade(listing.condition)
                    val sleeve = getShortGrade(listing.sleeve_condition)
                    val gradeText =
                        if (sleeve == "Not Graded") media else "$media / $sleeve"

                    Text(
                        text = "• $gradeText",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!selectionMode) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Item Options"
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
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                // 1. Price Field
                OutlinedTextField(
                    value = price,
                    onValueChange = {
                        price = it
                        priceError = null
                    },
                    label = { Text("Price (USD)") },
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
    onDismiss: () -> Unit,
    onEditClick: () -> Unit,
    onViewClick: () -> Unit
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
}
