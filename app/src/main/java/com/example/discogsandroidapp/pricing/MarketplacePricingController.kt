package com.example.discogsandroidapp.pricing

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import org.json.JSONArray
import org.json.JSONObject

private object PricingCooldowns {
    val releases = mutableMapOf<Long, Pair<MarketplaceUiPriceStatus, Long>>()
    val forceNextVisit = mutableSetOf<Long>()
    val blockedUntil: Long get() = MarketplaceTraffic.policy.blockedUntil
    val verificationGate = MarketplaceVerificationGate()
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
    private var loadAllowed by mutableStateOf(false)
    var verificationVisible by mutableStateOf(false); private set
    fun updateCooldown() { retryInSeconds = releaseId?.let { (PricingCooldowns.remaining(it) + 999) / 1000 } ?: 0 }
    private var generation = 0
    private var active = true
    private var webView: WebView? = null
    private var lastHttpStatus: Int? = null
    private var inspectedGeneration: Int? = null
    private var scannedGeneration = -1
    val canShowPage: Boolean get() = status != MarketplaceUiPriceStatus.RATE_LIMITED &&
        (webView != null || loadAllowed)
    val listingInfo: ListingPricingInfo get() = ListingPricingInfo(
        status, message, snapshot != null && status != MarketplaceUiPriceStatus.FRESH, retryInSeconds
    )
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
            MarketplaceUiPriceStatus.VERIFICATION_REQUIRED -> cached + "Discogs requires a Cloudflare security check before live prices can be read."
            MarketplaceUiPriceStatus.RATE_LIMITED -> cached + if (retryInSeconds > 0)
                "Marketplace website temporarily limited requests. Orders and release search can still work."
                else "Marketplace pause ended. Tap Refresh to check access again."
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
        val cooling = releaseId?.let { PricingCooldowns.remaining(it) > 0 } == true
        status = when {
            cooling -> if (PricingCooldowns.blockedUntil > System.currentTimeMillis()) MarketplaceUiPriceStatus.RATE_LIMITED
                else PricingCooldowns.releases[releaseId]?.first ?: MarketplaceUiPriceStatus.FAILED
            snapshot != null -> if (snapshot!!.prices.mediaLowest.isEmpty() && snapshot!!.prices.firstPagePrices.isEmpty()) MarketplaceUiPriceStatus.NO_MATCH else MarketplaceUiPriceStatus.CACHED
            else -> MarketplaceUiPriceStatus.LOADING
        }
        val forced = PricingCooldowns.forceNextVisit.remove(releaseId)
        needsLoad = releaseId != null && (forced || snapshot?.isFresh() != true) && !cooling
        updateCooldown()
        ready = true
    }

    suspend fun prepareLoad() {
        if (!active || !needsLoad || webView != null || loadAllowed) return
        // A cached guide stays visible while rapid release visits settle and page loads are spaced.
        kotlinx.coroutines.delay(500)
        while (active && needsLoad) {
            if (MarketplaceTraffic.policy.blockedFor() > 0) {
                status = MarketplaceUiPriceStatus.RATE_LIMITED
                needsLoad = false
                updateCooldown()
                return
            }
            if (MarketplaceTraffic.policy.reserveLoad()) {
                loadAllowed = true
                return
            }
            kotlinx.coroutines.delay(minOf(MarketplaceTraffic.policy.waitForLoad(), 1_000L).coerceAtLeast(1))
        }
    }
    fun refresh() {
        val id = releaseId ?: return
        if (PricingCooldowns.remaining(id) > 0) return
        generation++
        destroyView()
        loadAllowed = false
        status = MarketplaceUiPriceStatus.LOADING
        needsLoad = true
        attempt++
    }
    fun refreshOnNextVisit() {
        releaseId?.takeIf { PricingCooldowns.remaining(it) == 0L }?.let {
            PricingCooldowns.forceNextVisit.add(it)
        }
    }
    fun requestVisiblePage() {
        if (!ready || webView != null || needsLoad || status == MarketplaceUiPriceStatus.RATE_LIMITED) return
        if (releaseId == null || PricingCooldowns.remaining(releaseId) > 0) return
        needsLoad = true
        status = MarketplaceUiPriceStatus.LOADING
        attempt++
    }
    private fun fail(failure: MarketplaceUiPriceStatus, retryMs: Long = 60_000L, retryAfter: String? = null) {
        generation++ // Invalidate delayed scans; onPageFinished cannot overwrite a failed navigation.
        scannedGeneration = generation
        status = failure
        needsLoad = false
        webView?.stopLoading()
        val until = if (failure == MarketplaceUiPriceStatus.RATE_LIMITED)
            MarketplaceTraffic.limited(retryAfter) else System.currentTimeMillis() + retryMs
        releaseId?.let {
            PricingCooldowns.releases[it] = failure to until
            if (PricingCooldowns.releases.size > 128) PricingCooldowns.releases.keys.firstOrNull()?.let(PricingCooldowns.releases::remove)
        }
        if (failure == MarketplaceUiPriceStatus.RATE_LIMITED) {
            verificationVisible = false
            val failedView = webView
            failedView?.post {
                if (webView === failedView && status == MarketplaceUiPriceStatus.RATE_LIMITED) destroyView()
            }
        }
        updateCooldown()
    }
    private fun requireVerification() {
        if (status != MarketplaceUiPriceStatus.VERIFICATION_REQUIRED) {
            generation++
            scannedGeneration = -1
        }
        status = MarketplaceUiPriceStatus.VERIFICATION_REQUIRED
        releaseId?.let {
            PricingCooldowns.releases[it] = status to (System.currentTimeMillis() + 60_000L)
        }
        updateCooldown()
    }
    fun openVerification(automatic: Boolean = false) {
        if (!active || verificationVisible || !PricingCooldowns.verificationGate.shouldOpen(
                status, automatic, PricingCooldowns.blockedUntil > System.currentTimeMillis()
            )) return
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
        check(canShowPage) { "Marketplace loading is paused during cooldown" }
        return WebView(context).apply {
        MarketplaceExchangeRates.prepare(context)
        webView = this
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Keep the standard User-Agent and the same WebView/session for human verification.
        CookieManager.getInstance().setAcceptCookie(true)
        configureVisibility(this, hidden)
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                generation++
                lastHttpStatus = null
                scannedGeneration = -1
                if (!allowed(url)) return
                status = MarketplaceUiPriceStatus.LOADING
                val current = generation
                view.postDelayed({
                    if (active && generation == current && status == MarketplaceUiPriceStatus.LOADING) {
                        fail(MarketplaceUiPriceStatus.TIMEOUT)
                    }
                }, 15_000L)
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (active && view === webView && request.isForMainFrame && allowed(request.url.toString())) {
                    lastHttpStatus = response.statusCode
                    val challenge = response.responseHeaders?.entries?.any {
                        it.key.equals("cf-mitigated", true) && it.value.equals("challenge", true)
                    } == true
                    if (challenge && response.statusCode != 429) requireVerification()
                    else fail(pricingHttpStatus(response.statusCode), retryAfter =
                        response.responseHeaders?.entries?.firstOrNull { it.key.equals("Retry-After", true) }?.value)
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (active && view === webView && request.isForMainFrame && allowed(request.url.toString()) &&
                    status == MarketplaceUiPriceStatus.LOADING)
                    fail(MarketplaceUiPriceStatus.NETWORK)
            }
            override fun onPageFinished(view: WebView, url: String) {
                // Error pages can contain an actionable challenge. Inspect them too.
                inspectPage(view)
            }
        }
        loadUrl(marketplaceReleaseListingsUrl(releaseId!!))
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
                else -> if (status != access) fail(access)
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
                            PricingCooldowns.releases.remove(releaseId)
                            MarketplaceTraffic.succeeded()
                            updateCooldown()
                            CookieManager.getInstance().flush()
                            PricingCooldowns.verificationGate.verified()
                            verificationVisible = false
                        }
                        MarketplaceUiPriceStatus.VERIFICATION_REQUIRED -> requireVerification()
                        MarketplaceUiPriceStatus.LOADING -> status = result
                        else -> fail(result)
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
    fun dispose() { active = false; generation++; destroyView() }
}

@Composable
internal fun rememberMarketplacePricing(releaseId: Long?): MarketplacePricingController {
    val context = LocalContext.current.applicationContext
    val controller = remember(releaseId) { MarketplacePricingController(releaseId) }
    LaunchedEffect(controller) { controller.initialize(context) }
    LaunchedEffect(controller, controller.ready, controller.attempt) {
        if (controller.ready) controller.prepareLoad()
    }
    LaunchedEffect(controller, controller.status) {
        do {
            controller.updateCooldown()
            if (controller.retryInSeconds > 0) kotlinx.coroutines.delay(1000)
        } while (controller.retryInSeconds > 0)
    }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    return controller
}

