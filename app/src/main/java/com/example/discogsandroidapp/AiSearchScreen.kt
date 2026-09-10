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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton

@Composable
fun AiSearchScreen(
    viewModel: AiSearchViewModel,
    onBackClick: () -> Unit,
    onReleaseClick: (Long) -> Unit = {},
    onEditClick: (AiInventoryResult) -> Unit = {},
    onDeleteClick: (AiInventoryResult) -> Unit = {},
    onDeleteSelected: (List<AiInventoryResult>) -> Unit = {}
) {
    val query by viewModel.query.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    var selectedListingIds by remember {
        mutableStateOf<Set<Long>>(emptySet())
    }

    val selectionMode = selectedListingIds.isNotEmpty()

    fun toggleSelection(result: AiInventoryResult) {
        val listingId = result.listingId ?: return

        selectedListingIds =
            if (listingId in selectedListingIds) {
                selectedListingIds - listingId
            } else {
                selectedListingIds + listingId
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        if (selectionMode) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        selectedListingIds = emptySet()
                    }
                ) {
                    Text("Cancel")
                }

                TextButton(
                    onClick = {
                        val currentResults =
                            (uiState as? AiSearchUiState.Success)
                                ?.response
                                ?.results
                                .orEmpty()

                        val selectedResults =
                            currentResults.filter { result ->
                                result.listingId in selectedListingIds
                            }

                        if (selectedResults.isNotEmpty()) {
                            onDeleteSelected(selectedResults)
                        }

                        selectedListingIds = emptySet()
                    }
                ) {
                    Text(
                        text = "Delete (${selectedListingIds.size})",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        } else {
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
                    response = state.response,
                    selectedListingIds = selectedListingIds,
                    selectionMode = selectionMode,
                    onToggleSelection = ::toggleSelection,
                    onReleaseClick = onReleaseClick,
                    onEditClick = onEditClick,
                    onDeleteClick = onDeleteClick
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
    response: AiSearchResponse,
    selectedListingIds: Set<Long>,
    selectionMode: Boolean,
    onToggleSelection: (AiInventoryResult) -> Unit,
    onReleaseClick: (Long) -> Unit,
    onEditClick: (AiInventoryResult) -> Unit,
    onDeleteClick: (AiInventoryResult) -> Unit
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
                    result = result,
                    isSelected = result.listingId in selectedListingIds,
                    selectionMode = selectionMode,
                    onToggleSelection = onToggleSelection,
                    onReleaseClick = onReleaseClick,
                    onEditClick = onEditClick,
                    onDeleteClick = onDeleteClick
                )
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiInventoryResultCard(
    result: AiInventoryResult,
    isSelected: Boolean,
    selectionMode: Boolean,
    onToggleSelection: (AiInventoryResult) -> Unit,
    onReleaseClick: (Long) -> Unit,
    onEditClick: (AiInventoryResult) -> Unit,
    onDeleteClick: (AiInventoryResult) -> Unit
) {
    var menuExpanded by remember {
        mutableStateOf(false)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        onToggleSelection(result)
                    } else {
                        onReleaseClick(result.releaseId)
                    }
                },
                onLongClick = {
                    onToggleSelection(result)
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor =
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                }
        ),
        border =
            if (isSelected) {
                BorderStroke(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                null
            }
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
            }

            if (!selectionMode) {
                Column {

                    IconButton(
                        onClick = {
                            menuExpanded = true
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Listing options"
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = {
                            menuExpanded = false
                        }
                    ) {

                        DropdownMenuItem(
                            text = {
                                Text("Edit")
                            },
                            onClick = {
                                menuExpanded = false
                                onEditClick(result)
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text("Delete")
                            },
                            onClick = {
                                menuExpanded = false
                                onDeleteClick(result)
                            }
                        )
                    }
                }
            }
        }
    }
}
