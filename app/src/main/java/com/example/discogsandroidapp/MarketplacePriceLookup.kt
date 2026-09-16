package com.example.discogsandroidapp

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

private const val PRICE_PAGE_SIZE = 250
private const val MAX_PRICE_PAGES = 12
private const val SCAN_DEADLINE_MS = 20_000L
private const val DOM_SAMPLE_DELAY_MS = 300L
private const val MAX_DOM_SAMPLES = 12

private class IncompletePriceScan(message: String) : Exception(message)

private class MarketplaceLookupFailure(
    val code: String,
    message: String
) : Exception(message)

private data class PricePage(
    val prices: ActiveMarketplaceConditionPrices,
    val rowCount: Int,
    val parsedCount: Int,
    val hasNext: Boolean?,
    val empty: Boolean,
    val blocked: Boolean,
    val ready: Boolean,
    val total: Int?,
    val ids: List<String>
)

/**
 * Shared marketplace lookup used by Release Details and the sell dialog.
 * One lookup is kept per release, so opening the dialog does not start a
 * second WebView request. Successful results are cached for two minutes.
 */
object MarketplacePriceRequests {
    internal data class Request(
        val state: MutableStateFlow<MarketplacePriceState>,
        var users: Int = 0,
        var job: Job? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = LinkedHashMap<Long, Request>()
    private val cache = MarketplacePriceCache()

    internal fun acquire(context: Context, id: Long): Request {
        val request = requests.getOrPut(id) {
            val initial = cache.get(id) ?: MarketplacePriceState()
            Request(MutableStateFlow(initial))
        }
        request.users++

        if (request.state.value.status == MarketplacePriceStatus.LOADING && request.job?.isActive != true) {
            start(context.applicationContext, id, request)
        }
        return request
    }

    internal fun release(id: Long) {
        val request = requests[id] ?: return
        request.users = (request.users - 1).coerceAtLeast(0)
        // Do not cancel an in-flight warmup when the UI briefly recomposes.
        // The bounded map/cache keeps this small and lets the result be reused.
        if (requests.size > 64) {
            val removable = requests.entries.firstOrNull { it.value.users == 0 && it.value.job?.isActive != true }
            if (removable != null) requests.remove(removable.key)
        }
    }

    fun refresh(context: Context, id: Long) {
        val request = requests.getOrPut(id) {
            Request(MutableStateFlow(MarketplacePriceState()))
        }
        request.state.value = MarketplacePriceState(
            status = MarketplacePriceStatus.LOADING,
            message = "Checking current Discogs prices…"
        )
        start(context.applicationContext, id, request)
    }

    private fun start(context: Context, id: Long, request: Request) {
        request.job?.cancel()
        request.job = scope.launch {
            try {
                val prices = withTimeout(SCAN_DEADLINE_MS) {
                    scanMarketplace(context, id)
                }
                request.state.value = cache.put(id, prices)
            } catch (_: TimeoutCancellationException) {
                request.state.value = MarketplacePriceState(
                    status = MarketplacePriceStatus.FAILED,
                    message = "Price lookup timed out. Try again."
                )
            } catch (_: CancellationException) {
                // A replacement refresh owns the state now.
            } catch (e: IncompletePriceScan) {
                request.state.value = MarketplacePriceState(
                    status = MarketplacePriceStatus.INCOMPLETE,
                    message = e.message ?: "Search incomplete. Try again."
                )
            } catch (e: MarketplaceLookupFailure) {
                request.state.value = MarketplacePriceState(
                    status = MarketplacePriceStatus.FAILED,
                    message = e.message ?: "Price lookup failed (${e.code})."
                )
            } catch (e: Exception) {
                request.state.value = MarketplacePriceState(
                    status = MarketplacePriceStatus.FAILED,
                    message = "Unexpected lookup failure: ${e.javaClass.simpleName}. Try again."
                )
            }
        }
    }
}

@Composable
fun rememberMarketplacePrices(releaseId: Long?): MarketplacePriceState {
    val context = LocalContext.current
    var state by remember(releaseId) {
        mutableStateOf(
            if (releaseId == null) {
                MarketplacePriceState(
                    status = MarketplacePriceStatus.FAILED,
                    message = "Release ID unavailable"
                )
            } else {
                MarketplacePriceState()
            }
        )
    }

    LaunchedEffect(releaseId) {
        if (releaseId == null) return@LaunchedEffect
        val request = MarketplacePriceRequests.acquire(context, releaseId)
        try {
            request.state.collect { state = it }
        } finally {
            MarketplacePriceRequests.release(releaseId)
        }
    }

    // Refresh automatically once the cached snapshot reaches its TTL while
    // the release remains on screen.
    LaunchedEffect(releaseId, state.status, state.checkedAtMillis) {
        if (releaseId == null) return@LaunchedEffect
        if (state.status !in setOf(MarketplacePriceStatus.FRESH, MarketplacePriceStatus.CACHED)) {
            return@LaunchedEffect
        }
        val checked = state.checkedAtMillis ?: return@LaunchedEffect
        val age = System.currentTimeMillis() - checked
        val remaining = (MARKETPLACE_PRICE_CACHE_TTL_MS - age).coerceAtLeast(1L)
        delay(remaining)
        MarketplacePriceRequests.refresh(context, releaseId)
    }

    return state
}

private suspend fun scanMarketplace(
    context: Context,
    releaseId: Long
): ActiveMarketplaceConditionPrices {
    val script = try {
        context.assets.open("marketplace-prices.js")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    } catch (_: Exception) {
        throw MarketplaceLookupFailure(
            "ASSET_MISSING",
            "The installed app is missing its pricing script. Rebuild and reinstall the app."
        )
    }

    val webView = try {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = false
            settings.blockNetworkImage = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
        }
    } catch (_: Exception) {
        throw MarketplaceLookupFailure(
            "WEBVIEW_INIT",
            "Android WebView could not start. Check that Android System WebView is enabled and retry."
        )
    }

