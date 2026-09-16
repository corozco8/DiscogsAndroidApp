package com.example.discogsandroidapp

import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MarketplaceListingsScreen(
    releaseId: Long,
    priceSummary: ReleasePriceSummary? = null,
    viewModel: ReleaseViewModel,
    token: String,
    onBackClick: () -> Unit
) {
    var showSellDialog by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    var effectivePriceSummary by remember(releaseId, priceSummary) {
        mutableStateOf(priceSummary)
    }

    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // This screen already has the real marketplace page visible. Use that
    // exact WebView as the price source instead of loading a second hidden
    // copy of the page.
    val accumulatedMediaLowest =
        remember(releaseId) { mutableMapOf<String, Double>() }
    val accumulatedMediaSleeveLowest =
        remember(releaseId) { mutableMapOf<String, Double>() }

    fun mergeMarketplacePrices(
        prices: ActiveMarketplaceConditionPrices
    ) {
        prices.mediaLowest.forEach { (grade, value) ->
            val previous = accumulatedMediaLowest[grade]
            if (previous == null || value < previous) {
                accumulatedMediaLowest[grade] = value
            }
        }

        prices.mediaSleeveLowest.forEach { (key, value) ->
            val previous = accumulatedMediaSleeveLowest[key]
            if (previous == null || value < previous) {
                accumulatedMediaSleeveLowest[key] = value
            }
        }

        effectivePriceSummary =
            (effectivePriceSummary ?: priceSummary ?: ReleasePriceSummary())
                .withActiveMarketplacePrices(
                    ActiveMarketplaceConditionPrices(
                        mediaLowest = accumulatedMediaLowest.toMap(),
                        mediaSleeveLowest =
                            accumulatedMediaSleeveLowest.toMap()
                    )
                )
    }

    // Filter the loaded Discogs listings as the user types. The underlying
    // marketplace page remains permanently sorted lowest price first.
    LaunchedEffect(searchText, pageReady) {
        if (!pageReady) return@LaunchedEffect
        delay(120)
        webViewRef?.let { webView ->
            applyMarketplaceListingFilter(
                webView = webView,
                query = searchText
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
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
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        placeholder = { Text("Search listings...") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(searchFocusRequester),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = { keyboardController?.hide() }
                        ),
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null
                            )
                        },
                        trailingIcon = {
                            if (searchText.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        searchText = ""
                                        searchFocusRequester.requestFocus()
                                        scope.launch {
                                            delay(80)
                                            keyboardController?.show()
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = "Clear Search"
                                    )
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Button(
                        onClick = { showSellDialog = true },
                        contentPadding = PaddingValues(16.dp),
                        modifier = Modifier.height(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sell,
                            contentDescription = "Sell a copy"
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
                        override fun onPageFinished(
                            view: WebView,
                            url: String
                        ) {
                            super.onPageFinished(view, url)
                            pageReady = true
                            applyMarketplaceListingFilter(
                                webView = view,
                                query = searchText
                            )

                            // Marketplace rows can appear after onPageFinished.
                            // Sample this same visible page several times and
                            // retain the lowest price seen for each media grade.
                            listOf(
                                0L,
                                350L,
                                800L,
                                1600L,
                                2800L,
                                4500L
                            ).forEach { delayMs ->
                                view.postDelayed(
                                    {
                                        evaluateMarketplaceConditionPrices(
                                            webView = view,
                                            onPrices =
                                                ::mergeMarketplacePrices
                                        )
                                    },
                                    delayMs
                                )
                            }
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
            releaseId = releaseId,
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
