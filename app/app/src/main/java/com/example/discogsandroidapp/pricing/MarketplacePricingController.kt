package com.example.discogsandroidapp.pricing

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** Shared by release details and the visible marketplace; owned by the current screen. */
internal class MarketplacePricingController(
    private val releaseId: Long?,
    private val loadPolicy: MarketplaceLoadPolicy = MarketplaceTraffic.policy
) {
    var ready by mutableStateOf(false); private set
    var needsLoad by mutableStateOf(false); private set
    var attempt by mutableIntStateOf(0); private set
    var snapshot by mutableStateOf<MarketplacePriceSnapshot?>(null); private set
    var status by mutableStateOf(MarketplaceUiPriceStatus.IDLE); private set
    var errorDetails by mutableStateOf<String?>(null); private set
    private var loadAllowed by mutableStateOf(false)
    private var explicitLoad = false
    private var manualAttempt = false
    private var pageHidden = true
    @Volatile private var pagePaused = false
    private var forcePageReload = false
    private var generation = 0
    private var active = true
    private var webView: WebView? = null
    private var scannedGeneration = -1
    val canShowPage: Boolean get() = webView != null || loadAllowed
    val listingInfo: ListingPricingInfo get() = ListingPricingInfo(
        status, message, snapshot != null && status != MarketplaceUiPriceStatus.FRESH
    )
    val prices: ActiveMarketplaceConditionPrices? get() = snapshot?.prices
    val message: String get() {
        val cached = snapshot?.let {
            val age = ((System.currentTimeMillis() - it.updatedAtMillis) / 60_000L).coerceAtLeast(0)
            "Saved prices checked $age min ago. "
        }.orEmpty()
        return when (status) {
            MarketplaceUiPriceStatus.IDLE -> "Open Sell or Marketplace Listings to check live prices."
            MarketplaceUiPriceStatus.LOADING -> cached + "Checking marketplace prices…"
            MarketplaceUiPriceStatus.CACHED -> cached + "First page of listings; shipping excluded."
            MarketplaceUiPriceStatus.FRESH -> "Prices checked now. First page of listings; shipping excluded."
            MarketplaceUiPriceStatus.NO_MATCH -> "No matching listings found. Using estimated pricing."
            MarketplaceUiPriceStatus.PARTIAL -> cached + "Only part of the page could be read."
            MarketplaceUiPriceStatus.BLOCKED -> cached + "Discogs blocked the page. Open Marketplace Listings to check access."
            MarketplaceUiPriceStatus.VERIFICATION_REQUIRED -> cached + "Discogs is showing a verification page. Open Marketplace Listings to complete it in the normal browser view."
            MarketplaceUiPriceStatus.RATE_LIMITED -> cached + "Discogs returned Too many requests. Live requests are temporarily paused; saved or estimated pricing remains available."
            MarketplaceUiPriceStatus.NETWORK -> cached + "Connection failed. Check your connection and retry."
            MarketplaceUiPriceStatus.TIMEOUT -> cached + "The marketplace page took too long to load."
            MarketplaceUiPriceStatus.UNREADABLE -> cached + "Could not read listing prices from this page."
            MarketplaceUiPriceStatus.FAILED -> cached + "Discogs returned a page error."
            MarketplaceUiPriceStatus.AUTOMATIC_PAUSED -> cached + "Automatic live checks paused after a marketplace block. Tap Check live price to retry."
        } + if (snapshot == null && status !in setOf(MarketplaceUiPriceStatus.LOADING, MarketplaceUiPriceStatus.NO_MATCH)) " You can still enter a price or use the pricing algorithm when available." else ""
    }
    suspend fun initialize(context: Context) {
        MarketplaceTraffic.initialize(context)
        MarketplacePricingCache.initialize(context)
        if (!active) return
        snapshot = releaseId?.let { getCachedMarketplacePriceSnapshot(it) }
        status = when {
            snapshot != null -> if (snapshot!!.prices.mediaLowest.isEmpty() && snapshot!!.prices.firstPagePrices.isEmpty()) MarketplaceUiPriceStatus.NO_MATCH else MarketplaceUiPriceStatus.CACHED
            else -> MarketplaceUiPriceStatus.IDLE
        }
        // Browsing a release must never start a marketplace website request.
        needsLoad = false
        ready = true
    }

    suspend fun prepareLoad() {
        if (!active || !needsLoad || webView != null || loadAllowed) return
        // A cached guide stays visible while rapid release visits settle and page loads are spaced.
        if (!explicitLoad) kotlinx.coroutines.delay(500)
        while (active && needsLoad) {
            if (!explicitLoad && loadPolicy.automaticChecksSuspended) {
                pauseAutomaticCheck()
                return
            }
            if (loadPolicy.reserveLoad(explicit = explicitLoad)) {
                loadAllowed = true
                explicitLoad = false
                return
            }
            kotlinx.coroutines.delay(minOf(loadPolicy.waitForLoad(), 1_000L).coerceAtLeast(1))
        }
    }
    fun refresh() {
        if (!active || releaseId == null) return
        queueLoad(manual = true, replacePage = true)
    }
    private fun queueLoad(manual: Boolean, replacePage: Boolean = false) {
        if (replacePage) {
            generation++
            destroyView()
            loadAllowed = false
        }
        errorDetails = null
        status = MarketplaceUiPriceStatus.LOADING
        needsLoad = true
        explicitLoad = manual
        manualAttempt = manual
        forcePageReload = replacePage
        attempt++
    }
    fun requestSellPrices() {
        if (!active || !ready || releaseId == null || needsLoad) return
        // Opening Sell is allowed to perform one marketplace load when this release has
        // no saved pricing yet. Once a snapshot/page exists, reuse it until the user
        // explicitly taps Refresh prices. This prevents repeatedly opening the dialog
        // from generating website traffic.
        if (snapshot != null || webView != null) {
            MarketplaceTrafficReport.log.priceReuse(releaseId)
            return
        }
        if (loadPolicy.automaticChecksSuspended) {
            pauseAutomaticCheck()
            return
        }
        queueLoad(manual = false)
    }
    private fun pauseAutomaticCheck() {
        needsLoad = false
        loadAllowed = false
        status = MarketplaceUiPriceStatus.AUTOMATIC_PAUSED
    }
    /** Closing Sell before a spaced request starts must cancel that request. */
    fun cancelAutomaticCheck() {
        if (!active || !needsLoad || manualAttempt || webView != null) return
        needsLoad = false
        loadAllowed = false
        attempt++
        status = if (snapshot != null) MarketplaceUiPriceStatus.CACHED else MarketplaceUiPriceStatus.IDLE
    }
    fun requestVisiblePage() {
        if (!active || !ready || releaseId == null) return
        // If this controller already owns a page, always reuse it. Staleness alone is
        // not a reason to send another website request. A second request requires the
        // user's explicit Refresh action.
        if (webView != null) {
            MarketplaceTrafficReport.log.pageReuse(releaseId)
            return
        }
        if (needsLoad && explicitLoad) return
        // Opening Marketplace Listings is an explicit request to view the page, so one
        // initial navigation is allowed when no reusable page exists.
        queueLoad(manual = true)
    }
    /** Record a page error without scheduling retries. Rate-limit responses start a shared cooldown. */
    fun onPageError(
        failure: MarketplaceUiPriceStatus,
        preserveResponse: Boolean = false,
        retryAfterMillis: Long? = null
    ) {
        if (!active) return
        generation++ // Invalidate delayed scans; onPageFinished cannot overwrite a failed navigation.
        scannedGeneration = generation
        status = failure
        if (failure in setOf(MarketplaceUiPriceStatus.RATE_LIMITED, MarketplaceUiPriceStatus.BLOCKED)) {
            loadPolicy.recordBlock(retryAfterMillis ?: MarketplaceLoadPolicy.DEFAULT_BLOCK_COOLDOWN_MS)
        }
        if (errorDetails == null) {
            MarketplaceTrafficReport.log.error(releaseId ?: 0, "Marketplace status: $failure")
        }
        needsLoad = false
        if (!preserveResponse) webView?.stopLoading()
        // The loaded WebView stays visible for the user to read the actual error page.
    }
    private fun allowed(url: String?): Boolean {
        if (releaseId == null || !marketplaceUrlBelongsToRelease(url, releaseId)) return false
        val uri = Uri.parse(url)
        // Do not replace a full first-page snapshot with a user-filtered subset or a different currency.
        return (uri.getQueryParameter("page") ?: "1") == "1" &&
            uri.getQueryParameter("currency").isNullOrBlank() &&
            uri.queryParameterNames.all { it in setOf("release_id", "sort", "limit", "page", "currency") }
    }
    fun createWebView(context: Context, hidden: Boolean): WebView {
        webView?.let { existing ->
            configureVisibility(existing, hidden)
            return existing
        }
        check(canShowPage) { "Marketplace page load has not been prepared" }
        val parked = MarketplacePageCache.take(releaseId!!)
        val cached = parked?.takeIf { !forcePageReload && it.snapshot.isFresh() && allowed(it.view.url) }
        forcePageReload = false
        if (parked != null && cached == null) parked.view.destroy()
        return (cached?.view ?: WebView(context)).apply {
        MarketplaceExchangeRates.prepare(context)
        webView = this
        settings.javaScriptEnabled = cached == null || !hidden
        settings.domStorageEnabled = true
        // Native WebView and cookies: Discogs owns any verification rendered in the page.
        CookieManager.getInstance().setAcceptCookie(true)
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (pagePaused) return WebResourceResponse("text/plain", "UTF-8",
                    java.io.ByteArrayInputStream(ByteArray(0)))
                request.url.host?.let(MarketplaceTrafficReport.log::resource)
                return null
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                generation++
                scannedGeneration = -1
                if (!allowed(url)) return
                errorDetails = null
                status = MarketplaceUiPriceStatus.LOADING
                // Let the browser finish naturally rather than stopping it before a challenge renders.
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (active && view === webView && request.isForMainFrame && allowed(request.url.toString())) {
                    errorDetails = marketplaceErrorDetails(request.url.toString(), response.statusCode,
                        response.responseHeaders.orEmpty())
                    MarketplaceTrafficReport.log.error(releaseId, errorDetails!!)
                    android.util.Log.w("MarketplaceResponse", errorDetails!!)
                    // Report the HTTP status, but don't replace, reload, or intercept the
                    // rendered page. A real verification challenge remains interactive.
                    val retryAfterMillis = response.responseHeaders
                        ?.entries
                        ?.firstOrNull { it.key.equals("retry-after", ignoreCase = true) }
                        ?.value
                        ?.trim()
                        ?.toLongOrNull()
                        ?.coerceAtLeast(0L)
                        ?.times(1_000L)
                    onPageError(
                        pricingHttpStatus(response.statusCode),
                        preserveResponse = true,
                        retryAfterMillis = retryAfterMillis
                    )
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (active && view === webView && request.isForMainFrame && allowed(request.url.toString()) &&
                    status == MarketplaceUiPriceStatus.LOADING)
                    onPageError(MarketplaceUiPriceStatus.NETWORK, preserveResponse = true)
            }
            override fun onPageFinished(view: WebView, url: String) {
                // Only the pricing extractor reads the DOM. A verification page is
                // displayed as-is; no custom dialog, polling, or challenge clicks.
                if (active && view === webView && allowed(url) &&
                    status == MarketplaceUiPriceStatus.LOADING && scannedGeneration != generation) {
                    scanPrices(view)
                }
            }
        }
        if (cached == null) {
            configureVisibility(this, hidden)
            MarketplaceTrafficReport.log.pageLoad(releaseId)
            loadUrl(marketplaceReleaseListingsUrl(releaseId))
        } else {
            snapshot = cached.snapshot
            status = if (cached.snapshot.prices.mediaLowest.isEmpty() && cached.snapshot.prices.firstPagePrices.isEmpty())
                MarketplaceUiPriceStatus.NO_MATCH else MarketplaceUiPriceStatus.CACHED
            needsLoad = false
            generation++
            scannedGeneration = generation
            configureVisibility(this, hidden)
            MarketplaceTrafficReport.log.pageReuse(releaseId)
        }
        }
    }
    /** Presentation-only update used by Compose recomposition. Never loads or recreates a page. */
    fun updateWebViewVisibility(view: WebView?, hidden: Boolean) {
        if (!active || view == null || view !== webView) return
        configureVisibility(view, hidden)
    }

    private fun configureVisibility(view: WebView, hidden: Boolean) {
        pageHidden = hidden
        view.isClickable = !hidden
        view.isFocusable = !hidden
        view.isFocusableInTouchMode = !hidden
        view.settings.loadsImagesAutomatically = !hidden
        view.settings.blockNetworkImage = hidden
        if (hidden && status in setOf(MarketplaceUiPriceStatus.FRESH,
                MarketplaceUiPriceStatus.CACHED, MarketplaceUiPriceStatus.NO_MATCH)) {
            pauseHiddenPage(view)
        } else {
            pagePaused = false
            view.settings.javaScriptEnabled = true
            view.onResume()
        }
    }
    private fun pauseHiddenPage(view: WebView) {
        if (!pageHidden) return
        pagePaused = true
        view.stopLoading()
        view.settings.javaScriptEnabled = false
        view.onPause()
    }
    /** A successful page moved out of a visible host must stop background work too. */
    fun onPageDetached(view: WebView) {
        if (view !== webView || view.parent != null || status !in setOf(
                MarketplaceUiPriceStatus.FRESH, MarketplaceUiPriceStatus.CACHED, MarketplaceUiPriceStatus.NO_MATCH)) return
        pageHidden = true
        pauseHiddenPage(view)
    }
    private fun scanPrices(view: WebView) {
        val current = generation
        scannedGeneration = current
        beginMarketplacePriceScan(view, releaseId!!,
            isCurrent = { active && view === webView && current == generation && allowed(view.url) },
            onPrices = { /* Persist only a settled scan. */ },
            onStatus = { result ->
                if (active && current == generation) {
                    when (result) {
                        MarketplaceUiPriceStatus.FRESH, MarketplaceUiPriceStatus.NO_MATCH -> {
                            if (manualAttempt) loadPolicy.recordManualSuccess()
                            status = result
                            needsLoad = false
                            snapshot = getCachedMarketplacePriceSnapshot(releaseId)
                            CookieManager.getInstance().flush()
                            pauseHiddenPage(view)
                        }
                        MarketplaceUiPriceStatus.VERIFICATION_REQUIRED -> {
                            // Inform the UI but let the existing WebView render the real page.
                            status = result
                            needsLoad = false
                        }
                        MarketplaceUiPriceStatus.LOADING -> status = result
                        else -> onPageError(result, preserveResponse = true)
                    }
                }
            })
    }
    private fun destroyView() {
        webView?.apply {
            (parent as? android.view.ViewGroup)?.removeView(this)
            stopLoading(); webViewClient = WebViewClient(); destroy()
        }
        webView = null
        pagePaused = false
    }
    fun dispose() {
        active = false
        generation++
        val page = webView
        val prices = snapshot
        if (page != null && releaseId != null && prices?.isFresh() == true && allowed(page.url) &&
            status in setOf(MarketplaceUiPriceStatus.FRESH, MarketplaceUiPriceStatus.CACHED, MarketplaceUiPriceStatus.NO_MATCH)) {
            webView = null
            MarketplacePageCache.put(releaseId, page, prices)
        } else destroyView()
    }
}

@Composable
internal fun rememberMarketplacePricing(releaseId: Long?): MarketplacePricingController {
    val context = LocalContext.current.applicationContext
    val controller = remember(releaseId) { MarketplacePricingController(releaseId) }
    LaunchedEffect(controller) { controller.initialize(context) }
    LaunchedEffect(controller, controller.ready, controller.attempt) {
        if (controller.ready) controller.prepareLoad()
    }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    return controller
}