    var accumulated = ActiveMarketplaceConditionPrices()
    var expectedTotal: Int? = null
    val seenIds = LinkedHashSet<String>()

    try {
        for (pageNumber in 1..MAX_PRICE_PAGES) {
            currentCoroutineContext().ensureActive()
            loadMarketplacePage(webView, releaseId, pageNumber)

            var accepted: PricePage? = null
            var previousSignature: String? = null
            var stableSamples = 0

            for (sample in 0 until MAX_DOM_SAMPLES) {
                if (sample > 0) delay(DOM_SAMPLE_DELAY_MS)
                currentCoroutineContext().ensureActive()

                val page = readPricePage(webView, script)
                if (page.blocked) {
                    throw MarketplaceLookupFailure(
                        "HTTP_CHALLENGE",
                        "Discogs blocked automated price lookup. View the marketplace directly."
                    )
                }

                if (page.total != null) {
                    if (expectedTotal == null) expectedTotal = page.total
                    else if (expectedTotal != page.total) {
                        throw IncompletePriceScan("Listings changed during the search. Try again.")
                    }
                }

                val signature = "${page.rowCount}:${page.parsedCount}:${page.ids.joinToString(",")}:${page.hasNext}:${page.empty}"
                stableSamples = if (signature == previousSignature) stableSamples + 1 else 0
                previousSignature = signature

                val expectedRows = page.total?.let { total ->
                    (total - (pageNumber - 1) * PRICE_PAGE_SIZE).coerceIn(0, PRICE_PAGE_SIZE)
                }

                val rowsComplete = when {
                    page.empty -> true
                    expectedRows != null -> page.rowCount >= expectedRows
                    else -> page.rowCount > 0 && stableSamples >= 2
                }

                if (page.ready && rowsComplete) {
                    accepted = page
                    break
                }
            }

            val page = accepted ?: throw IncompletePriceScan(
                "Prices or pagination could not be verified. Try again or view the marketplace."
            )

            if (page.empty) {
                if (pageNumber == 1) return accumulated
                throw IncompletePriceScan("Not all marketplace listings could be checked. Try again.")
            }

            if (page.rowCount <= 0 || page.parsedCount != page.rowCount) {
                throw IncompletePriceScan(
                    "Not all marketplace listings could be checked. Try again."
                )
            }

            if (page.ids.isNotEmpty()) {
                if (page.ids.any { !seenIds.add(it) }) {
                    throw IncompletePriceScan("Listings changed or could not be identified. Try again.")
                }
            }

            accumulated = accumulated.plusPage(page.prices)

            when (page.hasNext) {
                false -> return accumulated
                true -> Unit
                null -> throw IncompletePriceScan(
                    "Prices or pagination could not be verified. Try again or view the marketplace."
                )
            }
        }

        throw IncompletePriceScan(
            "Search reached its page limit. View the marketplace for this release."
        )
    } finally {
        webView.stopLoading()
        webView.destroy()
    }
}

