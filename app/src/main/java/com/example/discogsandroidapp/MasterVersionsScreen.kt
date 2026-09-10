package com.example.discogsandroidapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private enum class MasterVersionSort(
    val label: String
) {
    RELEASED_OLDEST("Year: Oldest first"),
    RELEASED_NEWEST("Year: Newest first"),
    COUNTRY_AZ("Country: A-Z"),
    LABEL_AZ("Label: A-Z"),
    CATALOG_AZ("Catalog number: A-Z")
}

@Composable
fun MasterVersionsScreen(
    masterId: Long,
    versions: List<MasterVersion>,
    totalItems: Int,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onBackClick: () -> Unit,
    onVersionClick: (Long) -> Unit = {}
) {
    var showFilterDialog by remember {
        mutableStateOf(false)
    }

    var sortExpanded by remember {
        mutableStateOf(false)
    }

    var selectedCountry by remember {
        mutableStateOf<String?>(null)
    }

    var selectedFormat by remember {
        mutableStateOf<String?>(null)
    }

    var selectedYear by remember {
        mutableStateOf<String?>(null)
    }

    var selectedSort by remember {
        mutableStateOf(
            MasterVersionSort.RELEASED_OLDEST
        )
    }

    val listState = rememberLazyListState()

    val countries = remember(versions) {
        versions
            .mapNotNull { it.country }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    val formats = remember(versions) {
        versions
            .flatMap { version ->
                if (version.majorFormats.isNotEmpty()) {
                    version.majorFormats
                } else {
                    listOfNotNull(
                        version.format
                            ?.substringBefore(",")
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                    )
                }
            }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    val years = remember(versions) {
        versions
            .mapNotNull {
                extractVersionYear(it.released)
            }
            .distinct()
            .sortedDescending()
            .map { it.toString() }
    }

    val filteredAndSortedVersions = remember(
        versions,
        selectedCountry,
        selectedFormat,
        selectedYear,
        selectedSort
    ) {
        val filtered =
            versions.filter { version ->

                val countryMatches =
                    selectedCountry == null ||
                            version.country == selectedCountry

                val formatMatches =
                    selectedFormat == null ||
                            version.majorFormats.any {
                                it.equals(
                                    selectedFormat,
                                    ignoreCase = true
                                )
                            } ||
                            version.format
                                ?.contains(
                                    selectedFormat!!,
                                    ignoreCase = true
                                ) == true

                val yearMatches =
                    selectedYear == null ||
                            extractVersionYear(
                                version.released
                            )?.toString() == selectedYear

                countryMatches &&
                        formatMatches &&
                        yearMatches
            }

        when (selectedSort) {
            MasterVersionSort.RELEASED_OLDEST ->
                filtered.sortedWith(
                    compareBy<MasterVersion> {
                        extractVersionYear(it.released)
                            ?: Int.MAX_VALUE
                    }.thenBy { it.id }
                )

            MasterVersionSort.RELEASED_NEWEST ->
                filtered.sortedWith(
                    compareByDescending<MasterVersion> {
                        extractVersionYear(it.released)
                            ?: Int.MIN_VALUE
                    }.thenByDescending { it.id }
                )

            MasterVersionSort.COUNTRY_AZ ->
                filtered.sortedWith(
                    compareBy(
                        String.CASE_INSENSITIVE_ORDER
                    ) {
                        it.country ?: "ZZZZ"
                    }
                )

            MasterVersionSort.LABEL_AZ ->
                filtered.sortedWith(
                    compareBy(
                        String.CASE_INSENSITIVE_ORDER
                    ) {
                        it.label ?: "ZZZZ"
                    }
                )

            MasterVersionSort.CATALOG_AZ ->
                filtered.sortedWith(
                    compareBy(
                        String.CASE_INSENSITIVE_ORDER
                    ) {
                        it.catno ?: "ZZZZ"
                    }
                )
        }
    }

    val activeFilterCount =
        listOf(
            selectedCountry,
            selectedFormat,
            selectedYear
        ).count { it != null }

    // Filtering or sorting creates a new result set.
    // Always bring the user back to the first matching version.
    LaunchedEffect(
        selectedCountry,
        selectedFormat,
        selectedYear,
        selectedSort
    ) {
        if (filteredAndSortedVersions.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    if (showFilterDialog) {
        MasterVersionFilterDialog(
            countries = countries,
            formats = formats,
            years = years,
            selectedCountry = selectedCountry,
            selectedFormat = selectedFormat,
            selectedYear = selectedYear,
            onCountrySelected = {
                selectedCountry = it
            },
            onFormatSelected = {
                selectedFormat = it
            },
            onYearSelected = {
                selectedYear = it
            },
            onClear = {
                selectedCountry = null
                selectedFormat = null
                selectedYear = null
            },
            onDismiss = {
                showFilterDialog = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick
            ) {
                Icon(
                    imageVector =
                        Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "All Versions",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )

                if (
                    !isLoading &&
                    errorMessage == null
                ) {
                    Text(
                        text =
                            if (activeFilterCount > 0) {
                                "${filteredAndSortedVersions.size} of $totalItems versions"
                            } else {
                                "$totalItems versions"
                            },
                        fontSize = 13.sp,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(
                onClick = {
                    showFilterDialog = true
                }
            ) {
                Box {
                    Icon(
                        imageVector =
                            Icons.Default.FilterList,
                        contentDescription = "Filter versions",
                        tint =
                            if (activeFilterCount > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                    )

                    if (activeFilterCount > 0) {
                        Text(
                            text = activeFilterCount.toString(),
                            modifier = Modifier
                                .align(Alignment.TopEnd),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color =
                                MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Box {
                IconButton(
                    onClick = {
                        sortExpanded = true
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Sort,
                        contentDescription = "Sort versions"
                    )
                }

                DropdownMenu(
                    expanded = sortExpanded,
                    onDismissRequest = {
                        sortExpanded = false
                    }
                ) {
                    MasterVersionSort.entries
                        .forEach { sortOption ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = sortOption.label,
                                        fontWeight =
                                            if (
                                                selectedSort ==
                                                sortOption
                                            ) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Normal
                                            }
                                    )
                                },
                                onClick = {
                                    selectedSort = sortOption
                                    sortExpanded = false
                                }
                            )
                        }
                }
            }
        }

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        when {
            isLoading -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()

                    Spacer(
                        modifier = Modifier.height(10.dp)
                    )

                    Text(
                        text = "Loading master $masterId..."
                    )
                }
            }

            errorMessage != null -> {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error
                )
            }

            versions.isEmpty() -> {
                Text(
                    text = "No versions found.",
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            filteredAndSortedVersions.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp),
                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No versions match these filters.",
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )

                    TextButton(
                        onClick = {
                            selectedCountry = null
                            selectedFormat = null
                            selectedYear = null
                        }
                    ) {
                        Text("Clear filters")
                    }
                }
            }

            else -> {
                LazyColumn(
                    state = listState,
                    verticalArrangement =
                        Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items =
                            filteredAndSortedVersions,
                        key = { it.id }
                    ) { version ->
                        MasterVersionCard(
                            version = version,
                            onClick = {
                                onVersionClick(
                                    version.id
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MasterVersionFilterDialog(
    countries: List<String>,
    formats: List<String>,
    years: List<String>,
    selectedCountry: String?,
    selectedFormat: String?,
    selectedYear: String?,
    onCountrySelected: (String?) -> Unit,
    onFormatSelected: (String?) -> Unit,
    onYearSelected: (String?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Filter versions")
        },
        text = {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(14.dp)
            ) {
                VersionFilterDropdown(
                    label = "Country",
                    selected = selectedCountry,
                    options = countries,
                    onSelected = onCountrySelected
                )

                VersionFilterDropdown(
                    label = "Format",
                    selected = selectedFormat,
                    options = formats,
                    onSelected = onFormatSelected
                )

                VersionFilterDropdown(
                    label = "Year",
                    selected = selectedYear,
                    options = years,
                    onSelected = onYearSelected
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("Done")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onClear
            ) {
                Text("Clear")
            }
        }
    )
}

@Composable
private fun VersionFilterDropdown(
    label: String,
    selected: String?,
    options: List<String>,
    onSelected: (String?) -> Unit
) {
    var expanded by remember {
        mutableStateOf(false)
    }

    Column {
        Text(
            text = label,
            fontSize = 12.sp,
            color =
                MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(
            modifier = Modifier.height(4.dp)
        )

        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(
                onClick = {
                    expanded = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = selected ?: "All",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = {
                    expanded = false
                }
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "All",
                            fontWeight =
                                if (selected == null) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                }
                        )
                    },
                    onClick = {
                        onSelected(null)
                        expanded = false
                    }
                )

                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option,
                                fontWeight =
                                    if (
                                        selected ==
                                        option
                                    ) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    }
                            )
                        },
                        onClick = {
                            onSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MasterVersionCard(
    version: MasterVersion,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onClick()
            },
        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surfaceVariant.copy(
                    alpha = 0.5f
                )
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = version.thumb,
                contentDescription = version.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(68.dp)
                    .clip(
                        RoundedCornerShape(8.dp)
                    )
            )

            Spacer(
                modifier = Modifier.width(12.dp)
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = version.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                val labelLine =
                    listOfNotNull(
                        version.label
                            ?.takeIf { it.isNotBlank() },
                        version.catno
                            ?.takeIf { it.isNotBlank() }
                    ).joinToString(" • ")

                if (labelLine.isNotBlank()) {
                    Text(
                        text = labelLine,
                        fontSize = 12.sp,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val releaseLine =
                    listOfNotNull(
                        version.country
                            ?.takeIf { it.isNotBlank() },
                        version.released
                            ?.takeIf { it.isNotBlank() },
                        version.format
                            ?.takeIf { it.isNotBlank() }
                    ).joinToString(" • ")

                if (releaseLine.isNotBlank()) {
                    Text(
                        text = releaseLine,
                        fontSize = 12.sp,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = "Release ID ${version.id}",
                    fontSize = 11.sp,
                    color =
                        MaterialTheme.colorScheme.outline
                )
            }

            Icon(
                imageVector =
                    Icons.Default.KeyboardArrowRight,
                contentDescription = "Open release",
                tint =
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun extractVersionYear(
    released: String?
): Int? {
    if (released.isNullOrBlank()) {
        return null
    }

    return Regex("""\d{4}""")
        .find(released)
        ?.value
        ?.toIntOrNull()
}
