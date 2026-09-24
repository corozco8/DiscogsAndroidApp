package com.example.discogsandroidapp

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private object PricingCooldowns {
    val releases = mutableMapOf<Long, Pair<MarketplaceUiPriceStatus, Long>>()
    val forceNextVisit = mutableSetOf<Long>()
    var blockedUntil = 0L
    fun remaining(id: Long) = (maxOf(blockedUntil, releases[id]?.second ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0)
}

/** Shared by release details and the visible marketplace; owned by the current screen. */
internal class MarketplacePricingController(private val releaseId: Long?) {
    var ready by mutableStateOf(false); private set
    var needsLoad by mutableStateOf(false); private set
    var attempt by mutableIntStateOf(0); private set
    var snapshot by mutableStateOf<MarketplacePriceSnapshot?>(null); private set
    var status by mutableStateOf(MarketplaceUiPriceStatus.LOADING); private set
    var retryInSeconds by mutableLongStateOf(0L); private set
    fun updateCooldown() { retryInSeconds = releaseId?.let { (PricingCooldowns.remaining(it) + 999) / 1000 } ?: 0 }
    private var generation = 0
    private var active = true
    private var webView: WebView? = null
    val prices: ActiveMarketplaceConditionPrices? get() = snapshot?.prices
    val message: String get() {
        val cached = snapshot?.let {
            val age = ((System.currentTimeMillis() - it.updatedAtMillis) / 60_000L).coerceAtLeast(0)
            "Saved prices checked $age min ago. "
        }.orEmpty()
        return when (status) {
            MarketplaceUiPriceStatus.LOADING -> cached + "Checking marketplace prices…"
            MarketplaceUiPriceStatus.CACHED -> cached + "First page of listings; shipping excluded."
            MarketplaceUiPriceStatus.FRESH -> "Prices checked now. First page of listings; shipping excluded."
            MarketplaceUiPriceStatus.NO_MATCH -> "No matching listings found. Using estimated pricing."
            MarketplaceUiPriceStatus.PARTIAL -> cached + "Only part of the page could be read."
            MarketplaceUiPriceStatus.BLOCKED -> cached + "Discogs blocked the page. Open Marketplace Listings to check access."
            MarketplaceUiPriceStatus.RATE_LIMITED -> cached + "Discogs rate limit reached. Wait before retrying."
            MarketplaceUiPriceStatus.NETWORK -> cached + "Connection failed. Check your connection and retry."
            MarketplaceUiPriceStatus.TIMEOUT -> cached + "The marketplace page took too long to load."
            MarketplaceUiPriceStatus.UNREADABLE -> cached + "Could not read listing prices from this page."
            MarketplaceUiPriceStatus.FAILED -> cached + "Discogs returned a page error."
        } + if (snapshot == null && status !in setOf(MarketplaceUiPriceStatus.LOADING, MarketplaceUiPriceStatus.NO_MATCH)) " Using estimated pricing." else ""
    }
    suspend fun initialize(context: Context) {
        MarketplacePricingCache.initialize(context)
        if (!active) return
        snapshot = releaseId?.let { getCachedMarketplacePriceSnapshot(it) }
        val cooling = releaseId?.let { PricingCooldowns.remaining(it) > 0 } == true
        status = when {
            cooling -> if (PricingCooldowns.blockedUntil > System.currentTimeMillis()) MarketplaceUiPriceStatus.RATE_LIMITED
                else PricingCooldowns.releases[releaseId]?.first ?: MarketplaceUiPriceStatus.FAILED
            snapshot != null -> if (snapshot!!.prices.mediaLowest.isEmpty()) MarketplaceUiPriceStatus.NO_MATCH else MarketplaceUiPriceStatus.CACHED
            else -> MarketplaceUiPriceStatus.LOADING
        }
        val forced = PricingCooldowns.forceNextVisit.remove(releaseId)
        needsLoad = releaseId != null && (forced || snapshot?.isFresh() != true) && !cooling
        updateCooldown()
        ready = true
    }
    fun refresh() {
        val id = releaseId ?: return
        if (PricingCooldowns.remaining(id) > 0) return
        generation++
        destroyView()
        status = MarketplaceUiPriceStatus.LOADING
        needsLoad = true
        attempt++
    }
    fun refreshOnNextVisit() {
        releaseId?.takeIf { PricingCooldowns.remaining(it) == 0L }?.let {
            PricingCooldowns.forceNextVisit.add(it)
        }
    }
    private fun fail(failure: MarketplaceUiPriceStatus, retryMs: Long = 60_000L) {
        generation++ // Invalidate delayed scans; onPageFinished cannot overwrite a failed navigation.
        status = failure
        releaseId?.let {
            PricingCooldowns.releases[it] = failure to (System.currentTimeMillis() + retryMs)
            if (PricingCooldowns.releases.size > 128) PricingCooldowns.releases.keys.firstOrNull()?.let(PricingCooldowns.releases::remove)
        }
        if (failure == MarketplaceUiPriceStatus.RATE_LIMITED) PricingCooldowns.blockedUntil = System.currentTimeMillis() + retryMs
        updateCooldown()
    }
    private fun allowed(url: String?): Boolean {
        if (releaseId == null || !marketplaceUrlBelongsToRelease(url, releaseId)) return false
        val uri = Uri.parse(url)
        // Do not replace a full first-page snapshot with a user-filtered subset or a different currency.
        return (uri.getQueryParameter("page") ?: "1") == "1" &&
            uri.getQueryParameter("currency") == "USD" &&
            uri.queryParameterNames.all { it in setOf("release_id", "sort", "limit", "page", "currency") }
    }
    fun createWebView(context: Context, hidden: Boolean): WebView = WebView(context).apply {
        destroyView()
        webView = this
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        if (hidden) {
            isClickable = false; isFocusable = false; isFocusableInTouchMode = false
            settings.loadsImagesAutomatically = false; settings.blockNetworkImage = true
        }
        webViewClient = object : WebViewClient() {
            private var failed = false
            private var scanStarted = false
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                generation++
                failed = false; scanStarted = false
                if (!allowed(url)) return
                status = MarketplaceUiPriceStatus.LOADING
                val current = generation
                view.postDelayed({
                    if (active && generation == current && status == MarketplaceUiPriceStatus.LOADING) {
                        failed = true
                        fail(MarketplaceUiPriceStatus.TIMEOUT)
                    }
                }, 15_000L)
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) {
                    failed = true
                    fail(pricingHttpStatus(response.statusCode), if (response.statusCode == 429)
                        discogsRetryDelay(response.responseHeaders?.entries?.firstOrNull { it.key.equals("Retry-After", true) }?.value, System.currentTimeMillis()) else 60_000L)
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { failed = true; fail(MarketplaceUiPriceStatus.NETWORK) }
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (!active || failed || scanStarted || !allowed(url)) return
                scanStarted = true
                val current = generation
                beginMarketplacePriceScan(view, releaseId!!,
                    isCurrent = { active && current == generation && allowed(view.url) },
                    onPrices = { /* Publish only after the parser settles; never persist a partial sample. */ },
                    onStatus = { result ->
                        if (active && current == generation) {
                            status = result
                            when (result) {
                                MarketplaceUiPriceStatus.FRESH, MarketplaceUiPriceStatus.NO_MATCH -> {
                                    snapshot = getCachedMarketplacePriceSnapshot(releaseId)
                                    PricingCooldowns.releases.remove(releaseId)
                                }
                                MarketplaceUiPriceStatus.LOADING -> Unit
                                else -> fail(result)
                            }
                        }
                    })
            }
        }
        loadUrl(marketplaceReleaseListingsUrl(releaseId!!))
    }
    private fun destroyView() {
        webView?.apply { stopLoading(); webViewClient = WebViewClient(); destroy() }
        webView = null
    }
    fun dispose() { active = false; generation++; destroyView() }
}

@Composable
internal fun rememberMarketplacePricing(releaseId: Long?): MarketplacePricingController {
    val context = LocalContext.current.applicationContext
    val controller = remember(releaseId) { MarketplacePricingController(releaseId) }
    LaunchedEffect(controller) { controller.initialize(context) }
    LaunchedEffect(controller, controller.status) {
        do {
            controller.updateCooldown()
            if (controller.retryInSeconds > 0) kotlinx.coroutines.delay(1000)
        } while (controller.retryInSeconds > 0)
    }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    return controller
}

@Composable
internal fun MarketplacePricingStatus(pricing: MarketplacePricingController) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(pricing.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = pricing::refresh, enabled = pricing.ready && pricing.status != MarketplaceUiPriceStatus.LOADING && pricing.retryInSeconds == 0L) { Text(if (pricing.retryInSeconds > 0) "${pricing.retryInSeconds}s" else "Refresh") }
    }
}
