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
    val pricing = rememberMarketplacePricing(releaseId)
    val effectivePriceSummary = pricing.prices?.let {
        (priceSummary ?: ReleasePriceSummary()).withActiveMarketplacePrices(it)
    } ?: priceSummary
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scanner = remember { GmsBarcodeScanning.getClient(context) }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
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
                        modifier = Modifier.keyboardInputArea().weight(1f),
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

        MarketplacePricingStatus(pricing)
        if (pricing.ready) {
            key(releaseId, pricing.attempt) {
                AndroidView(
                    modifier = Modifier.weight(1f),
                    factory = { androidContext -> pricing.createWebView(androidContext, hidden = false) }
                )
            }
        }
    }

    if (showSellDialog) {
        AddListingDialog(
            pricingMessage = pricing.message,
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
