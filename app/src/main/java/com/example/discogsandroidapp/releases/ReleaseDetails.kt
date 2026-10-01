package com.example.discogsandroidapp.releases

import com.example.discogsandroidapp.data.DiscogsRelease
import com.example.discogsandroidapp.data.Price
import com.example.discogsandroidapp.data.ReleasePriceSummary
import com.example.discogsandroidapp.data.isAlbumFormat
import com.example.discogsandroidapp.inventory.getShortGrade
import com.example.discogsandroidapp.inventory.listingDescriptionForGrade
import com.example.discogsandroidapp.pricing.ListingPricingInfo
import com.example.discogsandroidapp.pricing.ListingPricingNotice
import com.example.discogsandroidapp.pricing.MarketplacePricingWebView
import com.example.discogsandroidapp.pricing.MarketplaceUiPriceStatus
import com.example.discogsandroidapp.pricing.MarketplaceVerificationDialog
import com.example.discogsandroidapp.pricing.listingPriceSource
import com.example.discogsandroidapp.pricing.priceGuideValues
import com.example.discogsandroidapp.pricing.rememberMarketplacePricing
import com.example.discogsandroidapp.pricing.withActiveMarketplacePrices
import com.example.discogsandroidapp.ui.shared.SearchAccessibleDialog
import com.example.discogsandroidapp.ui.shared.SwipeRefreshWrapper
import com.example.discogsandroidapp.ui.shared.keyboardInputArea

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import android.util.Log
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization

private const val TAG = "ReleaseDetails"

