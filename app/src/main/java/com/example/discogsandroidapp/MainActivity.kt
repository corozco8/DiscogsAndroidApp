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
import androidx.lifecycle.repeatOnLifecycle

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
                    val uiState by viewModel.uiState.collectWhileStarted()
                    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                    val storeVisible = uiState is ReleaseUiState.StoreLoading || uiState is ReleaseUiState.StoreSuccess
                    LaunchedEffect(storeVisible, lifecycleOwner) {
                        if (storeVisible) lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                            viewModel.observeStore()
                        }
                    }
                    val profileUiState by viewModel.profileUiState.collectWhileStarted()
                    val orderMessagesUiState by viewModel.orderMessagesUiState.collectWhileStarted()

                    var searchQuery by remember { mutableStateOf("") }
                    var storeSort by remember { mutableStateOf("listed") }
                    var storeSortOrder by remember { mutableStateOf("desc") }
                    // Hoist the My Store list state above the navigation content so
                    // opening a release does not discard the user's scroll position.
                    val storeListState = rememberLazyListState()
                    val ordersListState = rememberLazyListState()
                    var ordersScrollIndex by remember { mutableStateOf(0) }
                    var ordersScrollOffset by remember { mutableStateOf(0) }
                    var ordersScrollAnchor by remember { mutableStateOf<String?>(null) }
                    var restoreOrdersScroll by remember { mutableStateOf(false) }
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
                                        // Restore the already-loaded inventory snapshot instead
                                        // of fetching page 1 again. This preserves sort, loaded
                                        // pages, search context and the hoisted scroll position.
                                        viewModel.restoreStoreInventory(token)
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
                            },
                            onSearchRequested = { query ->
                                searchQuery = query
                                marketplaceReleaseId = null
                                marketplacePriceSummary = null
                                barcodeSearchPending = false
                                viewModel.search(query, token)
                            },
                            onBarcodeSearchRequested = { scannedValue ->
                                searchQuery = scannedValue
                                marketplaceReleaseId = null
                                marketplacePriceSummary = null
                                barcodeSearchPending = true
                                viewModel.search(scannedValue, token)
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
                            val focusManager = LocalFocusManager.current
                            val searchFocusRequester = remember { FocusRequester() }
                            val searchHostView = LocalView.current
                            DisposableEffect(searchFocusRequester) {
                                SearchDialogBridge.focusSearch = {
                                    appScope.launch {
                                        // Let the native dialog detach and return focus to the activity.
                                        kotlinx.coroutines.delay(32)
                                        val ready = kotlinx.coroutines.withTimeoutOrNull(1_500) {
                                            while (!searchHostView.hasWindowFocus()) kotlinx.coroutines.delay(16)
                                            true
                                        } ?: false
                                        if (ready && SearchDialogBridge.focusSearch != null) {
                                            searchFocusRequester.requestFocus()
                                            withFrameNanos { }
                                            keyboardController?.show()
                                        }
                                    }
                                }
                                onDispose {
                                    SearchDialogBridge.focusSearch = null
                                    SearchDialogBridge.searchBounds = null
                                }
                            }
                            val scanner = remember { GmsBarcodeScanning.getClient(context) }
                            if (
                                uiState !is ReleaseUiState.AiSearch &&
                                uiState !is ReleaseUiState.OrderDetails &&
                                uiState !is ReleaseUiState.SellerInboxLoading &&
                                uiState !is ReleaseUiState.SellerInboxSuccess &&
                                uiState !is ReleaseUiState.SellerInboxError &&
                                uiState !is ReleaseUiState.DiscogsWebView &&
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
                                        modifier = Modifier.keyboardInputArea()
                                            .weight(1f)
                                            .focusRequester(searchFocusRequester)
                                            .onGloballyPositioned { coordinates ->
                                                val screen = IntArray(2)
                                                val window = IntArray(2)
                                                searchHostView.getLocationOnScreen(screen)
                                                searchHostView.getLocationInWindow(window)
                                                SearchDialogBridge.searchBounds = coordinates.boundsInWindow().translate(
                                                    (screen[0] - window[0]).toFloat(),
                                                    (screen[1] - window[1]).toFloat()
                                                )
                                            },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            imeAction = ImeAction.Search
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onSearch = {
                                                if (
                                                    uiState !is ReleaseUiState.StoreSuccess &&
                                                    uiState !is ReleaseUiState.StoreLoading
                                                ) {
                                                    viewModel.search(
                                                        searchQuery,
                                                        token
                                                    )
                                                }
                                                // My Store search is filtered locally from the
                                                // synchronized full inventory as the user types.
                                                keyboardController?.hide()
                                            }
                                        ),
                                        trailingIcon = {
                                            if (searchQuery.isNotEmpty()) {
                                                IconButton(
                                                    onClick = {
                                                        searchQuery = ""

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

                                    FilledIconButton(
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
                                                            // My Store reacts to searchQuery directly.
                                                            barcodeSearchPending = false
                                                        } else {
                                                            viewModel.search(
                                                                scannedValue,
                                                                token
                                                            )
                                                        }

                                                        // The scanner returns control to this Activity while the
                                                        // search field may still own focus. Hiding the IME alone can
                                                        // let Android reopen it immediately, so also clear focus.
                                                        focusManager.clearFocus(force = true)
                                                        keyboardController?.hide()
                                                    }
                                                }
                                        },
                                        modifier = Modifier.size(54.dp),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.QrCodeScanner,
                                            contentDescription = "Scan Barcode",
                                            modifier = Modifier.size(22.dp)
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

                                    is ReleaseUiState.StoreSuccess -> {
                                        LaunchedEffect(searchQuery) {
                                            viewModel.setStoreQuery(searchQuery)
                                        }
                                        val displayedStoreListings = state.listings

                                        StoreScreen(
                                            listings = displayedStoreListings,
                                            token = token,
                                            listState = storeListState,
                                            totalItems = state.totalItems,
                                            isFetchingMore = false,
                                            onSortChanged = { sortField, sortOrder ->
                                                storeSort = sortField
                                                storeSortOrder = sortOrder
                                                // A deliberate sort change should start at the top,
                                                // but simply viewing a release should not.
                                                appScope.launch {
                                                    storeListState.scrollToItem(0)
                                                }
                                                viewModel.fetchStoreInventory(
                                                    token,
                                                    sortField,
                                                    sortOrder,
                                                    reset = true
                                                )
                                            },
                                            onLoadMore = {
                                                // All active listings are available locally.

                                            },
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
                                                viewModel.editListing(
                                                    id,
                                                    price,
                                                    condition,
                                                    sleeveCondition,
                                                    comments,
                                                    token
                                                )
                                            },
                                            onListingClick = { listing ->
                                                returnToStore = true
                                                returnToAiSearch = false
                                                // Keep the current My Store search text while the
                                                // release is open so Back returns to the same view.
                                                viewModel.fetchRelease(
                                                    releaseId = listing.release.id,
                                                    token = token
                                                )
                                            }
                                        )
                                    }

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
                                            },
                                            onRefresh = {
                                                state.release.id?.let { releaseId ->
                                                    viewModel.fetchRelease(
                                                        releaseId = releaseId,
                                                        token = token
                                                    )
                                                }
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
                                                state.orders.filter { matchesOrderStatus(it.status, viewModel.currentOrdersStatus) }
                                            )

                                        LaunchedEffect(Unit) {
                                            if (restoreOrdersScroll) {
                                                val anchorIndex = visibleOrders.indexOfFirst { it.id == ordersScrollAnchor }
                                                val targetIndex = if (anchorIndex >= 0) anchorIndex else
                                                    ordersScrollIndex.coerceAtMost((visibleOrders.size - 1).coerceAtLeast(0))
                                                ordersListState.scrollToItem(targetIndex, ordersScrollOffset)
                                                restoreOrdersScroll = false
                                            } else {
                                                ordersListState.scrollToItem(0)
                                            }
                                        }

                                        val selectedStatus = viewModel.currentOrdersStatus.let { if (it == "All") "All Orders" else it }
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
                                                    text = "$selectedStatus (${visibleOrders.size})",
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
                                                listState = ordersListState,
                                                isFetchingMore = state.isFetchingMore,
                                                hasMore = state.hasMore,
                                                onLoadMore = {
                                                    viewModel.loadNextOrdersPage(
                                                        token
                                                    )
                                                },
                                                onOrderClick = { selectedOrder ->
                                                    ordersScrollIndex = ordersListState.firstVisibleItemIndex
                                                    ordersScrollOffset = ordersListState.firstVisibleItemScrollOffset
                                                    ordersScrollAnchor = visibleOrders.getOrNull(ordersScrollIndex)?.id
                                                    restoreOrdersScroll = true
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
                                            onSendMessage = { message, onResult ->
                                                val orderId = state.order.id
                                                if (orderId == null) {
                                                    onResult(false)
                                                } else {
                                                    viewModel.sendOrderMessage(
                                                        orderId = orderId,
                                                        message = message,
                                                        token = token,
                                                        onResult = onResult
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

                                    is ReleaseUiState.SalesAnalytics -> {
                                        val localOrders by sellerInsightsViewModel.orders.collectWhileStarted()
                                        val localOrderItems by sellerInsightsViewModel.orderItems.collectWhileStarted()
                                        val orderSyncState by sellerInsightsViewModel.orderSyncState.collectWhileStarted()

                                        val localInventory by sellerInsightsViewModel.inventory.collectWhileStarted()
                                        val inventorySyncState by sellerInsightsViewModel.inventorySyncState.collectWhileStarted()

                                        SellerStatisticsScreen(
                                            inventory = localInventory,
                                            inventorySyncState = inventorySyncState,
                                            profile = (profileUiState as? ProfileUiState.Success)?.profile,
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
                                        val localOrders by sellerInsightsViewModel.orders.collectWhileStarted()
                                        val localOrderItems by sellerInsightsViewModel.orderItems.collectWhileStarted()
                                        val orderSyncState by sellerInsightsViewModel.orderSyncState.collectWhileStarted()

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
                                        OffersPlaceholderScreen()

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
