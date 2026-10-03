package com.example.discogsandroidapp.pricing

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/** Move the same live page between hosts, preserving challenge/session state. */
@Composable
internal fun MarketplacePricingWebView(
    pricing: MarketplacePricingController,
    hidden: Boolean,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            // Important: create/attach the Discogs page only when AndroidView is created.
            // Compose may call `update` many times during recomposition, so network page
            // creation must never live in the update block.
            FrameLayout(context).apply {
                val page = pricing.createWebView(context, hidden)
                (page.parent as? ViewGroup)?.removeView(page)
                addView(page, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
            }
        },
        update = { host ->
            // Recomposition is allowed to change presentation only. This method cannot
            // create a WebView or call loadUrl(), so state changes cannot generate extra
            // marketplace requests.
            val page = host.getChildAt(0) as? android.webkit.WebView
            pricing.updateWebViewVisibility(page, hidden)
        },
        onRelease = { host ->
            val page = host.getChildAt(0) as? android.webkit.WebView
            host.removeAllViews()
            page?.let(pricing::onPageDetached)
        }
    )
}

@Composable
internal fun MarketplaceVerificationDialog(pricing: MarketplacePricingController) {
    if (!pricing.verificationVisible) return
    LaunchedEffect(pricing) {
        while (true) {
            delay(1_500)
            pricing.checkVerificationPage()
        }
    }
    Dialog(
        onDismissRequest = pricing::closeVerification,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Verify Discogs access", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = pricing::closeVerification) { Text("Back to listing") }
                }
                Text(
                    "Complete the Cloudflare check below. Your listing details are saved in the form behind this page.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                MarketplacePricingWebView(pricing, hidden = false, modifier = Modifier.weight(1f).fillMaxWidth())
                Text(
                    if (pricing.status == MarketplaceUiPriceStatus.RATE_LIMITED) pricing.message
                    else "Live pricing resumes when access is confirmed. If the check keeps repeating, return to the form and enter a price or use the pricing algorithm.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            }
            com.example.discogsandroidapp.ui.shared.ApiRequestCounter(
                Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(top = 2.dp, end = 6.dp)
            )
            }
        }
    }
}

@Composable
internal fun ListingPricingNotice(
    info: ListingPricingInfo?,
    enabled: Boolean,
    onVerify: (() -> Unit)?,
    onRefresh: (() -> Unit)?
) {
    if (info == null || info.status in setOf(MarketplaceUiPriceStatus.IDLE, MarketplaceUiPriceStatus.FRESH, MarketplaceUiPriceStatus.CACHED)) return
    val notice = when (info.status) {
        MarketplaceUiPriceStatus.LOADING -> "Checking live prices…"
        MarketplaceUiPriceStatus.VERIFICATION_REQUIRED -> "Discogs verification required"
        MarketplaceUiPriceStatus.RATE_LIMITED -> "Discogs: too many requests"
        MarketplaceUiPriceStatus.NETWORK -> "Live pricing offline"
        MarketplaceUiPriceStatus.TIMEOUT -> "Live pricing timed out"
        MarketplaceUiPriceStatus.NO_MATCH -> "No matching live prices"
        MarketplaceUiPriceStatus.BLOCKED -> "Marketplace access blocked"
        MarketplaceUiPriceStatus.PARTIAL -> "Some listings could not be read"
        MarketplaceUiPriceStatus.AUTOMATIC_PAUSED -> "Automatic live checks paused"
        else -> "Live prices unavailable"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(notice, modifier = Modifier.weight(1f), maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (info.status == MarketplaceUiPriceStatus.VERIFICATION_REQUIRED && onVerify != null) {
            TextButton(onClick = onVerify, enabled = enabled) { Text("Verify Discogs") }
        } else if (info.status != MarketplaceUiPriceStatus.LOADING && onRefresh != null) {
            TextButton(onClick = onRefresh, enabled = enabled) {
                Text(if (info.status == MarketplaceUiPriceStatus.AUTOMATIC_PAUSED) "Check live price" else "Refresh prices")
            }
        }
    }
}

/** Replaces an unavailable page, rather than adding a permanent strip above Discogs. */
@Composable
internal fun MarketplacePricingUnavailable(pricing: MarketplacePricingController, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(pricing.message)
        if (pricing.status == MarketplaceUiPriceStatus.LOADING) {
            CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
        }
        TextButton(onClick = pricing::refresh, enabled = pricing.status != MarketplaceUiPriceStatus.LOADING) {
            Text("Open listings")
        }
    }
}
