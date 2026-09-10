package com.example.discogsandroidapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.material.icons.automirrored.filled.ArrowBack

@Composable
fun AiSearchScreen(
    viewModel: AiSearchViewModel,
    onBackClick: () -> Unit
) {
    val query by viewModel.query.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {

            IconButton(
                onClick = onBackClick
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }

            Text(
                text = "AI Inventory Search",
                style = MaterialTheme.typography.headlineMedium
            )
        }

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        Text(
            text = "Ask questions about your Discogs inventory in plain English.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        OutlinedTextField(
            value = query,
            onValueChange = viewModel::updateQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("Show me Beatles records over $20")
            },
            singleLine = true,
            trailingIcon = {
                IconButton(
                    onClick = {
                        viewModel.search()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search"
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Search
            ),
            keyboardActions = KeyboardActions(
                onSearch = {
                    viewModel.search()
                }
            )
        )

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        when (val state = uiState) {

            AiSearchUiState.Idle -> {
                AiSearchSuggestions(
                    onSuggestionClick = { suggestion ->
                        viewModel.updateQuery(suggestion)
                    }
                )
            }

            AiSearchUiState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()

                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )

                    Text("Searching inventory...")
                }
            }

            is AiSearchUiState.Success -> {
                AiSearchResults(
                    response = state.response
                )
            }

            is AiSearchUiState.Error -> {
                Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun AiSearchSuggestions(
    onSuggestionClick: (String) -> Unit
) {
    val suggestions = listOf(
        "Show me all Beatles records over $20",
        "What are my five most valuable jazz records?",
        "Show me all Blue Note records in my inventory",
        "Which records are priced below market value?",
        "Which records should I raise the price on?"
    )

    Text(
        text = "Try asking:",
        style = MaterialTheme.typography.titleMedium
    )

    Spacer(
        modifier = Modifier.height(8.dp)
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        suggestions.forEach { suggestion ->

            AssistChip(
                onClick = {
                    onSuggestionClick(suggestion)
                },
                label = {
                    Text(suggestion)
                }
            )
        }
    }
}

@Composable
private fun AiSearchResults(
    response: AiSearchResponse
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {

        response.summary?.let { summary ->

            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(
                modifier = Modifier.height(12.dp)
            )
        }

        Text(
            text = "${response.results.size} results",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = response.results,
                key = { result ->
                    result.listingId ?: result.releaseId
                }
            ) { result ->

                AiInventoryResultCard(
                    result = result
                )
            }
        }
    }
}

@Composable
private fun AiInventoryResultCard(
    result: AiInventoryResult
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            result.thumbnail?.let { imageUrl ->

                AsyncImage(
                    model = imageUrl,
                    contentDescription = "${result.artist} ${result.title}",
                    modifier = Modifier.size(80.dp)
                )

                Spacer(
                    modifier = Modifier.size(12.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {

                Text(
                    text = result.artist,
                    style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = result.title,
                    style = MaterialTheme.typography.bodyLarge
                )

                result.year?.let {
                    Text(
                        text = it.toString(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                result.condition?.let {
                    Text(
                        text = "Condition: $it",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                result.price?.let { price ->

                    val currency = result.currency ?: "USD"

                    Text(
                        text = "Price: %.2f %s".format(
                            price,
                            currency
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                result.marketValue?.let { marketValue ->

                    Text(
                        text = "Market value: %.2f".format(
                            marketValue
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                result.recommendedPrice?.let { recommendedPrice ->

                    Text(
                        text = "Suggested price: %.2f".format(
                            recommendedPrice
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                result.reason?.let { reason ->

                    Spacer(
                        modifier = Modifier.height(4.dp)
                    )

                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}