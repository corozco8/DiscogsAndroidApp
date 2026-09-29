package com.example.discogsandroidapp

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
        factory = { FrameLayout(it) },
        update = { host ->
            val page = pricing.createWebView(host.context, hidden)
            if (page.parent !== host) {
                (page.parent as? ViewGroup)?.removeView(page)
                host.addView(page, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
            }
        },
        onRelease = { it.removeAllViews() }
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
    if (info == null || info.status in setOf(MarketplaceUiPriceStatus.FRESH, MarketplaceUiPriceStatus.CACHED)) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(info.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (info.status == MarketplaceUiPriceStatus.VERIFICATION_REQUIRED && onVerify != null) {
            TextButton(onClick = onVerify, enabled = enabled) { Text("Verify Discogs") }
        } else if (info.status != MarketplaceUiPriceStatus.LOADING && onRefresh != null) {
            TextButton(onClick = onRefresh, enabled = enabled && info.retryInSeconds == 0L) {
                Text(if (info.retryInSeconds > 0) "Retry in ${info.retryInSeconds}s" else "Refresh prices")
            }
        }
    }
}

/** Replaces an unavailable page, rather than adding a permanent strip above Discogs. */
@Composable
internal fun MarketplacePricingUnavailable(pricing: MarketplacePricingController, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(pricing.message)
        TextButton(onClick = pricing::refresh, enabled = pricing.retryInSeconds == 0L) {
            Text(if (pricing.retryInSeconds > 0) "Retry in ${pricing.retryInSeconds}s" else "Open listings")
        }
    }
}
