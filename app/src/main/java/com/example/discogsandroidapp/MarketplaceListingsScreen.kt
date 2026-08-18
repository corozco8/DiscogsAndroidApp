package com.example.discogsandroidapp

import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceListingsScreen(
    releaseId: Long,
    viewModel: ReleaseViewModel,
    token: String,
    onBackClick: () -> Unit
) {
    var showSellDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 🆕 Collect the price suggestion state from ViewModel
    val priceSuggestion by viewModel.conditionPriceSuggestion.collectAsState()

    val sortOptions = mapOf(
        "Lowest Price" to "price%2Casc",
        "Highest Price" to "price%2Cdesc",
        "Best Media Condition" to "condition%2Cdesc",
        "Newly Listed" to "listed%2Cdesc"
    )

    val sortKeys = sortOptions.keys.toList()
    var selectedSortText by remember { mutableStateOf(sortKeys[0]) }
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
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
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded },
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = selectedSortText,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Sort By") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                sortKeys.forEach { selectionOption ->
                                    DropdownMenuItem(
                                        text = { Text(selectionOption) },
                                        onClick = {
                                            selectedSortText = selectionOption
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }

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

            val sortQuery = sortOptions[selectedSortText] ?: "price%2Casc"
            val url = "https://www.discogs.com/sell/release/$releaseId?sort=$sortQuery"

            AndroidView(
                modifier = Modifier.weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        webViewClient = WebViewClient()
                        settings.javaScriptEnabled = true
                    }
                },
                update = { webView ->
                    webView.loadUrl(url)
                }
            )
        }

        // 🆕 Updated dialog with price suggestion support
        if (showSellDialog) {
            AddListingDialog(
                onDismiss = {
                    showSellDialog = false
                    // Optionally clear suggestion when dialog closes
                    // viewModel.clearPriceSuggestion() // if you add that
                },
                onSave = { price: Double, condition: String, sleeveCondition: String, comments: String ->
                    Log.d("MARKETPLACE_LISTING", "Calling createListing with releaseId=$releaseId")
                    viewModel.createListing(
                        releaseId = releaseId.toInt(),
                        price = price,
                        condition = condition,
                        sleeveCondition = sleeveCondition,
                        comments = comments,
                        token = token,
                        onSuccess = {
                            Log.d("MARKETPLACE_LISTING", "Listing created successfully")
                            android.widget.Toast.makeText(
                                context,
                                "Listing created successfully!",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                    showSellDialog = false
                },
                // 🆕 Pass the suggestion and the callback
                priceSuggestion = priceSuggestion,
                onConditionsChanged = { condition, sleeveCondition ->
                    // Trigger price suggestion lookup when user changes conditions
                    viewModel.fetchPriceSuggestionForCondition(
                        releaseId = releaseId,
                        condition = condition,
                        token = token,
                        sleeveCondition = sleeveCondition
                    )
                }
            )
        }
    }
}