private suspend fun loadMarketplacePage(
    webView: WebView,
    releaseId: Long,
    pageNumber: Int
) {
    val url = marketplaceReleaseListingsUrl(releaseId) + "&page=$pageNumber"

    suspendCancellableCoroutine<Unit> { continuation ->
        var completed = false

        fun allowed(uri: Uri): Boolean = isMarketplacePriceUrlAllowed(
            scheme = uri.scheme?.lowercase(Locale.US),
            host = uri.host?.lowercase(Locale.US),
            path = uri.path,
            releaseQuery = uri.getQueryParameter("release_id"),
            pageQuery = uri.getQueryParameter("page"),
            releaseId = releaseId,
            expectedPage = pageNumber
        )

        fun fail(error: Throwable) {
            if (!completed && continuation.isActive) {
                completed = true
                continuation.resumeWithException(error)
            }
        }

        fun finish() {
            if (!completed && continuation.isActive) {
                completed = true
                continuation.resume(Unit)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (!request.isForMainFrame) return false
                if (allowed(request.url)) return false
                fail(
                    MarketplaceLookupFailure(
                        "REDIRECT",
                        "Discogs redirected away from this release's listings. Open the marketplace to check access."
                    )
                )
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                val uri = runCatching { Uri.parse(url) }.getOrNull()
                if (uri == null || !allowed(uri)) {
                    fail(
                        MarketplaceLookupFailure(
                            "REDIRECT",
                            "Discogs redirected away from this release's listings. Open the marketplace to check access."
                        )
                    )
                    return
                }
                finish()
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (!request.isForMainFrame) return
                val status = errorResponse.statusCode
                val message = when (status) {
                    403 -> "Discogs refused the pricing request. Open the marketplace to check access."
                    429 -> "Discogs is limiting requests. Wait before retrying."
                    else -> "Discogs returned HTTP $status. Try again later."
                }
                fail(MarketplaceLookupFailure("HTTP_$status", message))
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (!request.isForMainFrame) return
                fail(
                    MarketplaceLookupFailure(
                        "WEBVIEW_${error.errorCode}",
                        "Android WebView could not load the marketplace (error ${error.errorCode}). Retry or check access in the marketplace screen."
                    )
                )
            }
        }

        continuation.invokeOnCancellation { webView.stopLoading() }
        webView.loadUrl(url)
    }
}

private suspend fun readPricePage(
    webView: WebView,
    script: String
): PricePage = suspendCancellableCoroutine { continuation ->
    webView.evaluateJavascript(script) { raw ->
        if (!continuation.isActive) return@evaluateJavascript
        try {
            if (raw.isNullOrBlank() || raw == "null") {
                throw MarketplaceLookupFailure(
                    "PAGE_FORMAT",
                    "The marketplace page could not be read by the pricing script. Open the marketplace to check whether it loaded normally."
                )
            }

            // evaluateJavascript returns a JSON-encoded JavaScript string.
            val decoded = JSONArray("[$raw]").getString(0)
            val json = JSONObject(decoded)

            fun priceMap(name: String): Map<String, Double> {
                val source = json.optJSONObject(name) ?: return emptyMap()
                return source.keys().asSequence().mapNotNull { key ->
                    val value = source.optDouble(key, Double.NaN)
                    if (value.isFinite() && value > 0.0) key to value else null
                }.toMap()
            }

            val idsJson = json.optJSONArray("ids")
            val ids = if (idsJson == null) {
                emptyList()
            } else {
                (0 until idsJson.length()).mapNotNull { index ->
                    idsJson.optString(index).takeIf { it.isNotBlank() }
                }
            }

            val hasNext = if (json.isNull("hasNext")) null else json.optBoolean("hasNext")
            val total = if (json.isNull("total")) null else json.optInt("total").takeIf { it >= 0 }

            continuation.resume(
                PricePage(
                    prices = ActiveMarketplaceConditionPrices(
                        mediaLowest = priceMap("media"),
                        mediaSleeveLowest = priceMap("pairs")
                    ),
                    rowCount = json.optInt("rowCount", 0),
                    parsedCount = json.optInt("parsedCount", 0),
                    hasNext = hasNext,
                    empty = json.optBoolean("empty", false),
                    blocked = json.optBoolean("blocked", false),
                    ready = json.optBoolean("ready", false),
                    total = total,
                    ids = ids
                )
            )
        } catch (e: MarketplaceLookupFailure) {
            continuation.resumeWithException(e)
        } catch (e: Exception) {
            continuation.resumeWithException(
                MarketplaceLookupFailure(
                    "PAGE_FORMAT",
                    "The marketplace page could not be read by the pricing script. Open the marketplace to check whether it loaded normally."
                )
            )
        }
    }
}
