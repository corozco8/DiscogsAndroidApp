package com.example.discogsandroidapp.pricing

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

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
            TextButton(onClick = onVerify, enabled = enabled) { Text("View listings") }
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
