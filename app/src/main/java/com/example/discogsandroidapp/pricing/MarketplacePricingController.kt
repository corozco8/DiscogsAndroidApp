package com.example.discogsandroidapp.pricing

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import org.json.JSONArray
import org.json.JSONObject

private object PricingSessions {
    val verificationGate = MarketplaceVerificationGate()
}

/** Shared by release details and the visible marketplace; owned by the current screen. */
internal class MarketplacePricingController(private val releaseId: Long?) {
    var ready by mutableStateOf(false); private set
    var needsLoad by mutableStateOf(false); private set
    var attempt by mutableIntStateOf(0); private set
    var snapshot by mutableStateOf<MarketplacePriceSnapshot?>(null); private set
    var status by mutableStateOf(MarketplaceUiPriceStatus.IDLE); private set
    var errorDetails by mutableStateOf<String?>(null); private set
    private var loadAllowed by mutableStateOf(false)
    private var explicitLoad = false
    private var forcePageReload = false
    var verificationVisible by mutableStateOf(false); private set
    private var generation = 0
    private var active = true
    private var webView: WebView? = null
    private var lastHttpStatus: Int? = null
    private var inspectedGeneration: Int? = null
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
            MarketplaceUiPriceStatus.VERIFICATION_REQUIRED -> cached + "Discogs requires a Cloudflare security check before live prices can be read."
            MarketplaceUiPriceStatus.RATE_LIMITED -> cached + "Discogs returned Too many requests. Open Marketplace Listings to view the response, or tap Refresh prices to try again."
            MarketplaceUiPriceStatus.NETWORK -> cached + "Connection failed. Check your connection and retry."
            MarketplaceUiPriceStatus.TIMEOUT -> cached + "The marketplace page took too long to load."
            MarketplaceUiPriceStatus.UNREADABLE -> cached + "Could not read listing prices from this page."
            MarketplaceUiPriceStatus.FAILED -> cached + "Discogs returned a page error."
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
            if (MarketplaceTraffic.policy.reserveLoad(explicit = explicitLoad)) {
                loadAllowed = true
                explicitLoad = false
                return
            }
            kotlinx.coroutines.delay(minOf(MarketplaceTraffic.policy.waitForLoad(), 1_000L).coerceAtLeast(1))
        }
    }
    fun refresh() {
        if (!active || releaseId == null) return
        generation++
        destroyView()
        loadAllowed = false
        errorDetails = null
        status = MarketplaceUiPriceStatus.LOADING
        needsLoad = true
        explicitLoad = true
        forcePageReload = true
        attempt++
    }
    fun requestSellPrices() {
        if (!active || !ready || releaseId == null || needsLoad) return
        if (webView != null) {
            if (snapshot != null && snapshot?.isFresh() == false && status in setOf(
                    MarketplaceUiPriceStatus.FRESH, MarketplaceUiPriceStatus.CACHED, MarketplaceUiPriceStatus.NO_MATCH)) refresh()
            return
        }
        if (snapshot?.isFresh() == true) {
            MarketplaceTrafficReport.log.priceReuse(releaseId)
            return
        }
        requestVisiblePage()
    }
    fun requestVisiblePage() {
        if (!active || !ready || releaseId == null) return
        // Reuse even a failed response. Only Refresh should send another request.
        if (webView != null) {
            if (snapshot != null && snapshot?.isFresh() == false && status in setOf(
                    MarketplaceUiPriceStatus.FRESH, MarketplaceUiPriceStatus.CACHED, MarketplaceUiPriceStatus.NO_MATCH)) {
                refresh()
                return
            }
            MarketplaceTrafficReport.log.pageReuse(releaseId)
            return
        }
        if (needsLoad && explicitLoad) return
        needsLoad = true
        explicitLoad = true
        status = MarketplaceUiPriceStatus.LOADING
        attempt++
    }
    /** Record a page error without scheduling retries or blocking the seller's next request. */
    fun onPageError(failure: MarketplaceUiPriceStatus, preserveResponse: Boolean = false) {
        if (!active) return
        generation++ // Invalidate delayed scans; onPageFinished cannot overwrite a failed navigation.
        scannedGeneration = generation
        status = failure
        if (errorDetails == null) {
            MarketplaceTrafficReport.log.error(releaseId ?: 0, "Marketplace status: $failure")
        }
        needsLoad = false
        if (!preserveResponse) webView?.stopLoading()
        if (failure == MarketplaceUiPriceStatus.RATE_LIMITED) {
            verificationVisible = false
        }
    }
    private fun requireVerification() {
        if (status != MarketplaceUiPriceStatus.VERIFICATION_REQUIRED) {
            generation++
            scannedGeneration = -1
        }
        status = MarketplaceUiPriceStatus.VERIFICATION_REQUIRED
    }
    fun openVerification(automatic: Boolean = false) {
        if (!active || verificationVisible || !PricingSessions.verificationGate.shouldOpen(status, automatic)) return
        needsLoad = true
        verificationVisible = true
    }
    fun closeVerification() { verificationVisible = false }

    /** Polling reads the existing DOM only. It never reloads a page or clicks a challenge. */
    fun checkVerificationPage() {
        val view = webView ?: return
        if (verificationVisible && status != MarketplaceUiPriceStatus.RATE_LIMITED) inspectPage(view)
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
        return (cached?.view ?: WebView(context.applicationContext)).apply {
        MarketplaceExchangeRates.prepare(context)
        webView = this
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Keep the standard User-Agent and the same WebView/session for human verification.
        CookieManager.getInstance().setAcceptCookie(true)
        configureVisibility(this, hidden)
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                request.url.host?.let(MarketplaceTrafficReport.log::resource)
                return null
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                generation++
                lastHttpStatus = null
                scannedGeneration = -1
                if (!allowed(url)) return
                errorDetails = null
                status = MarketplaceUiPriceStatus.LOADING
                val current = generation
                view.postDelayed({
                    if (active && generation == current && status == MarketplaceUiPriceStatus.LOADING) {
                        onPageError(MarketplaceUiPriceStatus.TIMEOUT)
                    }
                }, 15_000L)
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (active && view === webView && request.isForMainFrame && allowed(request.url.toString())) {
                    lastHttpStatus = response.statusCode
                    errorDetails = marketplaceErrorDetails(request.url.toString(), response.statusCode,
                        response.responseHeaders.orEmpty())
                    MarketplaceTrafficReport.log.error(releaseId, errorDetails!!)
                    android.util.Log.w("MarketplaceResponse", errorDetails!!)
                    val challenge = response.responseHeaders?.entries?.any {
                        it.key.equals("cf-mitigated", true) && it.value.equals("challenge", true)
                    } == true
                    if (challenge && response.statusCode != 429) requireVerification()
                    else onPageError(pricingHttpStatus(response.statusCode), preserveResponse = true)
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (active && view === webView && request.isForMainFrame && allowed(request.url.toString()) &&
                    status == MarketplaceUiPriceStatus.LOADING)
                    onPageError(MarketplaceUiPriceStatus.NETWORK)
            }
            override fun onPageFinished(view: WebView, url: String) {
                // Error pages can contain an actionable challenge. Inspect them too.
                inspectPage(view)
            }
        }
        if (cached == null) {
            MarketplaceTrafficReport.log.pageLoad(releaseId)
            loadUrl(marketplaceReleaseListingsUrl(releaseId))
        } else {
            snapshot = cached.snapshot
            status = if (cached.snapshot.prices.mediaLowest.isEmpty() && cached.snapshot.prices.firstPagePrices.isEmpty())
                MarketplaceUiPriceStatus.NO_MATCH else MarketplaceUiPriceStatus.CACHED
            needsLoad = false
            generation++
            scannedGeneration = generation
            onResume()
            MarketplaceTrafficReport.log.pageReuse(releaseId)
        }
        }
    }
    private fun configureVisibility(view: WebView, hidden: Boolean) {
        view.isClickable = !hidden
        view.isFocusable = !hidden
        view.isFocusableInTouchMode = !hidden
        view.settings.loadsImagesAutomatically = !hidden
        view.settings.blockNetworkImage = hidden
    }
    private fun inspectPage(view: WebView) {
        if (!active || view !== webView || !allowed(view.url) ||
            status == MarketplaceUiPriceStatus.RATE_LIMITED || inspectedGeneration == generation) return
        val current = generation
        inspectedGeneration = current
        val script = """
            (function() {
                return JSON.stringify({
                    title: document.title || '',
                    body: (document.body && document.body.innerText || '').slice(0, 6000),
                    listings: !!document.querySelector('a[href*="/sell/item/"], .item_condition, [data-testid="media-condition"]'),
                    challenge: Array.from(document.querySelectorAll('iframe')).some(function(frame) {
                        return (frame.getAttribute('src') || '').indexOf('https://challenges.cloudflare.com/') === 0 &&
                            frame.getClientRects().length > 0 && frame.getBoundingClientRect().height > 0;
                    })
                });
            })();
        """.trimIndent()
        view.evaluateJavascript(script) { raw ->
            if (inspectedGeneration == current) inspectedGeneration = null
            if (!active || view !== webView || current != generation || !allowed(view.url)) return@evaluateJavascript
            val document = runCatching { JSONObject(JSONArray("[$raw]").getString(0)) }.getOrNull()
                ?: return@evaluateJavascript
            // A solved challenge can replace its DOM without a new navigation.
            val previousChallenge = status == MarketplaceUiPriceStatus.VERIFICATION_REQUIRED || verificationVisible
            when (val access = marketplaceAccessStatus(
                document.optString("title"), document.optString("body"), document.optBoolean("challenge"),
                httpStatus = if (previousChallenge) null else lastHttpStatus,
                hasMarketplaceListings = document.optBoolean("listings")
            )) {
                MarketplaceUiPriceStatus.VERIFICATION_REQUIRED -> requireVerification()
                null -> if (scannedGeneration != generation) scanPrices(view)
                else -> if (status != access) onPageError(access)
            }
        }
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
                            status = result
                            snapshot = getCachedMarketplacePriceSnapshot(releaseId)
                            CookieManager.getInstance().flush()
                            PricingSessions.verificationGate.verified()
                            verificationVisible = false
                        }
                        MarketplaceUiPriceStatus.VERIFICATION_REQUIRED -> requireVerification()
                        MarketplaceUiPriceStatus.LOADING -> status = result
                        else -> onPageError(result)
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