@Composable
internal fun ReleaseDetails(
    release: DiscogsRelease,
    priceSummary: ReleasePriceSummary? = null,
    onBackClick: () -> Unit,
    onSellConfirm: (
        price: Double, condition: String, sleeve: String, comments: String,
        onResult: (message: String, verified: Boolean) -> Unit
    ) -> Unit,
    onViewListingsClick: (releaseId: Long) -> Unit = {},
    onViewVersionsClick: (masterId: Long) -> Unit = {},
    onRefresh: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    pricingController: com.example.discogsandroidapp.pricing.MarketplacePricingController? = null
) {
    var showSellDialog by remember { mutableStateOf(false) }
    var isSubmittingListing by remember { mutableStateOf(false) }
    var listingSubmissionError by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val releaseId = release.id
    val pricing = pricingController ?: rememberMarketplacePricing(releaseId)
    val effectivePriceSummary = pricing.prices?.let {
        (priceSummary ?: ReleasePriceSummary()).withActiveMarketplacePrices(it)
    } ?: priceSummary
    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(showSellDialog, pricing.ready) {
        if (showSellDialog) pricing.requestSellPrices()
    }
    LaunchedEffect(showSellDialog, isSubmittingListing, pricing.status) {
        if (showSellDialog && !isSubmittingListing) pricing.openVerification(automatic = true)
    }

    if (showSellDialog) {
        AddListingDialog(
            // The loaded release metadata is authoritative even if the
            // optional price summary is still being fetched.
            priceSummary = (effectivePriceSummary ?: priceSummary
            ?: ReleasePriceSummary()).copy(
                isAlbumRelease = release.isAlbumFormat()
            ),
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
            onSave = { price, condition, sleeve, comments ->
                if (!isSubmittingListing) {
                    isSubmittingListing = true
                    listingSubmissionError = null
                    Log.d(TAG, "Sell confirmation: price=$price, condition='$condition', sleeve='$sleeve', comments='$comments'")
                    onSellConfirm(price, condition, sleeve, comments) { message, verified ->
                        isSubmittingListing = false
                        if (verified) {
                            showSellDialog = false
                            listingSubmissionError = null
                            // The activity's search field is behind this dialog.
                            // Don't let it reclaim focus and reopen the IME.
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                        } else {
                            // Preserve condition, price, and comments for correction.
                            // An unconfirmed POST may have created a listing, so show
                            // the exact verification warning rather than auto-retry.
                            listingSubmissionError = message
                        }
                    }
                }
            }
        )
    }


    MarketplaceVerificationDialog(pricing)
    Box(modifier = modifier.fillMaxSize()) {
        if (releaseId != null && pricing.ready && pricing.needsLoad &&
            !pricing.verificationVisible && pricing.canShowPage) {
            key(releaseId, pricing.attempt) {
                MarketplacePricingWebView(
                    pricing, hidden = true,
                    modifier = Modifier.matchParentSize().alpha(0.01f)
                )
            }
        }

        SwipeRefreshWrapper(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                if (onRefresh != null) {
                    onRefresh.invoke()
                } else if (showSellDialog) {
                    pricing.refresh()
                }

                // If the caller performs a full release reload this screen will
                // normally leave composition. This fallback keeps the spinner
                // from sticking when only marketplace pricing is refreshed.
                coroutineScope.launch {
                    kotlinx.coroutines.delay(1500)
                    isRefreshing = false
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.Start
            ) {
                // Large Album Art Carousel
                val imageUrls = if (!release.images.isNullOrEmpty()) {
                    release.images.mapNotNull { it.uri }
                } else {
                    listOf(release.thumb.takeIf { !it.isNullOrBlank() } ?: "https://via.placeholder.com/400")
                }

                val pagerState = rememberPagerState(pageCount = { imageUrls.size })

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                ) { page ->
                    AsyncImage(
                        model = imageUrls[page],
                        contentDescription = "Album Cover ${page + 1}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Thumbnail Preview Row
                if (imageUrls.size > 1) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(imageUrls.size) { index ->
                            val isSelected = pagerState.currentPage == index

                            AsyncImage(
                                model = imageUrls[index],
                                contentDescription = "Thumbnail ${index + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .alpha(if (isSelected) 1f else 0.5f)
                                    .border(
                                        width = if (isSelected) 2.dp else 0.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(index)
                                        }
                                    }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left side: Title and Artist
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = release.title ?: "Unknown Title",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = 32.sp
                        )
                        Text(
                            text = release.artists?.joinToString(", ") { it.name ?: "" } ?: "Unknown Artist",
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Right side: Sell button
                    FilledTonalButton(
                        onClick = { showSellDialog = true },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sell,
                            contentDescription = "Sell",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Sell", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // This browsing guide uses API pricing only, independent of live marketplace data.
                SalesRangeCard(
                    summary = priceSummary ?: ReleasePriceSummary(),
                    haveCount = release.community?.have ?: 0,
                    wantCount = release.community?.want ?: 0,
                    onListingsClick = { release.id?.let { onViewListingsClick(it) } }
                )

                Spacer(modifier = Modifier.height(24.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        @Composable
                        fun InfoRow(label: String, value: String) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(text = label, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.35f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(text = value, modifier = Modifier.weight(0.65f), color = MaterialTheme.colorScheme.onSurface)
                            }
                        }

                        val labelText = release.labels?.firstOrNull()?.name?.let {
                            "$it ${release.labels.firstOrNull()?.catno?.let { cat -> "– $cat" } ?: ""}"
                        } ?: "Unknown"

                        InfoRow("Label:", labelText)
                        val formatText =
                            release.formats
                                ?.flatMap { format ->
                                    buildList {
                                        format.name
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let { add(it) }

                                        format.descriptions
                                            ?.filter { it.isNotBlank() }
                                            ?.let { addAll(it) }

                                        format.text
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let { add(it) }
                                    }
                                }
                                ?.distinct()
                                ?.joinToString(", ")
                                ?.takeIf { it.isNotBlank() }
                                ?: "Unknown"

                        InfoRow("Format:", formatText)
                        InfoRow("Country:", release.country ?: "Unknown")
                        InfoRow(
                            "Released:",
                            release.released
                                ?.takeIf { it.isNotBlank() }
                                ?: release.year?.toString()
                                ?: "Unknown"
                        )
                        InfoRow("Genre:", release.genres?.joinToString(", ") ?: "Unknown")
                        InfoRow("Style:", release.styles?.joinToString(", ") ?: "Unknown")
                    }
                }

                release.masterId?.let { masterId ->
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = {
                            onViewVersionsClick(masterId)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "View All Versions of This Release",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                val tracks =
                    release.tracklist.orEmpty()

                if (tracks.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))

                    ReleaseInfoSection(
                        title = "Tracklist"
                    ) {
                        tracks.forEachIndexed { index, track ->
                            Column(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = track.position.orEmpty(),
                                        modifier = Modifier.width(44.dp),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )

                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = track.title
                                                ?: "Untitled",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )

                                        track.extraArtists
                                            .orEmpty()
                                            .filter {
                                                !it.name.isNullOrBlank() ||
                                                        !it.role.isNullOrBlank()
                                            }
                                            .forEach { credit ->
                                                Text(
                                                    text = buildString {
                                                        if (!credit.role.isNullOrBlank()) {
                                                            append(credit.role)
                                                            append(" – ")
                                                        }
                                                        append(
                                                            credit.name
                                                                ?: "Unknown"
                                                        )
                                                    },
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                    }

                                    track.duration
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { duration ->
                                            Text(
                                                text = duration,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                }

                                if (index != tracks.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(
                                            vertical = 8.dp
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                val companies =
                    release.companies.orEmpty()

                if (companies.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(20.dp))

                    ReleaseInfoSection(
                        title = "Companies, etc."
                    ) {
                        companies.forEach { company ->
                            Text(
                                text = buildString {
                                    company.entityTypeName
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let {
                                            append(it)
                                            append(" – ")
                                        }

                                    append(
                                        company.name
                                            ?: "Unknown"
                                    )

                                    company.catno
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let {
                                            append(" – ")
                                            append(it)
                                        }
                                },
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                val credits =
                    release.extraArtists.orEmpty()

                if (credits.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(20.dp))

                    ReleaseInfoSection(
                        title = "Credits"
                    ) {
                        credits.forEach { credit ->
                            Text(
                                text = buildString {
                                    credit.role
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let {
                                            append(it)
                                            append(" – ")
                                        }

                                    append(
                                        credit.name
                                            ?: "Unknown"
                                    )

                                    credit.tracks
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let {
                                            append(" (tracks: ")
                                            append(it)
                                            append(")")
                                        }
                                },
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                release.notes
                    ?.takeIf { it.isNotBlank() }
                    ?.let { notes ->
                        Spacer(modifier = Modifier.height(20.dp))

                        ReleaseInfoSection(
                            title = "Notes"
                        ) {
                            Text(
                                text = notes,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )
                        }
                    }

                val identifiers =
                    release.identifiers.orEmpty()

                if (identifiers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(20.dp))

                    ReleaseInfoSection(
                        title = "Barcode and Other Identifiers"
                    ) {
                        identifiers.forEach { identifier ->
                            Text(
                                text = buildString {
                                    identifier.type
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let {
                                            append(it)
                                            append(": ")
                                        }

                                    append(
                                        identifier.value
                                            ?: "Unknown"
                                    )

                                    identifier.description
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let {
                                            append(" (")
                                            append(it)
                                            append(")")
                                        }
                                },
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ReleaseInfoSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(
            alpha = 0.35f
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            HorizontalDivider()

            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddListingDialog(
    priceSummary: ReleasePriceSummary? = null,
    isSubmitting: Boolean = false,
    submissionError: String? = null,
    pricingInfo: ListingPricingInfo? = null,
    onVerifyPricing: (() -> Unit)? = null,
    onRefreshPricing: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onSave: (Double, String, String, String) -> Unit
) {
    var price by remember { mutableStateOf("") }
    var condition by remember { mutableStateOf("") }
    var sleeveCondition by remember { mutableStateOf("") }
    var comments by remember { mutableStateOf("") }

    var conditionError by remember { mutableStateOf(false) }
    var priceError by remember { mutableStateOf(false) }

    val conditions = listOf("Mint (M)", "Near Mint (NM or M-)", "Very Good Plus (VG+)", "Very Good (VG)", "Good Plus (G+)", "Good (G)", "Fair (F)", "Poor (P)", "Not Graded")
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

    SearchAccessibleDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        allowSearchFocus = !isSubmitting
    ) {
        val dialogFocusManager = LocalFocusManager.current
        val dialogKeyboardController = LocalSoftwareKeyboardController.current
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Top Custom Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss, enabled = !isSubmitting) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Text(
                        text = "List Item For Sale",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    TextButton(onClick = {
                        val cleanPriceString = price.replace(Regex("[^0-9.]"), "")
                        val parsedPrice = cleanPriceString.toDoubleOrNull() ?: 0.0

                        var hasError = false

                        if (condition.isBlank() || condition == "Not Graded") {
                            conditionError = true
                            hasError = true
                        } else {
                            conditionError = false
                        }

                        if (parsedPrice <= 0.0) {
                            priceError = true
                            hasError = true
                        } else {
                            priceError = false
                        }

                        if (!hasError) {
                            // Close the price/comments IME before reporting the result.
                            // Never transfer text focus to the activity search field.
                            dialogFocusManager.clearFocus(force = true)
                            dialogKeyboardController?.hide()
                            val finalSleeve = if (sleeveCondition.isBlank()) "Not Graded" else sleeveCondition
                            onSave(parsedPrice, condition, finalSleeve, comments)
                        }
                    }, enabled = !isSubmitting) {
                        if (isSubmitting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Listing…", fontWeight = FontWeight.Bold)
                        } else {
                            Text("Save", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (submissionError != null) {
                    Text(
                        text = submissionError,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }

                // 1. Media Condition Scrollable Row
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Media Condition",
                        fontSize = 12.sp,
                        color = if (conditionError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(count = conditions.size) { index ->
                            val fullGrade = conditions[index]
                            val isSelected = condition == fullGrade
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clickable(enabled = !isSubmitting) {
                                        comments = listingDescriptionForGrade(comments, fullGrade)
                                        condition = fullGrade
                                        conditionError = false
                                    }
                                    .border(
                                        width = 1.dp,
                                        color = if (conditionError && !isSelected) MaterialTheme.colorScheme.error else Color.Transparent,
                                        shape = RoundedCornerShape(16.dp)
                                    )
                            ) {
                                Text(
                                    text = getShortGrade(fullGrade),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    if (conditionError) {
                        Text(
                            text = "Media condition is required to list an item.",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp, start = 16.dp)
                        )
                    }
                }

                // 2. Sleeve Condition Scrollable Row
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
                        items(count = sleeveConditions.size) { index ->
                            val fullGrade = sleeveConditions[index]
                            val isSelected = sleeveCondition == fullGrade
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable(enabled = !isSubmitting) { sleeveCondition = fullGrade }
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

                // 3. Price Field
                OutlinedTextField(
                    value = price,
                    onValueChange = {
                        price = it
                        if (priceError) priceError = false
                    },
                    label = { Text("Price (USD)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    ),
                    isError = priceError,
                    enabled = !isSubmitting,
                    modifier = Modifier.keyboardInputArea().fillMaxWidth(),
                    supportingText =
                        if (priceError) {
                            {
                                Text("Price must be greater than $0.00")
                            }
                        } else {
                            null
                        }
                )

                ListingPricingNotice(
                    info = pricingInfo,
                    enabled = !isSubmitting,
                    onVerify = onVerifyPricing?.let { verify -> {
                        dialogFocusManager.clearFocus(force = true)
                        dialogKeyboardController?.hide()
                        verify()
                    } },
                    onRefresh = onRefreshPricing
                )

                // Compact seller recommendation. Distinguish a verified
                // live marketplace price from the app's fallback estimate.
                if (
                    condition.isNotBlank() &&
                    condition != "Not Graded"
                ) {
                    val livePrice =
                        priceSummary
                            ?.currentListingPriceFor(
                                condition = condition,
                                sleeveCondition = sleeveCondition
                            )
                            ?.takeIf { it > 0.0 }

                    val gradeEstimate = if (livePrice == null) {
                        priceSummary?.liveGradeEstimateFor(condition, sleeveCondition)
                    } else null

                    val estimatedPrice =
                        if (livePrice == null) {
                            gradeEstimate?.price ?: priceSummary
                                ?.fallbackRecommendedPriceFor(
                                    condition = condition,
                                    sleeveCondition = sleeveCondition
                                )
                                ?.takeIf { it > 0.0 }
                        } else {
                            null
                        }

                    val recommendation = livePrice ?: estimatedPrice
                    val isLive = livePrice != null

                    if (recommendation != null) {
                        val source = listingPriceSource(isLive, gradeEstimate != null, pricingInfo?.usingSavedPrices == true)
                        val amount = String.format(java.util.Locale.US, "%.2f", recommendation)
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = 4.dp,
                                        vertical = 0.dp
                                    ),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isLive) "$source: \$$amount"
                                        else "$source ${getShortGrade(condition)}: USD $amount",
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                TextButton(
                                    enabled = !isSubmitting,
                                    onClick = {
                                        price = String.format(
                                            java.util.Locale.US,
                                            "%.2f",
                                            recommendation
                                        )
                                        priceError = false
                                    },
                                    contentPadding = PaddingValues(
                                        horizontal = 8.dp,
                                        vertical = 0.dp
                                    )
                                ) {
                                    Text(
                                        text = "Use",
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = if (pricingInfo == null || pricingInfo.status == MarketplaceUiPriceStatus.LOADING)
                                "Checking current USD listings…" else "No price recommendation available. Enter your own price.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }

                // 4. Comments Field
                OutlinedTextField(
                    value = comments,
                    onValueChange = { comments = it },
                    label = { Text("Description / Comments") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    maxLines = 3,
                    enabled = !isSubmitting,
                    modifier = Modifier.keyboardInputArea().fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun SalesRangeCard(
    summary: ReleasePriceSummary,
    livePrices: List<Double> = emptyList(),
    pricingInfo: ListingPricingInfo? = null,
    haveCount: Int = 0,
    wantCount: Int = 0,
    onListingsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val guide = priceGuideValues(summary, livePrices, pricingInfo)
    fun formatPrice(price: Double?): String {
        return if (price != null && price > 0.0) "$${String.format(java.util.Locale.getDefault(), "%.2f", price)}" else "N/A"
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Have",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )

                        Spacer(
                            modifier = Modifier.width(6.dp)
                        )

                        Text(
                            text = haveCount.toString(),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Want",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )

                        Spacer(
                            modifier = Modifier.width(6.dp)
                        )

                        Text(
                            text = wantCount.toString(),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(guide.lowLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Text(
                            text = formatPrice(guide.low),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(if (guide.low != null && guide.low > 0) guide.currency else "", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    }

                    HorizontalDivider(
                        modifier = Modifier.weight(0.2f).padding(horizontal = 2.dp),
                        thickness = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )

                    Column(
                        modifier = Modifier.weight(1.2f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(guide.middleLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Text(
                            text = formatPrice(guide.middle),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                        Text(if (guide.middle != null && guide.middle > 0) guide.currency else "", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    }

                    HorizontalDivider(
                        modifier = Modifier.weight(0.2f).padding(horizontal = 2.dp),
                        thickness = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(guide.highLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Text(
                            text = formatPrice(guide.high),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(if (guide.high != null && guide.high > 0) guide.currency else "", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (summary.numForSale > 0 && summary.lowestAskingPrice != null && summary.lowestAskingPrice > 0.0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onListingsClick() }
                    .padding(vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ShoppingBag,
                    contentDescription = "For Sale",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${summary.numForSale} copies for sale from ",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "$${String.format(java.util.Locale.getDefault(), "%.2f", summary.lowestAskingPrice)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}
