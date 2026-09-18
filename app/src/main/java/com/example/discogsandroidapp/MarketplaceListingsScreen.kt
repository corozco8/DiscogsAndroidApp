package com.example.discogsandroidapp

import android.graphics.Bitmap
import android.util.Log
import android.webkit.WebView
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.atomic.AtomicBoolean
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

@Composable
fun MarketplaceListingsScreen(
    releaseId: Long,
    priceSummary: ReleasePriceSummary? = null,
    viewModel: ReleaseViewModel,
    token: String,
    onBackClick: () -> Unit,
    onSearchRequested: (String) -> Unit,
    onBarcodeSearchRequested: (String) -> Unit
) {
    var showSellDialog by remember { mutableStateOf(false) }
    var marketplaceSearchQuery by remember(releaseId) { mutableStateOf("") }
    var liveMarketplacePrices by remember(releaseId) {
        mutableStateOf(getCachedMarketplaceConditionPrices(releaseId))
    }
    var marketplacePricingStatus by remember(releaseId) {
        mutableStateOf(
            if (liveMarketplacePrices != null) {
                MarketplaceUiPriceStatus.CACHED
            } else {
                MarketplaceUiPriceStatus.LOADING
            }
        )
    }
    var effectivePriceSummary by remember(releaseId) {
        mutableStateOf(
            liveMarketplacePrices?.let { prices ->
                (priceSummary ?: ReleasePriceSummary())
                    .withActiveMarketplacePrices(prices)
            } ?: priceSummary
        )
    }
    var webViewRef by remember(releaseId) {
        mutableStateOf<WebView?>(null)
    }

    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scanner = remember { GmsBarcodeScanning.getClient(context) }
    val webViewActive = remember(releaseId) { AtomicBoolean(true) }
    val scanGeneration = remember(releaseId) { intArrayOf(0) }

    fun applyMarketplacePrices(
        prices: ActiveMarketplaceConditionPrices
    ) {
        liveMarketplacePrices = prices
        effectivePriceSummary =
            (priceSummary ?: ReleasePriceSummary())
                .withActiveMarketplacePrices(prices)
    }

    LaunchedEffect(releaseId) {
        getCachedMarketplacePriceSnapshot(releaseId)?.let { snapshot ->
            liveMarketplacePrices = snapshot.prices
            marketplacePricingStatus = MarketplaceUiPriceStatus.CACHED
            effectivePriceSummary =
                (priceSummary ?: ReleasePriceSummary())
                    .withActiveMarketplacePrices(snapshot.prices)
        }
    }

    // A late static price-summary response must not erase live marketplace
    // prices already discovered for this release.
    LaunchedEffect(priceSummary, releaseId, liveMarketplacePrices) {
        effectivePriceSummary =
            liveMarketplacePrices?.let { prices ->
                (priceSummary ?: ReleasePriceSummary())
                    .withActiveMarketplacePrices(prices)
            } ?: priceSummary
    }

    DisposableEffect(releaseId) {
        webViewActive.set(true)

        onDispose {
            webViewActive.set(false)
            scanGeneration[0]++
            webViewRef?.apply {
                stopLoading()
                webViewClient = WebViewClient()
                destroy()
            }
            webViewRef = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }

                    Text(
                        text = "Marketplace Listings",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                    )

                    FilledTonalButton(
                        onClick = { showSellDialog = true },
                        contentPadding = PaddingValues(
                            horizontal = 14.dp,
                            vertical = 8.dp
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sell,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sell")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = marketplaceSearchQuery,
                        onValueChange = { marketplaceSearchQuery = it },
                        placeholder = { Text("Search...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        leadingIcon = {
                            IconButton(
                                onClick = {
                                    val query = marketplaceSearchQuery.trim()
                                    if (query.isNotEmpty()) {
                                        focusManager.clearFocus(force = true)
                                        keyboardController?.hide()
                                        onSearchRequested(query)
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = "Search"
                                )
                            }
                        },
                        trailingIcon = {
                            if (marketplaceSearchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { marketplaceSearchQuery = "" }
                                ) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = "Clear Search"
                                    )
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                val query = marketplaceSearchQuery.trim()
                                if (query.isNotEmpty()) {
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                    onSearchRequested(query)
                                }
                            }
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            scanner.startScan()
                                .addOnSuccessListener { barcode ->
                                    barcode.rawValue
                                        ?.trim()
                                        ?.takeIf { it.isNotEmpty() }
                                        ?.let { scannedValue ->
                                            marketplaceSearchQuery = scannedValue
                                            focusManager.clearFocus(force = true)
                                            keyboardController?.hide()
                                            onBarcodeSearchRequested(scannedValue)
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
        }

        AndroidView(
            modifier = Modifier.weight(1f),
            factory = { androidContext ->
                WebView(androidContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView,
                            url: String?,
                            favicon: Bitmap?
                        ) {
                            super.onPageStarted(view, url, favicon)
                            // Invalidate callbacks scheduled by the previous page.
                            scanGeneration[0]++
                            marketplacePricingStatus =
                                if (liveMarketplacePrices != null) {
                                    MarketplaceUiPriceStatus.CACHED
                                } else {
                                    MarketplaceUiPriceStatus.LOADING
                                }
                            val navigationGeneration = scanGeneration[0]
                            view.postDelayed(
                                {
                                    if (
                                        webViewActive.get() &&
                                        navigationGeneration == scanGeneration[0] &&
                                        marketplacePricingStatus == MarketplaceUiPriceStatus.LOADING
                                    ) {
                                        marketplacePricingStatus = MarketplaceUiPriceStatus.FAILED
                                    }
                                },
                                10_000L
                            )
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: WebResourceRequest,
                            errorResponse: WebResourceResponse
                        ) {
                            super.onReceivedHttpError(view, request, errorResponse)
                            if (request.isForMainFrame) {
                                marketplacePricingStatus = MarketplaceUiPriceStatus.FAILED
                            }
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request.isForMainFrame) {
                                marketplacePricingStatus = MarketplaceUiPriceStatus.FAILED
                            }
                        }

                        override fun onPageFinished(
                            view: WebView,
                            url: String
                        ) {
                            super.onPageFinished(view, url)


                            // Never let navigation to another Discogs release
                            // contaminate this screen's recommendation data.
                            if (!marketplaceUrlBelongsToRelease(url, releaseId)) {
                                return
                            }

                            val generation = scanGeneration[0]

                            beginMarketplacePriceScan(
                                webView = view,
                                releaseId = releaseId,
                                isCurrent = {
                                    webViewActive.get() &&
                                            generation == scanGeneration[0] &&
                                            marketplaceUrlBelongsToRelease(
                                                view.url,
                                                releaseId
                                            )
                                },
                                onPrices = ::applyMarketplacePrices,
                                onStatus = { status ->
                                    if (
                                        webViewActive.get() &&
                                        generation == scanGeneration[0]
                                    ) {
                                        marketplacePricingStatus = status
                                    }
                                }
                            )
                        }
                    }

                    loadUrl(marketplaceReleaseListingsUrl(releaseId))
                    webViewRef = this
                }
            },
            update = { webView ->
                webViewRef = webView
            }
        )
    }

    if (showSellDialog) {
        AddListingDialog(
            priceSummary = effectivePriceSummary ?: priceSummary,
            onDismiss = { showSellDialog = false },
            onSave = { price, condition, sleeveCondition, comments ->
                Log.d(
                    "MARKETPLACE_LISTING",
                    "Creating listing for releaseId=$releaseId"
                )

                viewModel.createListing(
                    releaseId = releaseId.toInt(),
                    price = price,
                    condition = condition,
                    sleeveCondition = sleeveCondition,
                    comments = comments,
                    token = token,
                    onSuccess = {
                        android.widget.Toast.makeText(
                            context,
                            "Successfully listed",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                )

                showSellDialog = false
            }
        )
    }
}
