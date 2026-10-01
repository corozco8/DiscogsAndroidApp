package com.example.discogsandroidapp.pricing

import com.example.discogsandroidapp.data.ReleasePriceSummary
import com.example.discogsandroidapp.releases.AddListingDialog
import com.example.discogsandroidapp.releases.ReleaseViewModel
import com.example.discogsandroidapp.ui.shared.keyboardInputArea

import android.util.Log
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
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

@Composable
internal fun MarketplaceListingsScreen(
    releaseId: Long,
    priceSummary: ReleasePriceSummary? = null,
    viewModel: ReleaseViewModel,
    token: String,
    onBackClick: () -> Unit,
    onSearchRequested: (String) -> Unit,
    onBarcodeSearchRequested: (String) -> Unit,
    pricingController: MarketplacePricingController? = null,
    suggestionsModel: com.example.discogsandroidapp.releases.ReleaseSearchSuggestionsViewModel? = null,
    onReleaseRequested: (com.example.discogsandroidapp.data.SearchResult) -> Unit = {}
) {
    var showSellDialog by remember { mutableStateOf(false) }
    var isSubmittingListing by remember { mutableStateOf(false) }
    var listingSubmissionError by remember { mutableStateOf<String?>(null) }
    var marketplaceSearchQuery by remember(releaseId) { mutableStateOf("") }
    val suggestions = suggestionsModel ?: androidx.lifecycle.viewmodel.compose.viewModel<com.example.discogsandroidapp.releases.ReleaseSearchSuggestionsViewModel>()
    val pricing = pricingController ?: rememberMarketplacePricing(releaseId)
    LaunchedEffect(pricing, pricing.ready) {
        pricing.requestVisiblePage()
    }
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
                    com.example.discogsandroidapp.releases.ReleaseSearchField(
                        value = marketplaceSearchQuery,
                        onValueChange = { marketplaceSearchQuery = it },
                        suggestions = suggestions, token = token,
                        onSearch = onSearchRequested,
                        onRelease = onReleaseRequested,
                        modifier = Modifier.weight(1f)
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

        if (pricing.ready && !pricing.verificationVisible && pricing.canShowPage) {
            key(releaseId, pricing.attempt) {
                MarketplacePricingWebView(
                    pricing, hidden = false,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        } else if (pricing.ready && !pricing.verificationVisible) {
            MarketplacePricingUnavailable(pricing, Modifier.weight(1f).fillMaxWidth())
        }
        if (pricing.status in setOf(
                MarketplaceUiPriceStatus.RATE_LIMITED, MarketplaceUiPriceStatus.BLOCKED,
                MarketplaceUiPriceStatus.FAILED, MarketplaceUiPriceStatus.NETWORK,
                MarketplaceUiPriceStatus.TIMEOUT, MarketplaceUiPriceStatus.UNREADABLE,
                MarketplaceUiPriceStatus.PARTIAL)) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalArrangement = Arrangement.End) {
                if (pricing.errorDetails != null) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                            "Marketplace error", pricing.errorDetails.orEmpty()))
                        android.widget.Toast.makeText(context, "Error details copied", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("Copy error details") }
                }
                TextButton(onClick = pricing::refresh) { Text("Refresh") }
            }
        }
    }

    LaunchedEffect(showSellDialog, isSubmittingListing, pricing.status) {
        if (showSellDialog && !isSubmittingListing) pricing.openVerification(automatic = true)
    }
    if (showSellDialog) {
        AddListingDialog(
            priceSummary = effectivePriceSummary ?: priceSummary,
            isSubmitting = isSubmittingListing,
            submissionError = listingSubmissionError,
            pricingInfo = pricing.listingInfo,
            onVerifyPricing = { pricing.openVerification() },
            onRefreshPricing = pricing::refresh,
            onDismiss = {
                if (!isSubmittingListing) {
                    showSellDialog = false
                    listingSubmissionError = null
                }
            },
            onSave = saveListing@{ price, condition, sleeveCondition, comments ->
                if (isSubmittingListing) return@saveListing
                isSubmittingListing = true
                listingSubmissionError = null
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
                    onResult = { resultMessage, verified ->
                        isSubmittingListing = false
                        if (verified) {
                            showSellDialog = false
                            listingSubmissionError = null
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            android.widget.Toast.makeText(
                                context,
                                resultMessage,
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        } else {
                            listingSubmissionError = resultMessage
                        }
                    }
                )
            }
        )
    }
    MarketplaceVerificationDialog(pricing)
}
